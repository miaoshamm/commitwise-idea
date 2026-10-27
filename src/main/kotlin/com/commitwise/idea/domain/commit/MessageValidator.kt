package com.commitwise.idea.domain.commit

/** 提交信息格式 */
enum class CommitFormat(val id: String, val label: String) {
    CONVENTIONAL("conventional", "Conventional Commits"),
    ANGULAR("angular", "Angular 规范"),
    GITMOJI("gitmoji", "Gitmoji"),
    PLAIN("plain", "纯文本"),
    CUSTOM("custom", "自定义"),
    ;

    companion object {
        fun of(value: String?): CommitFormat =
            entries.firstOrNull { it.id == value } ?: CONVENTIONAL
    }
}

val CONVENTIONAL_TYPES = listOf(
    "feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore", "revert",
)

data class CommitIssue(val code: String, val severity: String, val message: String)

data class ParsedCommit(
    /** 清理后的完整提交信息 */
    val message: String,
    /** 首行 */
    val header: String,
    /** 首行中 `:` 之后的部分 */
    val description: String,
    val type: String? = null,
    val scope: String? = null,
    val breaking: Boolean = false,
    val body: String? = null,
    val issues: List<CommitIssue> = emptyList(),
    val valid: Boolean = false,
)

data class ParseOptions(val format: CommitFormat, val maxSubjectLength: Int)

data class CleanedOutput(val message: String, val hadPreamble: Boolean, val hadFence: Boolean)

object MessageValidator {

    private val PREAMBLE = Regex(
        "^(好(的)?[，,。]?|以下(是|为)|这是|根据(您|你)?的?|提交信息(如下)?|here('s| is)|sure|certainly|commit message)",
        RegexOption.IGNORE_CASE,
    )
    private val FENCED_FULL = Regex("^```[a-zA-Z0-9_-]*\n([\\s\\S]*?)\n?```$")
    private val FENCED_INNER = Regex("```[a-zA-Z0-9_-]*\n([\\s\\S]*?)```")
    private val HEADER_RE = Regex("^([A-Za-z]+)(?:\\(([^)]*)\\))?(!)?\\s*[:：]\\s*(.+)$")

    /**
     * 清洗模型输出：去掉代码块围栏、前置寒暄、包裹引号、多余空行。
     * 模型即使被要求"只输出结果"，也经常加这些壳。
     */
    fun cleanModelOutput(raw: String): CleanedOutput {
        var text = raw.replace("\r\n", "\n").trim()
        var hadFence = false
        var hadPreamble = false

        val fenced = FENCED_FULL.find(text)
        if (fenced != null) {
            text = fenced.groupValues[1].trim()
            hadFence = true
        } else {
            val inner = FENCED_INNER.find(text)
            if (inner != null && text.indexOf("```") < 40) {
                text = inner.groupValues[1].trim()
                hadFence = true
            }
        }

        val lines = text.split("\n").toMutableList()
        while (lines.size > 1) {
            val first = lines[0].trim()
            if (first.isEmpty()) {
                lines.removeAt(0)
                continue
            }
            val looksLikeLeadIn = Regex("[:：]$").containsMatchIn(first) &&
                (PREAMBLE.containsMatchIn(first) || first.length < 40)
            if (looksLikeLeadIn) {
                lines.removeAt(0)
                hadPreamble = true
                continue
            }
            break
        }

        text = lines.joinToString("\n").trim()

        // 整体被引号包裹
        if (text.length > 2) {
            val pairs = listOf("\"" to "\"", "'" to "'", "“" to "”")
            for ((open, close) in pairs) {
                if (text.startsWith(open) && text.endsWith(close) && !text.substring(1, text.length - 1).contains(close)) {
                    text = text.substring(1, text.length - 1).trim()
                    break
                }
            }
        }

        return CleanedOutput(text.replace(Regex("\n{3,}"), "\n\n"), hadPreamble, hadFence)
    }

    /** 首行是否以 emoji / 非 ASCII 符号开头（Gitmoji 场景） */
    private fun gitmojiPrefix(header: String): Pair<String, String>? {
        if (header.isEmpty()) return null
        val first = header.codePointAt(0)
        val isEmoji = first > 0x2000 && !Character.isLetterOrDigit(first)
        if (!isEmoji) return null
        val end = header.offsetByCodePoints(0, Character.charCount(first))
        val rest = header.substring(end).trimStart()
        return header.substring(0, end).trim() to rest
    }

