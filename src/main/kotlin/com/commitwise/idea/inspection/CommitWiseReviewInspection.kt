package com.commitwise.idea.inspection

import com.commitwise.idea.domain.review.ReviewIssue
import com.commitwise.idea.domain.review.Severity
import com.commitwise.idea.services.CommitWiseSettings
import com.commitwise.idea.services.ReviewStore
import com.commitwise.idea.ui.ApplySuggestion
import com.commitwise.idea.ui.ReportDialog
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * 把审查结果落到编辑器：只处理能精确到新增行的条目。
 *
 * 文件级问题没有可靠的行号，随便挑一行打波浪线会误导用户，因此不标记。
 */
class CommitWiseReviewInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                checkFile(holder, file)
            }
        }

    private fun checkFile(holder: ProblemsHolder, file: PsiFile) {
        val project = holder.project
        val settings = CommitWiseSettings.getInstance()
        if (!settings.state.showDiagnostics) return

        val review = ReviewStore.getInstance(project).get() ?: return
        val root = review.root
        if (root.isEmpty()) return

        val path = file.virtualFile?.path ?: return
        val normalizedRoot = root.replace('\\', '/').trimEnd('/')
        val normalizedPath = path.replace('\\', '/')
        if (!normalizedPath.startsWith(normalizedRoot)) return
        val relative = normalizedPath.removePrefix(normalizedRoot).trimStart('/')

        val issues = review.issues.filter { it.file == relative && it.line != null }
        if (issues.isEmpty()) return

        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        for (issue in issues) {
            val lineIdx = (issue.line!! - 1).coerceIn(0, maxOf(0, document.lineCount - 1))
            val start = document.getLineStartOffset(lineIdx)
            val end = document.getLineEndOffset(lineIdx)
            val message = if (issue.detail.isBlank()) issue.title else "${issue.title}\n\n${issue.detail}"

            val fixes = mutableListOf<LocalQuickFix>()
            if (!issue.suggestion.isNullOrBlank()) {
                fixes.add(CommitWiseApplyFix(issue))
            }
            fixes.add(CommitWiseOpenReportFix())

            holder.registerProblem(
                file,
                message,
                highlightTypeOf(issue.severity),
                TextRange(start, end),
                *fixes.toTypedArray(),
            )
        }
    }

    private fun highlightTypeOf(severity: Severity): ProblemHighlightType = when (severity) {
        Severity.HIGH -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        Severity.MEDIUM -> ProblemHighlightType.WEAK_WARNING
        Severity.LOW -> ProblemHighlightType.INFORMATION
    }
}

/** 一键应用模型建议（与工具窗口的「应用建议」走同一条落地逻辑） */
class CommitWiseApplyFix(private val issue: ReviewIssue) : LocalQuickFix {

    override fun getName(): String = "CommitWise: 应用建议 — ${issue.title}"

    override fun getFamilyName(): String = "CommitWise"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        ApplySuggestion.apply(project, issue)
    }
}

class CommitWiseOpenReportFix : LocalQuickFix {

    override fun getName(): String = "CommitWise: 打开审查报告"

    override fun getFamilyName(): String = "CommitWise"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        ReportDialog.show(project)
    }
}
