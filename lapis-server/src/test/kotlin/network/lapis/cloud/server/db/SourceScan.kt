package network.lapis.cloud.server.db

import java.io.File

/** Shared helpers of the V1.9.55 source-scan tripwires (no parser: comment/string blanking + brace matching). */
internal object SourceScan {
    fun mainRoot(): File = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }

    fun mainFiles(): List<File> = mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Blanks comments and string/char literal CONTENTS (replacing them by spaces / empty literals) while preserving the
     * code's length and line structure, so braces inside strings, templates and comments cannot confuse brace matching.
     */
    fun blank(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> {
                    while (i < n && src[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                src.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < n) {
                        if (src.startsWith("/*", i)) {
                            depth++
                            out.append("  ")
                            i += 2
                        } else if (src.startsWith("*/", i)) {
                            depth--
                            out.append("  ")
                            i += 2
                            if (depth == 0) break
                        } else {
                            out.append(if (src[i] == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                src.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < n && !src.startsWith("\"\"\"", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < n) {
                        out.append("\"\"\"")
                        i += 3
                    }
                }
                c == '"' -> {
                    out.append('"')
                    i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < n) {
                            out.append("  ")
                            i += 2
                        } else {
                            out.append(' ')
                            i++
                        }
                    }
                    if (i < n && src[i] == '"') {
                        out.append('"')
                        i++
                    }
                }
                c == '\'' && i + 2 < n && (src[i + 2] == '\'' || (src[i + 1] == '\\' && i + 3 < n)) -> {
                    val end = src.indexOf('\'', i + 2)
                    if (end == -1) {
                        out.append(c)
                        i++
                    } else {
                        repeat(end - i + 1) { out.append(' ') }
                        i = end + 1
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** `[start, end)` ranges of the bodies of every `transaction { ... }` / `transaction(x) { ... }` / `newSuspendedTransaction` block. */
    fun transactionBlocks(blanked: String): List<IntRange> {
        val regex = Regex("""(?<![A-Za-z0-9_.])transaction\s*(\([^()]*(\([^()]*\)[^()]*)*\))?\s*\{""")
        return regex
            .findAll(blanked)
            .map { m ->
                val open = m.range.last
                var depth = 0
                var j = open
                while (j < blanked.length) {
                    when (blanked[j]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    j++
                }
                open + 1 until j
            }.toList()
    }

    fun lineOf(
        text: String,
        index: Int,
    ): Int = text.substring(0, index.coerceAtMost(text.length)).count { it == '\n' } + 1
}
