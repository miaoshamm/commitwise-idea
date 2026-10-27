package com.commitwise.idea.domain.review

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewMarkdownTest {

    private fun report(
        issues: List<ReviewIssue> = emptyList(),
        truncated: Boolean = false,
        notices: List<String> = emptyList(),
    ): ReviewReport {
        val byFile = issues.groupBy { it.file }.map { it.key to it.value }
        val counts = mapOf(
            Severity.HIGH to issues.count { it.severity == Severity.HIGH },
            Severity.MEDIUM to issues.count { it.severity == Severity.MEDIUM },
            Severity.LOW to issues.count { it.severity == Severity.LOW },
        )
        return ReviewReport(
            savedAt = 1_760_000_000_000,
            repoName = "demo",
            branch = "main",
            providerLabel = "OpenAI",
            model = "gpt-4o",
            elapsedMs = 3_400,
            summary = "整体可用",
            issues = issues,
            byFile = byFile,
            counts = counts,
            notices = notices,
            truncated = truncated,
            totalBeforeLimit = if (truncated) issues.size + 5 else issues.size,
        )
    }

    @Test
    fun `无问题时给出明确结论`() {
        val md = ReviewMarkdown.toMarkdown(report())
        assertTrue(md.contains("# CommitWise 代码审查报告"))
        assertTrue(md.contains("未发现需要处理的问题。"))
        assertFalse(md.contains("## 问题清单"))
    }

    @Test
    fun `报告头包含仓库分支模型与统计`() {
        val md = ReviewMarkdown.toMarkdown(report())
        assertTrue(md.contains("- 仓库：demo（main）"))
        assertTrue(md.contains("- 模型：OpenAI / gpt-4o"))
        assertTrue(md.contains("- 结果：共 0 条（高 0 / 中 0 / 低 0）"))
        assertTrue(md.contains("3.4s"))
    }

    @Test
    fun `问题按文件分组并带级别分类与位置`() {
        val issues = listOf(
            ReviewIssue(
                id = "1",
                file = "src/a.ts",
                line = 12,
                severity = Severity.HIGH,
                category = Category.SECURITY,
                title = "明文拼接 SQL",
                detail = "存在注入风险",
            ),
            ReviewIssue(
                id = "2",
                file = "src/a.ts",
                severity = Severity.LOW,
                category = Category.STYLE,
                title = "命名不一致",
            ),
        )
        val md = ReviewMarkdown.toMarkdown(report(issues))
        assertTrue(md.contains("## 问题清单"))
        assertTrue(md.contains("### src/a.ts"))
        assertTrue(md.contains("**[高][安全] 明文拼接 SQL**（第 12 行）"))
        assertTrue(md.contains("**[低][风格] 命名不一致**（文件级）"))
        assertTrue(md.contains("存在注入风险"))
        // 只有一个文件分组标题
        assertTrue(md.split("### ").size == 2)
    }

    @Test
    fun `建议以代码块输出`() {
        val issue = ReviewIssue(
            id = "1",
            file = "src/a.ts",
            line = 5,
            severity = Severity.MEDIUM,
            category = Category.BUG,
            title = "空指针",
            suggestion = "if (x == null) return",
        )
        val md = ReviewMarkdown.toMarkdown(report(listOf(issue)))
        assertTrue(md.contains("```"))
        assertTrue(md.contains("  if (x == null) return"))
    }

    @Test
    fun `截断时提示总数`() {
        val issue = ReviewIssue(id = "1", file = "a.ts", severity = Severity.LOW, title = "t")
        val md = ReviewMarkdown.toMarkdown(report(listOf(issue), truncated = true))
        assertTrue(md.contains("此处仅列出前 1 条"))
        assertTrue(md.contains("共 6 条问题"))
    }

    @Test
    fun `审查说明以列表输出`() {
        val md = ReviewMarkdown.toMarkdown(report(notices = listOf("已排除 2 个文件", "内容已脱敏")))
        assertTrue(md.contains("## 审查说明"))
        assertTrue(md.contains("- 已排除 2 个文件"))
        assertTrue(md.contains("- 内容已脱敏"))
    }

    @Test
    fun `摘要为空时不输出摘要段`() {
        val r = report().copy(summary = "")
        assertFalse(ReviewMarkdown.toMarkdown(r).contains("## 摘要"))
    }
}
