package com.commitwise.idea.ui

import com.commitwise.idea.domain.review.ReviewIssue
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.ReviewStore
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * 把模型的审查建议应用到对应代码行。
 *
 * 用户在报告/工具窗口点击，或编辑器里 Alt+Enter 触发，统一走这条入口：
 *   1. 打开文件 → 2. 计算目标行 → 3. 弹确认预览（替换整行 / 在下方插入 / 取消）
 *   4. 写回文档，进入撤销栈。
 *
 * 绝不静默改写：模型建议与实际代码可能错位，必须先预览。
 */
object ApplySuggestion {

    fun apply(project: Project, issue: ReviewIssue): Boolean {
        if (issue.line == null || issue.suggestion.isNullOrBlank()) {
            return false
        }
        val review = ReviewStore.getInstance(project).get()
        val root = review?.root ?: return false

        val file = File(root, issue.file)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (vf == null) {
            Notify.warn(project, "无法打开文件：${issue.file}（可能已被移动或删除）")
            return false
        }
        val document = FileDocumentManager.getInstance().getDocument(vf)
        if (document == null) {
            Notify.warn(project, "无法读取文件内容：${issue.file}")
            return false
        }

        val suggestion = issue.suggestion
        val lineIdx = (issue.line - 1).coerceIn(0, maxOf(0, document.lineCount - 1))
        val targetText = document.charsSequence.let { text ->
            val start = document.getLineStartOffset(lineIdx)
            val end = document.getLineEndOffset(lineIdx)
            text.subSequence(start, end).toString()
        }

        val preview = if (suggestion.length > 240) suggestion.take(240) + "…" else suggestion
        val choice = Messages.showYesNoCancelDialog(
            project,
            "CommitWise 建议修复「${issue.file}:${issue.line}」\n\n原行：$targetText\n\n建议：\n$preview",
            "应用审查建议",
            "替换该行",
            "在下方插入",
            "取消",
            null,
        )
        if (choice != Messages.YES && choice != Messages.NO) {
            return false
        }

        WriteCommandAction.runWriteCommandAction(project, "应用 CommitWise 建议", null, {
            if (choice == Messages.YES) {
                val start = document.getLineStartOffset(lineIdx)
                val end = document.getLineEndOffset(lineIdx)
                document.replaceString(start, end, suggestion)
            } else {
                val insertAt = if (lineIdx + 1 < document.lineCount) {
                    document.getLineStartOffset(lineIdx + 1)
                } else {
                    document.textLength
                }
                val prefix = if (insertAt >= document.textLength) "\n" else ""
                document.insertString(insertAt, prefix + suggestion + "\n")
            }
        })

        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, lineIdx, 0), true)
        CommitWiseLogger.info(
            "已应用审查建议：${issue.file}:${issue.line}（${if (choice == Messages.YES) "替换该行" else "在下方插入"}）",
        )
        return true
    }
}
