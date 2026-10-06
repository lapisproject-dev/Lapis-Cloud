package network.lapis.cloud.server.encounter

import network.lapis.cloud.server.db.SourceScan
import java.io.File

/** One function found by [EncounterSourceScan.functions]: its name, the raw parameter list text and the raw body text. */
internal data class ScannedFunction(
    val name: String,
    val isOverride: Boolean,
    val params: String,
    val body: String,
)

/** A small, brace-matching function extractor for the tripwire tests of the encounter spaces. Comments and string contents are blanked first. */
internal object EncounterSourceScan {
    private val declaration =
        Regex(
            """(?:override\s+)?(?:private\s+|internal\s+|public\s+)?(?:suspend\s+)?(?:inline\s+)?fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.?]+\.)?(\w+)\s*\(""",
        )

    fun mainFile(relativePath: String): File = File(SourceScan.mainRoot(), "network/lapis/cloud/server/$relativePath")

    fun functions(file: File): List<ScannedFunction> {
        val raw = file.readText()
        val blanked = SourceScan.blank(raw)
        val result = mutableListOf<ScannedFunction>()
        declaration.findAll(blanked).forEach { m ->
            val name = m.groupValues[1]
            val isOverride = m.value.startsWith("override")
            val paramsStart = m.range.last
            val paramsEnd = matching(text = blanked, open = paramsStart, openChar = '(', closeChar = ')') ?: return@forEach
            var i = paramsEnd + 1
            // skip the return type up to the body marker
            while (i < blanked.length && blanked[i] != '{' && blanked[i] != '=' && blanked[i] != '\n') i++
            // a `:` return type may continue on the next line only for expression bodies; scan on until { or =
            while (i < blanked.length && blanked[i] != '{' && blanked[i] != '=') i++
            if (i >= blanked.length) return@forEach
            val bodyEnd =
                if (blanked[i] == '{') {
                    matching(text = blanked, open = i, openChar = '{', closeChar = '}') ?: return@forEach
                } else {
                    val next = Regex("""\n\s{0,4}(?:override|private|internal|public|fun|suspend|/\*\*|companion)\b""").find(blanked, i)
                    next?.range?.first ?: blanked.length
                }
            result +=
                ScannedFunction(
                    name = name,
                    isOverride = isOverride,
                    params = raw.substring(paramsStart + 1, paramsEnd),
                    body =
                        raw.substring(
                            i,
                            minOf(
                                bodyEnd + 1,
                                raw.length,
                            ),
                        ),
                )
        }
        return result
    }

    private fun matching(
        text: String,
        open: Int,
        openChar: Char,
        closeChar: Char,
    ): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
