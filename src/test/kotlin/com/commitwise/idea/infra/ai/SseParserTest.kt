package com.commitwise.idea.infra.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseParserTest {

    @Test
    fun `单个完整事件`() {
        val p = SseParser()
        assertEquals(listOf(SseEvent(null, "hello")), p.push("data: hello\n\n"))
    }

    @Test
    fun `一个 chunk 内多个事件`() {
        val p = SseParser()
        val events = p.push("data: a\n\ndata: b\n\n")
        assertEquals(listOf("a", "b"), events.map { it.data })
    }

    @Test
    fun `事件被拆成多个 chunk 时仍能拼出`() {
        val p = SseParser()
        val collected = mutableListOf<SseEvent>()
        collected += p.push("da")
        collected += p.push("ta: hi")
        collected += p.push("\n\n")
        assertEquals(listOf(SseEvent(null, "hi")), collected)
    }

    @Test
    fun `分隔符被拆到两个 chunk 时不会提前切分`() {
        val p = SseParser()
        assertTrue(p.push("data: a\n").isEmpty())
        assertEquals(listOf(SseEvent(null, "a")), p.push("\n"))
    }

    @Test
    fun `CRLF 行结束符被正确处理`() {
        val p = SseParser()
        assertEquals(listOf(SseEvent(null, "a")), p.push("data: a\r\n\r\n"))
    }

    @Test
    fun `CRLF 分隔符跨 chunk 也能识别`() {
        val p = SseParser()
        assertTrue(p.push("data: a\r").isEmpty())
        assertTrue(p.push("\n").isEmpty())
        assertEquals(listOf(SseEvent(null, "a")), p.push("\r\n"))
    }

    @Test
    fun `多行 data 用换行拼接`() {
        val p = SseParser()
        assertEquals(listOf(SseEvent(null, "a\nb")), p.push("data: a\ndata: b\n\n"))
    }

    @Test
    fun `event 字段被保留`() {
        val p = SseParser()
        assertEquals(listOf(SseEvent("done", "{}")), p.push("event: done\ndata: {}\n\n"))
    }

    @Test
    fun `注释帧不产出事件`() {
        val p = SseParser()
        assertTrue(p.push(": ping\n\n").isEmpty())
    }

    @Test
    fun `冒号后没有空格时值原样保留`() {
        val p = SseParser()
        assertEquals(listOf(SseEvent(null, "{\"a\":1}")), p.push("data:{\"a\":1}\n\n"))
    }

    @Test
    fun `flush 吐出未以空行收尾的残留帧`() {
        val p = SseParser()
        assertTrue(p.push("data: tail").isEmpty())
        assertEquals(listOf(SseEvent(null, "tail")), p.flush())
    }

    @Test
    fun `flush 后缓冲区清空`() {
        val p = SseParser()
        p.push("data: x\n\n")
        assertTrue(p.flush().isEmpty())
    }

    @Test
    fun `逐字符喂入的极端分片`() {
        val p = SseParser()
        val text = "event: message\ndata: {\"delta\":\"你好\"}\n\n"
        val collected = mutableListOf<SseEvent>()
        for (ch in text) collected += p.push(ch.toString())
        assertEquals(1, collected.size)
        assertEquals("message", collected[0].event)
        assertEquals("{\"delta\":\"你好\"}", collected[0].data)
    }
}

class NdjsonParserTest {

    @Test
    fun `按行切分并跨 chunk 拼接`() {
        val p = NdjsonParser()
        assertTrue(p.push("{\"a\":1}\n{\"b\"").size == 1)
        assertEquals(listOf("{\"b\":2}"), p.push(":2}\n"))
    }

    @Test
    fun `一个 chunk 内多行`() {
        val p = NdjsonParser()
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), p.push("{\"a\":1}\n{\"b\":2}\n"))
    }

    @Test
    fun `空行被忽略`() {
        val p = NdjsonParser()
        assertEquals(listOf("{\"a\":1}"), p.push("\n\n{\"a\":1}\n\n"))
    }

    @Test
    fun `flush 吐出没有换行结尾的残行`() {
        val p = NdjsonParser()
        assertTrue(p.push("{\"done\":true}").isEmpty())
        assertEquals(listOf("{\"done\":true}"), p.flush())
    }
}
