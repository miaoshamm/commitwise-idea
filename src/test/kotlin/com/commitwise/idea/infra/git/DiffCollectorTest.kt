package com.commitwise.idea.infra.git

import com.commitwise.idea.domain.diff.DiffParser
import com.commitwise.idea.domain.diff.FileStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 用临时真实仓库验证 git 采集链路。
 * 夹具用 ProcessBuilder 直接跑 git（而不是 GitRunner），
 * 这样万一采集器有 bug，失败点会落在断言上而不是初始化的异常上。
 */
class DiffCollectorTest {

    private lateinit var dir: Path

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "--no-pager") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }

    private fun write(rel: String, content: String) {
        val f = dir.resolve(rel)
        f.parent?.let { Files.createDirectories(it) }
        Files.writeString(f, content)
    }

    private fun commitAll(message: String) {
        git("add", "-A")
        git("commit", "-q", "-m", message)
    }

    private fun snapshot(recent: Int = 5) = DiffCollector.collectStaged(dir.toString(), recent)

    @BeforeEach
    fun setUp() {
        val gitAvailable = try {
            ProcessBuilder("git", "--version").start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
        Assumptions.assumeTrue(gitAvailable, "环境里没有 git，跳过 git 集成测试")

        dir = Files.createTempDirectory("commitwise-git-")
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "CommitWise Test")
        // 关掉全局 autocrlf 的影响，避免行尾被改写导致补丁形态不稳定
        git("config", "core.autocrlf", "false")
    }

    @AfterEach
    fun tearDown() {
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    @Test
    fun `尚无提交时也能采集到暂存的新增文件`() {
        write("a.txt", "line1\nline2\n")
        git("add", "-A")

        val s = snapshot()
        assertTrue(s.isUnborn, "新仓库应识别为 unborn HEAD")
        assertEquals("main", s.branch)
        assertEquals(1, s.files.size)
        val f = s.files.single()
        assertEquals("a.txt", f.path)
        assertEquals(FileStatus.ADDED, f.status)
        assertEquals(setOf(1, 2), DiffParser.addedLineNumbers(f))
    }

    @Test
    fun `只采集已暂存的改动`() {
        write("a.txt", "line1\n")
        commitAll("first")

        // 未暂存的修改不应出现在快照里
        write("a.txt", "line1\nline2\n")
        assertTrue(snapshot().files.isEmpty(), "未 add 的改动不应被采集")

        git("add", "-A")
        val s = snapshot()
        assertFalse(s.isUnborn)
        val f = s.files.single()
        assertEquals(FileStatus.MODIFIED, f.status)
        assertEquals(setOf(2), DiffParser.addedLineNumbers(f))
    }

    @Test
    fun `删除文件被识别`() {
        write("a.txt", "line1\n")
        commitAll("first")
        git("rm", "-q", "a.txt")

        val f = snapshot().files.single()
        assertEquals(FileStatus.DELETED, f.status)
        assertEquals("a.txt", f.path)
        assertEquals(1, f.deletions)
    }

    @Test
    fun `重命名保留旧路径`() {
        write("a.txt", "same content\n")
        commitAll("first")
        git("mv", "a.txt", "b.txt")

        val f = snapshot().files.single()
        assertEquals(FileStatus.RENAMED, f.status)
        assertEquals("b.txt", f.path)
        assertEquals("a.txt", f.oldPath)
    }

    @Test
    fun `二进制文件被标记且没有 hunk`() {
        Files.write(dir.resolve("logo.bin"), byteArrayOf(0, 0, 1, 2, 3))
        git("add", "-A")

        val f = snapshot().files.single()
        assertTrue(f.binary, "含 NUL 字节应判为二进制")
        assertEquals(FileStatus.BINARY, f.status)
        assertTrue(f.hunks.isEmpty())
    }

    @Test
    fun `多文件按路径切分`() {
        write("src/a.ts", "export const a = 1\n")
        write("docs/b.md", "# b\n")
        git("add", "-A")

        assertEquals(listOf("docs/b.md", "src/a.ts"), snapshot().files.map { it.path }.sorted())
    }

    @Test
    fun `最近提交标题按时间倒序`() {
        write("a.txt", "1\n")
        commitAll("first commit")
        write("a.txt", "2\n")
        commitAll("second commit")

        assertEquals(listOf("second commit", "first commit"), snapshot(recent = 5).recentCommits)
    }

    @Test
    fun `recentCommitCount 为 0 时不查日志`() {
        write("a.txt", "1\n")
        commitAll("first")
        write("a.txt", "2\n")
        git("add", "-A")

        assertTrue(snapshot(recent = 0).recentCommits.isEmpty())
    }

    @Test
    fun `nameStatus 与原始补丁长度可用`() {
        write("a.txt", "1\n")
        git("add", "-A")

        val s = snapshot()
        assertTrue(s.nameStatus.contains("A\ta.txt"), "实际：${s.nameStatus}")
        assertTrue(s.rawPatchChars > 0, "应记录原始补丁长度")
        assertTrue(s.root.isNotEmpty())
    }

    @Test
    fun `findRepoRoot 能从子目录定位仓库根`() {
        write("sub/dir/x.txt", "x\n")
        val root = DiffCollector.findRepoRoot(dir.resolve("sub/dir").toString())
        assertNotNull(root)
        assertEquals(DiffCollector.findRepoRoot(dir.toString()), root)
    }

    @Test
    fun `非仓库目录返回 null`() {
        val outside = Files.createTempDirectory("commitwise-nogit-")
        try {
            assertNull(DiffCollector.findRepoRoot(outside.toString()))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
