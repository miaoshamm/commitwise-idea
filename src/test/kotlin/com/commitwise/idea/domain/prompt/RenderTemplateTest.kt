package com.commitwise.idea.domain.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderTemplateTest {

    @Test
    fun `替换变量并报告缺失`() {
        val out = RenderTemplate.render("变更：\n{{diff}}\n", mapOf("diff" to "PATCH"))
        assertEquals("变更：\nPATCH\n", out.text)
        assertTrue(out.missing.isEmpty())
    }

    @Test
    fun `缺失变量替换为空串并记录`() {
        val out = RenderTemplate.render("a{{nope}}b", emptyMap())
        assertEquals("ab", out.text)
        assertEquals(listOf("nope"), out.missing)
    }

    @Test
    fun `花括号内允许空格`() {
        assertEquals("X", RenderTemplate.render("{{ diff }}", mapOf("diff" to "X")).text)
    }

    @Test
    fun `usedVariables 去重且保序`() {
        val tpl = "{{diff}} {{ language }} {{diff}}"
        assertEquals(listOf("diff", "language"), RenderTemplate.usedVariables(tpl))
    }

    @Test
    fun `内置模板声明的变量都在可用列表内`() {
        val known = TEMPLATE_VARIABLES.keys
        for (tpl in BuiltinTemplates.ALL) {
            for (name in RenderTemplate.usedVariables(tpl.userPrompt + (tpl.systemPrompt ?: ""))) {
                assertTrue(name in known, "模板 ${tpl.id} 使用了未声明的变量 $name")
            }
        }
    }

    @Test
    fun `每个内置模板都包含 diff 变量`() {
        for (tpl in BuiltinTemplates.ALL) {
            assertTrue(
                RenderTemplate.usedVariables(tpl.userPrompt).contains("diff"),
                "模板 ${tpl.id} 缺少 {{diff}}",
            )
        }
    }

    @Test
    fun `内置模板自身应通过校验`() {
        for (tpl in BuiltinTemplates.ALL) {
            assertTrue(RenderTemplate.validate(tpl).isEmpty(), "模板 ${tpl.id} 校验失败")
        }
    }

    @Test
    fun `校验捕获非法 id`() {
        val tpl = PromptTemplate(id = "1!", type = TemplateType.COMMIT, name = "n", userPrompt = "{{diff}}")
        val errors = RenderTemplate.validate(tpl)
        assertTrue(errors.any { it.field == "id" })
    }

    @Test
    fun `校验捕获空名称与空 userPrompt`() {
        val tpl = PromptTemplate(id = "ok-id", type = TemplateType.COMMIT, name = "  ", userPrompt = "")
        val errors = RenderTemplate.validate(tpl)
        assertEquals(setOf("name", "userPrompt"), errors.map { it.field }.toSet())
    }

    @Test
    fun `校验要求 userPrompt 含 diff`() {
        val tpl = PromptTemplate(id = "ok-id", type = TemplateType.COMMIT, name = "n", userPrompt = "只看文件名")
        val errors = RenderTemplate.validate(tpl)
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("{{diff}}"))
    }

    @Test
    fun `空模板直接报错`() {
        assertEquals(1, RenderTemplate.validate(null).size)
    }
}

class TemplateRegistryTest {

    @Test
    fun `内置模板数量与类型分组`() {
        val r = TemplateRegistry()
        assertEquals(6, r.list().size)
        assertEquals(4, r.list(TemplateType.COMMIT).size)
        assertEquals(2, r.list(TemplateType.REVIEW).size)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `按 id 命中时不回退`() {
        val r = TemplateRegistry()
        val (tpl, fallback) = r.resolve("builtin-gitmoji", TemplateType.COMMIT)
        assertEquals("builtin-gitmoji", tpl.id)
        assertFalse(fallback)
    }

    @Test
    fun `id 不存在时回退到同类型内置模板`() {
        val r = TemplateRegistry()
        val (tpl, fallback) = r.resolve("missing", TemplateType.COMMIT)
        assertTrue(fallback)
        assertTrue(tpl.builtin)
        assertEquals(TemplateType.COMMIT, tpl.type)
    }

    @Test
    fun `类型不匹配时也回退`() {
        val r = TemplateRegistry()
        val (tpl, fallback) = r.resolve("builtin-conventional-zh", TemplateType.REVIEW)
        assertTrue(fallback)
        assertEquals(TemplateType.REVIEW, tpl.type)
    }

    @Test
    fun `合法自定义模板被加入`() {
        val r = TemplateRegistry(
            listOf(
                CustomTemplate(
                    id = "my-commit",
                    type = "commit",
                    name = "我的模板",
                    description = "说明",
                    systemPrompt = "你是助手",
                    userPrompt = "看看 {{diff}}",
                ),
            ),
        )
        val tpl = assertNotNull(r.get("my-commit"))
        assertFalse(tpl.builtin)
        assertEquals("我的模板", tpl.name)
        assertEquals(5, r.list(TemplateType.COMMIT).size)
    }

    @Test
    fun `非法自定义模板被跳过并给出告警`() {
        val r = TemplateRegistry(
            listOf(
                CustomTemplate(id = "bad-one", type = "commit", name = "坏模板", userPrompt = "没有变量"),
            ),
        )
        assertNull(r.get("bad-one"))
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0].contains("bad-one"))
        assertTrue(r.warnings[0].contains("已跳过"))
        assertEquals(4, r.list(TemplateType.COMMIT).size)
    }

    @Test
    fun `自定义模板 type 为 review 时归入审查类型`() {
        val r = TemplateRegistry(
            listOf(CustomTemplate(id = "my-review", type = "REVIEW", name = "审", userPrompt = "{{diff}}")),
        )
        assertEquals(TemplateType.REVIEW, assertNotNull(r.get("my-review")).type)
        assertEquals(3, r.list(TemplateType.REVIEW).size)
    }
}
