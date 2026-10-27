package com.commitwise.idea.infra.log

import com.intellij.openapi.diagnostic.Logger
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 插件日志：写 IDE 日志文件的同时，在内存保留一份最近记录，
 * 供「查看日志」动作直接展示（用户很难去翻 idea.log）。
 */
object CommitWiseLogger {

    private val log: Logger = Logger.getInstance("CommitWise")
    private const val CAPACITY = 500
    private val buffer = ArrayDeque<String>()
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun record(level: String, message: String) {
        synchronized(buffer) {
            buffer.addLast("${LocalTime.now().format(stamp)} [$level] $message")
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
    }

    fun info(message: String) {
        log.info(message)
        record("INFO", message)
    }

    fun warn(message: String) {
        log.warn(message)
        record("WARN", message)
    }

    fun error(message: String, t: Throwable? = null) {
        if (t != null) log.warn(message, t) else log.error(message)
        record("ERROR", if (t == null) message else "$message（${t.message}）")
    }

    fun debug(message: String) {
        log.debug(message)
        record("DEBUG", message)
    }

    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }

    fun clear() = synchronized(buffer) { buffer.clear() }
}
