package com.commitwise.idea.infra.ai

data class SseEvent(val event: String?, val data: String)

/**
 * 增量 SSE 解析器：跨 chunk 边界安全。
 *
 * 不假设网络分片与事件边界对齐——同一事件经常被拆成多个 chunk，
 * 多个事件也可能挤在一个 chunk 里。
 */
class SseParser {
    private val buffer = StringBuilder()

    fun push(chunk: String): List<SseEvent> {
        buffer.append(chunk)
        val events = mutableListOf<SseEvent>()
        while (true) {
            val sep = findSeparator()
            if (sep == null) break
            val raw = buffer.substring(0, sep.first)
            buffer.delete(0, sep.second)
            parseFrame(raw)?.let { events.add(it) }
        }
        return events
    }

    /** 流结束时调用：把没有以空行收尾的残留帧也吐出来 */
    fun flush(): List<SseEvent> {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        if (rest.isEmpty()) return emptyList()
        val ev = parseFrame(rest)
        return if (ev != null) listOf(ev) else emptyList()
    }

    /** 返回 (分隔符起始下标, 分隔符结束下标) */
    private fun findSeparator(): Pair<Int, Int>? {
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '\n') {
                // \n\n
                if (i + 1 < buffer.length && buffer[i + 1] == '\n') return i to i + 2
                // \n\r\n
                if (i + 2 < buffer.length && buffer[i + 1] == '\r' && buffer[i + 2] == '\n') return i to i + 3
            } else if (c == '\r') {
                // \r\n\r\n
                if (i + 3 < buffer.length && buffer[i + 1] == '\n' && buffer[i + 2] == '\r' && buffer[i + 3] == '\n') {
                    return i to i + 4
                }
            }
            i++
        }
        return null
    }

    private fun parseFrame(raw: String): SseEvent? {
        if (raw.isBlank()) return null
        val dataLines = mutableListOf<String>()
        var event: String? = null

        for (line in raw.split("\n")) {
            val l = line.removeSuffix("\r")
            if (l.isEmpty() || l.startsWith(":")) continue
            val idx = l.indexOf(':')
            val field = if (idx == -1) l else l.substring(0, idx)
            var value = if (idx == -1) "" else l.substring(idx + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "data" -> dataLines.add(value)
                "event" -> event = value
            }
        }

        if (dataLines.isEmpty()) return null
        return SseEvent(event, dataLines.joinToString("\n"))
    }
}

/** NDJSON（Ollama /api/chat 使用）逐行解析 */
class NdjsonParser {
    private val buffer = StringBuilder()

    fun push(chunk: String): List<String> {
        buffer.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val idx = buffer.indexOf("\n")
            if (idx == -1) break
            val line = buffer.substring(0, idx).trim()
            buffer.delete(0, idx + 1)
            if (line.isNotEmpty()) out.add(line)
        }
        return out
    }

    fun flush(): List<String> {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return if (rest.isEmpty()) emptyList() else listOf(rest)
    }
}
