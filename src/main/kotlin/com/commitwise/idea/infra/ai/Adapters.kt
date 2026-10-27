package com.commitwise.idea.infra.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

fun joinUrl(base: String, path: String): String {
    val b = base.replace(Regex("/+$"), "")
    val p = path.replace(Regex("^/+"), "")
    if (b.isEmpty()) throw AiError("接口地址为空", AiErrorCode.UNSUPPORTED)
    return if (b.endsWith("/$p")) b else "$b/$p"
}

private fun messagesJson(messages: List<ChatMessage>): JsonArray {
    val arr = JsonArray()
    for (m in messages) {
        arr.add(JsonObject().apply {
            addProperty("role", m.role.id)
            addProperty("content", m.content)
        })
    }
    return arr
}

interface ProviderAdapter {
    val id: String
    val requiresApiKey: Boolean

    fun chat(
        req: ChatRequest,
        ctx: ProviderContext,
        onDelta: (String) -> Unit,
        token: CancelToken,
    ): String
}

private fun jsonHeaders(ctx: ProviderContext, withBearer: Boolean = true): MutableMap<String, String> {
    val headers = mutableMapOf("content-type" to "application/json")
    if (withBearer && !ctx.apiKey.isNullOrEmpty()) {
        headers["authorization"] = "Bearer ${ctx.apiKey}"
    }
    return headers
}

private fun isEventStream(ct: String) = ct.contains("text/event-stream")
private fun isNdjson(ct: String) = ct.contains("application/x-ndjson") || ct.contains("application/jsonl")

/** OpenAI 兼容：OpenAI / DeepSeek / Qwen / Moonshot / 自建网关 */
object OpenAiCompatibleAdapter : ProviderAdapter {
    override val id = "openai-compatible"
    override val requiresApiKey = true

    override fun chat(req: ChatRequest, ctx: ProviderContext, onDelta: (String) -> Unit, token: CancelToken): String {
        val url = if (Regex("/chat/completions$").containsMatchIn(ctx.baseUrl)) {
            ctx.baseUrl
        } else {
            joinUrl(ctx.baseUrl, "chat/completions")
        }

        val body = JsonObject().apply {
            addProperty("model", req.model)
            add("messages", messagesJson(req.messages))
            addProperty("temperature", req.temperature)
            addProperty("max_tokens", req.maxTokens)
            addProperty("stream", true)
        }.toString()

        val res = StreamClient.postJson(url, jsonHeaders(ctx), body, ctx.timeoutMs, token)
        if (res.statusCode() !in 200..299) {
            throw StreamClient.httpError(res.statusCode(), StreamClient.readAll(res))
        }
        if (!isEventStream(StreamClient.contentType(res))) {
            return StreamClient.readJsonFallback(res)
        }

        val parser = SseParser()
        val sb = StringBuilder()

        fun handle(data: String) {
            if (data == "[DONE]") return
            val json = try {
                JsonParser.parseString(data).asJsonObject
            } catch (_: Exception) {
                return
            }
            json.get("error")?.let { err ->
                val msg = if (err.isJsonObject) {
                    err.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                } else {
                    null
                }
                throw AiError(msg ?: "模型服务返回错误", AiErrorCode.SERVER)
            }
            val delta = optDelta(json)
            if (!delta.isNullOrEmpty()) {
                sb.append(delta)
                onDelta(delta)
            }
        }

        StreamClient.pump(res, token) { chunk -> parser.push(chunk).forEach { handle(it.data) } }
        parser.flush().forEach { handle(it.data) }

        if (sb.isBlank()) throw AiError("模型没有返回任何内容", AiErrorCode.BAD_RESPONSE)
        return sb.toString()
    }
}

private fun optDelta(json: JsonObject): String? {
    val choices = json.get("choices") ?: return null
    if (!choices.isJsonArray || choices.asJsonArray.size() == 0) return null
    val first = choices.asJsonArray[0]
    if (!first.isJsonObject) return null
    val delta = first.asJsonObject.get("delta") ?: return null
    if (!delta.isJsonObject) return null
    val content = delta.asJsonObject.get("content") ?: return null
    return if (content.isJsonPrimitive) content.asString else null
}

/** Anthropic Messages API */
object AnthropicAdapter : ProviderAdapter {
    override val id = "anthropic"
    override val requiresApiKey = true

    override fun chat(req: ChatRequest, ctx: ProviderContext, onDelta: (String) -> Unit, token: CancelToken): String {
        val url = if (Regex("/messages$").containsMatchIn(ctx.baseUrl)) {
            ctx.baseUrl
        } else {
            joinUrl(ctx.baseUrl, "messages")
        }
        val system = req.messages.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content }
        val messages = req.messages.filter { it.role != ChatRole.SYSTEM }

        val body = JsonObject().apply {
            addProperty("model", req.model)
            addProperty("max_tokens", req.maxTokens)
            addProperty("temperature", req.temperature)
            if (system.isNotEmpty()) addProperty("system", system)
            add("messages", messagesJson(messages))
            addProperty("stream", true)
        }.toString()

        val headers = jsonHeaders(ctx, withBearer = false).apply {
            put("x-api-key", ctx.apiKey ?: "")
            put("anthropic-version", "2023-06-01")
        }

        val res = StreamClient.postJson(url, headers, body, ctx.timeoutMs, token)
        if (res.statusCode() !in 200..299) {
            throw StreamClient.httpError(res.statusCode(), StreamClient.readAll(res))
        }
        if (!isEventStream(StreamClient.contentType(res))) {
            return StreamClient.readJsonFallback(res)
        }

        val parser = SseParser()
        val sb = StringBuilder()

