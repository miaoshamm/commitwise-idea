package com.commitwise.idea.infra.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private fun models(vararg ids: String) = ids.map { ModelInfo(it) }

    @Test
    fun `哪些服务商支持自动拉取模型列表`() {
        assertTrue(ModelCatalog.supportsModelList("openai-compatible"))
        assertTrue(ModelCatalog.supportsModelList("anthropic"))
        assertTrue(ModelCatalog.supportsModelList("ollama"))
        assertFalse(ModelCatalog.supportsModelList("azure-openai"))
        assertFalse(ModelCatalog.supportsModelList("unknown"))
    }

    @Test
    fun `嵌入与语音类模型被识别为非对话模型`() {
        assertFalse(ModelCatalog.isLikelyChatModel("text-embedding-3-large"))
        assertFalse(ModelCatalog.isLikelyChatModel("whisper-1"))
        assertFalse(ModelCatalog.isLikelyChatModel("tts-1-hd"))
        assertFalse(ModelCatalog.isLikelyChatModel("dall-e-3"))
        assertFalse(ModelCatalog.isLikelyChatModel("bge-m3"))
        assertFalse(ModelCatalog.isLikelyChatModel("omni-moderation-latest"))
    }

    @Test
    fun `对话模型不被误判`() {
        assertTrue(ModelCatalog.isLikelyChatModel("gpt-4o-mini"))
        assertTrue(ModelCatalog.isLikelyChatModel("deepseek-chat"))
        assertTrue(ModelCatalog.isLikelyChatModel("claude-3-5-sonnet-latest"))
        assertTrue(ModelCatalog.isLikelyChatModel("qwen2.5-coder:7b"))
        assertTrue(ModelCatalog.isLikelyChatModel("moonshot-v1-8k"))
    }

    @Test
    fun `非对话模型排在最后`() {
        val sorted = ModelCatalog.sortModels(
            models("text-embedding-3-small", "gpt-4o-mini", "whisper-1", "deepseek-chat"),
        )
        assertEquals(
            setOf("gpt-4o-mini", "deepseek-chat"),
            sorted.take(2).map { it.id }.toSet(),
            "对话模型应排在前面",
        )
        assertEquals(
            setOf("text-embedding-3-small", "whisper-1"),
            sorted.takeLast(2).map { it.id }.toSet(),
            "嵌入/语音模型应沉底",
        )
    }

    @Test
    fun `coder 系列排在最前`() {
        val sorted = ModelCatalog.sortModels(models("gpt-4o", "qwen2.5-coder:7b", "claude-3-5-haiku-latest"))
        assertEquals("qwen2.5-coder:7b", sorted.first().id)
    }

    @Test
    fun `新家族 gpt-5 与 gemini 也算偏好模型`() {
        val sorted = ModelCatalog.sortModels(models("some-random-model", "gemini-2.0-flash", "gpt-5-mini"))
        assertEquals("gpt-5-mini", sorted.first().id)
        assertEquals("some-random-model", sorted.last().id)
    }

    @Test
    fun `去掉重复模型且保留首次出现`() {
        val sorted = ModelCatalog.sortModels(
            listOf(ModelInfo("gpt-4o-mini", "first"), ModelInfo("gpt-4o-mini", "second")),
        )
        assertEquals(1, sorted.size)
        assertEquals("first", sorted.single().note)
    }

    @Test
    fun `同为普通模型时按名称排序`() {
        val sorted = ModelCatalog.sortModels(models("zeta-model", "alpha-model", "beta-model"))
        assertEquals(listOf("alpha-model", "beta-model", "zeta-model"), sorted.map { it.id })
    }

    @Test
    fun `空列表排序不炸`() {
        assertTrue(ModelCatalog.sortModels(emptyList()).isEmpty())
    }

    // ---------- 默认模型挑选 ----------

    @Test
    fun `候选里有建议模型时优先用建议`() {
        val picked = ModelCatalog.pickDefaultModel(
            models("gpt-4o", "gpt-4o-mini"),
            suggestions = listOf("gpt-4o-mini"),
        )
        assertEquals("gpt-4o-mini", picked)
    }

    @Test
    fun `建议模型不在列表里时退回第一个对话模型`() {
        val picked = ModelCatalog.pickDefaultModel(
            models("text-embedding-3-large", "gpt-4o", "gpt-4o-mini"),
            suggestions = listOf("gpt-5"),
        )
        assertEquals("gpt-4o", picked)
    }

    @Test
    fun `全是非对话模型时仍然返回第一个`() {
        val picked = ModelCatalog.pickDefaultModel(models("whisper-1", "tts-1"))
        assertEquals("whisper-1", picked)
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(ModelCatalog.pickDefaultModel(emptyList()))
    }
}
