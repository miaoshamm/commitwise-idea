package com.commitwise.idea.actions

import com.commitwise.idea.ui.QuickSetupFlow
import com.commitwise.idea.ui.RefreshModelsFlow
import com.commitwise.idea.ui.SelectModelFlow
import com.commitwise.idea.ui.SetApiKeyFlow
import com.commitwise.idea.ui.ShowLogDialog
import com.commitwise.idea.ui.TestConnectionFlow
import com.commitwise.idea.ui.HistoryFlow
import com.commitwise.idea.ui.Notify
import com.commitwise.idea.ui.RestoreDraftFlow
import com.commitwise.idea.app.ReviewStaged
import com.commitwise.idea.app.GenerateCommit
import com.commitwise.idea.services.ReviewStore
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class QuickSetupAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        QuickSetupFlow.run(project)
    }
}

class SelectModelAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SelectModelFlow.run(project)
    }
}

class RefreshModelsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RefreshModelsFlow.run(project)
    }
}

class SetApiKeyAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SetApiKeyFlow.run(project)
    }
}

class ClearApiKeyAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SetApiKeyFlow.clear(project)
    }
}

class TestConnectionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        TestConnectionFlow.run(project)
    }
}

class ShowHistoryAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        HistoryFlow.run(project)
    }
}

class RestoreDraftAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RestoreDraftFlow.run(project)
    }
}

class CancelRequestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val deps = com.commitwise.idea.app.AppDeps(project)
        if (deps.cancellation.cancel()) {
            Notify.info(project, "已发送取消请求。")
        } else {
            Notify.info(project, "当前没有进行中的请求。")
        }
    }
}

class OpenSettingsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        Notify.openSettings(project)
    }
}

class ShowLogAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ShowLogDialog.show(e.project)
    }
}

/** 重新审查（工具窗口按钮之外，命令面板也可触发） */
class RerunReviewAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (ReviewStore.getInstance(project).isRunning()) {
            Notify.warn(project, "审查正在进行中，请稍候。")
            return
        }
        ReviewStaged.run(project)
    }
}

@Suppress("unused")
private val unusedRefs = listOf(GenerateCommit::class.java)
