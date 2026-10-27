package com.commitwise.idea.domain.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewParserTest {

    @Test
    fun `解析标准 JSON`() {
        val raw = """
            |{
            |  "summary": "整体可用，有 1 处安全问题",
            |  "issues": [
            |    {
            |      "file": "src/a.ts",
            |      "line": 12,
            |      "severity": "high",
            |      "category": "security",
            |      "title": "明文拼接 SQL",
            |      "detail": "存在注入风险",
            |      "suggestion": "改用参数化查询"
            |    }
            |  ]
            |}
        """.trimMargin()
        val out = ReviewParser.parseReviewResponse(raw)
        val result = assertNotNull(out.result, "应解析成功")
        assertEquals("整体可用，有 1 处安全问题", result.summary)
        val issue = result.issues.single()
        assertEquals("src/a.ts", issue.file)
        assertEquals(12, issue.line)
        assertEquals(Severity.HIGH, issue.severity)
        assertEquals(Category.SECURITY, issue.category)
        assertEquals("明文拼接 SQL", issue.title)
        assertEquals("改用参数化查询", issue.suggestion)
        assertEquals(Anchor.LINE, issue.anchor)
        assertEquals("issue-0", issue.id)
        assertEquals(0, out.dropped)
    }

    @Test
    fun `容忍代码块围栏`() {
        val raw = "```json\n{\"summary\":\"s\",\"issues\":[]}\n```"
        val out = ReviewParser.parseReviewResponse(raw)
        assertNotNull(out.result)
        assertTrue(out.repaired)
        assertEquals("s", out.result.summary)
    }

    @Test
    fun `容忍前后解释文字`() {
        val raw = "好的，审查结果如下：\n{\"summary\":\"s\",\"issues\":[]}\n希望有帮助。"
        val out = ReviewParser.parseReviewResponse(raw)
        assertNotNull(out.result)
        assertTrue(out.repaired)
    }

    @Test
    fun `容忍尾随逗号`() {
        val raw = "{\"summary\":\"s\",\"issues\":[{\"file\":\"a.ts\",\"title\":\"t\",},],}"
        val out = ReviewParser.parseReviewResponse(raw)
        val result = assertNotNull(out.result)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `顶层数组也接受`() {
        val raw = "[{\"file\":\"a.ts\",\"title\":\"问题\"}]"
        val out = ReviewParser.parseReviewResponse(raw)
        val result = assertNotNull(out.result)
        assertEquals("", result.summary)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `非法 JSON 返回错误而不是抛异常`() {
        val out = ReviewParser.parseReviewResponse("这里没有 JSON")
        assertNull(out.result)
        assertNotNull(out.error)
    }

    @Test
    fun `级别与分类别名归一化`() {
        assertEquals(Severity.HIGH, ReviewParser.normalizeSeverity("blocker"))
        assertEquals(Severity.HIGH, ReviewParser.normalizeSeverity("CRITICAL"))
        assertEquals(Severity.MEDIUM, ReviewParser.normalizeSeverity("warning"))
        assertEquals(Severity.LOW, ReviewParser.normalizeSeverity("nit"))
        assertEquals(Severity.MEDIUM, ReviewParser.normalizeSeverity("莫名其妙"))
        assertEquals(Severity.MEDIUM, ReviewParser.normalizeSeverity(null))

        assertEquals(Category.BUG, ReviewParser.normalizeCategory("correctness"))
        assertEquals(Category.PERFORMANCE, ReviewParser.normalizeCategory("perf"))
        assertEquals(Category.STYLE, ReviewParser.normalizeCategory("naming"))
        assertEquals(Category.TEST, ReviewParser.normalizeCategory("coverage"))
        assertEquals(Category.MAINTAINABILITY, ReviewParser.normalizeCategory(null))
    }

    @Test
    fun `行号支持数字字符串与带前缀文本`() {
        assertEquals(42, ReviewParser.normalizeLine(com.google.gson.JsonPrimitive(42)))
        assertEquals(42, ReviewParser.normalizeLine(com.google.gson.JsonPrimitive("L42")))
        assertNull(ReviewParser.normalizeLine(com.google.gson.JsonPrimitive("无")))
        assertNull(ReviewParser.normalizeLine(com.google.gson.JsonPrimitive(0)))
    }

    @Test
    fun `畸形条目被丢弃并计数`() {
        val raw = """
            |{"summary":"s","issues":[
            |  {"file":"a.ts","title":"正常"},
            |  {"file":"a.ts"},
            |  {"title":"缺文件"},
            |  "不是对象",
            |  {"file":"b.ts","title":"也正常","line":"第 7 行"}
            |]}
        """.trimMargin()
        val out = ReviewParser.parseReviewResponse(raw)
        val result = assertNotNull(out.result)
        assertEquals(2, result.issues.size)
        assertEquals(3, out.dropped)
        assertEquals(7, result.issues[1].line)
    }

    @Test
    fun `路径前缀被剥离`() {
        val raw = """
            |{"issues":[
            |  {"file":"a/src/a.ts","title":"t1"},
            |  {"file":"./src/b.ts","title":"t2"},
            |  {"file":"src\\c.ts","title":"t3"}
            |]}
        """.trimMargin()
        val result = assertNotNull(ReviewParser.parseReviewResponse(raw).result)
        assertEquals(listOf("src/a.ts", "src/b.ts", "src/c.ts"), result.issues.map { it.file })
    }

    @Test
    fun `无 suggestion 时为空`() {
        val raw = """{"issues":[{"file":"a.ts","title":"t","suggestion":"   "}]}"""
        val result = assertNotNull(ReviewParser.parseReviewResponse(raw).result)
        assertNull(result.issues.single().suggestion)
    }

    @Test
    fun `无行号时 anchor 为文件级`() {
        val raw = """{"issues":[{"file":"a.ts","title":"t"}]}"""
        val result = assertNotNull(ReviewParser.parseReviewResponse(raw).result)
        assertEquals(Anchor.FILE, result.issues.single().anchor)
        assertNull(result.issues.single().line)
    }
}
