package com.commitwise.idea.infra.config

import java.net.URI

data class ModelSuggestion(val id: String, val note: String)

data class ProviderPreset(
    val id: ProviderId,
    val label: String,
    /** 官方默认接口地址，空串表示必须由用户填写 */
    val baseUrl: String,
    /** 未显式配置模型时使用的推荐默认模型 */
    val defaultModel: String,
    val requiresApiKey: Boolean,
    val supportsModelList: Boolean,
    /** 填写模型时的提示文案 */
    val modelHint: String,
    /** 填写接口地址时的提示文案 */
    val urlHint: String,
    val suggestions: List<ModelSuggestion>,
)

/**
 * 各服务商的默认值。目标是：用户只填密钥也能跑起来（model 留空时回填 defaultModel）。
 */
object ProviderPresets {

    private val PRESETS: Map<ProviderId, ProviderPreset> = mapOf(
        ProviderId.OPENAI_COMPATIBLE to ProviderPreset(
            id = ProviderId.OPENAI_COMPATIBLE,
            label = "OpenAI 兼容接口",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            requiresApiKey = true,
            supportsModelList = true,
            modelHint = "OpenAI 官方或兼容网关的模型名，例如 gpt-4o-mini、deepseek-chat、qwen-plus",
            urlHint = "兼容接口通常以 /v1 结尾，例如 https://api.openai.com/v1、https://api.deepseek.com/v1",
            suggestions = listOf(
                ModelSuggestion("gpt-4o-mini", "OpenAI · 便宜快速，够用"),
                ModelSuggestion("gpt-4o", "OpenAI · 质量更高"),
                ModelSuggestion("deepseek-chat", "DeepSeek · 需把地址改为 api.deepseek.com/v1"),
                ModelSuggestion("qwen-plus", "阿里云百炼 · 需改为 dashscope 兼容地址"),
            ),
        ),
        ProviderId.ANTHROPIC to ProviderPreset(
            id = ProviderId.ANTHROPIC,
            label = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-3-5-haiku-latest",
            requiresApiKey = true,
            supportsModelList = true,
            modelHint = "Claude 模型名，例如 claude-3-5-haiku-latest、claude-3-5-sonnet-latest",
            urlHint = "官方地址为 https://api.anthropic.com/v1",
            suggestions = listOf(
                ModelSuggestion("claude-3-5-haiku-latest", "快且便宜，适合写提交信息"),
                ModelSuggestion("claude-3-5-sonnet-latest", "审查质量更好"),
            ),
        ),
        ProviderId.AZURE_OPENAI to ProviderPreset(
            id = ProviderId.AZURE_OPENAI,
            label = "Azure OpenAI",
            baseUrl = "",
            defaultModel = "",
            requiresApiKey = true,
            supportsModelList = false,
            modelHint = "填 Azure 上的 deployment 名称（不是底层模型名）",
            urlHint = "填资源地址 + /openai，例如 https://<资源名>.openai.azure.com/openai",
            suggestions = emptyList(),
        ),
        ProviderId.OLLAMA to ProviderPreset(
            id = ProviderId.OLLAMA,
            label = "Ollama（本地）",
            baseUrl = "http://localhost:11434",
            defaultModel = "qwen2.5-coder:7b",
            requiresApiKey = false,
            supportsModelList = true,
            modelHint = "本地已 pull 的模型名，可用 ollama list 查看",
            urlHint = "Ollama 默认监听 http://localhost:11434，不要带 /api",
            suggestions = listOf(
                ModelSuggestion("qwen2.5-coder:7b", "代码场景推荐，7B 显存友好"),
                ModelSuggestion("deepseek-coder-v2:16b", "效果更好，需要更大显存"),
                ModelSuggestion("llama3.1:8b", "通用对话"),
            ),
        ),
    )

    fun of(provider: String?): ProviderPreset = PRESETS[ProviderId.ofOrDefault(provider)]!!
}

/** 「开箱即用」入口：把常见的服务商/网关连地址和默认模型一起准备好 */
data class EndpointPreset(
    val key: String,
    val label: String,
    val description: String,
    val provider: ProviderId,
    val baseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean,
    /** 申请密钥的说明 */
    val keyHint: String,
)

object EndpointPresets {

