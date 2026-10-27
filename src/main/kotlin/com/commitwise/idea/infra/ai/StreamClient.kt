package com.commitwise.idea.infra.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

/** HTTP 客户端与流式读取的公共部分 */
object StreamClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .build()

    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
        token: CancelToken,
    ): HttpResponse<InputStream> {
        token.throwIfCancelled()
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs.toLong()))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: HttpTimeoutException) {
            throw AiError("请求超时", AiErrorCode.TIMEOUT)
        } catch (e: IOException) {
            if (token.isCancelled) throw AiError("请求已取消", AiErrorCode.ABORTED)
            throw AiError(e.message ?: "网络请求失败", AiErrorCode.NETWORK)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiError("请求已取消", AiErrorCode.ABORTED)
        }
    }

    fun getText(url: String, headers: Map<String, String>, timeoutMs: Int, token: CancelToken): String {
        token.throwIfCancelled()
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs.toLong()))
            .GET()
        for ((k, v) in headers) builder.header(k, v)
        try {
            val res = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val text = res.body().use { String(it.readBytes(), StandardCharsets.UTF_8) }
            if (res.statusCode() !in 200..299) {
                throw httpError(res.statusCode(), text)
            }
            return text
        } catch (e: HttpTimeoutException) {
            throw AiError("请求超时", AiErrorCode.TIMEOUT)
        } catch (e: IOException) {
            if (token.isCancelled) throw AiError("请求已取消", AiErrorCode.ABORTED)
            throw AiError(e.message ?: "网络请求失败", AiErrorCode.NETWORK)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiError("请求已取消", AiErrorCode.ABORTED)
        }
    }

    /** 逐块读取响应体，交给解析器 */
    fun pump(res: HttpResponse<InputStream>, token: CancelToken, onChunk: (String) -> Unit) {
        res.body().use { stream ->
            val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                token.throwIfCancelled()
                val n = try {
                    reader.read(buf)
                } catch (e: IOException) {
                    if (token.isCancelled) throw AiError("请求已取消", AiErrorCode.ABORTED)
                    throw AiError("读取响应流失败：${e.message}", AiErrorCode.NETWORK)
                }
                if (n < 0) break
                if (n > 0) onChunk(String(buf, 0, n))
            }
        }
    }

    fun contentType(res: HttpResponse<InputStream>): String =
        res.headers().firstValue("content-type").orElse("")

    fun readAll(res: HttpResponse<InputStream>): String =
        res.body().use { String(it.readBytes(), StandardCharsets.UTF_8) }

    /** 某些网关会忽略 stream 参数直接返回完整 JSON，这里做兜底 */
    fun readJsonFallback(res: HttpResponse<InputStream>): String {
        val text = readAll(res)
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val content = when {
                json.has("choices") && json.getAsJsonArray("choices").size() > 0 -> {
                    val c = json.getAsJsonArray("choices")[0].asJsonObject
                    val msg = c.getAsJsonObject("message")
                    msg?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                }
                json.has("content") && json.getAsJsonArray("content").size() > 0 ->
                    json.getAsJsonArray("content")[0].asJsonObject.get("text")?.asString
                json.has("message") -> {
                    val m = json.get("message")
                    if (m.isJsonObject) m.asJsonObject.get("content")?.asString else null
                }
                else -> null
            }
            if (!content.isNullOrEmpty()) return content
        } catch (_: Exception) {
            // 落到统一报错
        }
        throw AiError("服务端返回了无法识别的响应", AiErrorCode.BAD_RESPONSE, res.statusCode(), text.take(300))
    }

    fun httpError(status: Int, bodyText: String): AiError {
        val code = when (status) {
            400, 404, 422 -> AiErrorCode.BAD_REQUEST
            401, 403 -> AiErrorCode.AUTH
            408 -> AiErrorCode.TIMEOUT
            429 -> AiErrorCode.RATE_LIMIT
            else -> if (status >= 500) AiErrorCode.SERVER else AiErrorCode.BAD_REQUEST
        }
        val detail = extractErrorMessage(bodyText)
        val summary = if (detail.isNotEmpty()) "$status $detail" else status.toString()
        return AiError(summary, code, status, detail)
    }

    private fun extractErrorMessage(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val err = json.get("error")
            if (err != null) {
                if (err.isJsonPrimitive) return err.asString
                if (err.isJsonObject) {
                    val m = err.asJsonObject.get("message")
                    if (m != null && m.isJsonPrimitive) return m.asString
                }
            }
            val msg = json.get("message")
            if (msg != null && msg.isJsonPrimitive) return msg.asString
        } catch (_: Exception) {
            // 非 JSON，直接截断返回
        }
        return if (text.length > 300) text.take(300) + "…" else text
    }

    /** 构造请求体 JSON，避免手拼字符串出错 */
    fun jsonObject(build: JsonObject.() -> Unit): String {
        val obj = JsonObject()
        obj.build()
        return obj.toString()
    }
}
