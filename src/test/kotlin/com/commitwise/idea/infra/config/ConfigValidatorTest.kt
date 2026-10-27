package com.commitwise.idea.infra.config

import com.commitwise.idea.domain.commit.CommitFormat
import com.commitwise.idea.domain.review.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigValidatorTest {

    private fun validate(
        raw: RawConfig = RawConfig(),
        ideLanguage: String = "zh_CN",
        apiKey: String? = "sk-test",
    ) = ConfigValidator.validate(raw, ideLanguage, apiKey)

    // ---------- 正常路径 ----------

    @Test
    fun `默认配置可以直接通过`() {
        val r = validate()
        assertTrue(r.errors.isEmpty(), "不应有错误：${r.errors}")
        assertTrue(r.warnings.isEmpty(), "不应有告警：${r.warnings}")
        val c = assertNotNull(r.config, "应产出配置")
        assertEquals(ProviderId.OPENAI_COMPATIBLE, c.provider)
        assertEquals("gpt-4o-mini", c.model)
        assertEquals("zh-CN", c.language, "语言应根据 IDE 语言回填")
        assertEquals("zh-CN", c.commit.language)
    }

    @Test
    fun `接口地址末尾的斜杠被去掉`() {
        val c = assertNotNull(validate(RawConfig(baseUrl = "https://api.openai.com/v1///")).config)
        assertEquals("https://api.openai.com/v1", c.baseUrl)
    }

    @Test
    fun `scheme 大小写不敏感`() {
        val r = validate(RawConfig(baseUrl = "HTTPS://api.openai.com/v1"))
        assertTrue(r.errors.isEmpty(), "大写协议头不应被判为非法：${r.errors}")
    }

    // ---------- 接口地址 ----------

    @Test
    fun `地址为空时报错`() {
        val r = validate(RawConfig(baseUrl = ""))
        assertTrue(r.errors.any { it.contains("不能为空") })
        assertNull(r.config)
    }

    @Test
    fun `Azure 地址为空时给出针对性提示`() {
        val r = validate(RawConfig(provider = "azure-openai", baseUrl = "", model = "dep"))
        assertTrue(r.errors.any { it.contains("Azure OpenAI 需要填写接口地址") }, "实际：${r.errors}")
    }

    @Test
    fun `非 http 协议被拒绝`() {
        val r = validate(RawConfig(baseUrl = "ftp://example.com/v1"))
        assertTrue(r.errors.any { it.contains("http://") })
    }

    @Test
    fun `缺少主机名的地址被拦下`() {
        assertTrue(validate(RawConfig(baseUrl = "https://")).errors.isNotEmpty())
        assertTrue(validate(RawConfig(baseUrl = "https:///v1")).errors.isNotEmpty())
    }

    @Test
    fun `明文 http 只对非本地地址告警`() {
        val remote = validate(RawConfig(baseUrl = "http://api.example.com/v1"))
        assertTrue(remote.warnings.any { it.contains("明文 http") }, "实际：${remote.warnings}")

        val local = validate(RawConfig(provider = "ollama", baseUrl = "http://localhost:11434"), apiKey = null)
        assertTrue(local.warnings.none { it.contains("明文 http") }, "实际：${local.warnings}")
    }

    @Test
    fun `ipv6 回环地址视为本地`() {
        val r = validate(RawConfig(provider = "ollama", baseUrl = "http://[::1]:11434"), apiKey = null)
        assertTrue(r.warnings.none { it.contains("明文 http") }, "实际：${r.warnings}")
    }

    @Test
    fun `地址与服务商不匹配时给出告警`() {
        val r = validate(RawConfig(provider = "ollama", baseUrl = "https://api.openai.com/v1"), apiKey = null)
        assertTrue(r.warnings.any { it.contains("Ollama") }, "实际：${r.warnings}")
    }

    // ---------- 模型回填 ----------

    @Test
    fun `模型留空时按实际接口地址回填`() {
        val r = validate(RawConfig(baseUrl = "https://api.deepseek.com/v1", model = ""))
        val c = assertNotNull(r.config)
        assertEquals("deepseek-chat", c.model)
        assertEquals("deepseek-chat", r.modelDefaulted)
        assertTrue(r.warnings.any { it.contains("DeepSeek") }, "实际：${r.warnings}")
    }

    @Test
    fun `地址漏写 v1 也能识别出正确的默认模型`() {
        val r = validate(RawConfig(baseUrl = "https://api.deepseek.com", model = ""))
        assertEquals("deepseek-chat", assertNotNull(r.config).model)
    }

    @Test
    fun `Azure 地址为空无法回填模型时要求手填`() {
        val r = validate(
            RawConfig(
                provider = "azure-openai",
                baseUrl = "https://myres.openai.azure.com/openai",
                model = "",
            ),
        )
        assertTrue(r.errors.any { it.contains("请填写模型名称") }, "实际：${r.errors}")
    }

    // ---------- 请求参数边界 ----------

    @Test
    fun `temperature 超出 0 到 2 报错`() {
        assertTrue(validate(RawConfig(temperature = -0.1)).errors.any { it.contains("temperature") })
        assertTrue(validate(RawConfig(temperature = 2.1)).errors.any { it.contains("temperature") })
        assertTrue(validate(RawConfig(temperature = 2.0)).errors.isEmpty())
    }

    @Test
    fun `maxTokens 过小报错`() {
        assertTrue(validate(RawConfig(maxTokens = 63)).errors.any { it.contains("maxTokens") })
        assertTrue(validate(RawConfig(maxTokens = 64)).errors.isEmpty())
    }

    @Test
    fun `请求超时过小报错`() {
        assertTrue(validate(RawConfig(requestTimeoutMs = 4999)).errors.any { it.contains("超时") })
        assertTrue(validate(RawConfig(requestTimeoutMs = 5000)).errors.isEmpty())
    }

    // ---------- 密钥 ----------

    @Test
    fun `没有密钥只告警不阻塞`() {
        val r = validate(apiKey = null)
        assertTrue(r.errors.isEmpty())
        assertTrue(r.warnings.any { it.contains("尚未设置 API 密钥") })
        assertNotNull(r.config, "缺密钥仍应产出配置，便于先测连接")
    }

    @Test
    fun `Ollama 不需要密钥`() {
        val r = validate(
            RawConfig(provider = "ollama", baseUrl = "http://localhost:11434", model = "llama3.1:8b"),
            apiKey = null,
        )
        assertTrue(r.warnings.none { it.contains("密钥") }, "实际：${r.warnings}")
    }

    @Test
    fun `配置了环境变量时提示会去读环境变量`() {
        val r = validate(RawConfig(apiKeyEnv = "DEEPSEEK_API_KEY"), apiKey = null)
        assertTrue(r.warnings.any { it.contains("DEEPSEEK_API_KEY") }, "实际：${r.warnings}")
    }

    // ---------- 枚举校验 ----------

    @Test
    fun `未知服务商报错且不产出配置`() {
        val r = validate(RawConfig(provider = "some-llm"))
        assertTrue(r.errors.any { it.contains("未知的服务商类型") })
        assertNull(r.config)
    }

    @Test
    fun `未知提交格式报错`() {
        val r = validate(RawConfig(commit = CommitSettings(format = "svn")))
        assertTrue(r.errors.any { it.contains("未知的提交信息格式") })
    }

    @Test
    fun `未知审查级别报错`() {
        val r = validate(RawConfig(review = ReviewSettings(minSeverity = "critical")))
        assertTrue(r.errors.any { it.contains("high / medium / low") })
    }

    @Test
    fun `合法的枚举被正确解析`() {
        val c = assertNotNull(
            validate(
                RawConfig(
                    provider = "anthropic",
                    baseUrl = "https://api.anthropic.com/v1",
                    model = "claude-3-5-haiku-latest",
                    commit = CommitSettings(format = "gitmoji", maxSubjectLength = 50, includeBody = false),
                    review = ReviewSettings(minSeverity = "high", maxIssues = 5),
                ),
            ).config,
        )
        assertEquals(ProviderId.ANTHROPIC, c.provider)
        assertEquals(CommitFormat.GITMOJI, c.commit.format)
        assertEquals(50, c.commit.maxSubjectLength)
        assertFalse(c.commit.includeBody)
        assertEquals(Severity.HIGH, c.review.minSeverity)
        assertEquals(5, c.review.maxIssues)
    }

    // ---------- 语言 ----------

    @Test
    fun `显式语言优先于 IDE 语言`() {
        assertEquals("zh-CN", ConfigValidator.resolveLanguage("zh-CN", "en_US"))
        assertEquals("en", ConfigValidator.resolveLanguage("en", "zh_CN"))
    }

    @Test
    fun `auto 跟随 IDE 语言`() {
        assertEquals("zh-CN", ConfigValidator.resolveLanguage("auto", "zh_CN"))
        assertEquals("en", ConfigValidator.resolveLanguage("auto", "en_US"))
        assertEquals("en", ConfigValidator.resolveLanguage("", "ja_JP"))
    }

    @Test
    fun `语言展示名`() {
        assertEquals("简体中文", ConfigValidator.languageName("zh-CN"))
        assertEquals("English", ConfigValidator.languageName("en"))
    }
}

