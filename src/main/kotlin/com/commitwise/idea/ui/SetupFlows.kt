package com.commitwise.idea.ui

import com.commitwise.idea.infra.ai.AiError
import com.commitwise.idea.infra.ai.CallModel
import com.commitwise.idea.infra.ai.CancelToken
import com.commitwise.idea.infra.ai.ModelCache
import com.commitwise.idea.infra.ai.ModelCatalog
import com.commitwise.idea.infra.config.ConfigValidator
import com.commitwise.idea.infra.config.EndpointPresets
import com.commitwise.idea.infra.config.ProviderId
import com.commitwise.idea.infra.config.ProviderPresets
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.CommitWiseSettings
import com.commitwise.idea.services.Secrets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPasswordField
import javax.swing.JPanel
import javax.swing.BoxLayout
import javax.swing.Box

/**
 * 快速配置：选入口 → 确认地址与模型 → 填密钥 → 测试连接。
 *
 * VSCode 版是 4 个连续输入框，这里合成一个表单：同样四步信息，但能一眼看全，
 * 也省掉来回跳转。
 */
class QuickSetupDialog(private val project: Project?) : DialogWrapper(project) {

    private val settings = CommitWiseSettings.getInstance()
    private val endpointCombo = ComboBox(EndpointPresets.ALL.toTypedArray())
    private val baseUrlField = JBTextField()
    private val modelCombo = ComboBox<String>().apply { isEditable = true }
    private val apiKeyField = JPasswordField()
    private val hintLabel = JBLabel(" ")
    private val keyHintLabel = JBLabel(" ")
    private val testButton = JButton("测试连接")

