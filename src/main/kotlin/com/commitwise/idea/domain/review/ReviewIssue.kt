package com.commitwise.idea.domain.review

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class Severity(val id: String, val label: String) {
    HIGH("high", "高"),
    MEDIUM("medium", "中"),
    LOW("low", "低"),
}

enum class Category(val id: String, val label: String) {
    BUG("bug", "缺陷"),
    SECURITY("security", "安全"),
    PERFORMANCE("performance", "性能"),
    STYLE("style", "风格"),
    MAINTAINABILITY("maintainability", "可维护性"),
    TEST("test", "测试"),
    COMPAT("compat", "兼容性"),
}

enum class Anchor { LINE, FILE }

data class ReviewIssue(
    val id: String,
    val file: String,
    /** 新增行行号；为 null 表示只能定位到文件级 */
    val line: Int? = null,
    val severity: Severity = Severity.MEDIUM,
    val category: Category = Category.MAINTAINABILITY,
    val title: String,
    val detail: String = "",
    val suggestion: String? = null,
    /** 定位精度 */
    val anchor: Anchor = Anchor.FILE,
)

data class ReviewResult(val summary: String, val issues: List<ReviewIssue>)

private val SEVERITY_ALIASES: Map<String, Severity> = buildMap {
    listOf("high", "critical", "blocker", "error", "major").forEach { put(it, Severity.HIGH) }
    listOf("medium", "moderate", "warning", "warn", "normal").forEach { put(it, Severity.MEDIUM) }
    listOf("low", "minor", "info", "suggestion", "nit", "trivial").forEach { put(it, Severity.LOW) }
}

private val CATEGORY_ALIASES: Map<String, Category> = buildMap {
    listOf("bug", "correctness", "logic", "error").forEach { put(it, Category.BUG) }
    listOf("security", "vuln").forEach { put(it, Category.SECURITY) }
    listOf("performance", "perf", "efficiency").forEach { put(it, Category.PERFORMANCE) }
    listOf("style", "formatting", "naming").forEach { put(it, Category.STYLE) }
    listOf("maintainability", "design", "readability", "refactor").forEach { put(it, Category.MAINTAINABILITY) }
    listOf("test", "testing", "coverage").forEach { put(it, Category.TEST) }
    listOf("compat", "compatibility", "deprecation").forEach { put(it, Category.COMPAT) }
}

object ReviewParser {

    fun normalizeSeverity(value: String?): Severity {
        if (value == null) return Severity.MEDIUM
        return SEVERITY_ALIASES[value.trim().lowercase()] ?: Severity.MEDIUM
    }

    fun normalizeCategory(value: String?): Category {
        if (value == null) return Category.MAINTAINABILITY
        return CATEGORY_ALIASES[value.trim().lowercase()] ?: Category.MAINTAINABILITY
    }

    fun normalizeSeverityOf(value: JsonElement?): Severity {
        val s = asString(value) ?: return Severity.MEDIUM
        return normalizeSeverity(s)
    }

    fun normalizeCategoryOf(value: JsonElement?): Category {
        val s = asString(value) ?: return Category.MAINTAINABILITY
        return normalizeCategory(s)
    }

    private fun normalizeFilePath(raw: String?): String? {
        if (raw == null) return null
        var p = raw.trim().replace('\\', '/')
        if (p.isEmpty()) return null
        p = p.removeSurrounding("\"")
        p = p.replace(Regex("^[ab]/"), "")
        p = p.replace(Regex("^\\./"), "")
        return p
    }

