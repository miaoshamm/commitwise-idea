package com.commitwise.idea.app

import com.commitwise.idea.infra.ai.CancelToken

/** 全局只允许一个进行中的请求，重复触发会取消上一次 */
class CancellationController {

    @Volatile
    private var current: CancelToken? = null

    @Synchronized
    fun begin(): CancelToken {
        cancel()
        val token = CancelToken()
        current = token
        return token
    }

    @Synchronized
    fun cancel(): Boolean {
        val token = current ?: return false
        if (token.isCancelled) return false
        token.cancel()
        return true
    }

    @Synchronized
    fun end(token: CancelToken) {
        if (current === token) {
            current = null
        }
    }

    val active: Boolean get() = current?.isCancelled == false
}
