package com.commitwise.idea.app

import com.commitwise.idea.domain.diff.PrepareDiff
import com.commitwise.idea.domain.diff.PrepareOptions
import com.commitwise.idea.domain.diff.PreparedDiff
import com.commitwise.idea.domain.diff.SkipReason
import com.commitwise.idea.infra.ai.AiError
import com.commitwise.idea.infra.ai.ModelCache
import com.commitwise.idea.infra.config.ConfigValidator
import com.commitwise.idea.infra.config.ProviderId
import com.commitwise.idea.infra.config.ProviderPresets
import com.commitwise.idea.infra.config.ResolvedConfig
import com.commitwise.idea.infra.git.DiffCollector
import com.commitwise.idea.infra.git.GitError
import com.commitwise.idea.infra.git.GitUnavailableError
import com.commitwise.idea.infra.git.RepoHandle
import com.commitwise.idea.infra.git.StagedSnapshot
import com.commitwise.idea.infra.log.CommitWiseLogger
import com.commitwise.idea.services.CommitHistoryService
import com.commitwise.idea.services.CommitWiseSettings
import com.commitwise.idea.services.ReviewStore
import com.commitwise.idea.services.Secrets
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/** 一次生成/审查所需的前置数据 */
data class StagedContext(
    val repo: RepoHandle,
    val snapshot: StagedSnapshot,
    val config: ResolvedConfig,
    val prepared: PreparedDiff,
)

/** 前置流程的失败原因，供调用方在 EDT 上给出提示 */
sealed class PrepareResult {
    data class Ok(val ctx: StagedContext) : PrepareResult()

    /** message 面向用户；actions 为可点的按钮文案 */
    data class Fail(val message: String, val actions: List<String> = emptyList(), val error: Throwable? = null) : PrepareResult()
}

/**
 * 依赖容器。与 VSCode 版的 AppDeps 对应，只是把 vscode 服务换成了 IDEA 服务。
 */
class AppDeps(val project: Project) {

    val settings: CommitWiseSettings get() = CommitWiseSettings.getInstance()
    val history: CommitHistoryService get() = CommitHistoryService.getInstance()
    val models: ModelCache get() = ModelCache.getInstance()
    val review: ReviewStore get() = ReviewStore.getInstance(project)
    val cancellation = CancellationController()
    val logger = CommitWiseLogger

    fun ideLanguage(): String = java.util.Locale.getDefault().language

    /** 读取密钥并校验配置 */
    fun loadConfig(): Pair<ResolvedConfig?, com.commitwise.idea.infra.config.ValidationResult> {
        val raw = settings.toRawConfig()
        val apiKey = Secrets.resolve(raw.provider, raw.apiKeyEnv)
        val validation = ConfigValidator.validate(raw, ideLanguage(), apiKey)
        return validation.config to validation
    }

    /**
     * 轻量的配置就绪检查：不采集暂存区、不联网，只看保存的配置是否足以发起一次请求。
     *
     * 在进入进度对话框之前调用：未就绪时直接给出可点的引导按钮，
     * 而不是先弹进度条、跑到调模型阶段才抛出一个让人摸不着头脑的运行时错误。
     *
     * 返回 null 表示就绪；返回 Fail 表示应中止并向用户提示。
     */
    fun isConfigReady(): PrepareResult.Fail? {
        val state = settings.state
        val providerId = ProviderId.of(state.provider)
            ?: return PrepareResult.Fail(
                "CommitWise 配置有误：未知的服务商类型 \"${state.provider}\"。",
                listOf("打开设置"),
            )
        val preset = ProviderPresets.of(state.provider)

        if (state.baseUrl.isBlank()) {
            return PrepareResult.Fail(
                "尚未填写接口地址。CommitWise 需要知道把请求发给谁。",
                listOf("快速配置", "打开设置"),
            )
        }

        // 校验器会把空 model 兜底成默认值（只算 warning），但兜底的模型名往往和用户实际地址不匹配，
        // 调用时才会报「model not found」。这里按用户是否显式填过 model 来判断，更准确。
        if (state.model.isBlank()) {
            return PrepareResult.Fail(
                "尚未选择模型。CommitWise 需要知道调用 ${preset.label} 的哪个模型（${preset.modelHint}）。",
                listOf("快速配置", "选择模型"),
            )
        }

        if (providerId != ProviderId.OLLAMA) {
            val hasKey = Secrets.has(state.provider) || state.apiKeyEnv.isNotBlank()
            if (!hasKey) {
                return PrepareResult.Fail(
                    "尚未设置 ${preset.label} 的 API 密钥，没有密钥无法调用模型。",
                    listOf("快速配置", "仅设置密钥"),
                )
            }
        }

        return null
    }

