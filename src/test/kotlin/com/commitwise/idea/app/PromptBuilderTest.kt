package com.commitwise.idea.app

import com.commitwise.idea.domain.diff.DiffFile
import com.commitwise.idea.domain.diff.PreparedDiff
import com.commitwise.idea.domain.diff.SkipReason
import com.commitwise.idea.domain.diff.SkippedFile
import com.commitwise.idea.domain.prompt.BuiltinTemplates
import com.commitwise.idea.domain.prompt.PromptTemplate
import com.commitwise.idea.domain.prompt.TemplateType
import com.commitwise.idea.infra.ai.ChatRole
import com.commitwise.idea.infra.config.CommitSettings
import com.commitwise.idea.infra.config.ConfigValidator
import com.commitwise.idea.infra.config.RawConfig
import com.commitwise.idea.infra.config.ResolvedConfig
import com.commitwise.idea.infra.git.StagedSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PromptBuilderTest {

    private fun config(
        includeBody: Boolean = true,
        maxSubjectLength: Int = 72,
        language: String = "zh-CN",
    ): ResolvedConfig {
        val raw = RawConfig(
            commit = CommitSettings(
                format = "conventional",
                language = language,
                maxSubjectLength = maxSubjectLength,
                includeBody = includeBody,
            ),
        )
        return assertNotNull(ConfigValidator.validate(raw, "zh_CN", "sk-test").config)
    }

    private fun snapshot(
        root: String = "C:/work/demo",
        branch: String = "feat/x",
        files: List<DiffFile> = emptyList(),
        recent: List<String> = emptyList(),
    ) = StagedSnapshot(
        root = root,
        branch = branch,
        isUnborn = false,
        files = files,
        nameStatus = "",
        recentCommits = recent,
        rawPatchChars = 100,
    )

    private fun prepared(
        patch: String = "PATCH-BODY",
        fileList: String = "M\tsrc/a.ts\t+1\t-0",
        truncated: List<String> = emptyList(),
        skipped: List<SkippedFile> = emptyList(),
    ) = PreparedDiff(
        patch = patch,
        included = emptyList(),
        skipped = skipped,
        truncated = truncated,
        fileList = fileList,
        chars = patch.length,
        redactionHits = 0,
        redactionRules = emptyList(),
    )

    // ---------- 仓库名 ----------

    @Test
    fun `仓库名支持两种分隔符`() {
        assertEquals("demo", repoNameOf("C:/Users/me/demo"))
        assertEquals("demo", repoNameOf("C:\\Users\\me\\demo"))
        assertEquals("demo", repoNameOf("/home/me/demo/"))
    }

    @Test
    fun `根目录本身就是名字时原样返回`() {
        assertEquals("demo", repoNameOf("demo"))
    }

    // ---------- 变量组装 ----------

    @Test
    fun `核心变量都按预期填充`() {
        val vars = PromptBuilder.buildVariables(
            snapshot(branch = "main", recent = listOf("feat: a", "fix: b")),
            prepared(),
            config(maxSubjectLength = 60),
        )
        assertEquals("PATCH-BODY", vars["diff"])
        assertEquals("M\tsrc/a.ts\t+1\t-0", vars["stagedFiles"])
        assertEquals("main", vars["branch"])
        assertEquals("demo", vars["repoName"])
        assertEquals("60", vars["maxSubjectLength"])
        assertEquals("conventional", vars["format"])
        assertEquals("简体中文", vars["language"])
        assertEquals("- feat: a\n- fix: b", vars["recentCommits"])
        assertEquals("无", vars["truncated"])
    }

    @Test
    fun `fileStats 汇总文件数与增删行`() {
        val files = listOf(
            DiffFile(path = "a.ts", additions = 3, deletions = 1),
            DiffFile(path = "b.ts", additions = 5, deletions = 0),
        )
        val vars = PromptBuilder.buildVariables(snapshot(files = files), prepared(), config())
        assertEquals("2 个文件，+8 / -1 行", vars["fileStats"])
    }

    @Test
    fun `没有历史提交时给出占位文案`() {
        val vars = PromptBuilder.buildVariables(snapshot(recent = emptyList()), prepared(), config())
        assertEquals("（暂无历史提交）", vars["recentCommits"])
    }

    @Test
    fun `文件清单为空时给出占位文案`() {
        val vars = PromptBuilder.buildVariables(snapshot(), prepared(fileList = ""), config())
        assertEquals("（空）", vars["stagedFiles"])
    }

    @Test
    fun `includeBody 影响正文指令`() {
        val withBody = PromptBuilder.buildVariables(snapshot(), prepared(), config(includeBody = true))
        assertTrue(withBody["bodyInstruction"]!!.contains("第二行留空"))

        val noBody = PromptBuilder.buildVariables(snapshot(), prepared(), config(includeBody = false))
        assertTrue(noBody["bodyInstruction"]!!.contains("只输出单行"))
    }

    @Test
    fun `英文配置输出 English`() {
        val vars = PromptBuilder.buildVariables(snapshot(), prepared(), config(language = "en"))
        assertEquals("English", vars["language"])
    }

    @Test
    fun `截断与排除会写进 truncated 说明`() {
        val vars = PromptBuilder.buildVariables(
            snapshot(),
            prepared(
                truncated = listOf("src/big.ts"),
                skipped = listOf(
                    SkippedFile("docs/guide.md", SkipReason.EXCLUDED, "docs/**"),
                    SkippedFile("logo.png", SkipReason.BINARY),
                    SkippedFile("empty.ts", SkipReason.EXCLUDED, "no hunk"),
                ),
            ),
            config(),
        )
        val truncated = vars["truncated"]!!
        assertTrue(truncated.contains("src/big.ts"), "应提到被截断的文件：$truncated")
        assertTrue(truncated.contains("docs/guide.md"), "应提到按规则排除的文件：$truncated")
        assertFalse(truncated.contains("logo.png"), "二进制文件已在文件清单里标注，不必重复：$truncated")
        assertFalse(truncated.contains("empty.ts"), "无 hunk 的文件不算「按规则排除」：$truncated")
    }

    @Test
    fun `超出预算被丢弃的文件也算排除`() {
        val vars = PromptBuilder.buildVariables(
            snapshot(),
            prepared(skipped = listOf(SkippedFile("src/huge.ts", SkipReason.EXCLUDED, "超出上下文预算"))),
            config(),
        )
        assertTrue(vars["truncated"]!!.contains("src/huge.ts"))
    }

    // ---------- 消息组装 ----------

    @Test
    fun `内置模板渲染后不再残留占位符`() {
        val tpl = BuiltinTemplates.ALL.first { it.id == "builtin-conventional-zh" }
        val built = PromptBuilder.buildMessages(tpl, snapshot(), prepared(patch = "DIFF-CONTENT"), config())

        assertTrue(built.missingVariables.isEmpty(), "缺失变量：${built.missingVariables}")
        val user = built.messages.last()
        assertEquals(ChatRole.USER, user.role)
        assertTrue(user.content.contains("DIFF-CONTENT"), "用户消息应包含补丁内容")
        assertFalse(user.content.contains("{{diff}}"), "占位符应已替换")
    }

    @Test
    fun `有 systemPrompt 时产出两条消息`() {
        val tpl = BuiltinTemplates.ALL.first { it.id == "builtin-conventional-zh" }
        val built = PromptBuilder.buildMessages(tpl, snapshot(), prepared(), config())
        assertEquals(2, built.messages.size)
        assertEquals(ChatRole.SYSTEM, built.messages[0].role)
        assertEquals(ChatRole.USER, built.messages[1].role)
    }

    @Test
    fun `没有 systemPrompt 时只产出用户消息`() {
        val tpl = PromptTemplate(id = "mine", type = TemplateType.COMMIT, name = "n", userPrompt = "看这里：{{diff}}")
        val built = PromptBuilder.buildMessages(tpl, snapshot(), prepared(patch = "X"), config())
        assertEquals(1, built.messages.size)
        assertTrue(built.messages.single().content.contains("看这里：X"))
    }

    @Test
    fun `自定义模板里的未知变量会被报告`() {
        val tpl = PromptTemplate(
            id = "mine",
            type = TemplateType.COMMIT,
            name = "n",
            userPrompt = "{{diff}} {{whatever}}",
        )
        val built = PromptBuilder.buildMessages(tpl, snapshot(), prepared(), config())
        assertEquals(listOf("whatever"), built.missingVariables)
    }

    // ---------- 审查纠错 ----------

    @Test
    fun `审查纠错消息包含结构与原文`() {
        val msg = PromptBuilder.buildReviewRepairMessage("这不是 JSON")
        assertTrue(msg.contains("只输出一个 JSON 对象"))
        assertTrue(msg.contains("这不是 JSON"))
        assertTrue(msg.contains("\"issues\""))
        assertTrue(msg.contains("severity"))
    }

    @Test
    fun `审查纠错消息会截断过长的原输出`() {
        val long = "x".repeat(1000)
        val msg = PromptBuilder.buildReviewRepairMessage(long)
        assertFalse(msg.contains("x".repeat(401)), "应只保留前 400 字符")
        assertTrue(msg.contains("x".repeat(400)))
    }
}
