package com.commitwise.idea.ui

import com.commitwise.idea.domain.review.ReviewMarkdown
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.ReviewStore
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object ReviewCommands {

    fun copyMarkdown(project: Project) {
        val review = ReviewStore.getInstance(project).get()
        if (review == null) {
            Notify.warn(project, "还没有审查结果。")
            return
        }
        CommitMessageBridge.copyToClipboard(ReviewMarkdown.toMarkdown(review.toReport()))
        Notify.info(project, "审查报告已复制为 Markdown。")
    }

    fun clear(project: Project) {
        ReviewStore.getInstance(project).clear()
        StatusHolder.setReviewTotal(null)
        DaemonCodeAnalyzer.getInstance(project).restart()
        Notify.info(project, "已清空审查结果。")
    }

    fun clearMarkers(project: Project) {
        DaemonCodeAnalyzer.getInstance(project).restart()
        Notify.info(project, "已清除编辑器中的审查标记。")
    }

    /** 从问题清单跳到具体代码行 */
    fun openAt(project: Project, root: String, file: String, line: Int?) {
        val path = File(root, file).path
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))
        if (vf == null) {
            Notify.warn(project, "无法打开文件：$file（可能已被移动或删除）")
            return
        }
        val offset = (line ?: 1).coerceAtLeast(1) - 1
        OpenFileDescriptor(project, vf, offset, 0).navigate(true)
        CommitWiseLogger.debug("跳转到 $file:$line")
    }
}
