package com.commitwise.idea.ui

import com.commitwise.idea.infra.git.RepoHandle
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container

enum class WriteOutcome { INPUT_BOX, CLIPBOARD }

/**
 * 写入提交信息输入框。
 *
 * 提交信息控件不支持撤销，所以写入前必须自己备份原草稿，
 * 否则用户手写了一半的提交信息会被无声覆盖。
 *
 * `CommitMessageI` 只有 setter，草稿内容从它内部的编辑器组件读。
 */
object CommitMessageBridge {

    private val backups = LinkedHashMap<String, String>()
    private var lastRoot: String? = null

    fun write(project: Project, repo: RepoHandle, message: String): WriteOutcome {
        val control = findCommitMessageControl(project)
        // 只在没有备份时才记草稿：重试（先复制剪贴板、打开窗口后再写入）时不把刚生成的内容误当成原草稿
        if (!backups.containsKey(repo.root)) {
            backups[repo.root] = control?.let { readDraft(it) } ?: ""
            lastRoot = repo.root
        }

        if (control != null) {
            onEdt { control.setCommitMessage(message) }
            CommitWiseLogger.debug("已写入提交输入框（${message.length} 字符）")
            return WriteOutcome.INPUT_BOX
        }

        copyToClipboard(message)
        return WriteOutcome.CLIPBOARD
    }

    /**
     * 主动激活 IDE 的提交工具窗口（对应 Ctrl+K / VCS → Commit）。
     *
     * 当用户在没有打开提交窗口的情况下点了「生成提交信息」时，调用此方法把窗口拉起来，
     * 随后 [write] 重试即能找到提交信息控件并写入——与 IDEA 原生 git 提交流程保持一致，
     * 而不是默默把内容塞进剪贴板让用户自己找地方贴。
     */
    fun activateCommitToolWindow(project: Project) {
        onEdt {
            try {
                val mgr = ToolWindowManager.getInstance(project)
                val tw = mgr.getToolWindow(ToolWindowId.COMMIT) ?: return@onEdt
                if (!tw.isAvailable) return@onEdt
                tw.show(null)
                tw.activate(null, false)
                CommitWiseLogger.debug("已激活提交工具窗口")
            } catch (e: Exception) {
                CommitWiseLogger.warn("激活提交工具窗口失败：${e.message}")
            }
        }
    }

    fun hasBackup(): Boolean = backups.isNotEmpty()

    /** 恢复上一次被覆盖的草稿，返回恢复的仓库根目录 */
    fun restore(project: Project, repo: RepoHandle): String? {
        val previous = backups[repo.root] ?: return null
        val control = findCommitMessageControl(project)
        if (control != null) {
            onEdt { control.setCommitMessage(previous) }
        } else {
            copyToClipboard(previous)
        }
        backups.remove(repo.root)
        return repo.root
    }

    fun peek(): String? = lastRoot?.let { backups[it] }

    fun copyToClipboard(text: String) {
        onEdt {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
    }

    /** 读取当前草稿：提交信息控件内部是一个编辑器组件 */
    private fun readDraft(control: CommitMessageI): String {
        val text = runCatching {
            val field = control.javaClass.methods.firstOrNull {
                it.name == "getEditor" || it.name == "getEditorField"
            }
            val editor = field?.invoke(control)
            when (editor) {
                is EditorEx -> editor.document.text
                is com.intellij.openapi.editor.Editor -> editor.document.text
                null -> null
                else -> {
                    // EditorTextField 之类：反射拿 document
                    val docGetter = editor.javaClass.methods.firstOrNull { it.name == "getDocument" }
                    (docGetter?.invoke(editor) as? com.intellij.openapi.editor.Document)?.text
                }
            }
        }.getOrNull()
        return text ?: ""
    }

    /** 在提交工具窗口里找到提交信息控件（一个实现 CommitMessageI 的 Swing 组件） */
    fun findCommitMessageControl(project: Project): CommitMessageI? {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.getToolWindow(ToolWindowId.COMMIT) ?: return null
        for (content in toolWindow.contentManager.contents) {
            findIn(content.component)?.let { return it }
        }
        return null
    }

    private fun findIn(component: Component?): CommitMessageI? {
        if (component == null) return null
        if (component is CommitMessageI) return component
        if (component is Container) {
            for (child in component.components) {
                findIn(child)?.let { return it }
            }
        }
        return null
    }

    @Suppress("unused")
    private fun warmUp(project: Project) {
        // 触发一次编辑器服务初始化，避免首次写入时的类加载卡顿
        EditorFactory.getInstance()
        FileDocumentManager.getInstance()
    }

    fun logAvailability(project: Project) {
        val present = findCommitMessageControl(project) != null
        CommitWiseLogger.debug("提交输入框可用：$present")
    }
}
