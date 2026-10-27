package com.commitwise.idea.app

import com.commitwise.idea.domain.prompt.TemplateRegistry
import com.commitwise.idea.domain.prompt.TemplateType
import com.commitwise.idea.domain.review.IssueMapper
import com.commitwise.idea.domain.review.MapOptions
import com.commitwise.idea.domain.review.ReviewParser
import com.commitwise.idea.infra.ai.AiError
import com.commitwise.idea.infra.ai.AiErrorCode
import com.commitwise.idea.infra.ai.CallModel
import com.commitwise.idea.infra.ai.ChatMessage
import com.commitwise.idea.infra.ai.ChatRole
import com.commitwise.idea.infra.git.RepoLocator
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.StoredReview
import com.commitwise.idea.ui.Notify
import com.commitwise.idea.ui.ReportDialog
import com.commitwise.idea.ui.ReviewToolWindowFactory
import com.commitwise.idea.ui.ShowLogDialog
import com.commitwise.idea.ui.StatusHolder
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project

/**
 * 审查暂存区代码（与 VSCode 版 reviewStaged.ts 对齐）：
 * 采集 → 组提示词 → 调模型 → JSON 容错解析（失败则要求重输出）→ 对齐 diff → 落库 → 展示。
 */
object ReviewStaged {

