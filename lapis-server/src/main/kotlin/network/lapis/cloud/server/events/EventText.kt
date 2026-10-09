package network.lapis.cloud.server.events

import java.net.URI
import java.text.Normalizer

/**
 * Welle V1.9.82 -- shared plain-text normalization for the public events surface (feed, detail page, import). Pure functions,
 * no database access. Event texts are PLAIN TEXT: no Markdown, no HTML. Whatever consumes them must render them as text.
 */
object EventText {
    private const val LF = '\n'
    private val HTML_LIKE = Regex("<\\s*/?\\s*[a-zA-Z!]")

    /**
     * Multi-line text (description): NFC; CRLF/CR become LF; TAB becomes a space; U+2028/U+2029 become LF; all other control characters and
     * bidi controls (overrides, isolates, marks; NUL/ESC) are removed, while ZWJ/ZWNJ/soft hyphen are kept; trailing whitespace per line is trimmed; three or more LF
     * collapse to exactly two (one empty line); leading/trailing whitespace is trimmed. Single LF are kept.
     */
    fun normalizeMultiline(raw: String): String {
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
                c == LF || c == '\u2028' || c == '\u2029' -> sb.append(LF)
                c == '\t' -> sb.append(' ')
                isStrippable(c) -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb
            .toString()
            .split(LF)
            .joinToString(separator = "\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Single-line text (summary, alt text, title, location): [normalizeMultiline], then every LF run becomes one space and repeated spaces collapse. */
    fun normalizeSingleLine(raw: String): String =
        normalizeMultiline(raw)
            .replace(Regex("\\s*\n+\\s*"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    /** Paragraphs are split at an empty line, lines inside a paragraph at a single LF. [normalized] must come from [normalizeMultiline]. */
    fun paragraphs(normalized: String): List<List<String>> =
        if (normalized.isEmpty()) {
            emptyList()
        } else {
            normalized.split("\n\n").map { paragraph -> paragraph.split(LF) }
        }

    /** `true` iff [raw] is an absolute `https` URL with a non-empty host and no userinfo. Syntax only -- never opens a connection. */
    fun isHttpsUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.userInfo == null
    }

    /** Only used for a non-blocking preview hint ("will be displayed as text"). */
    fun containsHtmlLikeMarkup(raw: String): Boolean = HTML_LIKE.containsMatchIn(raw)

    /**
     * Control characters and the bidi controls (U+200E/F, U+202A-E, U+2066-9, U+061C) are removed. Other format characters are KEPT on
     * purpose: U+200D ZWJ (emoji sequences), U+200C ZWNJ (Persian and other scripts) and U+00AD soft hyphen are legitimate text.
     */
    private fun isStrippable(c: Char): Boolean {
        if (Character.getType(c) == Character.CONTROL.toInt()) return true
        return c == '\u200E' ||
            c == '\u200F' ||
            c == '\u061C' ||
            c in '\u202A'..'\u202E' ||
            c in '\u2066'..'\u2069'
    }
}
