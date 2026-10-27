package com.commitwise.idea.app

import com.commitwise.idea.domain.diff.PreparedDiff
import com.commitwise.idea.domain.prompt.PromptTemplate
import com.commitwise.idea.domain.prompt.RenderTemplate
import com.commitwise.idea.infra.ai.ChatMessage
import com.commitwise.idea.infra.ai.ChatRole
import com.commitwise.idea.infra.config.ConfigValidator
import com.commitwise.idea.infra.config.ResolvedConfig
import com.commitwise.idea.infra.git.StagedSnapshot

fun repoNameOf(root: String): String {
    val parts = root.split('/', '\\').filter { it.isNotEmpty() }
    return parts.lastOrNull() ?: root
}

object PromptBuilder {

    fun buildVariables(
        snapshot: StagedSnapshot,
        prepared: PreparedDiff,
        config: ResolvedConfig,
    ): Map<String, String> {
        val additions = snapshot.files.sumOf { it.additions }
        val deletions = snapshot.files.sumOf { it.deletions }

        val truncatedParts = mutableListOf<String>()
        if (prepared.truncated.isNotEmpty()) {
            truncatedParts.add("以下文件内容过长已截断：${prepared.truncated.joinToString("、")}")
        }
        val excludedFiles = prepared.skipped.filter {
            it.reason == com.commitwise.idea.domain.diff.SkipReason.EXCLUDED && it.detail != "no hunk"
        }
        if (excludedFiles.isNotEmpty()) {
            truncatedParts.add("以下文件按排除规则未提供内容：${excludedFiles.joinToString("、") { it.path }}")
        }

        return mapOf(
            "diff" to prepared.patch,
            "stagedFiles" to prepared.fileList.ifEmpty { "（空）" },
            "fileStats" to "${snapshot.files.size} 个文件，+$additions / -$deletions 行",
            "repoName" to repoNameOf(snapshot.root),
            "branch" to snapshot.branch,
            "recentCommits" to if (snapshot.recentCommits.isNotEmpty()) {
                snapshot.recentCommits.joinToString("\n") { "- $it" }
            } else {
                "（暂无历史提交）"
            },
            "language" to ConfigValidator.languageName(config.commit.language),
            "format" to config.commit.format.id,
            "maxSubjectLength" to config.commit.maxSubjectLength.toString(),
            "bodyInstruction" to if (config.commit.includeBody) {
                "第二行留空，之后用列表补充「为什么这样改」，每条一行，不超过 5 条"
            } else {
                "只输出单行，不要正文"
            },
            "truncated" to if (truncatedParts.isNotEmpty()) truncatedParts.joinToString("；") else "无",
        )
    }

    data class BuiltPrompt(val messages: List<ChatMessage>, val missingVariables: List<String>)

    fun buildMessages(
        template: PromptTemplate,
        snapshot: StagedSnapshot,
        prepared: PreparedDiff,
        config: ResolvedConfig,
    ): BuiltPrompt {
        val vars = buildVariables(snapshot, prepared, config)
        val rendered = RenderTemplate.render(template.userPrompt, vars)
        val messages = mutableListOf<ChatMessage>()
        val system = template.systemPrompt?.trim()
        if (!system.isNullOrEmpty()) {
            messages.add(ChatMessage(ChatRole.SYSTEM, system))
        }
        messages.add(ChatMessage(ChatRole.USER, rendered.text))
        return BuiltPrompt(messages, rendered.missing)
    }

    private const val QUOTE3 = "\"\"\""

    /** 审查场景：把模型输出打回要求"只输出 JSON" */
    fun buildReviewRepairMessage(rawOutput: String): String =
        "你上一次的输出不是合法 JSON，无法解析。请只输出一个 JSON 对象，不要 Markdown 代码块，不要任何解释文字。\n\n" +
            "上一次的输出开头是：\n$QUOTE3\n${rawOutput.take(400)}\n$QUOTE3\n\n" +
            "请重新输出完整的 JSON 对象，结构为：\n" +
            "{\"summary\": \"整体评价\", \"issues\": [{\"file\": \"路径\", \"line\": 行号, " +
            "\"severity\": \"high|medium|low\", \"category\": \"bug|security|performance|style|maintainability|test|compat\", " +
            "\"title\": \"标题\", \"detail\": \"说明\", \"suggestion\": \"建议\"}]}"
}
