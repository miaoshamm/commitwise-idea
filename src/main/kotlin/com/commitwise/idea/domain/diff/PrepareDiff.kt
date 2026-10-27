package com.commitwise.idea.domain.diff

import com.commitwise.idea.domain.diff.DiffParser.renderFilePatch

/** 排除规则（命中则内容不发送，但仍在文件清单中可见） */
data class PrepareOptions(
    val excludeGlobs: List<String> = emptyList(),
    val maxFileChars: Int = 20000,
    val maxTotalChars: Int = 48000,
    val redactSecrets: Boolean = true,
)

enum class SkipReason { EXCLUDED, BINARY }

data class SkippedFile(val path: String, val reason: SkipReason, val detail: String? = null)

data class PreparedDiff(
    /** 最终发给模型的补丁文本（已脱敏） */
    val patch: String,
    val included: List<DiffFile>,
    val skipped: List<SkippedFile>,
    /** 因字符上限被截断内容的文件 */
    val truncated: List<String>,
    /** 供 {{stagedFiles}} 使用的文件清单 */
    val fileList: String,
    val chars: Int,
    val redactionHits: Int,
    val redactionRules: List<String>,
)

object PrepareDiff {

    private fun statusLetter(status: FileStatus): String = when (status) {
        FileStatus.ADDED -> "A"
        FileStatus.MODIFIED -> "M"
        FileStatus.DELETED -> "D"
        FileStatus.RENAMED -> "R"
        FileStatus.BINARY -> "B"
    }

    /** 按字符上限裁剪单个文件，保留尽可能多的完整 hunk */
    fun truncateFile(file: DiffFile, maxChars: Int): Pair<DiffFile, Boolean> {
        val full = renderFilePatch(file)
        if (full.length <= maxChars) {
            return file to false
        }

        val headerCost = file.headerLines.joinToString("\n").length + 120
        val kept = mutableListOf<DiffHunk>()
        var used = headerCost

        for (hunk in file.hunks) {
            var hunkCost = 40
            for (l in hunk.lines) hunkCost += l.content.length + 2
            if (used + hunkCost > maxChars) break
            kept.add(hunk)
            used += hunkCost
        }

        if (kept.isEmpty() && file.hunks.isNotEmpty()) {
            // 第一个 hunk 就放不下：截取该 hunk 的前若干行
            val first = file.hunks[0]
            val take = maxOf(5, (maxChars - headerCost) / 40)
            val parts = first.lines.take(take).toMutableList()
            kept.add(
                first.copy(
                    lines = parts,
                    newLines = parts.count { it.kind != LineKind.DEL },
                    oldLines = parts.count { it.kind != LineKind.ADD },
                ),
            )
        }

        return file.copy(hunks = kept) to true
    }

    private fun buildFileList(files: List<DiffFile>, skipped: List<SkippedFile>): String {
        val skipMap = skipped.associateBy { it.path }
        return files.joinToString("\n") { f ->
            val parts = mutableListOf(statusLetter(f.status), f.path, "+${f.additions}", "-${f.deletions}")
            val skip = skipMap[f.path]
            var note = ""
            if (skip != null) {
                note = if (skip.reason == SkipReason.BINARY) "  （二进制文件，内容未提供）" else "  （已按规则排除，内容未提供）"
            }
            if (f.status == FileStatus.RENAMED && f.oldPath != null) {
                parts[1] = "${f.oldPath} -> ${f.path}"
            }
            parts.joinToString("\t") + note
        }
    }

    /** 过滤 → 逐文件截断 → 总预算裁剪 → 脱敏，产出可直接塞进提示词的补丁文本。 */
    fun prepare(files: List<DiffFile>, opts: PrepareOptions): PreparedDiff {
        val skipped = mutableListOf<SkippedFile>()
        val candidates = mutableListOf<DiffFile>()
        val truncated = mutableListOf<String>()

        for (file in files) {
            if (file.binary) {
                skipped.add(SkippedFile(file.path, SkipReason.BINARY))
                continue
            }
            val hit = Glob.matchesAny(file.path, opts.excludeGlobs)
            if (hit != null) {
                skipped.add(SkippedFile(file.path, SkipReason.EXCLUDED, hit))
                continue
            }
            if (file.hunks.isEmpty()) {
                skipped.add(SkippedFile(file.path, SkipReason.EXCLUDED, "no hunk"))
                continue
            }
            val (trimmed, wasTruncated) = truncateFile(file, opts.maxFileChars)
            if (wasTruncated) truncated.add(file.path)
            candidates.add(trimmed)
        }

        // 超出总预算时，逐个丢弃体积最大的文件（文档类优先丢弃，源码尽量保留）
        fun cost(f: DiffFile): Int = renderFilePatch(f).length
        val order = candidates.sortedByDescending { cost(it) }
        val dropped = mutableSetOf<String>()

        fun total(): Int = candidates.sumOf { if (dropped.contains(it.path)) 0 else cost(it) }

        for (file in order) {
            if (total() <= opts.maxTotalChars) break
            if (candidates.size - dropped.size <= 1) break
            dropped.add(file.path)
            skipped.add(SkippedFile(file.path, SkipReason.EXCLUDED, "超出上下文预算"))
        }

        val included = candidates.filter { !dropped.contains(it.path) }
        var patch = included.joinToString("\n\n") { f ->
            if (truncated.contains(f.path)) "${renderFilePatch(f)}\n（注：该文件 diff 已截断）" else renderFilePatch(f)
        }

        if (patch.length > opts.maxTotalChars) {
            patch = patch.substring(0, opts.maxTotalChars) + "\n…（补丁已按总长度上限截断）"
        }

        var redactionHits = 0
        var redactionRules: List<String> = emptyList()
        if (opts.redactSecrets) {
            val r = Redact.redactSecrets(patch)
            patch = r.text
            redactionHits = r.hits
            redactionRules = r.rules
        }

        return PreparedDiff(
            patch = patch,
            included = included,
            skipped = skipped,
            truncated = truncated,
            fileList = buildFileList(files, skipped),
            chars = patch.length,
            redactionHits = redactionHits,
            redactionRules = redactionRules,
        )
    }
}
