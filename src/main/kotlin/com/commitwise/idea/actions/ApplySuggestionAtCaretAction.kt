package com.commitwise.idea.actions

import com.commitwise.idea.services.ReviewStore
import com.commitwise.idea.ui.ApplySuggestion
import com.commitwise.idea.ui.Notify
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * 把光标所在行的审查建议应用到代码（对应 VSCode 版的 applySuggestion 命令）。
 *
 * 与工具窗口的「应用建议」按钮、编辑器的 Alt+Enter Quick Fix 共用同一条落地逻辑。
 */
class ApplySuggestionAtCaretAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (editor == null || file == null) {
            Notify.warn(project, "请先在编辑器中把光标放到要修复的行。")
            return
        }

        val review = ReviewStore.getInstance(project).get()
        if (review == null) {
            Notify.warn(project, "还没有审查结果。")
            return
        }

        val root = review.root.replace('\\', '/').trimEnd('/')
        val path = file.path.replace('\\', '/')
        if (!path.startsWith(root)) {
            Notify.warn(project, "当前文件不在上次审查的仓库中。")
            return
        }
        val relative = path.removePrefix(root).trimStart('/')
        val caretLine = editor.caretModel.logicalPosition.line + 1

        val issue = review.issues.firstOrNull { it.file == relative && it.line == caretLine }
            ?: review.issues.firstOrNull {
                it.file == relative && it.line != null && kotlin.math.abs(it.line - caretLine) <= 1
            }
        if (issue == null) {
            Notify.warn(project, "光标所在行附近没有审查建议。")
            return
        }
        if (issue.suggestion.isNullOrBlank() || issue.line == null) {
            Notify.warn(project, "该问题没有可直接应用的修复建议。")
            return
        }
        ApplySuggestion.apply(project, issue)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