    init {
        title = "CommitWise 快速配置"
        setOKButtonText("保存")
        init()
        endpointCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.label }
        endpointCombo.selectedIndex = EndpointPresets.ALL.indexOfFirst {
            it.provider.id == settings.state.provider && it.baseUrl == settings.state.baseUrl
        }.takeIf { it >= 0 } ?: 0
        applyEndpoint()
        endpointCombo.addActionListener { applyEndpoint() }
        testButton.addActionListener { test() }
    }

    private fun currentEndpoint() = endpointCombo.selectedItem as com.commitwise.idea.infra.config.EndpointPreset

    private fun applyEndpoint() {
        val ep = currentEndpoint()
        if (ep.baseUrl.isNotEmpty()) baseUrlField.text = ep.baseUrl
        populateModelCombo(ep)
        keyHintLabel.text = if (ep.keyHint.isEmpty()) "该入口无需密钥" else "密钥申请：${ep.keyHint}"
        apiKeyField.isEnabled = ep.requiresApiKey
        hintLabel.text = " "
    }

    /** 当前下拉框的值 */
    private fun modelComboValue(): String =
        ((modelCombo.selectedItem as? String) ?: (modelCombo.editor.item as? String) ?: "").trim()

    /** 用缓存 + 入口推荐 + 手填候选填充模型下拉框 */
    private fun populateModelCombo(ep: com.commitwise.idea.infra.config.EndpointPreset) {
        val state = settings.state
        val merged = LinkedHashMap<String, com.commitwise.idea.infra.ai.ModelInfo>()
        if (ep.defaultModel.isNotEmpty()) merged[ep.defaultModel] = com.commitwise.idea.infra.ai.ModelInfo(ep.defaultModel, "推荐")
        ModelCache.getInstance().get(ep.provider.id, ep.baseUrl.ifEmpty { state.baseUrl })
            ?.forEach { merged.putIfAbsent(it.id, it) }
        state.availableModels.forEach { id ->
            if (id.isNotBlank()) merged.putIfAbsent(id.trim(), com.commitwise.idea.infra.ai.ModelInfo(id.trim(), "手填候选"))
        }
        ProviderPresets.of(ep.provider.id).suggestions.forEach { merged.putIfAbsent(it.id, com.commitwise.idea.infra.ai.ModelInfo(it.id, it.note)) }
        val ordered = com.commitwise.idea.infra.ai.ModelCatalog.sortModels(merged.values.filter { it.id.isNotEmpty() })
        modelCombo.removeAllItems()
        ordered.forEach { modelCombo.addItem(it.id) }
        if (ep.defaultModel.isNotEmpty()) {
            modelCombo.selectedItem = ep.defaultModel
        } else if (ordered.isNotEmpty()) {
            modelCombo.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        fun row(label: String, component: JComponent): JPanel {
            val p = JPanel()
            p.layout = BoxLayout(p, BoxLayout.X_AXIS)
            val l = JBLabel(label)
            l.preferredSize = Dimension(JBUI.scale(110), JBUI.scale(28))
            p.add(l)
            p.add(component)
            p.maximumSize = Dimension(JBUI.scale(560), JBUI.scale(34))
            return p
        }

        val modelRow = JPanel()
        modelRow.layout = BoxLayout(modelRow, BoxLayout.X_AXIS)
        val fetchBtn = JButton("获取列表").apply {
            addActionListener {
                val picked = fetchModelsSync()
                if (picked != null) {
                    modelCombo.selectedItem = picked
                } else {
                    populateModelCombo(currentEndpoint())
                }
            }
        }
        modelRow.add(modelCombo)
        modelRow.add(Box.createHorizontalStrut(JBUI.scale(6)))
        modelRow.add(fetchBtn)
        modelRow.maximumSize = Dimension(JBUI.scale(560), JBUI.scale(34))

        panel.add(row("服务商 / 入口", endpointCombo))
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(row("接口地址", baseUrlField))
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(row("模型", modelRow))
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(row("API 密钥", apiKeyField))
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        panel.add(keyHintLabel)
        panel.add(Box.createVerticalStrut(JBUI.scale(10)))
        panel.add(testButton)
        panel.add(Box.createVerticalStrut(JBUI.scale(8)))
        panel.add(hintLabel)

        val existing = Secrets.get(settings.state.provider)
        if (!existing.isNullOrEmpty()) {
            apiKeyField.text = existing
            hintLabel.text = "已存在保存的密钥，留空则保持不变"
        }
        return panel
    }

    private fun fetchModelsSync(): String? {
        val ep = currentEndpoint()
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isEmpty()) {
            hintLabel.text = "请先填写接口地址"
            return null
        }
        if (!ModelCatalog.supportsModelList(ep.provider.id)) {
            hintLabel.text = "该服务商不支持自动获取，请手填模型名"
            return null
        }
        val key = String(apiKeyField.password).trim().ifEmpty { Secrets.resolve(ep.provider.id, "") }
        var picked: String? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                val models = ModelCache.getInstance()
                    .fetch(ep.provider.id, baseUrl, key, 15000, CancelToken())
                picked = ModelCatalog.pickDefaultModel(models, listOf(ep.defaultModel))
                ApplicationManager.getApplication().invokeLater {
                    hintLabel.text = "已获取 ${models.size} 个模型"
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    hintLabel.text = if (e is AiError) e.toUserMessage() else "获取失败：${e.message}"
                }
            }
        }, "获取模型列表", true, project)
        return picked
    }

    private fun test() {
        val ep = currentEndpoint()
        val state = settings.state
        val backup = SavedState(
            state.provider, state.baseUrl, state.model,
        )
        // 用表单里的临时值校验，改完即还原，不污染已保存配置
        state.provider = ep.provider.id
        state.baseUrl = baseUrlField.text.trim()
        state.model = modelComboValue()
        val key = String(apiKeyField.password).trim().ifEmpty { Secrets.resolve(ep.provider.id, state.apiKeyEnv) }

        ApplicationManager.getApplication().executeOnPooledThread {
            val validation = ConfigValidator.validate(settings.toRawConfig(), "zh", key)
            val config = validation.config
            val message = if (config == null) {
                "配置有误：${validation.errors.firstOrNull() ?: "未知错误"}"
            } else {
                try {
                    val (ok, sample, ms) = CallModel.ping(config, CancelToken())
                    if (ok) "连接成功（${ms}ms）：$sample" else "连接失败"
                } catch (e: Exception) {
                    if (e is AiError) e.toUserMessage() else "连接失败：${e.message}"
                }
            }
            state.provider = backup.provider
            state.baseUrl = backup.baseUrl
            state.model = backup.model
            ApplicationManager.getApplication().invokeLater { hintLabel.text = message }
        }
    }

    private data class SavedState(val provider: String, val baseUrl: String, val model: String)

    override fun doOKAction() {
        val ep = currentEndpoint()
        val state = settings.state
        val baseUrl = baseUrlField.text.trim()
        val model = modelComboValue()

        if (baseUrl.isEmpty() && ep.provider != ProviderId.AZURE_OPENAI) {
            Messages.showWarningDialog("接口地址不能为空。", "CommitWise")
            return
        }

        state.provider = ep.provider.id
        state.baseUrl = baseUrl
        state.model = model
        if (ep.requiresApiKey) {
            val key = String(apiKeyField.password).trim()
            if (key.isNotEmpty()) {
                Secrets.set(ep.provider.id, key)
            }
        }
        settings.fireChanged()
        StatusHolder.refreshFromConfig()
        CommitWiseLogger.info("快速配置已保存：${ep.label} / ${state.baseUrl} / ${state.model}")
        super.doOKAction()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}