    val ALL: List<EndpointPreset> = listOf(
        EndpointPreset(
            "deepseek", "DeepSeek", "国内直连 · 性价比高",
            ProviderId.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", "deepseek-chat", true,
            "在 platform.deepseek.com 的「API keys」页面创建",
        ),
        EndpointPreset(
            "openai", "OpenAI", "需要外网访问",
            ProviderId.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4o-mini", true,
            "在 platform.openai.com 的「API keys」页面创建",
        ),
        EndpointPreset(
            "qwen", "阿里云百炼", "通义千问 · 兼容模式",
            ProviderId.OPENAI_COMPATIBLE, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", true,
            "在百炼控制台的「API-KEY 管理」创建",
        ),
        EndpointPreset(
            "moonshot", "Moonshot", "Kimi · 国内直连",
            ProviderId.OPENAI_COMPATIBLE, "https://api.moonshot.cn/v1", "moonshot-v1-8k", true,
            "在 platform.moonshot.cn 的「API Key 管理」创建",
        ),
        EndpointPreset(
            "zhipu", "智谱 GLM", "GLM · 国内直连",
            ProviderId.OPENAI_COMPATIBLE, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", true,
            "在 bigmodel.cn 的「API Keys」页面创建",
        ),
        EndpointPreset(
            "ollama", "Ollama 本地", "完全离线 · 无需密钥",
            ProviderId.OLLAMA, "http://localhost:11434", "qwen2.5-coder:7b", false, "",
        ),
        EndpointPreset(
            "anthropic", "Anthropic Claude", "Claude · 需要外网",
            ProviderId.ANTHROPIC, "https://api.anthropic.com/v1", "claude-3-5-haiku-latest", true,
            "在 console.anthropic.com 的「API Keys」页面创建",
        ),
        EndpointPreset(
            "azure", "Azure OpenAI", "企业内网 / 合规场景",
            ProviderId.AZURE_OPENAI, "", "", true,
            "在 Azure 门户的「密钥和终结点」页面获取",
        ),
    )

    /** 根据当前配置反查出对应的入口预设 */
    fun match(provider: String?, baseUrl: String): EndpointPreset? {
        val normalized = normalizeUrl(baseUrl)
        if (normalized.isEmpty()) return null

        val sameProvider = ALL.filter { it.provider.id == provider }
        sameProvider.firstOrNull { normalizeUrl(it.baseUrl) == normalized }?.let { return it }

        /*
         * 用户经常只写域名（漏掉 /v1、尾斜杠不一致），严格相等会匹配不到，
         * 结果回填出一个不属于该服务商的默认模型（如 DeepSeek 地址配 gpt-4o-mini）。
         * 这里退化为按主机名匹配：同一服务商下主机名唯一，足够安全。
         */
        val host = hostOf(normalized) ?: return null
        return sameProvider.firstOrNull { hostOf(normalizeUrl(it.baseUrl)) == host }
    }

    private fun normalizeUrl(url: String): String = url.trim().replace(Regex("/+$"), "").lowercase()

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }
}

/** 判断接口地址是否与所选服务商明显不匹配，用于给出配置提示 */
fun detectConfigMismatch(provider: String?, baseUrl: String): String? {
    val lower = baseUrl.lowercase()
    if (lower.isEmpty()) return null
    return when {
        provider == "ollama" && !Regex("localhost|127\\.0\\.0\\.1|\\[::1\\]|:11434").containsMatchIn(lower) ->
            "当前服务商是 Ollama，但接口地址是 $baseUrl。Ollama 是本地服务，通常应填 http://localhost:11434"
        provider == "azure-openai" && !lower.contains(".openai.azure.com") ->
            "Azure OpenAI 的接口地址应形如 https://<资源名>.openai.azure.com/openai，当前为 $baseUrl"
        provider == "openai-compatible" && lower.contains(".openai.azure.com") ->
            "这是 Azure OpenAI 的地址，请把服务商切换为「Azure OpenAI」"
        provider == "openai-compatible" && lower.contains("anthropic.com") ->
            "这是 Anthropic 的地址，请把服务商切换为「Anthropic Claude」"
        provider == "anthropic" && !lower.contains("anthropic") ->
            "当前服务商是 Anthropic，但接口地址不是 Anthropic 官方地址：$baseUrl"
        else -> null
    }
}
