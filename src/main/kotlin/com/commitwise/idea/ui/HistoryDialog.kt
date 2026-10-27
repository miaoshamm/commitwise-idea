package com.commitwise.idea.ui

import com.commitwise.idea.infra.git.RepoLocator
import com.commitwise.idea.services.CommitHistoryService
import com.commitwise.idea.services.HistoryEntry
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 提交信息历史：误清空输入框时把之前那条找回来 */
class HistoryDialog(private val project: Project) : DialogWrapper(project) {

    private val service = CommitHistoryService.getInstance()
    private val listModel = DefaultListModel<HistoryEntry>()
    private val list = JBList(listModel)
    private val stamp = SimpleDateFormat("MM-dd HH:mm")

    var picked: HistoryEntry? = null
        private set

    init {
        title = "提交信息历史"
        setOKButtonText("写入输入框")
        init()
        reload()
    }

    private fun reload() {
        listModel.clear()
        service.list().forEach { listModel.addElement(it) }
        if (listModel.isEmpty()) {
            list.emptyText.text = "还没有生成过提交信息"
        }
    }

    override fun createCenterPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<HistoryEntry>() {
            override fun customizeCellRenderer(
                l: JList<out HistoryEntry>,
                value: HistoryEntry?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                val first = value.message.lineSequence().firstOrNull() ?: value.message
                append(first)
                val meta = buildString {
                    append("    ")
                    append(stamp.format(Date(value.time)))
                    if (value.repoName.isNotEmpty()) append(" · ${value.repoName}")
                    if (value.branch.isNotEmpty()) append(" · ${value.branch}")
                    if (value.model.isNotEmpty()) append(" · ${value.model}")
                }
                append(meta, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val body = value.message.lineSequence().drop(1).joinToString(" ")
                if (body.isNotBlank()) {
                    append("\n${body.take(160)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }

        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(JBUI.scale(680), JBUI.scale(360))

        val copy = JButton("复制").apply {
            addActionListener {
                val v = list.selectedValue ?: return@addActionListener
                CommitMessageBridge.copyToClipboard(v.message)
                Notify.info(project, "已复制到剪贴板。")
            }
        }
        val remove = JButton("删除该条").apply {
            addActionListener {
                val idx = list.selectedIndex
                if (idx < 0) return@addActionListener
                service.removeAt(idx)
                reload()
            }
        }
        val clear = JButton("清空历史").apply {
            addActionListener {
                service.clear()
                reload()
            }
        }

        val bottom = JPanel(BorderLayout()).apply {
            add(copy, BorderLayout.WEST)
            add(JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
                add(remove)
                add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)))
                add(clear)
            }, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(scroll, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    override fun doOKAction() {
        val value = list.selectedValue
        if (value == null) {
            Notify.warn(project, "请先选择一条历史记录。")
            return
        }
        picked = value
        super.doOKAction()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}

object HistoryFlow {
    fun run(project: Project) {
        val dialog = HistoryDialog(project)
        if (!dialog.showAndGet()) return
        val entry = dialog.picked ?: return
        val repo = RepoLocator.pick(project)
        if (repo == null) {
            Notify.warn(project, "没有找到 Git 仓库。")
            return
        }
        val outcome = CommitMessageBridge.write(project, repo, entry.message)
        StatusHolder.succeeded(if (outcome == WriteOutcome.INPUT_BOX) "已写入输入框" else "已复制")
        if (outcome == WriteOutcome.INPUT_BOX) {
            Notify.infoWith(project, "已写回历史提交信息", "恢复原草稿" to {
                CommitMessageBridge.restore(project, repo)
                Notify.info(project, "已恢复原草稿。")
            })
        } else {
            Notify.info(project, "提交窗口未打开，提交信息已复制到剪贴板。")
        }
    }
}

object RestoreDraftFlow {
    fun run(project: Project) {
        if (!CommitMessageBridge.hasBackup()) {
            Notify.warn(project, "没有可恢复的草稿（只有生成过提交信息才会有备份）。")
            return
        }
        val repo = RepoLocator.pick(project)
        if (repo == null) {
            Notify.warn(project, "没有找到 Git 仓库。")
            return
        }
        val root = CommitMessageBridge.restore(project, repo)
        if (root == null) {
            Notify.warn(project, "该仓库没有备份的草稿。")
        } else {
            Notify.info(project, "已恢复生成前的提交信息草稿。")
        }
    }
}
