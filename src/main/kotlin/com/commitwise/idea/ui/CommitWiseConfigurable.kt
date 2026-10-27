package com.commitwise.idea.ui

import com.commitwise.idea.domain.commit.CommitFormat
import com.commitwise.idea.domain.prompt.CustomTemplate
import com.commitwise.idea.domain.prompt.TemplateRegistry
import com.commitwise.idea.domain.prompt.TemplateType
import com.commitwise.idea.domain.review.Severity
import com.commitwise.idea.infra.ai.AiError
import com.commitwise.idea.infra.ai.CancelToken
import com.commitwise.idea.infra.ai.ModelCache
import com.commitwise.idea.infra.ai.ModelCatalog
import com.commitwise.idea.infra.config.ProviderId
import com.commitwise.idea.infra.config.ProviderPresets
import com.commitwise.idea.services.CommitWiseSettings
import com.commitwise.idea.services.Secrets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * CommitWise 设置页（对应 VSCode 版的独立设置面板，五个分区）。
 *
 * 与 VSCode 版一致的分区划分：模型服务 / 提交信息 / 代码审查 / 上下文与隐私 / 高级。
 */
class CommitWiseConfigurable : Configurable {

    private val settings = CommitWiseSettings.getInstance()

    private var root: JPanel? = null

    // 模型服务
    private val providerCombo = ComboBox(ProviderId.entries.toTypedArray())
    private val baseUrlField = JBTextField()
    private val modelCombo = ComboBox<String>().apply { isEditable = true }
    private val apiKeyField = JPasswordField()
    private val apiKeyEnvField = JBTextField()
    private val autoFetchCheck = JBCheckBox("自动获取模型列表")
    private val availableModelsArea = JBTextArea()
    private val providerHint = JBLabel(" ")

