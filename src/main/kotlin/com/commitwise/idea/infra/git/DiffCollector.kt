package com.commitwise.idea.infra.git

import com.commitwise.idea.domain.diff.DiffFile
import com.commitwise.idea.domain.diff.DiffParser
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change

data class StagedSnapshot(
    val root: String,
    val branch: String,
    /** 仓库尚无任何提交（unborn HEAD） */
    val isUnborn: Boolean,
    val files: List<DiffFile>,
    val nameStatus: String,
    val recentCommits: List<String>,
    /** 原始补丁文本，未过滤未脱敏，仅用于统计 */
    val rawPatchChars: Int,
)

object DiffCollector {

    /** 采集暂存区快照（仅用于测试与无 IDEA 提交面板的兜底场景）。只读，不修改仓库状态。 */
    fun collectStaged(root: String, recentCommitCount: Int): StagedSnapshot {
        val isUnborn = isUnbornHead(root)
        val branch = currentBranch(root)

        val patch = GitRunner.exec(
            listOf("diff", "--cached", "--no-color", "--no-ext-diff", "-M", "-U3"),
            root,
        )
        val nameStatus = GitRunner.execOrEmpty(listOf("diff", "--cached", "--name-status", "-M"), root)
        val log = if (recentCommitCount > 0) {
            GitRunner.execOrEmpty(listOf("log", "-n$recentCommitCount", "--no-merges", "--pretty=format:%s"), root)
        } else {
            GitExecResult("", "")
        }

        val files = DiffParser.parse(patch.stdout)

        return StagedSnapshot(
            root = root,
            branch = branch,
            isUnborn = isUnborn,
            files = files,
            nameStatus = nameStatus.stdout.trim(),
            recentCommits = log.stdout.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
            rawPatchChars = patch.stdout.length,
        )
    }

    /**
     * 采集「要提交的变更」——对齐 IDEA 的提交流程。
     *
     * IDEA 不依赖 `git add` 暂存：用户在 Commit 对话框里勾选文件即表示要提交。
     * 因此这里优先读取提交对话框中勾选的文件（CheckinProjectPanel.includedChanges），
     * 没有提交面板时（从 Tools 菜单触发）回退到工作区的全部未提交变更。
     *
     * 拿到文件后：已跟踪的跑 `git diff HEAD`（工作区 vs HEAD），新增（未跟踪）文件合成
     * new-file hunk——这正是 IDEA 点 Commit 时实际提交的内容。
     */
    fun collectChanges(
        project: Project,
        root: String,
        event: AnActionEvent?,
        recentCommitCount: Int,
    ): StagedSnapshot {
        val (included, hasPanel) = pickIncludedChanges(project, root, event)
        val unborn = isUnbornHead(root)
        val patch = buildPatch(root, included, unborn, hasPanel)
        val branch = currentBranch(root)
        val log = if (recentCommitCount > 0 && !unborn) {
            GitRunner.execOrEmpty(listOf("log", "-n$recentCommitCount", "--no-merges", "--pretty=format:%s"), root)
        } else {
            GitExecResult("", "")
        }

        return StagedSnapshot(
            root = root,
            branch = branch,
            isUnborn = unborn,
            files = DiffParser.parse(patch),
            nameStatus = "",
            recentCommits = log.stdout.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
            rawPatchChars = patch.length,
        )
    }

    /**
     * 读取要提交的变更：
     * 1. 提交对话框打开时 → 勾选的文件（提交面板的 getIncludedChanges()）
     * 2. 否则 → 返回 hasPanel=false，由 buildPatch 走工作区全量采集
     *
     * 注意：提交面板开着但没勾选任何文件时，返回空列表 + hasPanel=true，
     * 这样 buildPatch 不会回退到全量（避免把用户没勾的文件也发给模型）。
     *
     * 这里用反射调用 getIncludedChanges()，不直接引用 CheckinProjectPanel：
     * 该接口在编译类路径上不可见（属于 vcs-impl 模块），但运行时提交面板实现类
     * 一定有这个方法。
     */
    private fun pickIncludedChanges(project: Project, root: String, event: AnActionEvent?): Pair<List<Change>, Boolean> {
        val panel = event?.let { findCheckinPanel(it) }
        if (panel != null) {
            val changes = includedChangesOf(panel)
                .filter { c -> changePath(c)?.let { relativize(root, it) != null } ?: false }
            return changes to true
        }
        return emptyList<Change>() to false
    }

    /** 在动作组件的祖先链上找提交面板：有 getIncludedChanges 方法的组件即是 */
    private fun findCheckinPanel(e: AnActionEvent): java.awt.Component? {
        var c: java.awt.Component? = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        while (c != null) {
            if (c.javaClass.methods.any { it.name == "getIncludedChanges" }) return c
            c = c.parent
        }
        return null
    }