    /**
     * 两个用例共用的前置流程：采集要提交的变更 → 校验配置 → 过滤裁剪。
     * 在后台线程调用（内部走 git 子进程 / IDEA 变更数据），不弹任何模态对话框。
     *
     * [event] 用于从提交对话框读取勾选的文件（对齐 IDEA 提交流程）；
     * 为 null 时（从 Tools 菜单触发）回退到工作区全部未提交变更。
     */
    fun prepareStaged(repo: RepoHandle, event: AnActionEvent? = null): PrepareResult {
        val snapshot = try {
            DiffCollector.collectChanges(
                project,
                repo.root,
                event,
                settings.toRawConfig().commit.recentCommitCount,
            )
        } catch (e: GitUnavailableError) {
            return PrepareResult.Fail(e.message ?: "git 不可用", error = e)
        } catch (e: GitError) {
            return PrepareResult.Fail(e.message ?: "git 执行失败", error = e)
        } catch (e: Exception) {
            return PrepareResult.Fail("采集变更失败：${e.message}", error = e)
        }

        if (snapshot.files.isEmpty()) {
            return PrepareResult.Fail("没有要提交的变更。请在提交对话框中勾选要提交的文件（IDEA 不需要 git add），或先修改文件后再试。")
        }

        val (config, validation) = loadConfig()
        logger.debug("配置校验：${validation.errors.size} 错误，${validation.warnings.size} 警告")

        if (validation.errors.isNotEmpty() || config == null) {
            return PrepareResult.Fail(
                "CommitWise 配置有误：${validation.errors.first()}",
                listOf("快速配置", "打开设置"),
            )
        }

        if (config.apiKey.isNullOrEmpty() && config.provider != ProviderId.OLLAMA) {
            return PrepareResult.Fail(
                "尚未设置 ${config.provider.label} 的 API 密钥。",
                listOf("快速配置", "仅设置密钥"),
            )
        }

        validation.warnings.forEach { logger.warn(it) }

        val prepared = PrepareDiff.prepare(
            snapshot.files,
            PrepareOptions(
                excludeGlobs = config.diff.excludeGlobs,
                maxFileChars = config.diff.maxFileChars,
                maxTotalChars = config.diff.maxTotalChars,
                redactSecrets = config.diff.redactSecrets,
            ),
        )

        logger.info(
            "变更：${snapshot.files.size} 个文件，补丁 ${snapshot.rawPatchChars} 字符 → 发送 ${prepared.chars} 字符" +
                if (prepared.skipped.isNotEmpty()) {
                    "，跳过 ${prepared.skipped.size} 个：${prepared.skipped.joinToString(", ") { it.path }}"
                } else {
                    ""
                },
        )
        if (prepared.redactionHits > 0) {
            logger.warn("已脱敏 ${prepared.redactionHits} 处疑似密钥（${prepared.redactionRules.joinToString(", ")}）")
        }

        if (prepared.patch.isBlank()) {
            // 全部是二进制文件时，说「被排除规则过滤」会让人去找排除规则，反而误导
            val onlyBinary = prepared.skipped.isNotEmpty() &&
                prepared.skipped.all { it.reason == SkipReason.BINARY }
            return PrepareResult.Fail(
                if (onlyBinary) {
                    "勾选的文件里只有二进制文件，没有可比对的文本改动。"
                } else {
                    "勾选的变更全部被排除规则过滤掉了（例如只有 lockfile 或构建产物）。请调整设置中的排除规则。"
                },
            )
        }

        return PrepareResult.Ok(StagedContext(repo, snapshot, config, prepared))
    }

    /** 把异常转成面向用户的提示文案；调用方负责实际弹出 */
    fun describeError(err: Throwable): String = when (err) {
        is AiError -> err.toUserMessage()
        is GitUnavailableError -> err.message ?: "git 不可用"
        is GitError -> err.message ?: "git 执行失败"
        else -> "CommitWise 执行失败：${err.message ?: err.javaClass.simpleName}"
    }
}
