package com.commitwise.idea.domain.review

import com.commitwise.idea.domain.diff.DiffFile
import com.commitwise.idea.domain.diff.DiffParser

/** 文件路径不在本次 diff 中 */
data class DroppedStats(
    val unknownFile: Int = 0,
    /** 行号不在任何新增行内，已降级为文件级 */
    val outOfRange: Int = 0,
    /** 结构缺失（如没有 title）被丢弃 */
    val malformed: Int = 0,
)

data class MappedReview(
    val summary: String,
    val issues: List<ReviewIssue>,
    val byFile: List<Pair<String, List<ReviewIssue>>>,
    val counts: Map<Severity, Int>,
    val dropped: DroppedStats,
    /** 是否因为 maxIssues 被截断 */
    val truncated: Boolean,
    val totalBeforeLimit: Int,
)

data class MapOptions(val minSeverity: Severity = Severity.LOW, val maxIssues: Int = 30)

private fun normalize(p: String): String =
    p.replace('\\', '/').replace(Regex("^\\./"), "").replace(Regex("^[ab]/"), "")

/**
 * 把模型给出的事物对齐到真实 diff：
 * - 路径必须能在本次暂存区中找到（支持重命名前的旧路径）
 * - 行号必须落在新增行内，否则降级为文件级（避免波浪线标错位置）
 * - 按级别过滤、排序、限量
 */
object IssueMapper {

    fun map(
        result: ReviewResult,
        files: List<DiffFile>,
        malformedDropped: Int,
        opts: MapOptions,
    ): MappedReview {
        val index = LinkedHashMap<String, DiffFile>()
        for (f in files) {
            index[normalize(f.path)] = f
            f.oldPath?.let { index[normalize(it)] = f }
        }

        val addedCache = HashMap<String, Set<Int>>()
        fun addedFor(file: DiffFile): Set<Int> = addedCache.getOrPut(file.path) { DiffParser.addedLineNumbers(file) }

        var unknownFile = 0
        var outOfRange = 0
        val kept = mutableListOf<ReviewIssue>()

        for (issue in result.issues) {
            val key = normalize(issue.file)
            var target = index[key]

            if (target == null) {
                // 容错：模型可能只给了文件名，或给了带仓库前缀的绝对路径
                for ((path, file) in index) {
                    if (path.endsWith("/$key") || key.endsWith("/$path")) {
                        target = file
                        break
                    }
                }
            }

            if (target == null) {
                unknownFile++
                continue
            }

            if (issue.severity.ordinal > opts.minSeverity.ordinal) {
                continue
            }

            var mapped = issue.copy(file = target.path)

            if (issue.line != null) {
                if (addedFor(target).contains(issue.line)) {
                    mapped = mapped.copy(anchor = Anchor.LINE)
                } else {
                    outOfRange++
                    mapped = mapped.copy(anchor = Anchor.FILE, line = null)
                }
            } else {
                mapped = mapped.copy(anchor = Anchor.FILE)
            }

            kept.add(mapped)
        }

        kept.sortWith(
            compareBy(
                { it.severity.ordinal },
                { it.file },
                { it.line ?: Int.MAX_VALUE },
            ),
        )

        val totalBeforeLimit = kept.size
        val limited = kept.take(maxOf(1, opts.maxIssues))

        val groups = LinkedHashMap<String, MutableList<ReviewIssue>>()
        val counts = mapOf(Severity.HIGH to 0, Severity.MEDIUM to 0, Severity.LOW to 0).toMutableMap()
        for (issue in limited) {
            counts[issue.severity] = (counts[issue.severity] ?: 0) + 1
            groups.getOrPut(issue.file) { mutableListOf() }.add(issue)
        }

        return MappedReview(
            summary = result.summary,
            issues = limited,
            byFile = groups.map { it.key to it.value.toList() },
            counts = counts,
            dropped = DroppedStats(unknownFile, outOfRange, malformedDropped),
            truncated = totalBeforeLimit > limited.size,
            totalBeforeLimit = totalBeforeLimit,
        )
    }
}