        fun handle(event: String?, data: String) {
            val json = try {
                JsonParser.parseString(data).asJsonObject
            } catch (_: Exception) {
                return
            }
            val type = json.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: event
            if (type == "error") {
                val msg = json.getAsJsonObject("error")?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                throw AiError(msg ?: "模型服务返回错误", AiErrorCode.SERVER)
            }
            if (type == "content_block_delta") {
                val delta = json.getAsJsonObject("delta") ?: return
                if (delta.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "text_delta") {
                    val text = delta.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!text.isNullOrEmpty()) {
                        sb.append(text)
                        onDelta(text)
                    }
                }
            }
        }

        StreamClient.pump(res, token) { chunk -> parser.push(chunk).forEach { handle(it.event, it.data) } }
        parser.flush().forEach { handle(it.event, it.data) }

        if (sb.isBlank()) throw AiError("模型没有返回任何内容", AiErrorCode.BAD_RESPONSE)
        return sb.toString()
    }
}

/** Azure OpenAI：模型名即 deployment 名 */
object AzureOpenAiAdapter : ProviderAdapter {
    override val id = "azure-openai"
    override val requiresApiKey = true

    override fun chat(req: ChatRequest, ctx: ProviderContext, onDelta: (String) -> Unit, token: CancelToken): String {
        val apiVersion = "2024-06-01"
        val url = if (Regex("/chat/completions").containsMatchIn(ctx.baseUrl)) {
            ctx.baseUrl
        } else {
            val dep = java.net.URLEncoder.encode(req.model, "UTF-8")
            joinUrl(ctx.baseUrl, "openai/deployments/$dep/chat/completions") + "?api-version=$apiVersion"
        }

        val body = JsonObject().apply {
            add("messages", messagesJson(req.messages))
            addProperty("temperature", req.temperature)
            addProperty("max_tokens", req.maxTokens)
            addProperty("stream", true)
        }.toString()

        val headers = jsonHeaders(ctx, withBearer = false).apply { put("api-key", ctx.apiKey ?: "") }
        val res = StreamClient.postJson(url, headers, body, ctx.timeoutMs, token)
        if (res.statusCode() !in 200..299) {
            throw StreamClient.httpError(res.statusCode(), StreamClient.readAll(res))
        }
        if (!isEventStream(StreamClient.contentType(res))) {
            return StreamClient.readJsonFallback(res)
        }

        val parser = SseParser()
        val sb = StringBuilder()
        StreamClient.pump(res, token) { chunk ->
            parser.push(chunk).forEach { ev ->
                if (ev.data == "[DONE]") return@forEach
                try {
                    val delta = optDelta(JsonParser.parseString(ev.data).asJsonObject)
                    if (!delta.isNullOrEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                } catch (_: Exception) {
                    // 忽略无法解析的帧
                }
            }
        }

        if (sb.isBlank()) throw AiError("模型没有返回任何内容", AiErrorCode.BAD_RESPONSE)
        return sb.toString()
    }
}

/** Ollama 本地服务：NDJSON 流，无需密钥 */
object OllamaAdapter : ProviderAdapter {
    override val id = "ollama"
    override val requiresApiKey = false

    override fun chat(req: ChatRequest, ctx: ProviderContext, onDelta: (String) -> Unit, token: CancelToken): String {
        val url = if (Regex("/api/chat$").containsMatchIn(ctx.baseUrl)) {
            ctx.baseUrl
        } else {
            joinUrl(ctx.baseUrl, "api/chat")
        }

        val body = JsonObject().apply {
            addProperty("model", req.model)
            add("messages", messagesJson(req.messages))
            addProperty("stream", true)
            add("options", JsonObject().apply {
                addProperty("temperature", req.temperature)
                addProperty("num_predict", req.maxTokens)
            })
        }.toString()

        val res = StreamClient.postJson(url, jsonHeaders(ctx), body, ctx.timeoutMs, token)
        if (res.statusCode() !in 200..299) {
            throw StreamClient.httpError(res.statusCode(), StreamClient.readAll(res))
        }
        val ct = StreamClient.contentType(res)
        if (!isNdjson(ct) && !isEventStream(ct)) {
            return StreamClient.readJsonFallback(res)
        }

        val parser = NdjsonParser()
        val sb = StringBuilder()

        fun handle(line: String) {
            val json = try {
                JsonParser.parseString(line).asJsonObject
            } catch (_: Exception) {
                return
            }
            json.get("error")?.let { e ->
                throw AiError(if (e.isJsonPrimitive) e.asString else "模型服务返回错误", AiErrorCode.SERVER)
            }
            val msg = json.get("message")
            if (msg != null && msg.isJsonObject) {
                val content = msg.asJsonObject.get("content")
                if (content != null && content.isJsonPrimitive) {
                    val text = content.asString
                    if (text.isNotEmpty()) {
                        sb.append(text)
                        onDelta(text)
                    }
                }
            }
        }

        StreamClient.pump(res, token) { chunk -> parser.push(chunk).forEach { handle(it) } }
        parser.flush().forEach { handle(it) }

        if (sb.isBlank()) throw AiError("模型没有返回任何内容", AiErrorCode.BAD_RESPONSE)
        return sb.toString()
    }
}

object Adapters {
    private val ALL = listOf(OpenAiCompatibleAdapter, AnthropicAdapter, AzureOpenAiAdapter, OllamaAdapter)

    fun get(id: String): ProviderAdapter =
        ALL.firstOrNull { it.id == id } ?: throw AiError("不支持的服务商类型：$id", AiErrorCode.UNSUPPORTED)
}
