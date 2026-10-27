package com.commitwise.idea.infra.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.charset.StandardCharsets

class GitError(
    message: String,
    val stderr: String = "",
    val exitCode: Int = -1,
) : Exception(message)

class GitUnavailableError : Exception("找不到 git 可执行文件，请确认已安装 Git 并加入 PATH")

data class GitExecResult(val stdout: String, val stderr: String)

/**
 * 走 git CLI：需要精确控制 --no-ext-diff / -M / -U 等参数，
 * 内置 Git 集成的 API 无法保证与命令行一致的补丁形态。
 */
object GitRunner {

    private const val TIMEOUT_MS = 30_000

    fun exec(args: List<String>, cwd: String): GitExecResult {
        val cmd = GeneralCommandLine(listOf("git", "--no-pager") + args)
            .withWorkDirectory(cwd)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(mapOf("GIT_OPTIONAL_LOCKS" to "0", "GIT_TERMINAL_PROMPT" to "0"))

        val output = try {
            CapturingProcessHandler(cmd).runProcess(TIMEOUT_MS)
        } catch (e: com.intellij.execution.process.ProcessNotCreatedException) {
            throw GitUnavailableError()
        } catch (e: java.util.concurrent.TimeoutException) {
            throw GitError("git ${args.firstOrNull() ?: ""} 执行超时（${TIMEOUT_MS / 1000}s）", "", -1)
        }

        if (output.exitCode != 0) {
            val first = output.stderr.trim().lineSequence().firstOrNull() ?: "未知错误"
            throw GitError("git ${args.firstOrNull() ?: ""} 执行失败：$first", output.stderr, output.exitCode)
        }
        return GitExecResult(output.stdout, output.stderr)
    }

    fun execOrEmpty(args: List<String>, cwd: String): GitExecResult = try {
        exec(args, cwd)
    } catch (_: Exception) {
        GitExecResult("", "")
    }
}
