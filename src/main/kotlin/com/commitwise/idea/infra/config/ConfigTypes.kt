package com.commitwise.idea.infra.config

import com.commitwise.idea.domain.commit.CommitFormat
import com.commitwise.idea.domain.prompt.CustomTemplate
import com.commitwise.idea.domain.review.Severity
import java.net.URI

data class CommitSettings(
    var format: String = "conventional",
    var language: String = "auto",
    var templateId: String = "builtin-conventional-zh",
    var maxSubjectLength: Int = 72,
    var includeBody: Boolean = true,
    var recentCommitCount: Int = 5,
    var confirmBeforeInsert: Boolean = false,
)

data class DiffSettings(
    var maxTotalChars: Int = 48000,
    var maxFileChars: Int = 20000,
    var excludeGlobs: MutableList<String> = mutableListOf(),
    var redactSecrets: Boolean = true,
)

data class ReviewSettings(
    var templateId: String = "builtin-review-standard",
    var minSeverity: String = "low",
    var maxIssues: Int = 30,
    var showDiagnostics: Boolean = true,
)

/** 原始配置（用户在设置页里填的） */
data class RawConfig(
    var provider: String = "openai-compatible",
    var baseUrl: String = "https://api.openai.com/v1",
    var model: String = "gpt-4o-mini",
    var autoFetchModels: Boolean = true,
    var availableModels: List<String> = emptyList(),
    var temperature: Double = 0.2,
    var maxTokens: Int = 1024,
    var requestTimeoutMs: Int = 60000,
    var maxRetries: Int = 1,
    var apiKeyEnv: String = "",
    var commit: CommitSettings = CommitSettings(),
    var diff: DiffSettings = DiffSettings(),
    var review: ReviewSettings = ReviewSettings(),
    var customTemplates: List<CustomTemplate> = emptyList(),
)

data class ResolvedCommit(
    val format: CommitFormat,
    val language: String,
    val templateId: String,
    val maxSubjectLength: Int,
    val includeBody: Boolean,
    val recentCommitCount: Int,
    val confirmBeforeInsert: Boolean,
)

data class ResolvedReview(
    val templateId: String,
    val minSeverity: Severity,
    val maxIssues: Int,
    val showDiagnostics: Boolean,
)

/** 校验并归一化后的配置 */
data class ResolvedConfig(
    val provider: ProviderId,
    val baseUrl: String,
    val model: String,
    val autoFetchModels: Boolean,
    val availableModels: List<String>,
    val apiKey: String?,
    val temperature: Double,
    val maxTokens: Int,
    val requestTimeoutMs: Int,
    val maxRetries: Int,
    val apiKeyEnv: String,
    val language: String,
    val commit: ResolvedCommit,
    val diff: DiffSettings,
    val review: ResolvedReview,
    val customTemplates: List<CustomTemplate>,
)

data class ValidationResult(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 模型名称留空时自动回填的推荐模型，用于提示用户 */
    val modelDefaulted: String? = null,
    val config: ResolvedConfig? = null,
)

object ConfigValidator {

    /** IDE 语言 → 输出语言 */
    fun resolveLanguage(raw: String, ideLanguage: String): String {
        if (raw == "zh-CN" || raw == "en") return raw
        return if (ideLanguage.lowercase().startsWith("zh")) "zh-CN" else "en"
    }

    fun languageName(lang: String): String = if (lang == "zh-CN") "简体中文" else "English"

    private fun isLocalHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1" || host == "0.0.0.0"

    fun validate(raw: RawConfig, ideLanguage: String, apiKey: String?): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var modelDefaulted: String? = null

        val providerId = ProviderId.of(raw.provider)
        if (providerId == null) {
            errors.add("未知的服务商类型 \"${raw.provider}\"，可选值：${ProviderId.entries.joinToString("、") { it.id }}")
        }
        val preset = ProviderPresets.of(raw.provider)