    // 请求参数
    private val temperatureSpinner = JSpinner(SpinnerNumberModel(0.2, 0.0, 2.0, 0.1))
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(1024, 64, 200000, 64))
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(60000, 5000, 600000, 1000))
    private val retriesSpinner = JSpinner(SpinnerNumberModel(1, 0, 5, 1))

    // 提交信息
    private val formatCombo = ComboBox(CommitFormat.entries.toTypedArray())
    private val languageCombo = ComboBox(arrayOf("auto", "zh-CN", "en"))
    private val commitTemplateCombo = ComboBox<String>()
    private val subjectLengthSpinner = JSpinner(SpinnerNumberModel(72, 10, 200, 1))
    private val includeBodyCheck = JBCheckBox("输出正文（说明为什么这样改）")
    private val recentCommitsSpinner = JSpinner(SpinnerNumberModel(5, 0, 20, 1))
    private val confirmInsertCheck = JBCheckBox("写入输入框前先确认（可编辑）")

    // 代码审查
    private val reviewTemplateCombo = ComboBox<String>()
    private val minSeverityCombo = ComboBox(Severity.entries.toTypedArray())
    private val maxIssuesSpinner = JSpinner(SpinnerNumberModel(30, 1, 500, 1))
    private val showDiagnosticsCheck = JBCheckBox("在编辑器中标记问题（波浪线 + Alt+Enter 快速修复）")

    // 上下文与隐私
    private val maxTotalSpinner = JSpinner(SpinnerNumberModel(48000, 1000, 2000000, 1000))
    private val maxFileSpinner = JSpinner(SpinnerNumberModel(20000, 500, 1000000, 500))
    private val excludeArea = JBTextArea()
    private val redactCheck = JBCheckBox("发送前对疑似密钥脱敏")

    // 高级
    private val customTemplateList = CustomTemplateListPanel()

    override fun getDisplayName(): String = "CommitWise"

    override fun createComponent(): JComponent {
        providerCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.label }
        formatCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.label }
        languageCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") {
            when (it) {
                "zh-CN" -> "简体中文"
                "en" -> "English"
                else -> "跟随 IDE 语言"
            }
        }
        minSeverityCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.label }

        availableModelsArea.rows = 3
        excludeArea.rows = 4
        providerCombo.addActionListener { updateProviderHint() }

        val fetchModelsBtn = JButton("获取列表").apply { addActionListener { fetchModels() } }
        val pickModelBtn = JButton("选择…").apply { addActionListener { openModelPicker() } }
        val setKeyBtn = JButton("设置密钥").apply { addActionListener { SetApiKeyFlow.run(currentProject()) } }
        val clearKeyBtn = JButton("清除密钥").apply { addActionListener { SetApiKeyFlow.clear(currentProject()) } }
        val testBtn = JButton("测试连接").apply { addActionListener { TestConnectionFlow.run(currentProject()) } }

        val modelRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(modelCombo, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(fetchModelsBtn)
                add(pickModelBtn)
            }, BorderLayout.EAST)
            maximumSize = Dimension(JBUI.scale(760), JBUI.scale(30))
        }
        val keyRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(apiKeyField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(setKeyBtn)
                add(clearKeyBtn)
            }, BorderLayout.EAST)
            maximumSize = Dimension(JBUI.scale(760), JBUI.scale(30))
        }

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(12, 14, 12, 14)

        // ① 模型服务
        content.add(sectionTitle("模型服务", "决定把代码发给谁、用哪个模型"))
        content.add(row("服务商类型", providerCombo))
        content.add(providerHint.also { it.foreground = UIUtil.getLabelDisabledForeground() })
        content.add(row("接口地址", baseUrlField))
        content.add(row("默认模型", modelRow))
        content.add(row("API 密钥", keyRow))
        content.add(hint("密钥保存在系统凭据库，不会写入任何配置文件；留空表示沿用已保存的密钥。"))
        content.add(row("密钥环境变量名", apiKeyEnvField))
        content.add(hint("填写后优先从该环境变量读取密钥（CI 场景有用），例如 DEEPSEEK_API_KEY。"))
        content.add(row("", autoFetchCheck))
        content.add(row("额外候选模型", JBScrollPane(availableModelsArea).also {
            it.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(70))
            it.maximumSize = Dimension(JBUI.scale(760), JBUI.scale(80))
        }))
        content.add(hint("每行一个。用于接口不支持列表、或想固定几个常用模型的场景。"))
        content.add(row("", JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(testBtn) }))
        content.add(vspace(14))

        // ② 请求参数
        content.add(sectionTitle("请求参数", "影响成本、速度与稳定性"))
        content.add(row("temperature", temperatureSpinner))
        content.add(row("maxTokens", maxTokensSpinner))
        content.add(row("超时（毫秒）", timeoutSpinner))
        content.add(row("失败重试次数", retriesSpinner))
        content.add(vspace(14))

        // ③ 提交信息
        content.add(sectionTitle("提交信息", "生成结果的格式、语言与长度"))
        content.add(row("提交格式", formatCombo))
        content.add(row("输出语言", languageCombo))
        content.add(row("提示词模板", commitTemplateCombo))
        content.add(row("首行最大长度", subjectLengthSpinner))
        content.add(row("", includeBodyCheck))
        content.add(row("参考最近提交条数", recentCommitsSpinner))
        content.add(row("", confirmInsertCheck))
        content.add(vspace(14))

        // ④ 代码审查
        content.add(sectionTitle("代码审查", "审查范围与结果展示"))
        content.add(row("审查模板", reviewTemplateCombo))
        content.add(row("最低报告级别", minSeverityCombo))
        content.add(row("最多展示条数", maxIssuesSpinner))
        content.add(row("", showDiagnosticsCheck))
        content.add(vspace(14))

        // ⑤ 上下文与隐私
        content.add(sectionTitle("上下文与隐私", "发送多少代码、发送前做什么处理"))
        content.add(row("补丁总字符上限", maxTotalSpinner))
        content.add(row("单文件字符上限", maxFileSpinner))
        content.add(row("排除规则（glob）", JBScrollPane(excludeArea).also {
            it.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(90))
            it.maximumSize = Dimension(JBUI.scale(760), JBUI.scale(100))
        }))
        content.add(hint("每行一条，命中则内容不发送（仍在文件清单里列出），支持 *、**、?。"))
        content.add(row("", redactCheck))
        content.add(vspace(14))

        // ⑥ 高级
        content.add(sectionTitle("高级", "自定义提示词模板"))
        content.add(customTemplateList)
        content.add(vspace(10))

        val scroll = JBScrollPane(content)
        scroll.border = JBUI.Borders.empty()
        root = JPanel(BorderLayout()).apply { add(scroll, BorderLayout.CENTER) }
        reset()
        return root!!
    }

    private fun currentProject(): com.intellij.openapi.project.Project? =
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()

    private fun sectionTitle(title: String, tagline: String): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = 0f
        val label = JBLabel(title).apply {
            font = font.deriveFont(font.size2D + 2f).deriveFont(java.awt.Font.BOLD)
        }
        panel.add(label)
        panel.add(JBLabel(tagline).apply {
            foreground = UIUtil.getLabelDisabledForeground()
            font = font.deriveFont(font.size2D - 1f)
        })
        panel.border = JBUI.Borders.empty(4, 0, 8, 0)
        panel.maximumSize = Dimension(JBUI.scale(900), JBUI.scale(52))
        return panel
    }

    private fun row(label: String, component: JComponent): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        val l = JBLabel(label)
        l.preferredSize = Dimension(JBUI.scale(150), JBUI.scale(28))
        l.minimumSize = Dimension(JBUI.scale(150), JBUI.scale(28))
        panel.add(l)
        panel.add(component)
        panel.maximumSize = Dimension(JBUI.scale(900), JBUI.scale(34))
        panel.alignmentX = 0f
        return panel
    }

    private fun hint(text: String): JComponent {
        val l = JBLabel(text)
        l.foreground = UIUtil.getLabelDisabledForeground()
        l.font = l.font.deriveFont(l.font.size2D - 1f)
        l.border = JBUI.Borders.empty(0, JBUI.scale(150), 6, 0)
        l.maximumSize = Dimension(JBUI.scale(900), JBUI.scale(40))
        l.alignmentX = 0f
        return l
    }

    private fun vspace(height: Int): JComponent {
        val panel = JPanel()
        panel.preferredSize = Dimension(JBUI.scale(900), JBUI.scale(height))
        panel.maximumSize = Dimension(JBUI.scale(900), JBUI.scale(height))
        panel.alignmentX = 0f
        return panel
    }

    private fun updateProviderHint() {
        val provider = providerCombo.selectedItem as? ProviderId ?: ProviderId.OPENAI_COMPATIBLE
        val preset = ProviderPresets.of(provider.id)
        providerHint.text = "接口地址：${preset.urlHint}　模型：${preset.modelHint}"
        modelCombo.toolTipText = preset.modelHint
        apiKeyField.isEnabled = preset.requiresApiKey
        populateModelCombo()
    }

    /** 当前下拉框的值：优先选中项，其次编辑器里输入的文本 */
    private fun modelComboValue(): String =
        ((modelCombo.selectedItem as? String) ?: (modelCombo.editor.item as? String) ?: "").trim()

    /**
     * 用缓存 + 手填候选 + 服务商推荐填充模型下拉框。
     * 保留用户当前输入的值（即便不在候选里也能留住），便于切服务商后快速挑选。
     */
    private fun populateModelCombo(keepCurrent: Boolean = true) {
        val state = settings.state
        val current = if (keepCurrent) modelComboValue() else state.model
        val merged = LinkedHashMap<String, com.commitwise.idea.infra.ai.ModelInfo>()
        if (current.isNotEmpty()) merged[current] = com.commitwise.idea.infra.ai.ModelInfo(current, "当前使用")
        ModelCache.getInstance().get(state.provider, state.baseUrl)?.forEach { merged.putIfAbsent(it.id, it) }
        state.availableModels.forEach { id ->
            if (id.isNotBlank()) merged.putIfAbsent(id.trim(), com.commitwise.idea.infra.ai.ModelInfo(id.trim(), "手填候选"))
        }
        ProviderPresets.of(state.provider).suggestions.forEach { s ->
            merged.putIfAbsent(s.id, com.commitwise.idea.infra.ai.ModelInfo(s.id, s.note))
        }
        val ordered = com.commitwise.idea.infra.ai.ModelCatalog.sortModels(merged.values.filter { it.id.isNotEmpty() })
        modelCombo.removeAllItems()
        ordered.forEach { modelCombo.addItem(it.id) }
        if (current.isNotEmpty()) {
            modelCombo.selectedItem = current
        } else if (ordered.isNotEmpty()) {
            modelCombo.selectedIndex = 0
        }
    }

    /** 打开模型选择对话框（带搜索 + 刷新），选定后回填下拉框 */
    private fun openModelPicker() {
        val dialog = ModelPickerDialog(currentProject(), modelComboValue())
        if (!dialog.showAndGet()) return
        val picked = dialog.selectedModel ?: return
        modelCombo.selectedItem = picked
    }

    private fun fetchModels() {
        val provider = (providerCombo.selectedItem as? ProviderId)?.id ?: return
        if (!ModelCatalog.supportsModelList(provider)) {
            Messages.showInfoMessage("该服务商不支持自动获取模型列表，请直接手填模型名。", "CommitWise")
            return
        }
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isEmpty()) {
            Messages.showInfoMessage("请先填写接口地址。", "CommitWise")
            return
        }
        val key = String(apiKeyField.password).trim().ifEmpty {
            Secrets.resolve(provider, apiKeyEnvField.text.trim())
        }
        var error: String? = null
        var picked: String? = null
        var count = 0
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                val models = ModelCache.getInstance().fetch(provider, baseUrl, key, 15000, CancelToken())
                count = models.size
                picked = ModelCatalog.pickDefaultModel(models, ProviderPresets.of(provider).suggestions.map { it.id })
            } catch (e: Exception) {
                error = if (e is AiError) e.toUserMessage() else "获取失败：${e.message}"
            }
        }, "获取模型列表", true, currentProject())

        val err = error
        if (err != null) {
            Messages.showErrorDialog(err, "CommitWise")
            return
        }
        // 获取成功后缓存已更新，重填下拉框；用户没填过模型时自动选用推荐默认值
        if (modelComboValue().isBlank() && picked != null) {
            populateModelCombo()
            modelCombo.selectedItem = picked
        } else {
            populateModelCombo()
        }
        Messages.showInfoMessage("已获取 $count 个可用模型，可在下拉框中直接选择。", "CommitWise")
    }

    private fun availableModels(): List<String> =
        availableModelsArea.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun excludeGlobs(): MutableList<String> =
        excludeArea.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun refreshTemplateCombos() {
        val registry = TemplateRegistry(settings.customTemplates)
        commitTemplateCombo.removeAllItems()
        registry.list(TemplateType.COMMIT).forEach { commitTemplateCombo.addItem(it.name) }
        reviewTemplateCombo.removeAllItems()
        registry.list(TemplateType.REVIEW).forEach { reviewTemplateCombo.addItem(it.name) }
        customTemplateList.setTemplates(settings.customTemplates)
    }

    private fun commitTemplateIdByName(name: String?): String? {
        if (name == null) return null
        return TemplateRegistry(settings.customTemplates).list(TemplateType.COMMIT)
            .firstOrNull { it.name == name }?.id
    }

    private fun reviewTemplateIdByName(name: String?): String? {
        if (name == null) return null
        return TemplateRegistry(settings.customTemplates).list(TemplateType.REVIEW)
            .firstOrNull { it.name == name }?.id
    }

    override fun isModified(): Boolean {
        val state = settings.state
        val custom = settings.customTemplates
        return state.provider != (providerCombo.selectedItem as? ProviderId)?.id
            || state.baseUrl != baseUrlField.text.trim()
            || state.model != modelComboValue()
            || state.apiKeyEnv != apiKeyEnvField.text.trim()
            || state.autoFetchModels != autoFetchCheck.isSelected
            || state.availableModels.toList() != availableModels()
            || state.temperature != (temperatureSpinner.value as Number).toDouble()
            || state.maxTokens != (maxTokensSpinner.value as Number).toInt()
            || state.requestTimeoutMs != (timeoutSpinner.value as Number).toInt()
            || state.maxRetries != (retriesSpinner.value as Number).toInt()
            || state.commitFormat != (formatCombo.selectedItem as? CommitFormat)?.id
            || state.commitLanguage != languageCombo.selectedItem
            || state.commitTemplateId != commitTemplateIdByName(commitTemplateCombo.selectedItem as? String)
            || state.maxSubjectLength != (subjectLengthSpinner.value as Number).toInt()
            || state.includeBody != includeBodyCheck.isSelected
            || state.recentCommitCount != (recentCommitsSpinner.value as Number).toInt()
            || state.confirmBeforeInsert != confirmInsertCheck.isSelected
            || state.reviewTemplateId != reviewTemplateIdByName(reviewTemplateCombo.selectedItem as? String)
            || state.minSeverity != (minSeverityCombo.selectedItem as? Severity)?.id
            || state.maxIssues != (maxIssuesSpinner.value as Number).toInt()
            || state.showDiagnostics != showDiagnosticsCheck.isSelected
            || state.maxTotalChars != (maxTotalSpinner.value as Number).toInt()
            || state.maxFileChars != (maxFileSpinner.value as Number).toInt()
            || state.excludeGlobs.toList() != excludeGlobs()
            || state.redactSecrets != redactCheck.isSelected
            || custom != customTemplateList.getTemplates()
    }

    override fun apply() {
        val state = settings.state
        (providerCombo.selectedItem as? ProviderId)?.let { state.provider = it.id }
        state.baseUrl = baseUrlField.text.trim()
        state.model = modelComboValue()
        state.apiKeyEnv = apiKeyEnvField.text.trim()
        state.autoFetchModels = autoFetchCheck.isSelected
        state.availableModels = availableModels().toMutableList()
        state.temperature = (temperatureSpinner.value as Number).toDouble()
        state.maxTokens = (maxTokensSpinner.value as Number).toInt()
        state.requestTimeoutMs = (timeoutSpinner.value as Number).toInt()
        state.maxRetries = (retriesSpinner.value as Number).toInt()
        state.commitFormat = (formatCombo.selectedItem as? CommitFormat)?.id ?: "conventional"
        state.commitLanguage = languageCombo.selectedItem as? String ?: "auto"
        commitTemplateIdByName(commitTemplateCombo.selectedItem as? String)?.let { state.commitTemplateId = it }
        state.maxSubjectLength = (subjectLengthSpinner.value as Number).toInt()
        state.includeBody = includeBodyCheck.isSelected
        state.recentCommitCount = (recentCommitsSpinner.value as Number).toInt()
        state.confirmBeforeInsert = confirmInsertCheck.isSelected
        reviewTemplateIdByName(reviewTemplateCombo.selectedItem as? String)?.let { state.reviewTemplateId = it }
        state.minSeverity = (minSeverityCombo.selectedItem as? Severity)?.id ?: "low"
        state.maxIssues = (maxIssuesSpinner.value as Number).toInt()
        state.showDiagnostics = showDiagnosticsCheck.isSelected
        state.maxTotalChars = (maxTotalSpinner.value as Number).toInt()
        state.maxFileChars = (maxFileSpinner.value as Number).toInt()
        state.excludeGlobs = excludeGlobs()
        state.redactSecrets = redactCheck.isSelected
        settings.customTemplates = customTemplateList.getTemplates()

        // 密钥：只有用户真的输入了新值才覆盖，留空表示保持原样
        val key = String(apiKeyField.password).trim()
        val provider = state.provider
        if (key.isNotEmpty()) {
            Secrets.set(provider, key)
            apiKeyField.text = ""
        }

        settings.fireChanged()
        StatusHolder.refreshFromConfig()
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(
            currentProject() ?: return,
        ).restart()
    }

    override fun reset() {
        val state = settings.state
        providerCombo.selectedItem = ProviderId.ofOrDefault(state.provider)
        baseUrlField.text = state.baseUrl
        populateModelCombo(keepCurrent = false)
        updateProviderHint()
        apiKeyField.text = ""
        apiKeyEnvField.text = state.apiKeyEnv
        autoFetchCheck.isSelected = state.autoFetchModels
        availableModelsArea.text = state.availableModels.joinToString("\n")
        temperatureSpinner.value = state.temperature
        maxTokensSpinner.value = state.maxTokens
        timeoutSpinner.value = state.requestTimeoutMs
        retriesSpinner.value = state.maxRetries
        formatCombo.selectedItem = CommitFormat.of(state.commitFormat)
        languageCombo.selectedItem = state.commitLanguage
        subjectLengthSpinner.value = state.maxSubjectLength
        includeBodyCheck.isSelected = state.includeBody
        recentCommitsSpinner.value = state.recentCommitCount
        confirmInsertCheck.isSelected = state.confirmBeforeInsert
        minSeverityCombo.selectedItem = Severity.entries.firstOrNull { it.id == state.minSeverity } ?: Severity.LOW
        maxIssuesSpinner.value = state.maxIssues
        showDiagnosticsCheck.isSelected = state.showDiagnostics
        maxTotalSpinner.value = state.maxTotalChars
        maxFileSpinner.value = state.maxFileChars
        excludeArea.text = state.excludeGlobs.joinToString("\n")
        redactCheck.isSelected = state.redactSecrets

        refreshTemplateCombos()
        val registry = TemplateRegistry(settings.customTemplates)
        commitTemplateCombo.selectedItem = registry.get(state.commitTemplateId)?.name
            ?: registry.list(TemplateType.COMMIT).first().name
        reviewTemplateCombo.selectedItem = registry.get(state.reviewTemplateId)?.name
            ?: registry.list(TemplateType.REVIEW).first().name
        updateProviderHint()
    }

    override fun disposeUIResources() {
        root = null
    }
}

