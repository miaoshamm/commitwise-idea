package com.commitwise.idea.infra.config

/** 服务商标识的最小集合 */
enum class ProviderId(val id: String, val label: String) {
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI 兼容接口"),
    ANTHROPIC("anthropic", "Anthropic Claude"),
    AZURE_OPENAI("azure-openai", "Azure OpenAI"),
    OLLAMA("ollama", "Ollama（本地）"),
    ;

    companion object {
        fun of(value: String?): ProviderId? = entries.firstOrNull { it.id == value }
        fun ofOrDefault(value: String?): ProviderId = of(value) ?: OPENAI_COMPATIBLE
    }
}

fun providerLabel(value: String?): String = ProviderId.of(value)?.label ?: (value ?: "")
