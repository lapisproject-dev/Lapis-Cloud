package network.lapis.cloud.server.encounter

import java.security.MessageDigest

/**
 * The versioned, hashed Art. 9 GDPR consent a NON-member (GUEST or FRIEND) must acknowledge before entering an encounter space that
 * admits non-members. Same immutability/versioning contract as [network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer]:
 * [VERSION]/[TEXT]/[SHA256] are immutable at runtime, a wording change requires a NEW [VERSION], and [TEXT] is COMPOSED from
 * [HEADLINE]/[KEY_POINTS]/[DETAIL] so the short client summary and the long hashed text cannot drift apart.
 *
 * **The text is a DRAFT.** It is a proposal for the explicit consent under Art. 9(2)(a) GDPR (taking part in a church service can
 * reveal religious belief) and MUST be reviewed by a lawyer before a real deployment replaces it under a new [VERSION]. The statements
 * it makes about the system (nothing about attendance is stored, no recording, only the pulpit is broadcast) are exactly what the
 * server implements -- `EncounterPrivacyWatchTest` pins them.
 */
object EncounterConsentDisclaimer {
    const val VERSION: String = "2026-10-06.v1"

    val HEADLINE: String = "Sie nehmen an einem Gottesdienst oder einer Andacht teil."

    val KEY_POINTS: List<String> =
        listOf(
            "Wer anwesend ist, wird nicht gespeichert. Die Anwesenheit besteht nur, solange Sie im Raum sind, und wird beim " +
                "Verlassen oder nach dem Ende der Veranstaltung gelöscht. Es gibt keine Teilnahmeliste und keinen Verlauf.",
            "Es wird nichts aufgezeichnet. Übertragen wird nur die Kanzel, Ihr eigenes Bild und Ihr Ton werden nicht " +
                "gesendet. Andere Anwesende sehen höchstens Ihren Namen, und nur solange Sie im Raum sind.",
        )

    private val DETAIL: String =
        """
        Besondere Kategorie von Daten: Die Teilnahme an einem Gottesdienst kann Rückschlüsse auf religiöse Überzeugungen zulassen. Das
        sind besondere Kategorien personenbezogener Daten (Art. 9 DSGVO). Wir verarbeiten sie nur, wenn Sie ausdrücklich einwilligen
        (Art. 9 Abs. 2 lit. a DSGVO).

        Was gespeichert wird: Als Nachweis Ihrer Einwilligung speichern wir die Version dieses Textes, dessen Prüfsumme und das Datum
        Ihrer Bestätigung, ohne Angabe von Raum oder Uhrzeit. Sonst wird nichts über Ihre Teilnahme gespeichert.

        Ihre Kontrolle: Sie können den Raum jederzeit verlassen. Ihre Einwilligung können Sie jederzeit mit Wirkung für die Zukunft
        widerrufen, indem Sie sich an die verantwortliche Organisation wenden. Die Rechtmäßigkeit der bisherigen Verarbeitung bleibt
        davon unberührt.

        Dieser Hinweis ist ein Entwurf und stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text unter einer neuen
        Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
        """.trimIndent()

    val TEXT: String = HEADLINE + "\n\n" + KEY_POINTS.joinToString("\n\n") + "\n\n" + DETAIL

    /** `SHA-256` over `"$VERSION\n$TEXT"`, byte-for-byte the shape of every other disclaimer in this codebase. */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /** `true` iff [version] equals [VERSION] AND [sha256] equals [SHA256] (constant-time compare; a malformed hash is a non-match, never thrown). */
    fun matches(
        version: String,
        sha256: String,
    ): Boolean {
        if (version != VERSION) return false
        val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
        return MessageDigest.isEqual(provided, hexToBytes(SHA256))
    }

    private fun sha256Hex(input: String): String {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex character" }
            ((high shl 4) + low).toByte()
        }
    }
}
