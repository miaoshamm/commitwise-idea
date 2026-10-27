package com.commitwise.idea.domain.commit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageValidatorTest {

    private fun opts(format: CommitFormat = CommitFormat.CONVENTIONAL, max: Int = 72) =
        ParseOptions(format, max)

    // ---------- 清洗 ----------

    @Test
    fun `去掉整段代码块围栏`() {
        val out = MessageValidator.cleanModelOutput("```\nfeat: 新增登录\n```")
        assertEquals("feat: 新增登录", out.message)
        assertTrue(out.hadFence)
    }

    @Test
    fun `去掉带语言标记的围栏`() {
        val out = MessageValidator.cleanModelOutput("```text\nfix: 修复崩溃\n```")
        assertEquals("fix: 修复崩溃", out.message)
        assertTrue(out.hadFence)
    }

    @Test
    fun `去掉前置寒暄中文`() {
        val out = MessageValidator.cleanModelOutput("好的，以下是提交信息：\nfeat: 新增登录")
        assertEquals("feat: 新增登录", out.message)
        assertTrue(out.hadPreamble)
    }

    @Test
    fun `去掉前置寒暄英文`() {
        val out = MessageValidator.cleanModelOutput("Here's the commit message:\nfeat: add login")
        assertEquals("feat: add login", out.message)
        assertTrue(out.hadPreamble)
    }

    @Test
    fun `寒暄加围栏同时存在时只留正文`() {
        val out = MessageValidator.cleanModelOutput("Sure! Here it is:\n```\nfeat: add login\n```")
        assertEquals("feat: add login", out.message)
        assertTrue(out.hadFence)
    }

    @Test
    fun `去掉整体包裹的引号`() {
        assertEquals("feat: add login", MessageValidator.cleanModelOutput("\"feat: add login\"").message)
        assertEquals("feat: add login", MessageValidator.cleanModelOutput("“feat: add login”").message)
    }

    @Test
    fun `正文内部的引号不会被误删`() {
        val raw = "\"feat: 支持 \"\"双引号\"\" 场景\""
        assertEquals(raw, MessageValidator.cleanModelOutput(raw).message)
    }

    @Test
    fun `连续空行压成一行`() {
        val out = MessageValidator.cleanModelOutput("feat: x\n\n\n\nbody line")
        assertEquals("feat: x\n\nbody line", out.message)
    }

    // ---------- 校验 ----------

    @Test
    fun `合规的 conventional 提交信息`() {
        val p = MessageValidator.parse("feat(api): 新增分页参数", opts())
        assertTrue(p.valid)
        assertTrue(p.issues.isEmpty(), "不应有告警：${p.issues}")
        assertEquals("feat", p.type)
        assertEquals("api", p.scope)
        assertEquals("新增分页参数", p.description)
        assertFalse(p.breaking)
    }

    @Test
    fun `breaking change 标记被识别`() {
        val p = MessageValidator.parse("feat(core)!: 移除旧接口", opts())
        assertTrue(p.breaking)
        assertEquals("core", p.scope)
    }

    @Test
    fun `type 不在规范集合内判为不合规`() {
        val p = MessageValidator.parse("feature: 新增功能", opts())
        assertFalse(p.valid)
        assertTrue(p.issues.any { it.code == "unknown-type" })
    }

    @Test
    fun `首行缺失 type 判为不合规`() {
        val p = MessageValidator.parse("添加登录页", opts())
        assertFalse(p.valid)
        assertTrue(p.issues.any { it.code == "missing-type" && it.severity == "error" })
    }

    @Test
    fun `大写 type 只告警并归一化为小写`() {
        val p = MessageValidator.parse("Feat: 新增功能", opts())
        assertTrue(p.valid, "仅有告警时仍视为合规")
        assertTrue(p.issues.any { it.code == "lowercase-type" })
        assertEquals("feat", p.type)
    }

    @Test
    fun `首行超长告警`() {
        val p = MessageValidator.parse("feat: 这是一段非常长的描述内容", opts(max = 10))
        assertTrue(p.valid)
        assertTrue(p.issues.any { it.code == "too-long" })
    }

    @Test
    fun `末尾句号告警`() {
        val p = MessageValidator.parse("fix: 修复空指针崩溃.", opts())
        assertTrue(p.issues.any { it.code == "trailing-period" })
    }

    @Test
    fun `正文被单独解析出来`() {
        val p = MessageValidator.parse("feat: 新增登录\n\n- 支持手机号\n- 支持邮箱", opts())
        assertEquals("feat: 新增登录", p.header)
        assertEquals("- 支持手机号\n- 支持邮箱", p.body)
    }

    @Test
    fun `gitmoji 格式识别 emoji 前缀`() {
        val p = MessageValidator.parse("✨ feat(ui): 新增按钮", opts(CommitFormat.GITMOJI))
        assertTrue(p.valid)
        assertEquals("feat", p.type)
        assertEquals("ui", p.scope)
        assertEquals("feat(ui): 新增按钮", p.description)
    }

    @Test
    fun `gitmoji 缺少 emoji 只告警`() {
        val p = MessageValidator.parse("feat(ui): 新增按钮", opts(CommitFormat.GITMOJI))
        assertTrue(p.valid)
        assertTrue(p.issues.any { it.code == "missing-type" && it.severity == "warn" })
    }

    @Test
    fun `纯文本格式不校验 type`() {
        val p = MessageValidator.parse("随便改了点东西", opts(CommitFormat.PLAIN))
        assertTrue(p.valid)
        assertNull(p.type)
        assertEquals("随便改了点东西", p.description)
    }

    @Test
    fun `空输出直接判为不合规`() {
        val p = MessageValidator.parse("   ", opts())
        assertFalse(p.valid)
        assertEquals("", p.message)
        assertEquals("empty", p.issues.single().code)
    }

    // ---------- 纠错重试 ----------

    @Test
    fun `纠错提示包含问题清单与原输出`() {
        val p = MessageValidator.parse("feature: 新增功能", opts())
        val prompt = MessageValidator.buildCorrectionPrompt(p, opts())
        assertTrue(prompt.contains("你上一次的输出不符合要求"))
        assertTrue(prompt.contains("feature: 新增功能"))
        assertTrue(prompt.contains("type 只能取"))
        assertTrue(prompt.contains("feat"))
    }

    @Test
    fun `非 strict 格式的纠错提示不追加 type 约束`() {
        val p = MessageValidator.parse("feat: x", opts(CommitFormat.PLAIN, max = 5))
        val prompt = MessageValidator.buildCorrectionPrompt(p, opts(CommitFormat.PLAIN, max = 5))
        assertTrue(prompt.contains("你上一次的输出不符合要求"))
        assertFalse(prompt.contains("type 只能取"))
    }
}