    fun run(project: Project, event: AnActionEvent? = null) {
        val deps = AppDeps(project)

        // 重复触发会打断进行中的审查（CancellationController 会取消上一个请求），直接拦下
        if (deps.review.isRunning()) {
            Notify.warn(project, "审查正在进行中，请等当前审查完成后再操作。")
            return
        }

        // 配置未就绪时直接引导，不进入进度对话框
        deps.isConfigReady()?.let {
            GenerateCommit.handlePrepareFailure(project, deps, it)
            return
        }

        val repo = RepoLocator.pick(project)
        if (repo == null) {
            Notify.warn(project, "没有找到 Git 仓库，请先打开一个 Git 项目。")
            return
        }

        var fail: PrepareResult.Fail? = null
        var error: Throwable? = null
        var stored: StoredReview? = null
        var totalCount = 0

        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val prep = deps.prepareStaged(repo, event)
            when (prep) {
                is PrepareResult.Fail -> fail = prep
                is PrepareResult.Ok -> {
                    try {
                        stored = review(deps, prep.ctx)
                        totalCount = stored?.total ?: 0
                    } catch (e: Throwable) {
                        error = e
                    }
                }
            }
        }, "CommitWise · 审查暂存区代码", true, project)

        fail?.let {
            GenerateCommit.handlePrepareFailure(project, deps, it)
            return
        }

        error?.let { e ->
            StatusHolder.failed()
            if (e is AiError && e.code == AiErrorCode.ABORTED) {
                Notify.info(project, "已取消审查。")
            } else {
                CommitWiseLogger.error(deps.describeError(e), e)
                Notify.errorWith(project, deps.describeError(e), "查看日志" to { ShowLogDialog.show(project) })
            }
            return
        }

        val result = stored
        if (result == null) {
            StatusHolder.failed()
            Notify.errorWith(
                project,
                "模型返回的内容无法解析为审查结果，已把原始输出写入日志。",
                "查看日志" to { ShowLogDialog.show(project) },
            )
            return
        }

        deps.review.save(result)
        StatusHolder.setReviewTotal(result.total)
        ReviewToolWindowFactory.showWindow(project)

        // 触发一次 daemon 重跑，让新的审查标记立即出现在编辑器里
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()

        if (totalCount == 0) {
            StatusHolder.succeeded("未发现问题")
            Notify.info(project, "审查完成，未发现明显问题。")
            return
        }

        StatusHolder.succeeded("$totalCount 条问题")
        Notify.infoWith(
            project,
            "审查完成：$totalCount 个问题（高 ${result.high} / 中 ${result.medium} / 低 ${result.low}），已在侧边栏列出。",
            "打开报告" to { ReportDialog.show(project) },
            "复制为 Markdown" to {
                com.commitwise.idea.ui.ReviewCommands.copyMarkdown(project)
            },
        )
    }

    private fun review(deps: AppDeps, ctx: StagedContext): StoredReview {
        val config = ctx.config
        val registry = TemplateRegistry(config.customTemplates)
        registry.warnings.forEach { CommitWiseLogger.warn(it) }

        val (template, fellBack) = registry.resolve(config.review.templateId, TemplateType.REVIEW)
        if (fellBack) {
            CommitWiseLogger.warn("找不到审查模板 \"${config.review.templateId}\"，已回退到 \"${template.name}\"")
        }

        val built = PromptBuilder.buildMessages(template, ctx.snapshot, ctx.prepared, config)
        val token = deps.cancellation.begin()
        val startedAt = System.currentTimeMillis()
        val maxTokens = maxOf(config.maxTokens, 2048)
        val indicator: ProgressIndicator? = ProgressManager.getGlobalProgressIndicator()

        // 运行态先落库：工具窗口与报告立即显示「审查中」并禁用重复触发
        deps.review.setRunning(true)

        var chars = 0
        var lastReport = 0L
        val onDelta: (String) -> Unit = { delta ->
            chars += delta.length
            val now = System.currentTimeMillis()
            if (indicator != null && now - lastReport > 200) {
                lastReport = now
                indicator.text2 = "已接收 $chars 字符…"
                if (indicator.isCanceled) token.cancel()
            }
        }

        try {
            StatusHolder.startRequesting("审查中")
            indicator?.text2 = "模板：${template.name}"

            val first = CallModel.call(
                config = config,
                messages = built.messages,
                token = token,
                onDelta = onDelta,
                maxTokens = maxTokens,
                logger = { CommitWiseLogger.debug(it) },
            )

            var outcome = ReviewParser.parseReviewResponse(first.text)
            CommitWiseLogger.info(
                "审查响应：${first.text.length} 字符，解析${if (outcome.result != null) "成功" else "失败"}" +
                    if (outcome.repaired) "（已自动修复 JSON）" else "",
            )

            if (outcome.result == null) {
                CommitWiseLogger.warn("JSON 解析失败，触发修复重试：${outcome.error ?: "未知原因"}")
                indicator?.text2 = "返回格式不合法，正在要求模型重新输出…"
                val second = CallModel.call(
                    config = config,
                    messages = built.messages +
                        ChatMessage(ChatRole.ASSISTANT, first.text) +
                        ChatMessage(ChatRole.USER, PromptBuilder.buildReviewRepairMessage(first.text)),
                    token = token,
                    maxTokens = maxTokens,
                    logger = { CommitWiseLogger.debug(it) },
                )
                outcome = ReviewParser.parseReviewResponse(second.text)
                if (outcome.result == null) {
                    CommitWiseLogger.error("两次均无法解析模型输出，原始内容：\n${second.text.take(2000)}")
                }
            }

            val parsed = outcome.result
                ?: throw AiError("模型返回的内容无法解析为审查结果", AiErrorCode.BAD_RESPONSE)

            val mapped = IssueMapper.map(
                parsed,
                ctx.snapshot.files,
                outcome.dropped,
                MapOptions(config.review.minSeverity, config.review.maxIssues),
            )

            val notices = mutableListOf<String>()
            if (mapped.dropped.unknownFile > 0) {
                notices.add("${mapped.dropped.unknownFile} 条问题指向的文件不在本次暂存区中，已忽略（可能由模型臆造）。")
            }
            if (mapped.dropped.outOfRange > 0) {
                notices.add("${mapped.dropped.outOfRange} 条问题的行号不在新增行范围内，已降级为文件级问题。")
            }
            if (mapped.dropped.malformed > 0) {
                notices.add("${mapped.dropped.malformed} 条问题缺少必要字段，已丢弃。")
            }
            if (mapped.truncated) {
                notices.add("共 ${mapped.totalBeforeLimit} 条问题，按设置仅展示前 ${mapped.issues.size} 条。")
            }
            if (ctx.prepared.truncated.isNotEmpty()) {
                notices.add("以下文件内容过长已截断，其问题可能未被发现：${ctx.prepared.truncated.joinToString("、")}。")
            }
            if (ctx.prepared.skipped.isNotEmpty()) {
                notices.add("以下文件未参与审查：${ctx.prepared.skipped.joinToString("、") { it.path }}。")
            }

            val high = mapped.counts[com.commitwise.idea.domain.review.Severity.HIGH] ?: 0
            val medium = mapped.counts[com.commitwise.idea.domain.review.Severity.MEDIUM] ?: 0
            val low = mapped.counts[com.commitwise.idea.domain.review.Severity.LOW] ?: 0
            val elapsed = System.currentTimeMillis() - startedAt

            CommitWiseLogger.info(
                "审查完成：${high + medium + low} 条（高 $high / 中 $medium / 低 $low），用时 ${"%.1f".format(elapsed / 1000.0)}s",
            )

            return StoredReview(
                savedAt = System.currentTimeMillis(),
                root = ctx.repo.root,
                repoName = repoNameOf(ctx.snapshot.root),
                branch = ctx.snapshot.branch,
                providerLabel = config.provider.label,
                model = config.model,
                elapsedMs = elapsed,
                summary = mapped.summary,
                issues = mapped.issues,
                notices = notices,
                truncated = mapped.truncated,
                totalBeforeLimit = mapped.totalBeforeLimit,
                high = high,
                medium = medium,
                low = low,
            )
        } finally {
            deps.cancellation.end(token)
            // 成功、失败、取消都会走到这里：运行态必须复位，否则按钮永远禁用
            deps.review.setRunning(false)
        }
    }
}
