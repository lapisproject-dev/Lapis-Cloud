package network.lapis.cloud.server.articles

import network.lapis.cloud.server.events.EventText
import network.lapis.cloud.shared.rpc.BadRequestException

/**
 * Welle V1.9.105 -- pure rules for the author line (byline) of an article: no database access, no clock.
 *
 * The text is PLAIN TEXT, one line. It is normalized (NFC, control and bidi characters removed, whitespace collapsed) BEFORE the length
 * check, so the limit applies to the stored form. The public page and the embed feed hand it out as text only.
 */
object ArticleBylinePolicy {
    const val BYLINE_MAX = 120

    fun normalize(raw: String): String = EventText.normalizeSingleLine(raw)

    /** `text` must already be [normalize]d. */
    fun requireValid(text: String) {
        if (text.isEmpty()) throw BadRequestException("Bitte geben Sie an, wie Sie genannt werden möchten.")
        if (text.length > BYLINE_MAX) throw BadRequestException("Die Autorenzeile ist zu lang (maximal $BYLINE_MAX Zeichen).")
    }
}