object QuickSetupFlow {
    fun run(project: Project) {
        val dialog = QuickSetupDialog(project)
        if (dialog.showAndGet()) {
            Notify.info(project, "配置已保存，可以生成提交信息了。")
        }
    }
}

object SetApiKeyFlow {
    fun run(project: Project?) {
        val settings = CommitWiseSettings.getInstance()
        val provider = settings.state.provider
        val label = ProviderPresets.of(provider).label
        val existing = Secrets.get(provider)
        val value = Messages.showPasswordDialog(
            project,
            "为 $label 设置 API 密钥（保存在系统凭据库，不会写入配置文件）",
            "CommitWise · 设置 API 密钥",
            null,
        ) ?: return

        if (value.isBlank()) {
            Notify.warn(project, "密钥为空，未做修改。")
            return
        }
        Secrets.set(provider, value)
        StatusHolder.refreshFromConfig()
        Notify.info(project, "已保存 $label 的 API 密钥。")
        if (existing != null) CommitWiseLogger.info("已覆盖原有密钥（$provider）")
    }

    fun clear(project: Project?) {
        val settings = CommitWiseSettings.getInstance()
        val provider = settings.state.provider
        Secrets.clear(provider)
        StatusHolder.refreshFromConfig()
        Notify.info(project, "已清除 ${ProviderPresets.of(provider).label} 的 API 密钥。")
    }
}

object SelectModelFlow {
    fun run(project: Project) {
        val settings = CommitWiseSettings.getInstance()
        val dialog = ModelPickerDialog(project, settings.state.model)
        if (!dialog.showAndGet()) return
        val picked = dialog.selectedModel ?: return
        settings.state.model = picked
        settings.fireChanged()
        StatusHolder.refreshFromConfig()
        Notify.info(project, "已切换模型：$picked")
    }
}

object RefreshModelsFlow {
    fun run(project: Project) {
        val settings = CommitWiseSettings.getInstance()
        val state = settings.state
        if (!ModelCatalog.supportsModelList(state.provider)) {
            Notify.warn(project, "该服务商不提供模型列表接口（Azure 请手填 deployment 名称）。")
            return
        }
        var count = 0
        var error: String? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                val apiKey = Secrets.resolve(state.provider, state.apiKeyEnv)
                val models = ModelCache.getInstance().fetch(state.provider, state.baseUrl, apiKey, 15000, CancelToken())
                count = models.size
            } catch (e: Exception) {
                error = if (e is AiError) e.toUserMessage() else "获取失败：${e.message}"
            }
        }, "获取模型列表", true, project)

        val err = error
        if (err != null) {
            Notify.error(project, err)
        } else {
            Notify.info(project, "已获取 $count 个可用模型，可在「选择模型」中查看。")
        }
    }
}

object TestConnectionFlow {
    fun run(project: Project?) {
        val settings = CommitWiseSettings.getInstance()
        var ok = false
        var message = ""
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val raw = settings.toRawConfig()
            val key = Secrets.resolve(raw.provider, raw.apiKeyEnv)
            val validation = ConfigValidator.validate(raw, "zh", key)
            val config = validation.config
            message = if (config == null) {
                "配置有误：${validation.errors.firstOrNull() ?: "未知错误"}"
            } else {
                try {
                    val (_, sample, ms) = CallModel.ping(config, CancelToken())
                    ok = true
                    "连接成功（${ms}ms）：$sample"
                } catch (e: Exception) {
                    if (e is AiError) e.toUserMessage() else "连接失败：${e.message}"
                }
            }
        }, "测试连接", true, project)

        if (ok) Notify.info(project, message) else Notify.error(project, message)
    }
}
