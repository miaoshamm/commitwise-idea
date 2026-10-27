package com.commitwise.idea.infra.ai

import com.commitwise.idea.infra.config.ResolvedConfig

data class CallModelResult(val text: String, val attempts: Int)

/**
 * 统一调用入口：选适配器 → 调用 → 失败退避重试。
 *
 * 重试前提：错误可重试 且 **尚未输出任何内容**。
 * 一旦流式内容已经开始吐字，重试会导致用户看到重复片段，宁可报错。
 */
object CallModel {

    fun call(
        config: ResolvedConfig,
        messages: List<ChatMessage>,
        token: CancelToken,
        onDelta: ((String) -> Unit)? = null,
        maxTokens: Int? = null,
        logger: ((String) -> Unit)? = null,
        onRetry: ((Int, AiError, Long) -> Unit)? = null,
    ): CallModelResult {
        val adapter = Adapters.get(config.provider.id)
        val maxRetries = config.maxRetries.coerceAtLeast(0)
        var emitted = false
        var lastError: AiError? = null

        for (attempt in 0..maxRetries) {
            if (token.isCancelled) throw AiError("请求已取消", AiErrorCode.ABORTED)
            try {
                logger?.invoke("调用 ${config.provider.id} / ${config.model}（第 ${attempt + 1} 次）")
                val text = adapter.chat(
                    ChatRequest(
                        model = config.model,
                        messages = messages,
                        temperature = config.temperature,
                        maxTokens = maxTokens ?: config.maxTokens,
                    ),
                    ProviderContext(config.baseUrl, config.apiKey, config.requestTimeoutMs),
                    { delta ->
                        emitted = true
                        onDelta?.invoke(delta)
                    },
                    token,
                )
                return CallModelResult(text, attempt + 1)
            } catch (e: AiError) {
                lastError = e
                val canRetry = e.retryable && !emitted && attempt < maxRetries
                if (!canRetry) throw e
                val delay = minOf(8000L, 600L * (1L shl attempt)) + (0..250).random()
                logger?.invoke("请求失败（${e.code}），${delay}ms 后重试：${e.message}")
                onRetry?.invoke(attempt + 1, e, delay)
                sleepCancellable(delay, token)
                emitted = false
            } catch (e: Exception) {
                val wrapped = AiError(e.message ?: "未知错误", AiErrorCode.UNSUPPORTED)
                lastError = wrapped
                throw wrapped
            }
        }

        throw lastError ?: AiError("未知错误", AiErrorCode.UNSUPPORTED)
    }

    private fun sleepCancellable(ms: Long, token: CancelToken) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (token.isCancelled) throw AiError("请求已取消", AiErrorCode.ABORTED)
            try {
                Thread.sleep(minOf(100L, end - System.currentTimeMillis()).coerceAtLeast(1L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AiError("请求已取消", AiErrorCode.ABORTED)
            }
        }
    }

    /** 「测试连接」用：只发一句最短的指令 */
    fun ping(config: ResolvedConfig, token: CancelToken): Triple<Boolean, String, Long> {
        val started = System.currentTimeMillis()
        val result = call(
            config = config,
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, "你是一个测试端点，只回复 OK。"),
                ChatMessage(ChatRole.USER, "回复 OK"),
            ),
            token = token,
            maxTokens = 16,
        )
        return Triple(true, result.text.trim().take(40), System.currentTimeMillis() - started)
    }
}
