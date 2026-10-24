package network.lapis.cloud.server.events

import network.lapis.cloud.shared.domain.EventRegistrationMessageLimits

/**
 * V1.9.204 -- validation and normalisation of the optional message a registrant leaves for the organisers.
 *
 * The text is stored VERBATIM otherwise (no HTML stripping): it is escaped where it is rendered (client `untrustedDiv`, mail text-only,
 * CSV formula guard). Control characters and the Unicode bidi controls are removed here because they can reorder text visually
 * (trojan-source style) and have no place in a chat-like note.
 */
internal object EventRegistrationMessage {
    sealed interface Outcome {
        data class Ok(
            val value: String?,
        ) : Outcome

        data object TooLong : Outcome
    }

    const val MAX_LENGTH: Int = EventRegistrationMessageLimits.MAX_LENGTH

    const val TOO_LONG_TEXT = "Die Nachricht darf höchstens 500 Zeichen lang sein."

    /**
     * CR LF and lone CR become LF; control characters other than LF, U+200E/F and U+202A-202E and U+2066-2069 are dropped; the result is
     * trimmed; blank becomes `null`. The length (UTF-16 units, the strictest of H2, PostgreSQL and the browser's `maxlength`) is checked
     * AFTER cleaning.
     */
    fun normalize(raw: String?): Outcome {
        if (raw == null) return Outcome.Ok(null)
        val unified = raw.replace("\r\n", "\n").replace('\r', '\n')
        val cleaned =
            buildString(unified.length) {
                unified.forEach { c -> if (keep(c)) append(c) }
            }.trim()
        if (cleaned.isEmpty()) return Outcome.Ok(null)
        if (cleaned.length > MAX_LENGTH) return Outcome.TooLong
        return Outcome.Ok(cleaned)
    }

    private fun keep(c: Char): Boolean =
        when {
            c == '\n' -> true
            c.isISOControl() -> false
            c == '‎' || c == '‏' -> false
            c in '‪'..'‮' -> false
            c in '⁦'..'⁩' -> false
            else -> true
        }
}
