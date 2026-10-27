package com.commitwise.idea.infra.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File

/** 定位到的仓库 */
data class RepoHandle(
    val root: String,
    /** 是否拿到了提交信息输入框（决定生成结果能直接写入还是只能复制） */
    val hasInputBox: Boolean,
)

/**
 * 定位要操作的仓库。
 *
 * 走 git CLI 探测（`rev-parse --show-toplevel`），不依赖 IDE 的 Git 集成数据结构：
 * 这样在无 VCS 集成、或仓库根目录与项目根不一致（子目录打开）时同样能工作。
 * 多个仓库时让用户选择。
 */
object RepoLocator {

    fun pick(project: Project): RepoHandle? {
        val base = project.basePath
        if (base == null) {
            Messages.showWarningDialog("当前项目没有本地路径，无法定位 Git 仓库。", "CommitWise")
            return null
        }

        val roots = discoverRoots(base)
        if (roots.isEmpty()) {
            return null
        }
        if (roots.size == 1) {
            return RepoHandle(roots[0], true)
        }

        val index = Messages.showChooseDialog(
            project,
            "选择要操作的 Git 仓库",
            "CommitWise",
            null,
            roots.toTypedArray(),
            roots[0],
        )
        if (index < 0) return null
        return RepoHandle(roots[index], true)
    }

    /** basePath 自身可能是仓库，也可能包含多个子仓库（少见）；两种都覆盖 */
    private fun discoverRoots(base: String): List<String> {
        val self = DiffCollector.findRepoRoot(base)
        if (self != null) return listOf(self)

        val children = File(base).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val roots = mutableListOf<String>()
        for (child in children) {
            val root = DiffCollector.findRepoRoot(child.path) ?: continue
            if (!roots.contains(root)) roots.add(root)
        }
        return roots
    }
}