class ProviderPresetsTest {

    @Test
    fun `四个服务商都有预设`() {
        for (p in ProviderId.entries) {
            val preset = ProviderPresets.of(p.id)
            assertEquals(p, preset.id)
            assertTrue(preset.label.isNotEmpty())
            assertEquals(p, ProviderId.of(p.id))
        }
    }

    @Test
    fun `未知服务商回落到 openai 兼容`() {
        assertEquals(ProviderId.OPENAI_COMPATIBLE, ProviderPresets.of("nope").id)
        assertEquals(ProviderId.OPENAI_COMPATIBLE, ProviderPresets.of(null).id)
    }

    @Test
    fun `Azure 没有默认模型也不支持模型列表`() {
        val azure = ProviderPresets.of("azure-openai")
        assertEquals("", azure.defaultModel)
        assertFalse(azure.supportsModelList)
        assertTrue(azure.baseUrl.isEmpty(), "Azure 必须由用户填写地址")
    }

    @Test
    fun `Ollama 不需要密钥`() {
        assertFalse(ProviderPresets.of("ollama").requiresApiKey)
        assertTrue(ProviderPresets.of("openai-compatible").requiresApiKey)
    }

    @Test
    fun `开箱入口数量与内容完整`() {
        assertEquals(8, EndpointPresets.ALL.size)
        for (e in EndpointPresets.ALL) {
            assertTrue(e.label.isNotEmpty())
            assertTrue(e.description.isNotEmpty())
            assertEquals(e.provider, ProviderPresets.of(e.provider.id).id)
        }
    }

