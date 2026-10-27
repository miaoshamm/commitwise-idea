package com.commitwise.idea.ui

import com.commitwise.idea.infra.ai.AiError
import com.commitwise.idea.infra.ai.CancelToken
import com.commitwise.idea.infra.ai.ModelCache
import com.commitwise.idea.infra.ai.ModelCatalog
import com.commitwise.idea.infra.ai.ModelInfo
import com.commitwise.idea.infra.config.ProviderPresets
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.CommitWiseSettings
import com.commitwise.idea.services.Secrets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 模型选择器：接口返回的列表 + 用户手填的候选合并展示，支持过滤与刷新。
 * 对应 VSCode 版设置页里的自绘 combobox。
 */
class ModelPickerDialog(
    private val project: Project?,
    private val currentModel: String,
) : DialogWrapper(project) {

    private val settings = CommitWiseSettings.getInstance()
    private val cache = ModelCache.getInstance()
    private val filter = JBTextField()
    private val listModel = DefaultListModel<ModelInfo>()
    private val list = JBList(listModel)
    private val hint = JBLabel(" ")

    var selectedModel: String? = null
        private set

    init {
        title = "选择模型"
        setOKButtonText("使用该模型")
        init()
        loadModels()
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })
    }

    private fun loadModels() {
        val state = settings.state
        val merged = LinkedHashMap<String, ModelInfo>()
        merged[state.model.trim()] = ModelInfo(state.model.trim(), "当前使用")
        cache.get(state.provider, state.baseUrl)?.forEach { merged[it.id] = it }
        state.availableModels.forEach { id ->
            if (id.isNotBlank()) merged.putIfAbsent(id.trim(), ModelInfo(id.trim(), "手填候选"))
        }
        ProviderPresets.of(state.provider).suggestions.forEach { s ->
            merged.putIfAbsent(s.id, ModelInfo(s.id, s.note))
        }

        val models = merged.values.filter { it.id.isNotEmpty() }
        val ordered = ModelCatalog.sortModels(models)
        listModel.clear()
        ordered.forEach { listModel.addElement(it) }
        list.selectedIndex = (0 until listModel.size()).firstOrNull { listModel.getElementAt(it).id == currentModel } ?: 0
        hint.text = "共 ${ordered.size} 个候选"
    }

    private val allModels = mutableListOf<ModelInfo>()

    private fun applyFilter() {
        val needle = filter.text.trim().lowercase()
        val snapshot = (0 until listModel.size()).map { listModel.getElementAt(it) }
        if (allModels.isEmpty()) allModels.addAll(snapshot)
        val source = if (allModels.isNotEmpty()) allModels else snapshot
        listModel.clear()
        source.filter { needle.isEmpty() || it.id.lowercase().contains(needle) || (it.note ?: "").lowercase().contains(needle) }
            .forEach { listModel.addElement(it) }
    }

    override fun createCenterPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : com.intellij.ui.ColoredListCellRenderer<ModelInfo>() {
            override fun customizeCellRenderer(
                l: javax.swing.JList<out ModelInfo>,
                value: ModelInfo?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.id)
                if (!value.note.isNullOrBlank()) {
                    append("  ${value.note}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        val top = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(0, 0, JBUI.scale(6), 0)
            add(JBLabel("过滤："), BorderLayout.WEST)
            add(filter, BorderLayout.CENTER)
        }

        // 双击即选中并确认，与原生下拉框体验一致
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 2 && list.selectedValue != null) {
                    doOKAction()
                }
            }
        })

        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(320))

        val refresh = javax.swing.JButton("刷新模型列表").apply {
            addActionListener { refreshFromApi() }
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(top, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(hint, BorderLayout.WEST)
                add(refresh, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
    }

    private fun refreshFromApi() {
        val state = settings.state
        val provider = state.provider
        if (!ModelCatalog.supportsModelList(provider)) {
            hint.text = "该服务商不支持自动获取，请直接在设置里手填模型名"
            return
        }
        hint.text = "正在获取…"
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiKey = Secrets.resolve(provider, state.apiKeyEnv)
                val models = cache.fetch(provider, state.baseUrl, apiKey, 15000, CancelToken())
                ApplicationManager.getApplication().invokeLater {
                    allModels.clear()
                    allModels.addAll(ModelCatalog.sortModels(models))
                    listModel.clear()
                    allModels.forEach { listModel.addElement(it) }
                    if (listModel.size() > 0) list.selectedIndex = 0
                    hint.text = "已获取 ${models.size} 个模型，双击或选中后点「使用该模型」"
                }
            } catch (e: Exception) {
                CommitWiseLogger.warn("获取模型列表失败：${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    hint.text = if (e is AiError) e.toUserMessage() else "获取失败：${e.message}"
                }
            }
        }
    }

    override fun doOKAction() {
        val picked = list.selectedValue
        if (picked == null) {
            Messages.showInfoMessage("请先选择一个模型。", "CommitWise")
            return
        }
        selectedModel = picked.id
        super.doOKAction()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}
