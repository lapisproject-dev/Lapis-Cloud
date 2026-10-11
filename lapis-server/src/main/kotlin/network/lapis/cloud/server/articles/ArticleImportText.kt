package network.lapis.cloud.server.articles

import java.text.Normalizer

/**
 * Welle V1.9.105 -- text normalization for the article import. Deliberately NOT `EventText.normalizeMultiline`: that one trims trailing
 * whitespace per line (which destroys the Markdown hard line break "two spaces + newline" that the legacy articles contain) and turns TAB
 * into a space. Here only the structure-neutral clean-up happens.
 */
internal object ArticleImportText {
    private const val LF = '\n'

    /**
     * NFC; CRLF/CR/U+2028/U+2029 become LF; control characters (except LF and TAB) and bidi controls are removed; ZWJ/ZWNJ/soft hyphen are
     * kept; leading and trailing EMPTY lines are trimmed (the first/last content line keeps its own leading/trailing spaces except those of
     * the very first/last line, which are trimmed too -- a body never starts or ends with whitespace). Everything inside stays byte-for-byte.
     */
    fun normalizeMarkdownBody(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val c = nfc[i]
            when {
                c == '\r' -> {
                    sb.append(LF)
                    if (i + 1 < nfc.length && nfc[i + 1] == LF) i++
                }
                c == LF || c == ' ' || c == ' ' -> sb.append(LF)
                c == '\t' -> sb.append(c)
                isStrippable(c) -> Unit
                else -> sb.append(c)
            }
            i++
        }
        val lines = sb.toString().split(LF).toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return ""
        lines[0] = lines[0].trimStart()
        lines[lines.lastIndex] = lines.last().trimEnd()
        return lines.joinToString(separator = "\n")
    }

    private fun isStrippable(c: Char): Boolean {
        if (Character.getType(c) == Character.CONTROL.toInt()) return true
        return c == '‎' ||
            c == '‏' ||
            c == '؜' ||
            c in '‪'..'‮' ||
            c in '⁦'..'⁩'
    }
}