    @Test
    fun `精确地址能匹配回入口`() {
        for (e in EndpointPresets.ALL.filter { it.baseUrl.isNotEmpty() }) {
            val m = EndpointPresets.match(e.provider.id, e.baseUrl)
            assertEquals(e.key, m?.key, "入口 ${e.key} 未能匹配回自身")
        }
    }

    @Test
    fun `漏写 v1 或带尾斜杠也能匹配`() {
        assertEquals("deepseek", EndpointPresets.match("openai-compatible", "https://api.deepseek.com")?.key)
        assertEquals("deepseek", EndpointPresets.match("openai-compatible", "  https://api.deepseek.com/v1/  ")?.key)
        assertEquals("qwen", EndpointPresets.match("openai-compatible", "https://dashscope.aliyuncs.com")?.key)
    }

    @Test
    fun `服务商不匹配或地址为空时返回 null`() {
        assertNull(EndpointPresets.match("anthropic", "https://api.deepseek.com/v1"))
        assertNull(EndpointPresets.match(null, "https://api.deepseek.com/v1"))
        assertNull(EndpointPresets.match("openai-compatible", ""))
        assertNull(EndpointPresets.match("openai-compatible", "   "))
    }

    // ---------- 配置冲突提示 ----------

    @Test
    fun `Ollama 配了远程地址时提示`() {
        val msg = detectConfigMismatch("ollama", "https://api.openai.com/v1")
        assertTrue(msg != null && msg.contains("localhost"), "实际：$msg")
    }

    @Test
    fun `Azure 地址配了 OpenAI 兼容时提示切换服务商`() {
        val msg = detectConfigMismatch("openai-compatible", "https://x.openai.azure.com/openai")
        assertTrue(msg != null && msg.contains("Azure"), "实际：$msg")
    }

    @Test
    fun `Anthropic 地址配了 OpenAI 兼容时提示切换服务商`() {
        val msg = detectConfigMismatch("openai-compatible", "https://api.anthropic.com/v1")
        assertTrue(msg != null && msg.contains("Anthropic"), "实际：$msg")
    }

    @Test
    fun `Anthropic 服务商配了别家地址时提示`() {
        val msg = detectConfigMismatch("anthropic", "https://api.deepseek.com/v1")
        assertTrue(msg != null && msg.contains("Anthropic"), "实际：$msg")
    }

    @Test
    fun `匹配正常时没有提示`() {
        assertNull(detectConfigMismatch("openai-compatible", "https://api.openai.com/v1"))
        assertNull(detectConfigMismatch("ollama", "http://localhost:11434"))
        assertNull(detectConfigMismatch("azure-openai", "https://x.openai.azure.com/openai"))
        assertNull(detectConfigMismatch("anthropic", "https://api.anthropic.com/v1"))
        assertNull(detectConfigMismatch("openai-compatible", ""))
    }
}
