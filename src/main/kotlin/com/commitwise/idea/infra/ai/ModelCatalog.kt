package com.commitwise.idea.infra.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class ModelInfo(val id: String, val note: String? = null)

object ModelCatalog {

    /** Azure 没有公开的模型列表接口（deployment 属于管理面），只能手填 */
    fun supportsModelList(provider: String): Boolean =
        provider == "openai-compatible" || provider == "anthropic" || provider == "ollama"

    private fun join(base: String, path: String): String {
        val b = base.trim().replace(Regex("/+$"), "")
        if (b.isEmpty()) throw AiError("接口地址为空，无法获取模型列表", AiErrorCode.UNSUPPORTED)
        return "$b/${path.replace(Regex("^/+"), "")}"
    }

    private fun getJson(url: String, headers: Map<String, String>, timeoutMs: Int, token: CancelToken): JsonObject {
        val text = StreamClient.getText(url, headers, timeoutMs, token)
        return try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            throw AiError("模型列表不是合法 JSON", AiErrorCode.BAD_RESPONSE)
        }
    }

    /** OpenAI / Anthropic 的 /models 结构兼容：{ data: [{ id, ... }] } */
    private fun fromDataArray(json: JsonObject): List<ModelInfo> {
        val arr = when {
            json.get("data")?.isJsonArray == true -> json.getAsJsonArray("data")
            json.get("models")?.isJsonArray == true -> json.getAsJsonArray("models")
            else -> return emptyList()
        }
        val out = mutableListOf<ModelInfo>()
        for (item in arr) {
            val id = when {
                item.isJsonObject && item.asJsonObject.get("id")?.isJsonPrimitive == true ->
                    item.asJsonObject.get("id").asString
                item.isJsonObject && item.asJsonObject.get("name")?.isJsonPrimitive == true ->
                    item.asJsonObject.get("name").asString
                item.isJsonPrimitive -> item.asString
                else -> ""
            }
            if (id.isEmpty()) continue
            val note = when {
                item.isJsonObject && item.asJsonObject.get("display_name")?.isJsonPrimitive == true ->
                    item.asJsonObject.get("display_name").asString
                item.isJsonObject && item.asJsonObject.get("owned_by")?.isJsonPrimitive == true ->
                    item.asJsonObject.get("owned_by").asString
                else -> null
            }
            out.add(ModelInfo(id, note))
        }
        return out
    }

    private fun fromOllama(json: JsonObject): List<ModelInfo> {
        val arr = json.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = mutableListOf<ModelInfo>()
        for (item in arr) {
            if (!item.isJsonObject) continue
            val obj = item.asJsonObject
            val id = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("model")?.takeIf { it.isJsonPrimitive }?.asString
                ?: ""
            if (id.isEmpty()) continue
            val size = obj.get("size")?.takeIf { it.isJsonPrimitive }?.asLong
            val sizeText = if (size != null) "%.1fGB".format(size / 1024.0 / 1024.0 / 1024.0) else ""
            val params = obj.get("details")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("parameter_size")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val note = listOf(params, sizeText).filter { it.isNotEmpty() }.joinToString(" · ")
            out.add(ModelInfo(id, note.ifEmpty { null }))
        }
        return out
    }

    /**
     * 拉取服务商可用的模型列表。失败时抛 AiError，由调用方决定是提示还是静默降级。
     */
    fun listModels(
        provider: String,
        baseUrl: String,
        apiKey: String?,
        timeoutMs: Int = 15000,
        token: CancelToken = CancelToken(),
    ): List<ModelInfo> {
        val effectiveTimeout = minOf(timeoutMs, 30000)

        if (!supportsModelList(provider)) {
            throw AiError(
                if (provider == "azure-openai") {
                    "Azure OpenAI 不提供模型列表接口，请手动填写 deployment 名称"
                } else {
                    "服务商 $provider 不支持自动获取模型列表"
                },
                AiErrorCode.UNSUPPORTED,
            )
        }

        if (provider == "ollama") {
            // Ollama 的 baseUrl 可能被填成 http://host:11434/api，这里做容错
            val base = baseUrl.trim().replace(Regex("/+$"), "").replace(Regex("/api$"), "")
            val json = getJson(join(base, "api/tags"), mapOf("accept" to "application/json"), effectiveTimeout, token)
            return sortModels(fromOllama(json))
        }

        if (provider == "anthropic") {
            val json = getJson(
                join(baseUrl, "models?limit=1000"),
                mapOf(
                    "accept" to "application/json",
                    "x-api-key" to (apiKey ?: ""),
                    "anthropic-version" to "2023-06-01",
                ),
                effectiveTimeout,
                token,
            )
            return sortModels(fromDataArray(json))
        }

        val headers = mutableMapOf("accept" to "application/json")
        if (!apiKey.isNullOrEmpty()) headers["authorization"] = "Bearer $apiKey"
        val json = getJson(join(baseUrl, "models"), headers, effectiveTimeout, token)
        return sortModels(fromDataArray(json))
    }

    /** 明显不是对话模型的家族，排在最后（不直接丢弃，用户可能就要用） */
    private val NON_CHAT = listOf(
        "embedding", "embed", "whisper", "tts", "dall-e", "moderation", "image", "audio",
        "rerank", "transcribe", "speech", "diffusion", "stable-diffusion", "bge-", "text-embedding", "aqa",
    )

    /**
     * 适合本插件（写提交信息 / 审代码）的偏好关键词，命中越靠前。
     * 顺序即优先级：先通用编码向，再各家旗舰，最后其他可对话模型。
     */
    private val PREFERRED = listOf(
        "coder", "gpt-5", "gpt-4", "claude", "deepseek", "qwen", "glm", "o3", "o4",
        "sonnet", "haiku", "gemini", "llama", "mistral", "moonshot", "kimi",
        "hunyuan", "doubao", "ernie", "minimax", "grok", "spark", "baichuan", "yi-",
    )

    fun isLikelyChatModel(id: String): Boolean {
        val lower = id.lowercase()
        return NON_CHAT.none { lower.contains(it) }
    }

    private fun rank(id: String): Int {
        val lower = id.lowercase()
        if (!isLikelyChatModel(id)) return 100
        val idx = PREFERRED.indexOfFirst { lower.contains(it) }
        return if (idx >= 0) idx else 50
    }

    fun sortModels(models: List<ModelInfo>): List<ModelInfo> {
        val seen = mutableSetOf<String>()
        val unique = models.filter { seen.add(it.id) }
        return unique.sortedWith(compareBy({ rank(it.id) }, { it.id }))
    }

    /** 从模型列表里挑一个「最可能好用」的默认模型 */
    fun pickDefaultModel(models: List<ModelInfo>, suggestions: List<String> = emptyList()): String? {
        if (models.isEmpty()) return null
        for (s in suggestions) {
            if (models.any { it.id == s }) return s
        }
        val chat = models.firstOrNull { isLikelyChatModel(it.id) }
        return (chat ?: models[0]).id
    }
}