    fun normalizeLine(value: JsonElement?): Int? {
        if (value == null || value.isJsonNull) return null
        if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            val n = value.asDouble
            return if (n.isFinite() && n > 0) n.toInt() else null
        }
        if (value.isJsonPrimitive) {
            val m = Regex("\\d+").find(value.asString)
            if (m != null) {
                val n = m.value.toIntOrNull() ?: return null
                if (n > 0) return n
            }
        }
        return null
    }

    data class JsonExtraction(val value: JsonElement?, val error: String? = null, val repaired: Boolean = false)

    private fun tryParse(text: String): JsonElement? = try {
        JsonParser.parseString(text)
    } catch (_: Exception) {
        null
    }

    /**
     * Gson 的 JsonParser 默认是宽松模式：像「好的，结果如下：\n{...}」这样的文本会被
     * 当成一个未加引号的字符串「解析成功」，从而让后面的剥离逻辑永远走不到。
     * 这里只接受对象/数组，保证容错顺序与 TS 版（JSON.parse 严格）一致。
     */
    private fun accept(el: JsonElement?): JsonElement? =
        if (el != null && (el.isJsonObject || el.isJsonArray)) el else null

    /** 从模型输出中提取 JSON。容忍：代码块围栏、前后解释文字、尾随逗号、BOM。 */
    fun extractJson(raw: String): JsonExtraction {
        var repaired = false
        var text = raw.removePrefix("\uFEFF").trim()

        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        if (fenced != null) {
            text = fenced.groupValues[1].trim()
            repaired = true
        }

        accept(tryParse(text))?.let { return JsonExtraction(it, null, repaired) }

        // 截出第一个完整的对象或数组（取位置上更靠前的那个起始符）
        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        val arrStart = text.indexOf('[')
        val arrEnd = text.lastIndexOf(']')
        val useArray = arrStart >= 0 && arrEnd > arrStart && (objStart < 0 || arrStart < objStart)
        val sliced = when {
            useArray -> text.substring(arrStart, arrEnd + 1)
            objStart >= 0 && objEnd > objStart -> text.substring(objStart, objEnd + 1)
            else -> null
        }
        if (sliced != null) {
            repaired = true
            accept(tryParse(sliced))?.let { return JsonExtraction(it, null, repaired) }
            text = sliced
        }

        // 去掉对象/数组中的尾随逗号后重试
        val noTrailing = text.replace(Regex(",\\s*([}\\]])"), "$1")
        if (noTrailing != text) {
            repaired = true
            accept(tryParse(noTrailing))?.let { return JsonExtraction(it, null, repaired) }
        }

        return JsonExtraction(null, "模型输出不是合法 JSON", repaired)
    }

    data class ParseOutcome(
        val result: ReviewResult? = null,
        val error: String? = null,
        val repaired: Boolean = false,
        /** 被丢弃的畸形条目数 */
        val dropped: Int = 0,
    )

    private fun asString(el: JsonElement?): String? {
        if (el == null || el.isJsonNull) return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    private fun str(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = asString(obj.get(k))
            if (v != null) return v
        }
        return null
    }

    private fun elem(obj: JsonObject, vararg keys: String): JsonElement? {
        for (k in keys) {
            val v = obj.get(k)
            if (v != null && !v.isJsonNull) return v
        }
        return null
    }

    /** 把模型返回的 JSON 规范化为 ReviewResult，逐条容错 */
    fun parseReviewResponse(raw: String): ParseOutcome {
        val extracted = extractJson(raw)
        val value = extracted.value
            ?: return ParseOutcome(error = extracted.error, repaired = extracted.repaired, dropped = 0)

        val payload: JsonObject = when {
            value.isJsonArray -> JsonObject().apply {
                addProperty("summary", "")
                add("issues", value as JsonArray)
            }
            value.isJsonObject -> value as JsonObject
            else -> return ParseOutcome(error = "模型返回的 JSON 结构不符合预期", repaired = extracted.repaired, dropped = 0)
        }

        val rawIssues = payload.get("issues")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val issues = mutableListOf<ReviewIssue>()
        var dropped = 0

        rawIssues.forEachIndexed { index, item ->
            if (!item.isJsonObject) {
                dropped++
                return@forEachIndexed
            }
            val it2 = item.asJsonObject
            val file = normalizeFilePath(str(it2, "file", "path", "filename"))
            val title = (str(it2, "title", "message") ?: "").trim()
            if (file == null || title.isEmpty()) {
                dropped++
                return@forEachIndexed
            }
            val line = normalizeLine(elem(it2, "line", "lineNumber", "startLine"))
            issues.add(
                ReviewIssue(
                    id = "issue-$index",
                    file = file,
                    line = line,
                    severity = normalizeSeverityOf(elem(it2, "severity", "level")),
                    category = normalizeCategoryOf(elem(it2, "category", "type")),
                    title = title,
                    detail = (str(it2, "detail", "description") ?: "").trim(),
                    suggestion = str(it2, "suggestion", "fix")?.trim()?.ifEmpty { null },
                    anchor = if (line != null) Anchor.LINE else Anchor.FILE,
                ),
            )
        }

        return ParseOutcome(
            result = ReviewResult((asString(payload.get("summary")) ?: "").trim(), issues),
            repaired = extracted.repaired,
            dropped = dropped,
        )
    }
}
