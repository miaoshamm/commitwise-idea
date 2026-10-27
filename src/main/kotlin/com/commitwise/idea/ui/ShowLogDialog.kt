package com.commitwise.idea.ui

import com.commitwise.idea.infra.log.CommitWiseLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/** 直接展示插件最近日志，避免让用户去翻 idea.log */
class ShowLogDialog(private val project: Project?) : DialogWrapper(project) {

    init {
        title = "CommitWise 日志"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(CommitWiseLogger.dump().ifBlank { "（暂无日志）" })
        area.isEditable = false
        area.lineWrap = false
        area.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBUI.scale(12))
        val scroll = JBScrollPane(area)
        scroll.preferredSize = Dimension(JBUI.scale(820), JBUI.scale(460))
        return scroll
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    companion object {
        fun show(project: Project?) {
            ShowLogDialog(project).show()
        }
    }
}