    /** 反射调用 getIncludedChanges()，返回其中的 Change 列表 */
    private fun includedChangesOf(panel: java.awt.Component): List<Change> {
        val method = panel.javaClass.methods.firstOrNull { it.name == "getIncludedChanges" } ?: return emptyList()
        return try {
            val result = method.invoke(panel) ?: return emptyList()
            when (result) {
                is Iterable<*> -> result.filterIsInstance<Change>()
                is Array<*> -> result.filterIsInstance<Change>()
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun changePath(change: Change): String? =
        change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path

    private fun relativize(root: String, absPath: String): String? = try {
        java.nio.file.Paths.get(root).relativize(java.nio.file.Paths.get(absPath)).toString().replace('\\', '/')
    } catch (_: Exception) {
        null
    }

    /**
     * 把变更组装成统一补丁：
     * - 已跟踪（MODIFICATION/DELETED/MOVED）→ `git diff HEAD -- <paths>`，git 负责行级 diff
     * - 新增（NEW）→ 合成 new-file hunk（读磁盘内容，全部作为新增行）
     * - 没有勾选任何文件且没有提交面板 → 全量 `git diff HEAD` + 未跟踪文件
     * - 提交面板开着但没勾选 → 返回空补丁（让上层提示用户勾选）
     */
    private fun buildPatch(root: String, changes: List<Change>, unborn: Boolean, hasPanel: Boolean): String {
        if (changes.isEmpty()) {
            // 提交面板开着但没勾选：不回退全量，返回空
            if (hasPanel) return ""
            // 没有提交面板：采集工作区全部未提交变更
            return buildFullPatch(root, unborn)
        }

        val tracked = mutableListOf<String>()
        val newFiles = mutableListOf<String>()
        for (change in changes) {
            val after = change.afterRevision?.file?.path
            val before = change.beforeRevision?.file?.path
            val rel = (after ?: before)?.let { relativize(root, it) } ?: continue
            if (change.type == Change.Type.NEW) {
                newFiles.add(rel)
            } else {
                tracked.add(rel)
            }
        }

        val sb = StringBuilder()
        if (tracked.isNotEmpty() && !unborn) {
            val args = mutableListOf("diff", "HEAD", "--no-color", "--no-ext-diff", "-M", "-U3", "--")
            args.addAll(tracked)
            sb.append(GitRunner.execOrEmpty(args, root).stdout)
        }
        for (rel in newFiles) {
            sb.append(synthesizeNewFilePatch(root, rel))
        }
        return sb.toString()
    }

    /** 全量采集：已跟踪的走 git diff HEAD，未跟踪的合成 new-file hunk */
    private fun buildFullPatch(root: String, unborn: Boolean): String {
        val sb = StringBuilder()
        if (!unborn) {
            sb.append(GitRunner.execOrEmpty(
                listOf("diff", "HEAD", "--no-color", "--no-ext-diff", "-M", "-U3"),
                root,
            ).stdout)
        }
        val untracked = GitRunner.execOrEmpty(
            listOf("ls-files", "--others", "--exclude-standard"),
            root,
        ).stdout.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (rel in untracked) {
            sb.append(synthesizeNewFilePatch(root, rel))
        }
        return sb.toString()
    }

    /** 为新增文件合成标准 new-file 补丁段，格式与 `git diff --no-index /dev/null <file>` 一致 */
    private fun synthesizeNewFilePatch(root: String, relPath: String): String {
        val file = java.io.File(root, relPath)
        if (!file.isFile) return ""
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return ""
        }
        // 简单二进制判定：出现非文本控制字节则按二进制处理
        if (bytes.any { it < 0x08 || (it > 0x0D && it < 0x20) }) {
            return "diff --git a/$relPath b/$relPath\nnew file mode 100644\nBinary files differ\n"
        }
        val content = String(bytes, Charsets.UTF_8)
        val lines = if (content.isEmpty()) emptyList() else content.split("\n")
        val sb = StringBuilder()
        sb.append("diff --git a/$relPath b/$relPath\n")
        sb.append("new file mode 100644\n")
        sb.append("--- /dev/null\n")
        sb.append("+++ b/$relPath\n")
        sb.append("@@ -0,0 +1,").append(lines.size).append(" @@\n")
        for (line in lines) {
            sb.append('+').append(line).append('\n')
        }
        return sb.toString()
    }

    private fun isUnbornHead(root: String): Boolean = try {
        GitRunner.exec(listOf("rev-parse", "--verify", "--quiet", "HEAD"), root)
        false
    } catch (_: GitError) {
        true
    }

    private fun currentBranch(root: String): String = try {
        val out = GitRunner.exec(listOf("symbolic-ref", "--short", "-q", "HEAD"), root).stdout.trim()
        out.ifEmpty { "(detached HEAD)" }
    } catch (_: Exception) {
        "(detached HEAD)"
    }

    /** 探测某目录是否在 git 仓库内，返回仓库根目录 */
    fun findRepoRoot(dir: String): String? = try {
        val out = GitRunner.exec(listOf("rev-parse", "--show-toplevel"), dir).stdout.trim().trimEnd('\r')
        out.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
