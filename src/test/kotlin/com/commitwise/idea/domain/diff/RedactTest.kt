package com.commitwise.idea.domain.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactTest {

    @Test
    fun `私钥整块替换`() {
        val text = """
            |-----BEGIN RSA PRIVATE KEY-----
            |MIIEowIBAAKCAQEA
            |-----END RSA PRIVATE KEY-----
        """.trimMargin()
        val r = Redact.redactSecrets(text)
        assertFalse(r.text.contains("MIIEowIBAAKCAQEA"))
        assertTrue(r.text.contains("***REDACTED PRIVATE KEY***"))
        assertTrue(r.rules.contains("private-key-block"))
        assertEquals(1, r.hits)
    }

    @Test
    fun `OpenAI 风格密钥被打码`() {
        val r = Redact.redactSecrets("const k = \"sk-abcdefghijklmnop1234\"")
        assertFalse(r.text.contains("abcdefghijklmnop1234"))
        assertTrue(r.text.contains("***REDACTED***"))
        assertEquals(1, r.hits)
    }

    @Test
    fun `AWS Access Key 被打码`() {
        val r = Redact.redactSecrets("aws_key=AKIAIOSFODNN7EXAMPLE")
        assertFalse(r.text.contains("AKIAIOSFODNN7EXAMPLE"))
        assertTrue(r.rules.contains("aws-access-key"))
    }

    @Test
    fun `GitHub token 被打码`() {
        val r = Redact.redactSecrets("token: ghp_abcdefghijklmnopqrst")
        assertFalse(r.text.contains("ghp_abcdefghijklmnopqrst"))
        assertTrue(r.rules.contains("github-token"))
    }

    @Test
    fun `JWT 被打码`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijk"
        val r = Redact.redactSecrets("Authorization: $jwt")
        assertFalse(r.text.contains(jwt))
        assertTrue(r.rules.contains("jwt"))
    }

    @Test
    fun `Bearer 保留前缀只打码值`() {
        val r = Redact.redactSecrets("Authorization: Bearer abcdefghijklmnop")
        assertTrue(r.text.contains("Bearer ***REDACTED***"))
        assertFalse(r.text.contains("abcdefghijklmnop"))
        assertTrue(r.rules.contains("bearer"))
    }

    @Test
    fun `赋值式保留键名与引号，只打码值`() {
        val r = Redact.redactSecrets("""api_key = "supersecret123"""")
        assertTrue(r.text.contains("api_key"))
        assertTrue(r.text.contains("\"***REDACTED***\""))
        assertFalse(r.text.contains("supersecret123"))
        assertTrue(r.rules.contains("assignment"))
    }

    @Test
    fun `过短的普通变量不误伤`() {
        val text = "const password = \"short\"\nlet count = 1"
        val r = Redact.redactSecrets(text)
        assertEquals(text, r.text)
        assertEquals(0, r.hits)
        assertTrue(r.rules.isEmpty())
    }

    @Test
    fun `普通代码不产生命中`() {
        val text = "fun main() {\n  println(\"hello world\")\n}"
        val r = Redact.redactSecrets(text)
        assertEquals(0, r.hits)
        assertEquals(text, r.text)
    }

    @Test
    fun `多处命中按规则去重但仍累计次数`() {
        val r = Redact.redactSecrets(
            "sk-abcdefghijklmnop1111 and sk-abcdefghijklmnop2222",
        )
        assertEquals(2, r.hits)
        assertEquals(1, r.rules.size)
    }
}
