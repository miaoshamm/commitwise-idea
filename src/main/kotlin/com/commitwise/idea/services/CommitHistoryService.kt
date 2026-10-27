package com.commitwise.idea.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

data class HistoryEntry(
    /** 完整提交信息（含正文） */
    val message: String,
    /** 生成时使用的模型 */
    val model: String,
    /** 生成时间戳 */
    val time: Long,
    /** 仓库名（根目录 base name） */
    val repoName: String,
    /** 分支 */
    val branch: String,
)

/**
 * 最近生成的提交信息环形缓冲（最多 10 条）。
 *
 * 写入提交输入框的成功结果入栈，重开项目后仍可找回；
 * 误清空输入框、或者想复用之前那条，运行「提交信息历史」即可。
 */
@Service(Service.Level.APP)
@State(name = "CommitWiseHistory", storages = [Storage("commitwise.xml")])
class CommitHistoryService : PersistentStateComponent<CommitHistoryService.State> {

    class State {
        var entriesJson: String = ""
    }

    private var myState = State()
    private val gson = Gson()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun list(): List<HistoryEntry> {
        if (myState.entriesJson.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson<List<HistoryEntry>>(myState.entriesJson, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun push(entry: HistoryEntry) {
        val list = list().toMutableList()
        list.add(0, entry)
        while (list.size > MAX) list.removeAt(list.size - 1)
        myState.entriesJson = gson.toJson(list)
    }

    @Synchronized
    fun removeAt(index: Int) {
        val list = list().toMutableList()
        if (index < 0 || index >= list.size) return
        list.removeAt(index)
        myState.entriesJson = gson.toJson(list)
    }

    fun clear() {
        myState.entriesJson = ""
    }

    companion object {
        private const val MAX = 10

        fun getInstance(): CommitHistoryService =
            ApplicationManager.getApplication().getService(CommitHistoryService::class.java)
    }
}