        val baseUrl = raw.baseUrl.trim().replace(Regex("/+$"), "")
        if (baseUrl.isEmpty()) {
            errors.add(
                if (raw.provider == "azure-openai") {
                    "Azure OpenAI 需要填写接口地址，形如 https://<资源名>.openai.azure.com/openai"
                } else {
                    "接口地址不能为空"
                },
            )
        } else {
            try {
                val uri = URI(baseUrl)
                // scheme 可能是大写（用户从文档里复制的 HTTPS://…），比较前统一小写
                val scheme = (uri.scheme ?: "").lowercase()
                // host 为 null 时（如含下划线的主机名）退化为取 authority 的主机部分
                val host = uri.host?.lowercase()
                    ?: uri.authority?.substringBefore(':')?.lowercase()
                if (scheme != "https" && scheme != "http") {
                    errors.add("接口地址必须以 http:// 或 https:// 开头")
                } else if (host.isNullOrBlank()) {
                    /*
                     * 「https://」这类只有协议没有主机的地址能通过 URI 解析，
                     * 但请求一定失败，提前拦下比让用户看到连接错误更好。
                     */
                    errors.add("接口地址缺少主机名：$baseUrl")
                } else if (scheme == "http" && !isLocalHost(host)) {
                    warnings.add("接口地址使用明文 http，发送的代码内容可被中间人读取：$baseUrl")
                }
            } catch (_: Exception) {
                errors.add("接口地址不是合法的 URL：$baseUrl")
            }
        }

        detectConfigMismatch(raw.provider, baseUrl)?.let { warnings.add(it) }

        var model = raw.model.trim()
        if (model.isEmpty()) {
            /*
             * 留空时的兜底模型要按「实际接口地址」选，而不是只看服务商类型：
             * DeepSeek 的地址走的是 openai-compatible 协议，但它的默认模型显然不该是 gpt-4o-mini。
             */
            val matched = EndpointPresets.match(raw.provider, baseUrl)
            val fallback = matched?.defaultModel?.ifEmpty { null } ?: preset.defaultModel
            val fallbackLabel = matched?.label ?: preset.label
            if (fallback.isNotEmpty()) {
                model = fallback
                modelDefaulted = fallback
                warnings.add("未设置模型，已使用 $fallbackLabel 的推荐默认模型 $model")
            } else {
                errors.add("请填写模型名称。${preset.modelHint}")
            }
        }

        if (raw.temperature < 0 || raw.temperature > 2) {
            errors.add("temperature 必须在 0–2 之间")
        }
        if (raw.maxTokens < 64) {
            errors.add("maxTokens 不能小于 64")
        }
        if (raw.requestTimeoutMs < 5000) {
            errors.add("请求超时不能小于 5000 毫秒")
        }

        if (raw.provider != "ollama" && apiKey.isNullOrEmpty()) {
            if (raw.apiKeyEnv.isNotEmpty()) {
                warnings.add("未在凭据库中找到密钥，将尝试读取环境变量 ${raw.apiKeyEnv}")
            } else {
                warnings.add("尚未设置 API 密钥，执行「设置 API 密钥」后再试")
            }
        }

        val format = CommitFormat.of(raw.commit.format)
        if (CommitFormat.entries.none { it.id == raw.commit.format }) {
            errors.add("未知的提交信息格式 \"${raw.commit.format}\"")
        }

        val minSeverity = Severity.entries.firstOrNull { it.id == raw.review.minSeverity }
        if (minSeverity == null) {
            errors.add("审查级别过滤必须是 high / medium / low")
        }

        if (errors.isNotEmpty() || providerId == null || minSeverity == null) {
            return ValidationResult(errors, warnings, modelDefaulted, null)
        }

        val language = resolveLanguage(raw.commit.language, ideLanguage)

        val config = ResolvedConfig(
            provider = providerId,
            baseUrl = baseUrl,
            model = model,
            autoFetchModels = raw.autoFetchModels,
            availableModels = raw.availableModels,
            apiKey = apiKey,
            temperature = raw.temperature,
            maxTokens = raw.maxTokens,
            requestTimeoutMs = raw.requestTimeoutMs,
            maxRetries = raw.maxRetries,
            apiKeyEnv = raw.apiKeyEnv,
            language = language,
            commit = ResolvedCommit(
                format = format,
                language = language,
                templateId = raw.commit.templateId,
                maxSubjectLength = raw.commit.maxSubjectLength,
                includeBody = raw.commit.includeBody,
                recentCommitCount = raw.commit.recentCommitCount,
                confirmBeforeInsert = raw.commit.confirmBeforeInsert,
            ),
            diff = raw.diff,
            review = ResolvedReview(
                templateId = raw.review.templateId,
                minSeverity = minSeverity,
                maxIssues = raw.review.maxIssues,
                showDiagnostics = raw.review.showDiagnostics,
            ),
            customTemplates = raw.customTemplates,
        )

        return ValidationResult(errors, warnings, modelDefaulted, config)
    }
}
