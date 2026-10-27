package com.commitwise.idea.domain.review

import com.commitwise.idea.domain.diff.DiffParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueMapperTest {

    /** src/a.ts 新增 2/3 行；new.ts 由 old.ts 改名而来；另外还有一个 b.ts */
    private fun diffFiles() = DiffParser.parse(
        """
        |diff --git a/src/a.ts b/src/a.ts
        |--- a/src/a.ts
        |+++ b/src/a.ts
        |@@ -1,3 +1,4 @@
        | const a = 1
        |-const b = 2
        |+const b = 3
        |+const c = 4
        | export default a
        |
        |diff --git a/old.ts b/new.ts
        |similarity index 90%
        |rename from old.ts
        |rename to new.ts
        |--- a/old.ts
        |+++ b/new.ts
        |@@ -1,1 +1,1 @@
        |-x
        |+y
        |
        |diff --git a/b.ts b/b.ts
        |--- a/b.ts
        |+++ b/b.ts
        |@@ -1,1 +1,1 @@
        |-1
        |+2
        """.trimMargin(),
    )

    private fun issue(
        file: String,
        line: Int? = null,
        severity: Severity = Severity.MEDIUM,
        title: String = "问题",
    ) = ReviewIssue(id = title, file = file, line = line, severity = severity, title = title)

    @Test
    fun `行号命中新增行时保留行级定位`() {
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("src/a.ts", 2))),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        val i = out.issues.single()
        assertEquals(Anchor.LINE, i.anchor)
        assertEquals(2, i.line)
    }

    @Test
    fun `行号不在新增行时降级为文件级`() {
        // 第 1 行是上下文行，第 99 行根本不存在
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("src/a.ts", 1), issue("src/a.ts", 99))),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        assertEquals(2, out.issues.size)
        assertTrue(out.issues.all { it.anchor == Anchor.FILE && it.line == null })
        assertEquals(2, out.dropped.outOfRange)
    }

    @Test
    fun `diff 中不存在的文件被丢弃`() {
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("nope.ts", 3))),
            diffFiles(),
            malformedDropped = 1,
            opts = MapOptions(),
        )
        assertTrue(out.issues.isEmpty())
        assertEquals(1, out.dropped.unknownFile)
        assertEquals(1, out.dropped.malformed)
    }

    @Test
    fun `只给文件名时按后缀匹配`() {
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("a.ts", 2))),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        assertEquals("src/a.ts", out.issues.single().file)
        assertEquals(Anchor.LINE, out.issues.single().anchor)
    }

    @Test
    fun `重命名的旧路径也对齐到新路径`() {
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("old.ts", 1))),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        val i = out.issues.single()
        assertEquals("new.ts", i.file)
        assertEquals(Anchor.LINE, i.anchor)
    }

    @Test
    fun `低于最小级别的问题被过滤`() {
        val out = IssueMapper.map(
            ReviewResult(
                "s",
                listOf(
                    issue("src/a.ts", 2, Severity.LOW, "低"),
                    issue("src/a.ts", 3, Severity.MEDIUM, "中"),
                    issue("b.ts", 1, Severity.HIGH, "高"),
                ),
            ),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(minSeverity = Severity.MEDIUM),
        )
        assertEquals(listOf("高", "中"), out.issues.map { it.title })
    }

    @Test
    fun `按级别排序后高在前`() {
        val out = IssueMapper.map(
            ReviewResult(
                "s",
                listOf(
                    issue("b.ts", 1, Severity.LOW, "低"),
                    issue("src/a.ts", 3, Severity.HIGH, "高"),
                    issue("src/a.ts", 2, Severity.MEDIUM, "中"),
                ),
            ),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        assertEquals(listOf("高", "中", "低"), out.issues.map { it.title })
    }

    @Test
    fun `超出上限时截断并给出总数`() {
        val out = IssueMapper.map(
            ReviewResult(
                "s",
                listOf(
                    issue("src/a.ts", 2, Severity.HIGH, "一"),
                    issue("src/a.ts", 3, Severity.HIGH, "二"),
                    issue("b.ts", 1, Severity.HIGH, "三"),
                ),
            ),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(maxIssues = 2),
        )
        assertEquals(2, out.issues.size)
        assertTrue(out.truncated)
        assertEquals(3, out.totalBeforeLimit)
    }

    @Test
    fun `分组与计数正确`() {
        val out = IssueMapper.map(
            ReviewResult(
                "s",
                listOf(
                    issue("src/a.ts", 2, Severity.HIGH, "一"),
                    issue("src/a.ts", 3, Severity.MEDIUM, "二"),
                    issue("b.ts", 1, Severity.HIGH, "三"),
                ),
            ),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        // 排序后先按级别，再按文件名：HIGH 的 b.ts 排在最前
        assertEquals(listOf("b.ts", "src/a.ts"), out.byFile.map { it.first })
        assertEquals(1, out.byFile[0].second.size)
        assertEquals(2, out.byFile[1].second.size)
        assertEquals(2, out.counts[Severity.HIGH])
        assertEquals(1, out.counts[Severity.MEDIUM])
        assertEquals(0, out.counts[Severity.LOW])
        assertEquals("s", out.summary)
    }

    @Test
    fun `带仓库前缀的绝对路径也能对齐`() {
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("/repo/src/a.ts", 2))),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        assertEquals("src/a.ts", out.issues.single().file)
    }

    @Test
    fun `maxIssues 至少保留一条`() {
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("src/a.ts", 2))),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(maxIssues = 0),
        )
        assertEquals(1, out.issues.size)
    }

    @Test
    fun `文件级问题保持文件级`() {
        val out = IssueMapper.map(
            ReviewResult("s", listOf(issue("src/a.ts"))),
            diffFiles(),
            malformedDropped = 0,
            opts = MapOptions(),
        )
        assertNull(out.issues.single().line)
        assertEquals(Anchor.FILE, out.issues.single().anchor)
    }
}
