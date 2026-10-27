package com.commitwise.idea.domain.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobTest {

    private fun matches(glob: String, path: String): Boolean =
        Glob.globToRegExp(glob).containsMatchIn(path)

    @Test
    fun `不含斜杠的模式匹配任意层级同名文件`() {
        assertTrue(matches("*.log", "app.log"))
        assertTrue(matches("*.log", "logs/app.log"))
        assertTrue(matches("*.log", "a/b/c/app.log"))
        assertFalse(matches("*.log", "app.log.txt"))
    }

    @Test
    fun `双星号跨目录匹配`() {
        assertTrue(matches("src/**/*.ts", "src/a.ts"))
        assertTrue(matches("src/**/*.ts", "src/x/y/a.ts"))
        assertFalse(matches("src/**/*.ts", "lib/a.ts"))
        assertTrue(matches("**/*.md", "README.md"))
        assertTrue(matches("**/*.md", "docs/guide/a.md"))
        assertTrue(matches("dist/**", "dist/a/b.js"))
        assertFalse(matches("dist/**", "src/dist/a.js"))
    }

    @Test
    fun `问号匹配单个非分隔符字符`() {
        assertTrue(matches("f?.txt", "f1.txt"))
        assertFalse(matches("f?.txt", "f12.txt"))
        assertFalse(matches("f?.txt", "f/.txt"))
    }

    @Test
    fun `点号按字面量处理`() {
        val re = Glob.globToRegExp("*.min.js")
        assertTrue(re.containsMatchIn("a.min.js"))
        assertFalse(re.containsMatchIn("aXminYjs"))
    }

    @Test
    fun `matchesAny 返回首个命中的规则`() {
        val globs = listOf("docs/**", "*.md", "**/generated/**")
        assertEquals("docs/**", Glob.matchesAny("docs/a.md", globs))
        assertEquals("*.md", Glob.matchesAny("README.md", globs))
        assertEquals("**/generated/**", Glob.matchesAny("src/generated/x.ts", globs))
        assertNull(Glob.matchesAny("src/main.ts", globs))
    }

    @Test
    fun `反斜杠路径先归一化再匹配`() {
        assertEquals("**/temp/**", Glob.matchesAny("src\\temp\\a.ts", listOf("**/temp/**")))
    }

    @Test
    fun `空白规则被忽略`() {
        assertNull(Glob.matchesAny("a.ts", listOf("", "   ")))
    }
}
