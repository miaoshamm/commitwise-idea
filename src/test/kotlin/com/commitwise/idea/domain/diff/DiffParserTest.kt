package com.commitwise.idea.domain.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffParserTest {

    private val modified = """
        |diff --git a/src/a.ts b/src/a.ts
        |index 1111111..2222222 100644
        |--- a/src/a.ts
        |+++ b/src/a.ts
        |@@ -1,3 +1,4 @@ function hello
        | const a = 1
        |-const b = 2
        |+const b = 3
        |+const c = 4
        | export default a
    """.trimMargin()

    @Test
    fun `解析修改文件的路径、状态与增删计数`() {
        val files = DiffParser.parse(modified)
        assertEquals(1, files.size)
        val f = files[0]
        assertEquals("src/a.ts", f.path)
        assertNull(f.oldPath)
        assertEquals(FileStatus.MODIFIED, f.status)
        assertEquals(2, f.additions)
        assertEquals(1, f.deletions)
    }

    @Test
    fun `hunk 头部与行号正确递增`() {
        val hunk = DiffParser.parse(modified)[0].hunks.single()
        assertEquals(1, hunk.oldStart)
        assertEquals(3, hunk.oldLines)
        assertEquals(1, hunk.newStart)
        assertEquals(4, hunk.newLines)
        assertEquals("function hello", hunk.section)

        val kinds = hunk.lines.map { it.kind }
        assertEquals(
            listOf(LineKind.CTX, LineKind.DEL, LineKind.ADD, LineKind.ADD, LineKind.CTX),
            kinds,
        )
        // 上下文行同时持有新旧行号
        assertEquals(1, hunk.lines[0].oldLineNo)
        assertEquals(1, hunk.lines[0].newLineNo)
        // 删除行只有旧行号
        assertEquals(2, hunk.lines[1].oldLineNo)
        assertNull(hunk.lines[1].newLineNo)
        // 新增行只有新行号
        assertEquals(2, hunk.lines[2].newLineNo)
        assertEquals(3, hunk.lines[3].newLineNo)
        assertNull(hunk.lines[3].oldLineNo)
        // 尾部上下文行：旧 3 / 新 4
        assertEquals(3, hunk.lines[4].oldLineNo)
        assertEquals(4, hunk.lines[4].newLineNo)
    }

    @Test
    fun `addedLineNumbers 只包含新增行`() {
        assertEquals(setOf(2, 3), DiffParser.addedLineNumbers(DiffParser.parse(modified)[0]))
    }

    @Test
    fun `renderFilePatch 可无损还原补丁`() {
        val f = DiffParser.parse(modified)[0]
        assertEquals(modified, DiffParser.renderFilePatch(f))
    }

    @Test
    fun `新增文件识别为 ADDED 且旧路径为空`() {
        val patch = """
            |diff --git a/new.txt b/new.txt
            |new file mode 100644
            |index 0000000..1111111
            |--- /dev/null
            |+++ b/new.txt
            |@@ -0,0 +1,2 @@
            |+hello
            |+world
        """.trimMargin()
        val f = DiffParser.parse(patch).single()
        assertEquals(FileStatus.ADDED, f.status)
        assertEquals("new.txt", f.path)
        assertNull(f.oldPath)
        assertEquals(setOf(1, 2), DiffParser.addedLineNumbers(f))
    }

    @Test
    fun `删除文件的路径回落为原路径`() {
        val patch = """
            |diff --git a/old.txt b/old.txt
            |deleted file mode 100644
            |--- a/old.txt
            |+++ /dev/null
            |@@ -1,1 +0,0 @@
            |-bye
        """.trimMargin()
        val f = DiffParser.parse(patch).single()
        assertEquals(FileStatus.DELETED, f.status)
        assertEquals("old.txt", f.path)
        assertEquals(1, f.deletions)
        assertEquals(emptySet(), DiffParser.addedLineNumbers(f))
    }

    @Test
    fun `重命名保留旧路径`() {
        val patch = """
            |diff --git a/old/name.ts b/new/name.ts
            |similarity index 95%
            |rename from old/name.ts
            |rename to new/name.ts
            |--- a/old/name.ts
            |+++ b/new/name.ts
            |@@ -1,1 +1,1 @@
            |-x
            |+y
        """.trimMargin()
        val f = DiffParser.parse(patch).single()
        assertEquals(FileStatus.RENAMED, f.status)
        assertEquals("new/name.ts", f.path)
        assertEquals("old/name.ts", f.oldPath)
    }

    @Test
    fun `二进制文件被标记且无 hunk`() {
        val patch = """
            |diff --git a/logo.png b/logo.png
            |new file mode 100644
            |index 0000000..1111111
            |Binary files /dev/null and b/logo.png differ
        """.trimMargin()
        val f = DiffParser.parse(patch).single()
        assertTrue(f.binary)
        assertEquals(FileStatus.BINARY, f.status)
        assertTrue(f.hunks.isEmpty())
    }

    @Test
    fun `带引号的路径可正确去引号与前缀`() {
        val patch = """
            |diff --git "a/with space.txt" "b/with space.txt"
            |--- "a/with space.txt"
            |+++ "b/with space.txt"
            |@@ -1,1 +1,1 @@
            |-a
            |+b
        """.trimMargin()
        val f = DiffParser.parse(patch).single()
        assertEquals("with space.txt", f.path)
    }

    @Test
    fun `多文件补丁按顺序切分`() {
        val patch = modified + "\n" + """
            |diff --git a/b.txt b/b.txt
            |--- a/b.txt
            |+++ b/b.txt
            |@@ -1,1 +1,1 @@
            |-1
            |+2
        """.trimMargin()
        val files = DiffParser.parse(patch)
        assertEquals(listOf("src/a.ts", "b.txt"), files.map { it.path })
    }

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList(), DiffParser.parse(""))
    }
}
