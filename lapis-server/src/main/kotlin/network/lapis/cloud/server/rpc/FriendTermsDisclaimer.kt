package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * The versioned, hashed FRIEND terms of use a registrant must be shown -- and echo back verbatim
 * (via [matches]) -- before [RegistrationService.registerFriend] will create their
 * `Member(status=FRIEND)`+`Account`. Same shape/mechanism as [MembershipAgreementDisclaimer], but
 * a DIFFERENT document for a DIFFERENT, smaller legal act: a FRIEND does NOT enter the Satzung/
 * Beitritts contract -- see [network.lapis.cloud.shared.domain.MemberStatus.FRIEND] KDoc for the
 * scope (video-conference access only, no Beitragspflicht, no governance/accounting/LTR rights).
 *
 * **Legal-verification disclaimer, same class as [MembershipAgreementDisclaimer]**: [TEXT] below
 * is a placeholder summary a real deployment MUST review/replace (under a NEW [VERSION]) before
 * relying on it. In particular it discloses, honestly, that [FriendRegistrationInput.displayName]
 * is NOT identity-verified and is visible to every participant of any conference room the FRIEND
 * joins -- the honest disclosure the non-verification design decision requires.
 *
 * [VERSION]/[TEXT]/[SHA256] are all `const`/`val` (immutable at runtime) -- a future wording change
 * requires a NEW [VERSION] string, never an in-place edit of [TEXT] under the same version (that
 * would silently invalidate the audit trail's claim that a given registrant saw a given version's
 * exact wording).
 */
object FriendTermsDisclaimer {
    const val VERSION: String = "2026-08-15.v1"

    val TEXT: String =
        """
        Nutzungsbedingungen fuer Freund-Konten (Zusammenfassung)

        Ein Freund-Konto ist KEINE Mitgliedschaft in der Organisation. Mit der Registrierung
        bestaetigen Sie, folgende Punkte zur Kenntnis genommen zu haben:

        - Ein Freund-Konto begruendet keine Mitgliedschaft, keine Beitragspflicht und keine
          Mitwirkungs-/Stimmrechte in der Organisation.
        - Der Funktionsumfang ist derzeit ausschliesslich auf die Teilnahme an Videokonferenzen
          beschraenkt, sofern der jeweilige Konferenzraum von seiner Moderation dafuer freigegeben
          wurde.
        - Ihr angezeigter Name wird NICHT ueberprueft und ist fuer alle uebrigen Teilnehmenden
          einer Videokonferenz sichtbar.
        - Sie koennen jederzeit einen Antrag auf echte Mitgliedschaft stellen; Ihr Freund-Konto
          bleibt dabei erhalten, bis der Vorstand ueber den Antrag entschieden hat.
        - Ihre personenbezogenen Daten werden gemaess der Datenschutzerklaerung der Organisation
          verarbeitet; Sie koennen jederzeit deren Export oder Loeschung verlangen.

        Dieser Hinweis stellt KEINE Rechtsberatung dar. Ein reales Deployment MUSS diesen Text vor
        dem produktiven Einsatz pruefen und bei Bedarf anpassen (unter einer neuen VERSION).
        """.trimIndent()

    /**
     * `SHA-256` over `"$VERSION\n$TEXT"` -- a fresh [MessageDigest] instance PER CALL (thread-safe,
     * see the codebase's standing security checklist "Kryptografie: MessageDigest neue Instanz pro
     * Aufruf"), computed once at class-init time since [VERSION]/[TEXT] are themselves immutable.
     */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /**
     * `true` iff [version] equals [VERSION] AND [sha256] equals [SHA256] (case-insensitive hex
     * comparison, constant-time via [MessageDigest.isEqual] so a malformed/tampered hash cannot be
     * distinguished from a correct-length-wrong-value one by timing). A malformed (non-hex, wrong
     * length) [sha256] is treated as a non-match, never thrown.
     */
    fun matches(
        version: String,
        sha256: String,
    ): Boolean {
        if (version != VERSION) return false
        val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
        val expected = hexToBytes(SHA256)
        return MessageDigest.isEqual(provided, expected)
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
            require(high >= 0 && low >= 0) { "invalid hex character in '$hex'" }
            ((high shl 4) + low).toByte()
        }
    }
}
