package com.commitwise.idea.app

import com.commitwise.idea.domain.commit.MessageValidator
import com.commitwise.idea.domain.commit.ParseOptions
import com.commitwise.idea.domain.commit.ParsedCommit
import com.commitwise.idea.domain.prompt.TemplateRegistry
import com.commitwise.idea.domain.prompt.TemplateType
import com.commitwise.idea.infra.ai.AiError
import com.commitwise.idea.infra.ai.AiErrorCode
import com.commitwise.idea.infra.ai.CallModel
import com.commitwise.idea.infra.git.RepoLocator
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.HistoryEntry
import com.commitwise.idea.ui.CommitMessageBridge
import com.commitwise.idea.ui.Notify
import com.commitwise.idea.ui.QuickSetupFlow
import com.commitwise.idea.ui.SelectModelFlow
import com.commitwise.idea.ui.SetApiKeyFlow
import com.commitwise.idea.ui.ShowLogDialog
import com.commitwise.idea.ui.StatusHolder
import com.commitwise.idea.ui.WriteOutcome
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * 生成提交信息主流程（与 VSCode 版 generateCommit.ts 对齐）：
 * 采集暂存区 → 组提示词 → 调模型（流式）→ 校验 → 不合规带反馈重试一次 → 写入输入框。
 */
object GenerateCommit {