/** 自定义模板列表：新增 / 编辑 / 删除 */
class CustomTemplateListPanel : JPanel(BorderLayout()) {

    private var templates: List<CustomTemplate> = emptyList()
    private val listModel = javax.swing.DefaultListModel<CustomTemplate>()
    private val list = com.intellij.ui.components.JBList(listModel)

    init {
        list.cellRenderer = object : com.intellij.ui.ColoredListCellRenderer<CustomTemplate>() {
            override fun customizeCellRenderer(
                l: javax.swing.JList<out CustomTemplate>,
                value: CustomTemplate?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.name.ifBlank { value.id })
                append("  ${value.type}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(120))
        scroll.maximumSize = Dimension(JBUI.scale(760), JBUI.scale(130))

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JButton("新增").apply {
                addActionListener {
                    val dialog = CustomTemplateDialog(null)
                    if (dialog.showAndGet()) {
                        val tpl = dialog.result ?: return@addActionListener
                        templates = templates + tpl
                        setTemplates(templates)
                    }
                }
            })
            add(JButton("编辑").apply {
                addActionListener {
                    val idx = list.selectedIndex
                    if (idx < 0) return@addActionListener
                    val dialog = CustomTemplateDialog(templates[idx])
                    if (dialog.showAndGet()) {
                        val tpl = dialog.result ?: return@addActionListener
                        templates = templates.toMutableList().also { it[idx] = tpl }
                        setTemplates(templates)
                    }
                }
            })
            add(JButton("删除").apply {
                addActionListener {
                    val idx = list.selectedIndex
                    if (idx < 0) return@addActionListener
                    templates = templates.toMutableList().also { it.removeAt(idx) }
                    setTemplates(templates)
                }
            })
        }

        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.WEST)
            add(JBLabel("模板中必须包含 {{diff}}").apply {
                foreground = UIUtil.getLabelDisabledForeground()
            }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        maximumSize = Dimension(JBUI.scale(900), JBUI.scale(170))
        alignmentX = 0f
    }

