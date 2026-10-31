package network.lapis.cloud.server.partner

import network.lapis.cloud.server.events.EventText
import java.text.Normalizer

/**
 * V1.9.244 -- text that comes from a FOREIGN server (a partner feed) or from an admin's free input is cleaned once, at the border, and is plain
 * text from then on: NFC, control and bidi characters removed ([EventText.normalizeSingleLine]), line breaks and runs of blanks collapsed,
 * cut to [maxChars] CODE POINTS (never half a surrogate pair). HTML is NEVER interpreted or stripped: `<b>` stays the literal text `<b>` and the
 * client sets it only through its `untrusted*` helpers.
 */
internal object ForeignText {
    fun clean(
        raw: String?,
        maxChars: Int,
    ): String? {
        if (raw.isNullOrBlank()) return null
        val single = EventText.normalizeSingleLine(Normalizer.normalize(raw, Normalizer.Form.NFC))
        if (single.isEmpty()) return null
        val cut =
            if (single.codePointCount(0, single.length) <= maxChars) {
                single
            } else {
                single.substring(0, single.offsetByCodePoints(0, maxChars)).trimEnd()
            }
        return cut.ifEmpty { null }
    }

    /**
     * Display shortening of an ALREADY CLEANED name: cut to [maxChars] code points (never half a surrogate pair) with a trailing ellipsis.
     * Used for names in the public lists; the full text stays in the database.
     */
    fun ellipsize(
        text: String,
        maxChars: Int,
    ): String {
        require(maxChars >= 1) { "maxChars must be positive" }
        if (text.codePointCount(0, text.length) <= maxChars) return text
        return text.substring(0, text.offsetByCodePoints(0, maxChars)).trimEnd() + "\u2026"
    }
}
