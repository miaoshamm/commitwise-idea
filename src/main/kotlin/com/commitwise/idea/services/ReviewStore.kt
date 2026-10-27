package com.commitwise.idea.services

import com.commitwise.idea.domain.review.Anchor
import com.commitwise.idea.domain.review.Category
import com.commitwise.idea.domain.review.ReviewIssue
import com.commitwise.idea.domain.review.ReviewReport
import com.commitwise.idea.domain.review.Severity
import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ide.util.PropertiesComponent
import java.util.concurrent.CopyOnWriteArrayList

/** 落库的审查结果（关掉工具窗口也不会丢） */
data class StoredReview(
    val savedAt: Long = 0,
    /** 仓库根目录绝对路径 */
    val root: String = "",
    val repoName: String = "",
    val branch: String = "",
    val providerLabel: String = "",
    val model: String = "",
    val elapsedMs: Long = 0,
    val summary: String = "",
    val issues: List<ReviewIssue> = emptyList(),
    val notices: List<String> = emptyList(),
    val truncated: Boolean = false,
    val totalBeforeLimit: Int = 0,
    val high: Int = 0,
    val medium: Int = 0,
    val low: Int = 0,
) {
    val total: Int get() = high + medium + low

    fun byFile(): List<Pair<String, List<ReviewIssue>>> {
        val groups = LinkedHashMap<String, MutableList<ReviewIssue>>()
        for (issue in issues) {
            groups.getOrPut(issue.file) { mutableListOf() }.add(issue)
        }
        return groups.map { it.key to it.value.toList() }
    }

    fun findById(id: String): ReviewIssue? = issues.firstOrNull { it.id == id }

    fun toReport(): ReviewReport = ReviewReport(
        savedAt = savedAt,
        repoName = repoName,
        branch = branch,
        providerLabel = providerLabel,
        model = model,
        elapsedMs = elapsedMs,
        summary = summary,
        issues = issues,
        byFile = byFile(),
        counts = mapOf(Severity.HIGH to high, Severity.MEDIUM to medium, Severity.LOW to low),
        notices = notices,
        truncated = truncated,
        totalBeforeLimit = totalBeforeLimit,
    )
}

/**
 * 审查结果的唯一存放点。
 *
 * 之所以要落盘：工具窗口关掉就没了，而用户经常是「审核完先去改代码，回头再对一遍清单」。
 * 工具窗口、报告、状态栏、复制的 Markdown 全部读这一份数据。
 */
@Service(Service.Level.PROJECT)
class ReviewStore(private val project: Project) {

    private var current: StoredReview? = null

    /** 审查进行中：工具窗口与报告据此显示运行态、禁用重复触发 */
    @Volatile
    private var runningFlag = false

    private val listeners = CopyOnWriteArrayList<(StoredReview?) -> Unit>()

    init {
        current = load()
    }

    fun get(): StoredReview? = current

    fun isRunning(): Boolean = runningFlag

    fun setRunning(running: Boolean) {
        if (runningFlag == running) return
        runningFlag = running
        fire()
    }

    /** 状态变化通知所有视图（树、报告、状态栏刷新） */
    fun addListener(listener: (StoredReview?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (StoredReview?) -> Unit) {
        listeners.remove(listener)
    }

    fun save(review: StoredReview) {
        current = review
        persist(review)
        fire()
    }

    fun clear() {
        current = null
        PropertiesComponent.getInstance(project).unsetValue(KEY)
        fire()
    }

    private fun fire() {
        val snapshot = current
        listeners.toList().forEach { it(snapshot) }
    }

    private fun persist(review: StoredReview) {
        try {
            PropertiesComponent.getInstance(project).setValue(KEY, Gson().toJson(review))
        } catch (_: Exception) {
            // 序列化失败不影响内存中的结果
        }
    }

    private fun load(): StoredReview? {
        val json = PropertiesComponent.getInstance(project).getValue(KEY) ?: return null
        if (StringUtil.isEmptyOrSpaces(json)) return null
        return try {
            val stored = Gson().fromJson(json, StoredReview::class.java)
            if (stored != null && stored.issues.isNotEmpty() || (stored != null && stored.savedAt > 0)) stored else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val KEY = "commitwise.lastReview.v1"

        fun getInstance(project: Project): ReviewStore = project.service<ReviewStore>()
    }
}

/** 便捷判断：只要能精确到行的问题才适合打波浪线 */
fun ReviewIssue.canMarkInEditor(): Boolean = anchor == Anchor.LINE && line != null

/** 严重级别 → 编辑器高亮级别的映射说明（供 inspection 使用） */
fun severityOf(issue: ReviewIssue): Severity = issue.severity

@Suppress("unused")
private val ALL_CATEGORIES = Category.entries
