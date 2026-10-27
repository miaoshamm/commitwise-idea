package com.commitwise.idea.actions

import com.commitwise.idea.app.GenerateCommit
import com.commitwise.idea.app.ReviewStaged
import com.commitwise.idea.domain.prompt.TemplateRegistry
import com.commitwise.idea.domain.prompt.TemplateType
import com.commitwise.idea.services.CommitWiseSettings
import com.commitwise.idea.ui.Notify
import com.commitwise.idea.ui.ReportDialog
import com.commitwise.idea.ui.ReviewCommands
import com.commitwise.idea.ui.ReviewToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class GenerateCommitAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GenerateCommit.run(project, e)
    }
}

/** 用指定模板生成（临时覆盖设置中的默认模板） */
class GenerateWithTemplateAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = CommitWiseSettings.getInstance()
        val templates = TemplateRegistry(settings.customTemplates).list(TemplateType.COMMIT)
        val labels = templates.map { it.name }.toTypedArray()
        val current = templates.indexOfFirst { it.id == settings.state.commitTemplateId }.takeIf { it >= 0 } ?: 0
        val index = Messages.showChooseDialog(
            project,
            "选择提示词模板",
            "CommitWise",
            null,
            labels,
            labels[current],
        )
        if (index < 0) return
        GenerateCommit.run(project, e, templates[index].id)
    }
}

class ReviewStagedAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewStaged.run(project, e)
    }
}

class ShowReviewReportAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (com.commitwise.idea.services.ReviewStore.getInstance(project).get() == null) {
            Notify.warn(project, "还没有审查结果。")
            return
        }
        ReportDialog.show(project)
    }
}

class CopyReviewMarkdownAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewCommands.copyMarkdown(project)
    }
}

class ClearReviewAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewCommands.clear(project)
    }
}

class ClearMarkersAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewCommands.clearMarkers(project)
    }
}

class ShowReviewWindowAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewToolWindowFactory.showWindow(project)
    }
}
