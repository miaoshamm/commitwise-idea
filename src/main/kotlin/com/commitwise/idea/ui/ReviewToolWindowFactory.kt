package com.commitwise.idea.ui

import com.commitwise.idea.domain.review.Anchor
import com.commitwise.idea.domain.review.ReviewIssue
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.ReviewStore
import com.commitwise.idea.services.StoredReview
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

private data class FileNode(val file: String)
private data class IssueNode(val issue: ReviewIssue)

/** 审查问题清单（对应 VSCode 版侧边栏 TreeView + 报告面板） */
class ReviewPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val store = ReviewStore.getInstance(project)
    private val root = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(root)
    private val tree = JTree(treeModel)
    private val detail = JEditorPane("text/html", "")
    private val banner = JBLabel(" ")
    private val applyButton = JButton("应用建议")
    private val rerunButton = JButton("重新审查")
    private var selectedIssue: ReviewIssue? = null

    private val listener: (StoredReview?) -> Unit = { refresh() }

    init {
        border = JBUI.Borders.empty(4)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener { onSelect() }

        detail.isEditable = false
        detail.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED && e.description.contains("|")) {
                val (root, rest) = e.description.split("|", limit = 2)
                val parts = rest.split(":")
                val line = parts.getOrNull(1)?.toIntOrNull()
                ReviewCommands.openAt(project, root, parts[0], line)
            }
        }

        banner.foreground = UIUtil.getLabelForeground()
        banner.isVisible = false

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JButton("打开报告").apply { addActionListener { ReportDialog.show(project) } })
            add(JButton("复制为 Markdown").apply { addActionListener { ReviewCommands.copyMarkdown(project) } })
            add(rerunButton.apply {
                addActionListener {
                    if (store.isRunning()) return@addActionListener
                    com.commitwise.idea.app.ReviewStaged.run(project)
                }
                isEnabled = !store.isRunning()
            })
            add(JButton("清空结果").apply { addActionListener { ReviewCommands.clear(project) } })
        }

        applyButton.isEnabled = false
        applyButton.addActionListener {
            val issue = selectedIssue ?: return@addActionListener
            ApplySuggestion.apply(project, issue)
        }

        val north = JPanel(BorderLayout()).apply {
            add(banner, BorderLayout.NORTH)
            add(toolbar, BorderLayout.SOUTH)
        }

        val detailWrapper = JPanel(BorderLayout()).apply {
            add(JBScrollPane(detail), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(applyButton) }, BorderLayout.SOUTH)
        }

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(tree), detailWrapper)
        split.resizeWeight = 0.34
        add(north, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)

        store.addListener(listener)
        refresh()
    }

    override fun dispose() {
        store.removeListener(listener)
    }

    private fun refresh() {
        val review = store.get()
        root.removeAllChildren()

        if (review == null) {
            treeModel.reload()
            detail.text = emptyHtml("还没有审查结果。运行「审查暂存区代码」开始。")
            banner.isVisible = false
            applyButton.isEnabled = false
            return
        }

        for ((file, issues) in review.byFile()) {
            val fileNode = DefaultMutableTreeNode(FileNode(file))
            for (issue in issues) {
                fileNode.add(DefaultMutableTreeNode(IssueNode(issue)))
            }
            root.add(fileNode)
        }
        treeModel.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)

        rerunButton.isEnabled = !store.isRunning()
        banner.isVisible = store.isRunning()
        if (store.isRunning()) {
            banner.text = "正在审查暂存区代码…"
            banner.icon = AllIcons.Process.Step_1
        }

        if (review.issues.isEmpty()) {
            detail.text = emptyHtml("没有发现需要处理的问题。")
        }
    }

    private fun onSelect() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        when (val value = node.userObject) {
            is FileNode -> {
                selectedIssue = null
                applyButton.isEnabled = false
                detail.text = fileHtml(value.file, node)
            }
            is IssueNode -> {
                selectedIssue = value.issue
                applyButton.isEnabled = !value.issue.suggestion.isNullOrBlank() && value.issue.line != null
                detail.text = issueHtml(value.issue)
                detail.caretPosition = 0
            }
            else -> Unit
        }
    }

    private fun emptyHtml(text: String): String =
        "<html><body style='font-family:sans-serif;padding:10px;color:${color(UIUtil.getLabelForeground())}'>" +
            escape(text) + "</body></html>"

    private fun color(c: java.awt.Color): String =
        "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun fileHtml(file: String, node: DefaultMutableTreeNode): String {
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;padding:10px'>")
        sb.append("<h3 style='margin:0 0 8px 0'>").append(escape(file)).append("</h3>")
        sb.append("<ul style='margin:0;padding-left:18px'>")
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val issue = (child.userObject as? IssueNode)?.issue ?: continue
            val loc = if (issue.line != null) "第 ${issue.line} 行" else "文件级"
            sb.append("<li>[${issue.severity.label}][${issue.category.label}] ")
                .append(escape(issue.title)).append("（").append(loc).append("）</li>")
        }
        sb.append("</ul></body></html>")
        return sb.toString()
    }

    private fun issueHtml(issue: ReviewIssue): String {
        val review = store.get()
        val rootPath = review?.root ?: ""
        val loc = if (issue.anchor == Anchor.LINE && issue.line != null) {
            "<a href='${escapeAttr(rootPath)}|${escapeAttr(issue.file)}:${issue.line}'>" +
                "${escape(issue.file)}:${issue.line}</a>"
        } else {
            "${escape(issue.file)}（文件级）"
        }
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;padding:10px'>")
        sb.append("<div style='color:${color(UIUtil.getLabelForeground())};font-size:11px'>")
            .append(issue.severity.label).append(" · ").append(issue.category.label).append(" · ").append(loc)
            .append("</div>")
        sb.append("<h3 style='margin:6px 0 8px 0'>").append(escape(issue.title)).append("</h3>")
        if (issue.detail.isNotBlank()) {
            sb.append("<p style='margin:0 0 10px 0'>").append(escape(issue.detail).replace("\n", "<br/>")).append("</p>")
        }
        if (!issue.suggestion.isNullOrBlank()) {
            sb.append("<div style='font-size:11px;color:${color(UIUtil.getLabelForeground())}'>建议修改</div>")
            sb.append("<pre style='background:#00000010;padding:8px;white-space:pre-wrap;font-family:monospace'>")
                .append(escape(issue.suggestion))
                .append("</pre>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(text: String): String = escape(text).replace("'", "&#39;")
}

class ReviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "CommitWise"

        fun showWindow(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return@invokeLater
                toolWindow.show()
                toolWindow.activate(null, false)
                CommitWiseLogger.debug("审查工具窗口已显示")
            }
        }
    }
}