    fun setTemplates(list: List<CustomTemplate>) {
        templates = list
        listModel.clear()
        list.forEach { listModel.addElement(it) }
    }

    fun getTemplates(): List<CustomTemplate> = templates
}

/** 自定义模板编辑器 */
class CustomTemplateDialog(private val existing: CustomTemplate?) :
    com.intellij.openapi.ui.DialogWrapper(null) {

    private val idField = JBTextField(existing?.id ?: "")
    private val typeCombo = ComboBox(arrayOf("commit", "review")).apply { selectedItem = existing?.type ?: "commit" }
    private val nameField = JBTextField(existing?.name ?: "")
    private val descField = JBTextField(existing?.description ?: "")
    private val systemArea = JBTextArea(existing?.systemPrompt ?: "").apply { rows = 3 }
    private val userArea = JBTextArea(existing?.userPrompt ?: "").apply { rows = 12 }

    var result: CustomTemplate? = null
        private set

    init {
        title = if (existing == null) "新增自定义模板" else "编辑自定义模板"
        setOKButtonText("保存")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        fun labeled(label: String, component: JComponent, height: Int = 30): JComponent {
            val p = JPanel()
            p.layout = BoxLayout(p, BoxLayout.X_AXIS)
            val l = JBLabel(label)
            l.preferredSize = Dimension(JBUI.scale(110), JBUI.scale(height))
            p.add(l)
            p.add(component)
            p.maximumSize = Dimension(JBUI.scale(720), JBUI.scale(height + 6))
            return p
        }

        panel.add(labeled("id", idField))
        panel.add(labeled("类型", typeCombo))
        panel.add(labeled("名称", nameField))
        panel.add(labeled("描述", descField))
        panel.add(labeled("system 提示", JBScrollPane(systemArea).also {
            it.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(70))
        }, height = 76))
        panel.add(labeled("user 提示", JBScrollPane(userArea).also {
            it.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(240))
        }, height = 246))
        return panel
    }

    override fun doOKAction() {
        val tpl = CustomTemplate(
            id = idField.text.trim(),
            type = typeCombo.selectedItem as? String ?: "commit",
            name = nameField.text.trim(),
            description = descField.text.trim(),
            systemPrompt = systemArea.text,
            userPrompt = userArea.text,
        )
        val errors = com.commitwise.idea.domain.prompt.RenderTemplate.validate(
            com.commitwise.idea.domain.prompt.PromptTemplate(
                id = tpl.id,
                type = if (tpl.type == "review") TemplateType.REVIEW else TemplateType.COMMIT,
                name = tpl.name,
                userPrompt = tpl.userPrompt,
            ),
        )
        if (errors.isNotEmpty()) {
            Messages.showErrorDialog(errors.joinToString("\n") { it.message }, "模板校验失败")
            return
        }
        result = tpl
        super.doOKAction()
    }
}
