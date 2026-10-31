package network.lapis.cloud.server.activism

import network.lapis.cloud.server.events.EventText
import network.lapis.cloud.shared.rpc.BadRequestException
import java.text.Normalizer

/**
 * V1.9.246 -- every text of the activism module is PLAIN TEXT, cleaned once at the border: NFC, control and bidi characters removed
 * ([EventText]), blanks collapsed. HTML and Markdown are never interpreted: `<b>` stays the literal text `<b>`. An over-long text is REJECTED
 * (never silently cut) by [clean]/[cleanMultiline]. There is deliberately NO content detection of any kind in this package (no word list, no
 * regex filter on meaning): see `ActivismTripwireTest`.
 */
internal object ActivismText {
    /** A single-line text; blank = null; longer than [maxChars] code points = [BadRequestException]. */
    fun clean(
        raw: String?,
        maxChars: Int,
    ): String? {
        if (raw.isNullOrBlank()) return null
        val single = EventText.normalizeSingleLine(Normalizer.normalize(raw, Normalizer.Form.NFC))
        return limit(text = single, maxChars = maxChars)
    }

    /** A multi-line text (line breaks kept, three or more collapse to one empty line). */
    fun cleanMultiline(
        raw: String?,
        maxChars: Int,
    ): String? {
        if (raw.isNullOrBlank()) return null
        return limit(text = EventText.normalizeMultiline(raw), maxChars = maxChars)
    }

    fun require(
        raw: String?,
        maxChars: Int,
    ): String = clean(raw = raw, maxChars = maxChars) ?: throw BadRequestException("Text is required")

    fun requireMultiline(
        raw: String?,
        maxChars: Int,
    ): String = cleanMultiline(raw = raw, maxChars = maxChars) ?: throw BadRequestException("Text is required")

    /** A required text with a minimum length in code points (reasons). */
    fun requireBetween(
        raw: String?,
        min: Int,
        max: Int,
    ): String {
        val text = require(raw = raw, maxChars = max)
        if (text.codePointCount(0, text.length) < min) throw BadRequestException("Text is too short")
        return text
    }

    private fun limit(
        text: String,
        maxChars: Int,
    ): String? {
        if (text.isEmpty()) return null
        if (text.codePointCount(0, text.length) > maxChars) throw BadRequestException("Text is too long")
        return text
    }
}
