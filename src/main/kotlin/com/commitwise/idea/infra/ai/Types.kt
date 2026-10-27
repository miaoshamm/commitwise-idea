package com.commitwise.idea.infra.ai

enum class ChatRole(val id: String) {
    SYSTEM("system"), USER("user"), ASSISTANT("assistant")
}

data class ChatMessage(val role: ChatRole, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val maxTokens: Int,
)

enum class AiErrorCode {
    AUTH, RATE_LIMIT, TIMEOUT, NETWORK, SERVER, BAD_REQUEST, ABORTED, BAD_RESPONSE, UNSUPPORTED
}

class AiError(
    message: String,
    val code: AiErrorCode,
    val status: Int? = null,
    val detail: String? = null,
) : Exception(message) {

    val retryable: Boolean
        get() = code == AiErrorCode.RATE_LIMIT || code == AiErrorCode.NETWORK ||
            code == AiErrorCode.SERVER || code == AiErrorCode.TIMEOUT

    /** 面向用户的中文提示 */
    fun toUserMessage(): String = when (code) {
        AiErrorCode.AUTH -> "API 密钥无效或已过期，请执行「CommitWise: 设置 API 密钥」重新填写"
        AiErrorCode.RATE_LIMIT -> "触发了服务商的速率限制，请稍后重试"
        AiErrorCode.TIMEOUT -> "请求超时，可在设置中调大请求超时时间，或减少暂存区变更量"
        AiErrorCode.NETWORK -> "网络请求失败：${message ?: ""}"
        AiErrorCode.SERVER -> "模型服务返回 ${status ?: ""} 错误，稍后重试；若持续失败请检查网关地址"
        AiErrorCode.ABORTED -> "请求已取消"
        AiErrorCode.BAD_REQUEST ->
            "请求被拒绝：${detail ?: message ?: ""}。请检查模型名称与参数是否符合该服务商要求"
        AiErrorCode.BAD_RESPONSE -> "模型返回内容无法解析：${message ?: ""}"
        AiErrorCode.UNSUPPORTED -> message ?: "不支持的操作"
    }
}

data class ProviderContext(val baseUrl: String, val apiKey: String?, val timeoutMs: Int)

/** 取消令牌：ProgressIndicator 取消时置位，流式读取循环据此中断 */
class CancelToken {
    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
    }

    fun throwIfCancelled() {
        if (cancelled) throw AiError("请求已取消", AiErrorCode.ABORTED)
    }
}
