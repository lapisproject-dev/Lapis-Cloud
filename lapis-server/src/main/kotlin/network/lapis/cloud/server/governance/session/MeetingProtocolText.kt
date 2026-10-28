package network.lapis.cloud.server.governance.session

/**
 * V1.9.239 -- the ONE sanitiser for every free text of the Protokoll draft. The text is PLAIN text: nothing is ever interpreted (no HTML, no
 * Markdown, no wiki syntax), so the sanitiser does not escape anything -- it only removes what can disguise or break text:
 * line breaks are normalised to `\n`; control characters (C0 and C1, except `\n` and `\t`), the bidirectional controls (U+061C, U+200E/U+200F,
 * U+202A..U+202E, U+2066..U+2069), the zero-width characters (U+200B..U+200D, U+2060, U+FEFF), the line/paragraph separators (U+2028/U+2029)
 * and unpaired surrogates are dropped. The allowed set is explicit -- a `\p{C}` regex would also remove `\n` and `\t`.
 *
 * Sanitising walks code points (so a surrogate pair is kept or removed as a whole). LENGTH is counted in UTF-16 units, [protocolCharCount]:
 * H2 measures a `VARCHAR(n)` in UTF-16 units, PostgreSQL in code points, so the unit count is the one bound that fits both engines (and it is
 * what the browser's `maxlength` counts). A character outside the BMP therefore costs two. The caller checks the length AFTER sanitising.
 */
internal fun sanitizeProtocolText(raw: String): String {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val out = StringBuilder(normalized.length)
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        index += Character.charCount(codePoint)
        if (isAllowedProtocolCodePoint(codePoint)) out.appendCodePoint(codePoint)
    }
    return out.toString()
}

internal fun protocolCharCount(text: String): Int = text.length

private const val NEWLINE = 0x0A
private const val TAB = 0x09
private const val C0_END = 0x20
private const val DELETE = 0x7F
private const val C1_END = 0x9F

@Suppress("CyclomaticComplexMethod")
private fun isAllowedProtocolCodePoint(codePoint: Int): Boolean =
    when {
        codePoint == NEWLINE || codePoint == TAB -> true
        codePoint < C0_END -> false
        codePoint in DELETE..C1_END -> false
        Character.isSurrogate(codePoint.toChar()) && codePoint <= 0xFFFF -> false
        codePoint == 0x061C || codePoint == 0x200E || codePoint == 0x200F -> false
        codePoint in 0x200B..0x200D -> false
        codePoint in 0x2028..0x202E -> false
        codePoint == 0x2060 || codePoint in 0x2066..0x2069 -> false
        codePoint == 0xFEFF -> false
        else -> true
    }
