package com.commitwise.idea.domain.diff

/**
 * 脱敏：在把 diff 发出去之前，把疑似密钥替换为占位符（移植自 redact.ts）。
 * 只做保守的正则匹配，宁可漏也不误伤业务代码（除最后的赋值式兜底）。
 */
object Redact {

    data class Result(val text: String, val hits: Int, val rules: List<String>)

    private class Rule(
        val name: String,
        val regex: Regex,
        /** 命中后如何重写；null 表示整体替换为固定文案 */
        val replace: String? = null,
        val rewrite: ((MatchResult) -> String)? = null,
    )

    private val RULES: List<Rule> = listOf(
        Rule(
            "private-key-block",
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]{0,4000}?-----END [A-Z ]*PRIVATE KEY-----"),
            replace = "***REDACTED PRIVATE KEY***",
        ),
        Rule("openai-key", Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b"), replace = "***REDACTED***"),
        Rule("anthropic-key", Regex("\\bsk-ant-[A-Za-z0-9_-]{16,}\\b"), replace = "***REDACTED***"),
        Rule("aws-access-key", Regex("\\bAKIA[0-9A-Z]{16}\\b"), replace = "***REDACTED***"),
        Rule("github-token", Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"), replace = "***REDACTED***"),
        Rule("google-api-key", Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"), replace = "***REDACTED***"),
        Rule("slack-token", Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"), replace = "***REDACTED***"),
        Rule(
            "jwt",
            Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
            replace = "***REDACTED JWT***",
        ),
        Rule(
            "bearer",
            Regex("\\b(Bearer|Basic)\\s+[A-Za-z0-9\\-._~+/]{12,}=*", RegexOption.IGNORE_CASE),
            rewrite = { m -> "${m.groupValues[1]} ***REDACTED***" },
        ),
        Rule(
            // 键名保留，值打码，便于模型理解"这里本来是密钥"
            "assignment",
            Regex(
                "\\b(api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|password|passwd" +
                    "|private[_-]?key|client[_-]?secret)\\b(\\s*[:=]\\s*)(['\"`])([^'\"`\\r\\n]{8,})\\3",
                RegexOption.IGNORE_CASE,
            ),
            rewrite = { m ->
                "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}***REDACTED***${m.groupValues[3]}"
            },
        ),
    )

    fun redactSecrets(text: String): Result {
        var out = text
        var hits = 0
        val matched = mutableListOf<String>()

        for (rule in RULES) {
            val found = rule.regex.findAll(out).toList()
            if (found.isEmpty()) continue
            for (_m in found) {
                hits++
                if (!matched.contains(rule.name)) matched.add(rule.name)
            }
            out = if (rule.rewrite != null) {
                rule.regex.replace(out) { m -> rule.rewrite.invoke(m) }
            } else {
                rule.regex.replace(out, rule.replace ?: "***REDACTED***")
            }
        }

        return Result(out, hits, matched)
    }
}
