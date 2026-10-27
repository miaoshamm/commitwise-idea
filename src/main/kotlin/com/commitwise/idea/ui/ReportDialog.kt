package com.commitwise.idea.ui

import com.commitwise.idea.domain.review.ReviewMarkdown
import com.commitwise.idea.services.ReviewStore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/** 审查报告（对应 VSCode 版的 Webview 报告面板） */
class ReportDialog(private val project: Project) : DialogWrapper(project) {

    init {
        title = "CommitWise 审查报告"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val review = ReviewStore.getInstance(project).get()
        val pane = JEditorPane("text/html", render(review?.toReport()))
        pane.isEditable = false
        pane.caretPosition = 0
        val scroll = JBScrollPane(pane)
        scroll.preferredSize = Dimension(JBUI.scale(860), JBUI.scale(560))

        val copy = JButton("复制为 Markdown").apply {
            addActionListener { ReviewCommands.copyMarkdown(project) }
        }
        val reRun = JButton("重新审查").apply {
            addActionListener {
                close(0)
                com.commitwise.idea.app.ReviewStaged.run(project)
            }
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(scroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(copy, BorderLayout.WEST)
                add(reRun, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun fg(): String {
        val c = UIUtil.getLabelForeground()
        return "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }

    private fun muted(): String {
        val c = UIUtil.getLabelDisabledForeground()
        return "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }

    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun render(report: com.commitwise.idea.domain.review.ReviewReport?): String {
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;padding:14px;color:${fg()}'>")
        if (report == null) {
            sb.append("<h2>CommitWise 代码审查报告</h2><p>还没有审查结果。</p></body></html>")
            return sb.toString()
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(report.savedAt))
        sb.append("<h2 style='margin:0 0 10px 0'>CommitWise 代码审查报告</h2>")
        sb.append("<div style='color:${muted()};font-size:11px;line-height:1.7'>")
            .append("仓库：").append(esc(report.repoName)).append("（").append(esc(report.branch)).append("）<br/>")
            .append("模型：").append(esc(report.providerLabel)).append(" / ").append(esc(report.model)).append("<br/>")
            .append("时间：").append(stamp).append(" · 用时 ")
            .append("%.1f".format(report.elapsedMs / 1000.0)).append("s<br/>")
            .append("结果：共 ").append(report.total).append(" 条（高 ")
            .append(report.counts[com.commitwise.idea.domain.review.Severity.HIGH] ?: 0).append(" / 中 ")
            .append(report.counts[com.commitwise.idea.domain.review.Severity.MEDIUM] ?: 0).append(" / 低 ")
            .append(report.counts[com.commitwise.idea.domain.review.Severity.LOW] ?: 0).append("）")
            .append("</div>")

        if (report.summary.isNotBlank()) {
            sb.append("<h3>摘要</h3><p style='line-height:1.6'>").append(esc(report.summary)).append("</p>")
        }

        if (report.notices.isNotEmpty()) {
            sb.append("<h3>审查说明</h3><ul style='line-height:1.6'>")
            report.notices.forEach { sb.append("<li>").append(esc(it)).append("</li>") }
            sb.append("</ul>")
        }

        if (report.issues.isEmpty()) {
            sb.append("<p>未发现需要处理的问题。</p></body></html>")
            return sb.toString()
        }

        if (report.truncated) {
            sb.append("<p style='color:${muted()}'>共 ").append(report.totalBeforeLimit)
                .append(" 条问题，此处仅列出前 ").append(report.issues.size).append(" 条。</p>")
        }

        sb.append("<h3>问题清单</h3>")
        for ((file, issues) in report.byFile) {
            sb.append("<div style='margin-top:12px;font-weight:bold'>").append(esc(file)).append("</div>")
            for (issue in issues) {
                val loc = if (issue.line != null) "第 ${issue.line} 行" else "文件级"
                sb.append("<div style='margin:8px 0 0 0'>")
                sb.append("<span style='color:${severityColor(issue.severity)};font-weight:bold'>")
                    .append("[").append(issue.severity.label).append("]")
                    .append("[").append(issue.category.label).append("]</span> ")
                    .append("<b>").append(esc(issue.title)).append("</b>")
                    .append("<span style='color:${muted()}'>（").append(loc).append("）</span>")
                if (issue.detail.isNotBlank()) {
                    sb.append("<div style='margin:2px 0 0 0;color:${muted()};line-height:1.6'>")
                        .append(esc(issue.detail)).append("</div>")
                }
                if (!issue.suggestion.isNullOrBlank()) {
                    sb.append("<pre style='background:#00000010;padding:8px;white-space:pre-wrap;font-family:monospace'>")
                        .append(esc(issue.suggestion)).append("</pre>")
                }
                sb.append("</div>")
            }
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun severityColor(severity: com.commitwise.idea.domain.review.Severity): String = when (severity) {
        com.commitwise.idea.domain.review.Severity.HIGH -> "#c0392b"
        com.commitwise.idea.domain.review.Severity.MEDIUM -> "#b9770e"
        com.commitwise.idea.domain.review.Severity.LOW -> "#2471a3"
    }

    companion object {
        fun show(project: Project) {
            ReportDialog(project).show()
        }

        fun markdown(project: Project): String? {
            val review = ReviewStore.getInstance(project).get() ?: return null
            return ReviewMarkdown.toMarkdown(review.toReport())
        }
    }
}
