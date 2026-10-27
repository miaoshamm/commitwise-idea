package com.commitwise.idea.domain.diff

/**
 * 极简 glob 匹配（gitignore 风格子集），移植自 glob.ts。
 *
 * 支持：`**`（跨目录）、`*`（目录内任意）、`?`（单字符）。
 * 不含 `/` 的模式按 gitignore 语义匹配任意层级的文件名。
 */
object Glob {

    fun globToRegExp(glob: String): Regex {
        val pattern = glob.trim().replace('\\', '/')
        val anchored = pattern.contains('/')
        val body = StringBuilder()

        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            when {
                ch == '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                            // `**/` 可匹配零层目录
                            body.append("(?:.*/)?")
                            i += 2
                        } else {
                            body.append(".*")
                            i += 1
                        }
                    } else {
                        body.append("[^/]*")
                    }
                }
                ch == '?' -> body.append("[^/]")
                ch == '/' -> body.append('/')
                else -> body.append(Regex.escape(ch.toString()))
            }
            i++
        }

        return if (anchored) Regex("^$body$") else Regex("(?:^|/)$body$")
    }

    /** 返回命中的第一个规则，未命中返回 null */
    fun matchesAny(path: String, globs: List<String>): String? {
        val normalized = path.replace('\\', '/')
        for (glob in globs) {
            if (glob.isBlank()) continue
            if (globToRegExp(glob).containsMatchIn(normalized)) {
                return glob
            }
        }
        return null
    }
}
