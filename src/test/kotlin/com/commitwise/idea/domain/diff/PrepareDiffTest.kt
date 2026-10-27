package com.commitwise.idea.domain.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepareDiffTest {

    private fun filePatch(path: String, lines: Int, prefix: String = "+"): String =
        "diff --git a/$path b/$path\n" +
            "--- a/$path\n" +
            "+++ b/$path\n" +
            "@@ -1,0 +1,$lines @@\n" +
            (1..lines).joinToString("\n") { "${prefix}line $it of $path" }

    private fun docsFile(): DiffFile = DiffFile(
        path = "docs/guide.md",
        status = FileStatus.ADDED,
        hunks = listOf(
            DiffHunk(
                oldStart = 0,
                oldLines = 0,
                newStart = 1,
                newLines = 1,
                section = "",
                lines = mutableListOf(DiffLine(LineKind.ADD, "hello docs", null, 1)),
            ),
        ),
        additions = 1,
    )

    @Test
    fun `命中排除规则的文件不进补丁但仍在清单里`() {
        val files = DiffParser.parse(filePatch("src/a.ts", 2)) + docsFile()
        val out = PrepareDiff.prepare(
            files,
            PrepareOptions(excludeGlobs = listOf("docs/**"), redactSecrets = false),
        )

        assertEquals(listOf("src/a.ts"), out.included.map { it.path })
        assertFalse(out.patch.contains("hello docs"))
        val skipped = out.skipped.single()
        assertEquals("docs/guide.md", skipped.path)
        assertEquals(SkipReason.EXCLUDED, skipped.reason)
        assertEquals("docs/**", skipped.detail)

        // 清单里仍然要体现被排除的文件，并带说明
        assertTrue(out.fileList.contains("docs/guide.md"))
        assertTrue(out.fileList.contains("已按规则排除"))
    }

    @Test
    fun `二进制文件被跳过`() {
        val binary = DiffFile(path = "logo.png", status = FileStatus.BINARY, binary = true)
        val out = PrepareDiff.prepare(listOf(binary), PrepareOptions(redactSecrets = false))
        assertEquals(SkipReason.BINARY, out.skipped.single().reason)
        assertTrue(out.patch.isEmpty())
        assertTrue(out.fileList.contains("二进制文件"))
    }

    @Test
    fun `无 hunk 的文件被跳过`() {
        val empty = DiffFile(path = "empty.txt", status = FileStatus.MODIFIED)
        val out = PrepareDiff.prepare(listOf(empty), PrepareOptions(redactSecrets = false))
        assertEquals("no hunk", out.skipped.single().detail)
    }

    @Test
    fun `超过单文件上限时截断并打标`() {
        val files = DiffParser.parse(filePatch("src/big.ts", 200))
        val out = PrepareDiff.prepare(
            files,
            PrepareOptions(maxFileChars = 800, maxTotalChars = 100_000, redactSecrets = false),
        )
        assertTrue(out.truncated.contains("src/big.ts"))
        assertTrue(out.patch.contains("该文件 diff 已截断"))
        assertTrue(out.patch.length < DiffParser.renderFilePatch(files[0]).length)
    }

    @Test
    fun `截断后仍保留至少部分行`() {
        val files = DiffParser.parse(filePatch("src/big.ts", 200))
        val out = PrepareDiff.prepare(
            files,
            PrepareOptions(maxFileChars = 500, maxTotalChars = 100_000, redactSecrets = false),
        )
        val kept = out.included.single()
        assertTrue(kept.hunks.isNotEmpty(), "至少保留一个 hunk")
        assertTrue(kept.hunks.first().lines.isNotEmpty(), "至少保留若干行")
    }

    @Test
    fun `超出总预算时丢弃大文件并记录原因`() {
        val files = DiffParser.parse(filePatch("src/big.ts", 40)) +
            DiffParser.parse(filePatch("src/mid.ts", 30)) +
            DiffParser.parse(filePatch("src/small.ts", 2))

        val out = PrepareDiff.prepare(
            files,
            PrepareOptions(maxFileChars = 100_000, maxTotalChars = 1000, redactSecrets = false),
        )

        assertTrue(out.included.size < files.size, "应有文件被丢弃")
        assertTrue(out.included.isNotEmpty(), "至少保留一个文件")
        assertTrue(
            out.skipped.any { it.detail == "超出上下文预算" },
            "被丢弃的文件应写入跳过原因",
        )
        assertTrue(out.patch.length <= 1000 + 40, "补丁长度应受总预算约束")
    }

    @Test
    fun `脱敏在产出前生效`() {
        val patch = "diff --git a/.env b/.env\n--- a/.env\n+++ b/.env\n@@ -0,0 +1,1 @@\n" +
            "+API_KEY=\"sk-abcdefghijklmnop1234\""
        val out = PrepareDiff.prepare(DiffParser.parse(patch), PrepareOptions())
        assertFalse(out.patch.contains("abcdefghijklmnop1234"))
        assertTrue(out.redactionHits >= 1)
        assertTrue(out.redactionRules.isNotEmpty())
    }

    @Test
    fun `关闭脱敏后保留原文`() {
        val patch = "diff --git a/.env b/.env\n--- a/.env\n+++ b/.env\n@@ -0,0 +1,1 @@\n" +
            "+API_KEY=\"sk-abcdefghijklmnop1234\""
        val out = PrepareDiff.prepare(DiffParser.parse(patch), PrepareOptions(redactSecrets = false))
        assertTrue(out.patch.contains("abcdefghijklmnop1234"))
        assertEquals(0, out.redactionHits)
    }

    @Test
    fun `文件清单含状态字母与增删计数`() {
        val files = DiffParser.parse(filePatch("src/a.ts", 3))
        val out = PrepareDiff.prepare(files, PrepareOptions(redactSecrets = false))
        val line = out.fileList.lines().single()
        assertTrue(line.startsWith("M\tsrc/a.ts\t+3\t-0"), "实际：$line")
    }

    @Test
    fun `重命名在清单中显示新旧路径`() {
        val patch = "diff --git a/old.ts b/new.ts\nsimilarity index 90%\n" +
            "rename from old.ts\nrename to new.ts\n--- a/old.ts\n+++ b/new.ts\n" +
            "@@ -1,1 +1,1 @@\n-x\n+y"
        val out = PrepareDiff.prepare(DiffParser.parse(patch), PrepareOptions(redactSecrets = false))
        assertTrue(out.fileList.contains("old.ts -> new.ts"), "实际：${out.fileList}")
        assertEquals("R", out.fileList.substringBefore('\t'))
    }

    @Test
    fun `chars 与实际补丁长度一致`() {
        val files = DiffParser.parse(filePatch("src/a.ts", 3))
        val out = PrepareDiff.prepare(files, PrepareOptions(redactSecrets = false))
        assertEquals(out.patch.length, out.chars)
    }
}
