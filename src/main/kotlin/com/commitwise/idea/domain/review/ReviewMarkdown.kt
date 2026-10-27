package com.commitwise.idea.domain.review

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ReviewReport(
    val savedAt: Long,
    val repoName: String,
    val branch: String,
    val providerLabel: String,
    val model: String,
    val elapsedMs: Long,
    val summary: String,
    val issues: List<ReviewIssue>,
    val byFile: List<Pair<String, List<ReviewIssue>>>,
    val counts: Map<Severity, Int>,
    val notices: List<String>,
    val truncated: Boolean,
    val totalBeforeLimit: Int,
) {
    val total: Int get() = (counts[Severity.HIGH] ?: 0) + (counts[Severity.MEDIUM] ?: 0) + (counts[Severity.LOW] ?: 0)
}

object ReviewMarkdown {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun stamp(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(STAMP)

    /** 把审查结果导出为 Markdown，便于贴到 PR / 群里 / 记录到文档。 */
    fun toMarkdown(src: ReviewReport): String {
        val lines = mutableListOf<String>()

        lines.add("# CommitWise 代码审查报告")
        lines.add("")
        lines.add("- 仓库：${src.repoName}（${src.branch}）")
        lines.add("- 模型：${src.providerLabel} / ${src.model}")
        lines.add("- 时间：${stamp(src.savedAt)} · 用时 ${"%.1f".format(src.elapsedMs / 1000.0)}s")
        lines.add(
            "- 结果：共 ${src.total} 条（高 ${src.counts[Severity.HIGH] ?: 0} / " +
                "中 ${src.counts[Severity.MEDIUM] ?: 0} / 低 ${src.counts[Severity.LOW] ?: 0}）",
        )
        lines.add("")

        if (src.summary.isNotEmpty()) {
            lines.add("## 摘要")
            lines.add("")
            lines.add(src.summary)
            lines.add("")
        }

        if (src.notices.isNotEmpty()) {
            lines.add("## 审查说明")
            lines.add("")
            for (n in src.notices) lines.add("- $n")
            lines.add("")
        }

        if (src.issues.isEmpty()) {
            lines.add("未发现需要处理的问题。")
            return lines.joinToString("\n")
        }

        if (src.truncated) {
            lines.add("> 共 ${src.totalBeforeLimit} 条问题，此处仅列出前 ${src.issues.size} 条。")
            lines.add("")
        }

        lines.add("## 问题清单")
        lines.add("")
        for ((file, issues) in src.byFile) {
            lines.add("### $file")
            lines.add("")
            for (issue in issues) {
                val loc = if (issue.line != null) "第 ${issue.line} 行" else "文件级"
                lines.add(
                    "- **[${issue.severity.label}][${issue.category.label}] ${issue.title}**（$loc）",
                )
                if (issue.detail.isNotEmpty()) {
                    lines.add("  ${issue.detail}")
                }
                if (!issue.suggestion.isNullOrEmpty()) {
                    lines.add("")
                    lines.add("  ```")
                    for (l in issue.suggestion.split("\n")) lines.add("  $l")
                    lines.add("  ```")
                }
            }
            lines.add("")
        }

        return lines.joinToString("\n").trimEnd() + "\n"
    }
}