    fun parse(raw: String, opts: ParseOptions): ParsedCommit {
        val format = opts.format
        val maxSubjectLength = opts.maxSubjectLength
        val issues = mutableListOf<CommitIssue>()
        val cleaned = cleanModelOutput(raw)
        val message = cleaned.message

        if (message.isEmpty()) {
            return ParsedCommit(
                message = "",
                header = "",
                description = "",
                issues = listOf(CommitIssue("empty", "error", "模型返回为空")),
                valid = false,
            )
        }

        val lines = message.split("\n")
        val header = lines[0].trim()
        val body = lines.drop(1).joinToString("\n").trim()
        var description = header
        var type: String? = null
        var scope: String? = null
        var breaking = false

        val strictFormat = format == CommitFormat.CONVENTIONAL || format == CommitFormat.ANGULAR || format == CommitFormat.CUSTOM

        if (format == CommitFormat.GITMOJI) {
            val split = gitmojiPrefix(header)
            if (split == null) {
                issues.add(CommitIssue("missing-type", "warn", "首行缺少 emoji 前缀"))
            }
            val afterEmoji = split?.second ?: header
            val hm = HEADER_RE.find(afterEmoji)
            if (hm != null) {
                type = hm.groupValues[1].lowercase()
                scope = hm.groupValues[2].ifEmpty { null }
                breaking = hm.groupValues[3].isNotEmpty()
            }
            description = afterEmoji.trim()
        } else if (strictFormat) {
            val m = HEADER_RE.find(header)
            if (m == null) {
                issues.add(
                    CommitIssue(
                        "missing-type",
                        "error",
                        "首行不符合 \"<type>(<scope>): <subject>\" 格式：${header.take(40)}",
                    ),
                )
            } else {
                var t = m.groupValues[1]
                scope = m.groupValues[2].ifEmpty { null }
                breaking = m.groupValues[3].isNotEmpty()
                description = m.groupValues[4].trim()
                if (t != t.lowercase()) {
                    issues.add(CommitIssue("lowercase-type", "warn", "type 建议小写：$t"))
                    t = t.lowercase()
                }
                type = t
                if (!CONVENTIONAL_TYPES.contains(t)) {
                    issues.add(
                        CommitIssue(
                            "unknown-type",
                            "error",
                            "type \"$t\" 不在规范集合内（${CONVENTIONAL_TYPES.joinToString("/")}）",
                        ),
                    )
                }
            }
        } else {
            description = header
        }

        if (header.length > maxSubjectLength) {
            issues.add(
                CommitIssue("too-long", "warn", "首行 ${header.length} 字符，超过上限 $maxSubjectLength"),
            )
        }

        if (Regex("[。.]$").containsMatchIn(description) && description.length > 1) {
            issues.add(CommitIssue("trailing-period", "warn", "首行末尾不应有句号"))
        }

        val valid = issues.none { it.severity == "error" }

        return ParsedCommit(
            message = message,
            header = header,
            description = description,
            type = type,
            scope = scope,
            breaking = breaking,
            body = body.ifEmpty { null },
            issues = issues,
            valid = valid,
        )
    }

    private const val QUOTE3 = "\"\"\""

    /** 把校验失败原因转成给模型的纠错指令，用于一次带反馈重试 */
    fun buildCorrectionPrompt(parsed: ParsedCommit, opts: ParseOptions): String {
        val problems = parsed.issues.joinToString("\n") { "- ${it.message}" }
        val extra = if (opts.format == CommitFormat.CONVENTIONAL || opts.format == CommitFormat.ANGULAR ||
            opts.format == CommitFormat.CUSTOM
        ) {
            "\n首行必须是 <type>(<scope>): <subject>，type 只能取 " +
                "${CONVENTIONAL_TYPES.joinToString("、")}，首行不超过 ${opts.maxSubjectLength} 个字符。"
        } else {
            ""
        }
        return "你上一次的输出不符合要求：\n$problems\n\n" +
            "上一次的输出是：\n$QUOTE3\n${parsed.message}\n$QUOTE3\n\n" +
            "请修正上述问题，重新输出一条提交信息。仍然只输出提交信息本身，不要任何解释、不要代码块围栏。$extra"
    }
}
