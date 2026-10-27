package com.commitwise.idea.services

import com.commitwise.idea.domain.prompt.CustomTemplate
import com.commitwise.idea.infra.config.CommitSettings
import com.commitwise.idea.infra.config.DiffSettings
import com.commitwise.idea.infra.config.RawConfig
import com.commitwise.idea.infra.config.ReviewSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件配置的唯一存放点（替代 VSCode 版的 settings.json）。
 *
 * 字段与 VSCode 版一一对应，便于两边行为一致；扁平化存储让 XML 结构简单、
 * 升级时新增字段也能安全回填默认值。
 */
@Service(Service.Level.APP)
@State(name = "CommitWiseSettings", storages = [Storage("commitwise.xml")])
class CommitWiseSettings : PersistentStateComponent<CommitWiseSettings.State> {

    class State {
        // 模型服务
        var provider: String = "openai-compatible"
        var baseUrl: String = "https://api.openai.com/v1"
        var model: String = ""
        var autoFetchModels: Boolean = true
        var availableModels: MutableList<String> = mutableListOf()
        var temperature: Double = 0.2
        var maxTokens: Int = 1024
        var requestTimeoutMs: Int = 60000
        var maxRetries: Int = 1
        var apiKeyEnv: String = ""

        // 提交信息
        var commitFormat: String = "conventional"
        var commitLanguage: String = "auto"
        var commitTemplateId: String = "builtin-conventional-zh"
        var maxSubjectLength: Int = 72
        var includeBody: Boolean = true
        var recentCommitCount: Int = 5
        var confirmBeforeInsert: Boolean = false

        // 上下文与隐私
        var maxTotalChars: Int = 48000
        var maxFileChars: Int = 20000
        var excludeGlobs: MutableList<String> = mutableListOf(
            "*.lock", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "*.min.js", "*.min.css",
            "dist/**", "build/**", "*.snap", "*.map",
        )
        var redactSecrets: Boolean = true

        // 代码审查
        var reviewTemplateId: String = "builtin-review-standard"
        var minSeverity: String = "low"
        var maxIssues: Int = 30
        var showDiagnostics: Boolean = true

        /** 自定义提示词模板，以 JSON 存储（避免为一组可选字段写 @Tag 注解） */
        var customTemplatesJson: String = ""

        /** 首次使用引导是否已提示过 */
        var onboarded: Boolean = false
    }

    private var myState = State()

    /** 供设置页在保存后广播刷新 */
    private val listeners = mutableListOf<() -> Unit>()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun fireChanged() {
        listeners.toList().forEach { it() }
    }

    /** 转成校验层使用的原始配置 */
    fun toRawConfig(): RawConfig = RawConfig(
        provider = myState.provider,
        baseUrl = myState.baseUrl,
        model = myState.model,
        autoFetchModels = myState.autoFetchModels,
        availableModels = myState.availableModels.toList(),
        temperature = myState.temperature,
        maxTokens = myState.maxTokens,
        requestTimeoutMs = myState.requestTimeoutMs,
        maxRetries = myState.maxRetries,
        apiKeyEnv = myState.apiKeyEnv,
        commit = CommitSettings(
            format = myState.commitFormat,
            language = myState.commitLanguage,
            templateId = myState.commitTemplateId,
            maxSubjectLength = myState.maxSubjectLength,
            includeBody = myState.includeBody,
            recentCommitCount = myState.recentCommitCount,
            confirmBeforeInsert = myState.confirmBeforeInsert,
        ),
        diff = DiffSettings(
            maxTotalChars = myState.maxTotalChars,
            maxFileChars = myState.maxFileChars,
            excludeGlobs = myState.excludeGlobs,
            redactSecrets = myState.redactSecrets,
        ),
        review = ReviewSettings(
            templateId = myState.reviewTemplateId,
            minSeverity = myState.minSeverity,
            maxIssues = myState.maxIssues,
            showDiagnostics = myState.showDiagnostics,
        ),
        customTemplates = customTemplates,
    )

    /** 自定义模板（从 State 里的 JSON 解析；写入时同步序列化） */
    var customTemplates: List<CustomTemplate>
        get() = readCustomTemplates()
        set(value) {
            myState.customTemplatesJson = writeCustomTemplates(value)
        }

    private fun readCustomTemplates(): List<CustomTemplate> {
        val json = myState.customTemplatesJson
        if (json.isBlank()) return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<CustomTemplate>>() {}.type
            com.google.gson.Gson().fromJson<List<CustomTemplate>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeCustomTemplates(list: List<CustomTemplate>): String = try {
        com.google.gson.Gson().toJson(list)
    } catch (_: Exception) {
        ""
    }

    companion object {
        fun getInstance(): CommitWiseSettings =
            ApplicationManager.getApplication().getService(CommitWiseSettings::class.java)
    }
}