    fun run(project: Project, event: AnActionEvent? = null, templateIdOverride: String? = null) {
        val deps = AppDeps(project)

        // 配置未就绪时直接引导，不进入进度对话框——避免跑到调模型阶段才报运行时错误
        deps.isConfigReady()?.let {
            handlePrepareFailure(project, deps, it)
            return
        }

        val repo = RepoLocator.pick(project)
        if (repo == null) {
            Notify.warn(project, "没有找到 Git 仓库，请先打开一个 Git 项目。")
            return
        }

        var fail: PrepareResult.Fail? = null
        var error: Throwable? = null
        var parsed: ParsedCommit? = null
        var correctedFlag = false
        var snapshotRoot = ""
        var snapshotBranch = ""
        var truncatedFiles: List<String> = emptyList()

        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val prep = deps.prepareStaged(repo, event)
            when (prep) {
                is PrepareResult.Fail -> fail = prep
                is PrepareResult.Ok -> {
                    snapshotRoot = prep.ctx.snapshot.root
                    snapshotBranch = prep.ctx.snapshot.branch
                    truncatedFiles = prep.ctx.prepared.truncated
                    try {
                        val outcome = generate(deps, prep.ctx, templateIdOverride)
                        parsed = outcome.first
                        correctedFlag = outcome.second
                    } catch (e: Throwable) {
                        error = e
                    }
                }
            }
        }, "CommitWise · 生成提交信息", true, project)

        fail?.let { f ->
            handlePrepareFailure(project, deps, f)
            return
        }

        error?.let { e ->
            StatusHolder.failed()
            if (e is AiError && e.code == AiErrorCode.ABORTED) {
                Notify.info(project, "已取消生成。")
            } else {
                CommitWiseLogger.error(deps.describeError(e), e)
                Notify.errorWith(project, deps.describeError(e), "查看日志" to { ShowLogDialog.show(project) })
            }
            return
        }

        val result = parsed
        if (result == null || result.message.isBlank()) {
            StatusHolder.failed()
            Notify.error(project, "模型没有返回可用的提交信息，请查看日志或更换模型重试。")
            return
        }

        var message = result.message

        if (deps.settings.state.confirmBeforeInsert) {
            val edited = Messages.showMultilineInputDialog(
                project,
                if (correctedFlag) "（首次输出不合规，已自动修正）可直接编辑后写入" else "可直接编辑后写入提交输入框",
                "确认提交信息",
                message,
                null,
                null,
            )
            if (edited == null) {
                StatusHolder.idle()
                Notify.info(project, "已取消写入。")
                return
            }
            message = edited
        }

        val outcome = CommitMessageBridge.write(project, repo, message)
        StatusHolder.succeeded(if (outcome == WriteOutcome.INPUT_BOX) "已写入输入框" else "已复制")

        // 写入成功后入栈历史：误清空输入框也能从「提交信息历史」找回
        deps.history.push(
            HistoryEntry(
                message = message,
                model = deps.settings.state.model,
                time = System.currentTimeMillis(),
                repoName = repoNameOf(snapshotRoot),
                branch = snapshotBranch,
            ),
        )

        val firstLine = message.lineSequence().firstOrNull() ?: message
        if (outcome == WriteOutcome.INPUT_BOX) {
            Notify.infoWith(project, "已写入提交输入框（$firstLine）", "恢复原草稿" to {
                CommitMessageBridge.restore(project, repo)
                Notify.info(project, "已恢复生成前的草稿。")
            })
        } else {
            // 与 IDEA git 提交流程对齐：提交窗口没开就把它拉起来再写入，而不是让用户自己贴剪贴板
            Notify.infoWith(
                project,
                "提交窗口未打开，提交信息已复制到剪贴板。",
                "打开提交窗口并写入" to {
                    CommitMessageBridge.activateCommitToolWindow(project)
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        val retry = CommitMessageBridge.write(project, repo, message)
                        if (retry == WriteOutcome.INPUT_BOX) {
                            StatusHolder.succeeded("已写入输入框")
                            Notify.info(project, "已写入提交输入框（$firstLine）")
                        }
                    }
                },
            )
        }

        if (!result.valid) {
            CommitWiseLogger.warn("两次生成均未通过格式校验，输出已按原样提供，请人工确认。")
            Notify.warn(project, "提交信息未完全符合规范，请检查后再提交。")
        }

        if (truncatedFiles.isNotEmpty()) {
            Notify.warn(
                project,
                "部分文件内容过长已截断（${truncatedFiles.joinToString("、")}），生成的描述可能不完整。",
            )
        }
    }

    /** 返回 (解析结果, 是否触发过纠错重试) */
    private fun generate(
        deps: AppDeps,
        ctx: StagedContext,
        templateIdOverride: String?,
    ): Pair<ParsedCommit, Boolean> {
        val config = ctx.config
        val registry = TemplateRegistry(config.customTemplates)
        registry.warnings.forEach { CommitWiseLogger.warn(it) }

        val requestedId = templateIdOverride ?: config.commit.templateId
        val (template, fellBack) = registry.resolve(requestedId, TemplateType.COMMIT)
        if (fellBack) {
            CommitWiseLogger.warn("模板 \"$requestedId\" 不存在或类型不符，已回退到 \"${template.name}\"")
        }

        val built = PromptBuilder.buildMessages(template, ctx.snapshot, ctx.prepared, config)
        if (built.missingVariables.isNotEmpty()) {
            CommitWiseLogger.warn("模板中存在未知变量：${built.missingVariables.joinToString("、")}（已按空值处理）")
        }

        val parseOpts = ParseOptions(config.commit.format, config.commit.maxSubjectLength)
        val token = deps.cancellation.begin()
        val indicator: ProgressIndicator? = ProgressManager.getGlobalProgressIndicator()

        var streamed = 0
        var lastReport = 0L
        val onDelta: (String) -> Unit = { delta ->
            streamed += delta.length
            val now = System.currentTimeMillis()
            if (indicator != null && now - lastReport > 200) {
                lastReport = now
                indicator.text2 = "已接收 $streamed 字符…"
                if (indicator.isCanceled) token.cancel()
            }
        }

        try {
            StatusHolder.startRequesting("生成中")
            indicator?.text2 = "模板：${template.name}"

            val first = CallModel.call(
                config = config,
                messages = built.messages,
                token = token,
                onDelta = onDelta,
                logger = { CommitWiseLogger.debug(it) },
            )

            var parsed = MessageValidator.parse(first.text, parseOpts)
            CommitWiseLogger.info("首次生成：${if (parsed.valid) "通过校验" else "未通过校验"}，尝试 ${first.attempts} 次")

            var corrected = false
            if (!parsed.valid) {
                corrected = true
                CommitWiseLogger.warn("输出不合规，触发纠错重试：${parsed.issues.joinToString("；") { it.message }}")
                indicator?.text2 = "格式不合规，正在修正…"
                val second = CallModel.call(
                    config = config,
                    messages = built.messages +
                        com.commitwise.idea.infra.ai.ChatMessage(
                            com.commitwise.idea.infra.ai.ChatRole.ASSISTANT,
                            first.text,
                        ) +
                        com.commitwise.idea.infra.ai.ChatMessage(
                            com.commitwise.idea.infra.ai.ChatRole.USER,
                            MessageValidator.buildCorrectionPrompt(parsed, parseOpts),
                        ),
                    token = token,
                    logger = { CommitWiseLogger.debug(it) },
                )
                parsed = MessageValidator.parse(second.text, parseOpts)
            }

            parsed.issues.filter { it.severity == "warn" }.forEach {
                CommitWiseLogger.warn("提交信息校验：${it.message}")
            }
            return parsed to corrected
        } finally {
            deps.cancellation.end(token)
        }
    }

    fun handlePrepareFailure(project: Project, deps: AppDeps, fail: PrepareResult.Fail) {
        StatusHolder.idle()
        if (fail.actions.isEmpty()) {
            Notify.warn(project, fail.message)
            return
        }
        val actions = fail.actions.map { title ->
            title to {
                when (title) {
                    "快速配置" -> QuickSetupFlow.run(project)
                    "选择模型" -> SelectModelFlow.run(project)
                    "打开设置" -> Notify.openSettings(project)
                    "仅设置密钥" -> SetApiKeyFlow.run(project)
                }
            }
        }
        Notify.errorWith(project, fail.message, *actions.toTypedArray())
        fail.error?.let { CommitWiseLogger.error(fail.message, it) }
    }
}
