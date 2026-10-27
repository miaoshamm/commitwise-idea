package com.commitwise.idea.domain.diff

/**
 * 暂存区 diff 的结构化模型与解析器（移植自 VSCode 版 diffModel.ts）。
 *
 * 输入是 `git diff --cached` 的原始输出，输出按文件切分、按 hunk 组织，
 * 并保留原始头部行以便无损重排（截断、裁剪时需要重新拼回补丁文本）。
 */
enum class FileStatus { ADDED, MODIFIED, DELETED, RENAMED, BINARY }

enum class LineKind { ADD, DEL, CTX }

data class DiffLine(
    val kind: LineKind,
    val content: String,
    /** 该行在旧文件中的行号（仅 CTX / DEL 有） */
    val oldLineNo: Int? = null,
    /** 该行在新文件中的行号（仅 CTX / ADD 有） */
    val newLineNo: Int? = null,
)

data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val section: String,
    val lines: MutableList<DiffLine> = mutableListOf(),
)

data class DiffFile(
    /** 新路径（删除的文件为原路径） */
    val path: String,
    val oldPath: String? = null,
    val status: FileStatus = FileStatus.MODIFIED,
    val binary: Boolean = false,
    /** `diff --git` 到第一个 `@@` 之间的原始头部行，用于无损重排 */
    val headerLines: List<String> = emptyList(),
    val hunks: List<DiffHunk> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
)

