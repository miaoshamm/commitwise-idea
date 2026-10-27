package com.commitwise.idea.domain.prompt

/** 模板类型 */
enum class TemplateType { COMMIT, REVIEW }

data class PromptTemplate(
    val id: String,
    val type: TemplateType,
    val name: String,
    val description: String? = null,
    val systemPrompt: String? = null,
    val userPrompt: String,
    val builtin: Boolean = false,
)

/** 模板中可用的变量，及其说明（用于设置提示与校验） */
val TEMPLATE_VARIABLES: Map<String, String> = linkedMapOf(
    "diff" to "暂存区补丁文本（已过滤、截断、脱敏）",
    "stagedFiles" to "暂存文件清单，含增删行数与排除标记",
    "fileStats" to "文件数量与总增删行数",
    "repoName" to "仓库目录名",
    "branch" to "当前分支名",
    "recentCommits" to "最近若干条提交标题",
    "language" to "输出语言",
    "format" to "提交信息格式",
    "maxSubjectLength" to "首行最大长度",
    "bodyInstruction" to "关于是否输出正文的说明",
    "truncated" to "是否存在被截断或排除的文件",
)

private val VAR_PATTERN = Regex("""\{\{\s*([a-zA-Z0-9_.]+)\s*}}""")

data class RenderResult(val text: String, val missing: List<String>)

object RenderTemplate {

    fun render(template: String, vars: Map<String, String?>): RenderResult {
        val missing = mutableListOf<String>()
        val text = VAR_PATTERN.replace(template) { m ->
            val name = m.groupValues[1]
            val value = vars[name]
            if (value == null) {
                missing.add(name)
                ""
            } else {
                value
            }
        }
        return RenderResult(text, missing.distinct())
    }

    fun usedVariables(template: String): List<String> =
        VAR_PATTERN.findAll(template).map { it.groupValues[1] }.distinct().toList()

    data class TemplateValidationError(val field: String, val message: String)

    /** 校验自定义模板，错误信息直接面向用户 */
    fun validate(tpl: PromptTemplate?): List<TemplateValidationError> {
        if (tpl == null) {
            return listOf(TemplateValidationError("id", "模板为空"))
        }
        val errors = mutableListOf<TemplateValidationError>()
        if (!Regex("^[a-zA-Z0-9_-]{2,64}$").matches(tpl.id)) {
            errors.add(TemplateValidationError("id", "id 必填，只能包含字母、数字、下划线或连字符（2–64 位）"))
        }
        if (tpl.name.isBlank()) {
            errors.add(TemplateValidationError("name", "name 必填"))
        }
        if (tpl.userPrompt.isBlank()) {
            errors.add(TemplateValidationError("userPrompt", "userPrompt 必填"))
        } else if (!Regex("""\{\{\s*diff\s*}}""").containsMatchIn(tpl.userPrompt)) {
            errors.add(TemplateValidationError("userPrompt", "userPrompt 必须包含 {{diff}}，否则模型看不到代码变更"))
        }
        return errors
    }
}
