package com.commitwise.idea.ui

import com.commitwise.idea.infra.config.ProviderPresets
import com.commitwise.idea.infra.config.providerLabel
import com.commitwise.idea.services.CommitWiseSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态栏数据源。
 *
 * 状态栏是用户唯一随时可见的位置，必须让它同时回答两个问题：
 * 「我现在用的是哪个模型」和「刚才的审查结果去哪看了」。
 */
object StatusHolder {

    enum class Phase { IDLE, COLLECTING, REQUESTING }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    var actionText: String = ""
        private set

    @Volatile
    var modelLabel: String = ""
        private set

    @Volatile
    var providerText: String = ""
        private set

    @Volatile
    var reviewTotal: Int? = null
        private set

    @Volatile
    var lastResult: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun fire() = listeners.toList().forEach { it() }

    fun refreshFromConfig() {
        val state = CommitWiseSettings.getInstance().state
        val preset = ProviderPresets.of(state.provider)
        modelLabel = state.model.trim().ifEmpty { preset.defaultModel }
        providerText = providerLabel(state.provider)
        fire()
    }

    fun startCollecting() {
        phase = Phase.COLLECTING
        actionText = "读取暂存区…"
        fire()
    }

    fun startRequesting(text: String) {
        phase = Phase.REQUESTING
        actionText = text
        fire()
    }

    fun succeeded(text: String) {
        phase = Phase.IDLE
        actionText = ""
        lastResult = text
        fire()
    }

    fun failed() {
        phase = Phase.IDLE
        actionText = ""
        fire()
    }

    fun idle() {
        phase = Phase.IDLE
        actionText = ""
        fire()
    }

    fun setReviewTotal(total: Int?) {
        reviewTotal = total
        fire()
    }
}
