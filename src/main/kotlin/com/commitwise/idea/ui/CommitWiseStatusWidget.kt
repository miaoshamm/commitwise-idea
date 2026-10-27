package com.commitwise.idea.ui

import com.commitwise.idea.infra.config.ProviderPresets
import com.commitwise.idea.services.CommitWiseSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * 状态栏：同时回答「我现在用的是哪个模型」和「刚才的审查结果去哪看了」。
 */
class CommitWiseStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = CommitWiseStatusWidget.ID

    override fun getDisplayName(): String = "CommitWise"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = CommitWiseStatusWidget(project)
}

class CommitWiseStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val listener: () -> Unit = {
        statusBar?.updateWidget(ID)
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        StatusHolder.addListener(listener)
        StatusHolder.refreshFromConfig()
        val review = com.commitwise.idea.services.ReviewStore.getInstance(project).get()
        StatusHolder.setReviewTotal(review?.total)
    }

    override fun dispose() {
        StatusHolder.removeListener(listener)
        statusBar = null
    }

    override fun getText(): String {
        val state = CommitWiseSettings.getInstance().state
        val model = state.model.trim().ifEmpty { ProviderPresets.of(state.provider).defaultModel }
        val modelText = if (model.isEmpty()) "未配置模型" else model

        return when (StatusHolder.phase) {
            StatusHolder.Phase.COLLECTING -> "CommitWise · 读取暂存区…"
            StatusHolder.Phase.REQUESTING -> "CommitWise · ${StatusHolder.actionText}"
            StatusHolder.Phase.IDLE -> {
                val total = StatusHolder.reviewTotal
                when {
                    total == null -> "CommitWise · $modelText"
                    total == 0 -> "CommitWise · 审查无问题 · $modelText"
                    else -> "CommitWise · $total 个问题 · $modelText"
                }
            }
        }
    }

    override fun getAlignment(): Float = ComponentPlace.LEFT

    override fun getTooltipText(): String {
        val state = CommitWiseSettings.getInstance().state
        val provider = ProviderPresets.of(state.provider).label
        val model = state.model.trim().ifEmpty { ProviderPresets.of(state.provider).defaultModel }
        val review = com.commitwise.idea.services.ReviewStore.getInstance(project).get()
        val sb = StringBuilder()
        sb.append("CommitWise\n当前模型：$provider / $model")
        if (review != null) {
            sb.append("\n上次审查：${review.total} 条问题（${review.repoName} · ${review.branch}）")
            sb.append("\n点击打开审查报告")
        } else {
            sb.append("\n点击切换模型")
        }
        return sb.toString()
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val review = com.commitwise.idea.services.ReviewStore.getInstance(project).get()
        if (review != null) ReportDialog.show(project) else SelectModelFlow.run(project)
    }

    companion object {
        const val ID = "CommitWiseStatus"
    }
}

private object ComponentPlace {
    const val LEFT = 0.0f
}
