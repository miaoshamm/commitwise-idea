package com.commitwise.idea.infra.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** 超过这个时间认为是「旧数据」，下次选择模型时静默刷新一次 */
const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

private fun norm(url: String): String = url.trim().replace(Regex("/+$"), "").lowercase()

/**
 * 模型列表缓存。
 *
 * 存在插件配置里而不是项目文件里：这是可再生的探测结果，
 * 不该出现在版本控制中。
 */
@Service(Service.Level.APP)
@State(name = "CommitWiseModelCache", storages = [Storage("commitwise.xml")])
class ModelCache : PersistentStateComponent<ModelCache.State> {

    class State {
        var provider: String = ""
        var baseUrl: String = ""
        var fetchedAt: Long = 0
        var modelsJson: String = ""
    }

    private var myState = State()
    private val gson = Gson()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    private fun cachedModels(): List<ModelInfo> {
        if (myState.modelsJson.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<ModelInfo>>() {}.type
            gson.fromJson<List<ModelInfo>>(myState.modelsJson, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 命中缓存返回模型列表（不检查是否过期，过期由 needsRefresh 判断） */
    fun get(provider: String, baseUrl: String): List<ModelInfo>? {
        if (myState.provider != provider || norm(myState.baseUrl) != norm(baseUrl)) return null
        val models = cachedModels()
        return models.ifEmpty { null }
    }

    /** 缓存是否需要在后台刷新 */
    fun needsRefresh(provider: String, baseUrl: String): Boolean {
        if (myState.provider != provider || norm(myState.baseUrl) != norm(baseUrl)) return true
        return System.currentTimeMillis() - myState.fetchedAt > CACHE_TTL_MS
    }

    val fetchedAt: Long get() = myState.fetchedAt

    /** 真正发起请求并写缓存；失败时抛 AiError，不写缓存 */
    fun fetch(provider: String, baseUrl: String, apiKey: String?, timeoutMs: Int, token: CancelToken): List<ModelInfo> {
        if (!ModelCatalog.supportsModelList(provider)) {
            return ModelCatalog.listModels(provider, baseUrl, apiKey, timeoutMs, token)
        }
        val models = ModelCatalog.listModels(provider, baseUrl, apiKey, timeoutMs, token)
        myState.provider = provider
        myState.baseUrl = baseUrl
        myState.fetchedAt = System.currentTimeMillis()
        myState.modelsJson = gson.toJson(models)
        return models
    }

    fun clear() {
        myState = State()
    }

    companion object {
        fun getInstance(): ModelCache =
            ApplicationManager.getApplication().getService(ModelCache::class.java)
    }
}
