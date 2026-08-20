package network.lapis.cloud.server.payment.sepa

import java.text.Normalizer

/**
 * V1.2.2 "SEPA-Lastschriftmandate". The SEPA basic character set: `a-z A-Z 0-9` and
 * ``/ - ? : ( ) . , ' +`` plus a space. Umlauts/diacritics are transliterated, not rejected --
 * "Mueller" is a perfectly ordinary account-holder name, and failing a direct debit on that would be
 * absurd.
 */
object SepaCharacterSet {
    private val GERMAN_MAP =
        mapOf(
            'ä' to "ae",
            'ö' to "oe",
            'ü' to "ue",
            'ß' to "ss",
            'Ä' to "Ae",
            'Ö' to "Oe",
            'Ü' to "Ue",
        )
    private val ALLOWED_PUNCTUATION = setOf('/', '-', '?', ':', '(', ')', '.', ',', '\'', '+', ' ')

    /** ae/oe/ue/ss transliteration (German umlaut convention) THEN accent-stripping via NFD, then anything left unmapped becomes `.`. */
    fun transliterate(raw: String): String {
        val germanFolded = raw.map { GERMAN_MAP[it] ?: it.toString() }.joinToString("")
        val normalized = Normalizer.normalize(germanFolded, Normalizer.Form.NFD)
        val accentsStripped = normalized.filterNot { Character.getType(it).toByte() == Character.NON_SPACING_MARK.toByte() }
        return accentsStripped.map { ch -> if (isSepaChar(ch)) ch.toString() else "." }.joinToString("")
    }

    /** [transliterate]s, collapses runs of whitespace, trims, and truncates to [maxLength]. */
    fun sanitize(
        raw: String,
        maxLength: Int,
    ): String {
        val transliterated = transliterate(raw)
        val collapsed = transliterated.replace(Regex("\\s+"), " ").trim()
        return collapsed.take(maxLength)
    }

    /** `true` iff every character of [value] is in the SEPA basic character set. */
    fun isSepaSafe(value: String): Boolean = value.all { isSepaChar(it) }

    private fun isSepaChar(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in ALLOWED_PUNCTUATION
}