private val HUNK_HEADER =
    Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@ ?(.*)$""")

private fun stripPathPrefix(raw: String, prefix: String): String {
    var p = raw.trim()
    if (p.length >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
        p = p.substring(1, p.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    }
    // 个别情况下 git 会在路径后追加制表符与时间戳
    val tab = p.indexOf('\t')
    if (tab >= 0) {
        p = p.substring(0, tab)
    }
    return if (p.startsWith(prefix)) p.substring(prefix.length) else p
}

/** 从 `diff --git a/x b/x` 中尽力解析两个路径（路径含空格时不可靠，仅作兜底） */
private fun parseGitHeaderPaths(line: String): Pair<String, String> {
    val rest = line.removePrefix("diff --git ").trim()
    if (rest.startsWith("\"")) {
        val quoted = Regex("""^("(?:[^"\\]|\\.)*")\s+("(?:[^"\\]|\\.)*")$""").find(rest)
        if (quoted != null) {
            return stripPathPrefix(quoted.groupValues[1], "a/") to
                stripPathPrefix(quoted.groupValues[2], "b/")
        }
    }
    val mid = rest.indexOf(" b/")
    if (mid > 0) {
        return stripPathPrefix(rest.substring(0, mid), "a/") to
            stripPathPrefix(rest.substring(mid + 1), "b/")
    }
    val sp = rest.indexOf(' ')
    if (sp > 0) {
        return stripPathPrefix(rest.substring(0, sp), "a/") to stripPathPrefix(rest.substring(sp + 1), "b/")
    }
    return rest to rest
}

object DiffParser {

    /** 解析统一 diff 文本。空输入返回空列表。 */
    fun parse(patch: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        val lines = patch.split("\n").toMutableList()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("diff --git ")) {
                i++
                continue
            }

            val fromHeader = parseGitHeaderPaths(line)
            var path = fromHeader.second
            var oldPath: String? = null
            var status = FileStatus.MODIFIED
            var binary = false
            val headerLines = mutableListOf(line)
            val hunks = mutableListOf<DiffHunk>()
            var additions = 0
            var deletions = 0
            i++

            // 头部区：直到第一个 @@ 或下一个 diff --git
            while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git ")) {
                val h = lines[i]
                headerLines.add(h)
                when {
                    h.startsWith("new file mode") -> status = FileStatus.ADDED
                    h.startsWith("deleted file mode") -> status = FileStatus.DELETED
                    h.startsWith("rename from ") -> {
                        status = FileStatus.RENAMED
                        oldPath = stripPathPrefix(h.removePrefix("rename from "), "a/")
                    }
                    h.startsWith("rename to ") -> path = stripPathPrefix(h.removePrefix("rename to "), "b/")
                    h.startsWith("Binary files ") || h.startsWith("GIT binary patch") -> {
                        binary = true
                        status = FileStatus.BINARY
                    }
                    h.startsWith("--- ") -> {
                        val p = h.substring(4)
                        if (p.trim() != "/dev/null") {
                            oldPath = stripPathPrefix(p, "a/")
                        }
                    }
                    h.startsWith("+++ ") -> {
                        val p = h.substring(4)
                        if (p.trim() != "/dev/null") {
                            path = stripPathPrefix(p, "b/")
                        } else {
                            path = oldPath ?: path
                        }
                    }
                }
                i++
            }

            // hunk 区
            while (i < lines.size && lines[i].startsWith("@@")) {
                val m = HUNK_HEADER.find(lines[i])
                if (m == null) {
                    i++
                    continue
                }
                val g = m.groupValues
                val hunk = DiffHunk(
                    oldStart = g[1].toInt(),
                    oldLines = if (g[2].isEmpty()) 1 else g[2].toInt(),
                    newStart = g[3].toInt(),
                    newLines = if (g[4].isEmpty()) 1 else g[4].toInt(),
                    section = g[5],
                )
                i++

                var oldNo = hunk.oldStart
                var newNo = hunk.newStart
                var oldRemaining = hunk.oldLines
                var newRemaining = hunk.newLines

                while (i < lines.size) {
                    val l = lines[i]
                    if (l.startsWith("diff --git ") || l.startsWith("@@")) {
                        break
                    }
                    val tag = l.firstOrNull()
                    val rest = if (l.isEmpty()) "" else l.substring(1)
                    when {
                        tag == '+' -> {
                            hunk.lines.add(DiffLine(LineKind.ADD, rest, null, newNo++))
                            newRemaining--
                            additions++
                        }
                        tag == '-' -> {
                            hunk.lines.add(DiffLine(LineKind.DEL, rest, oldNo++, null))
                            oldRemaining--
                            deletions++
                        }
                        tag == ' ' -> {
                            hunk.lines.add(DiffLine(LineKind.CTX, rest, oldNo++, newNo++))
                            oldRemaining--
                            newRemaining--
                        }
                        // "\ No newline at end of file" 之类的标记，忽略
                        tag == '\\' -> Unit
                        // hunk 末尾的空行（补丁文本常以换行结尾），视为结束
                        l.isEmpty() -> break
                        else -> break
                    }
                    i++
                    if (oldRemaining <= 0 && newRemaining <= 0) {
                        break
                    }
                }
                hunks.add(hunk)
            }

            files.add(
                DiffFile(
                    path = path,
                    oldPath = if (oldPath != null && oldPath != path) oldPath else null,
                    status = status,
                    binary = binary,
                    headerLines = headerLines,
                    hunks = hunks,
                    additions = additions,
                    deletions = deletions,
                ),
            )
        }
        return files
    }

    /** 把单个文件重新渲染成补丁文本（用于裁剪后重组提示词） */
    fun renderFilePatch(file: DiffFile): String {
        val out = mutableListOf<String>()
        out.addAll(file.headerLines)
        for (hunk in file.hunks) {
            out.add(
                "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@" +
                    if (hunk.section.isNotEmpty()) " " + hunk.section else "",
            )
            for (line in hunk.lines) {
                val sign = when (line.kind) {
                    LineKind.ADD -> "+"
                    LineKind.DEL -> "-"
                    LineKind.CTX -> " "
                }
                out.add(sign + line.content)
            }
        }
        return out.joinToString("\n")
    }

    /** 文件中所有「新增行」的行号集合，用于校验审查问题的定位是否合法 */
    fun addedLineNumbers(file: DiffFile): Set<Int> {
        val set = mutableSetOf<Int>()
        for (hunk in file.hunks) {
            for (line in hunk.lines) {
                if (line.kind == LineKind.ADD && line.newLineNo != null) {
                    set.add(line.newLineNo)
                }
            }
        }
        return set
    }
}
