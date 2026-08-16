package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * The versioned, hashed Beitrittsvertrag/Satzungs-text a registrant must be shown -- and echo back
 * verbatim (via [matches]) -- before [RegistrationService.registerApplication] will create their
 * `Member(status=APPLICATION)`+`Account`. Same shape/mechanism as [AuctionComplianceDisclaimer]
 * (see [network.lapis.cloud.shared.rpc.IAuctionService] KDoc "The disclaimer-acknowledgment
 * mechanism"); the reasoning for using this exact pattern here is stronger than for auctions,
 * not weaker -- membership in this codebase's own concept is explicitly a private-law contract,
 * not a mere account signup:
 *
 * > "Mitgliedschaft ist Vertrag... Eintritt und Austritt sind ausschliesslich
 * > Willenserklaerungen der Vertragspartner." (project concept doc, "Rechtlicher Rahmen")
 *
 * **Legal-verification disclaimer, same class as [AuctionComplianceDisclaimer]/
 * [PartyDonationComplianceCalculator]/[DsgvoComplianceService]'s own KDoc**: [TEXT] below is a
 * placeholder summary of the kind of Satzungs-/Beitrittsbedingungen content a real organization
 * would substitute here -- **this is NOT the organization's actual, legally reviewed Satzung**,
 * and the platform performs no legal classification of its own. [TEXT] only documents that a
 * registrant was shown this exact wording before applying. A real deployment MUST replace [TEXT]
 * (under a NEW [VERSION]) with its own organization's actual, lawyer-reviewed Satzung and
 * Beitrittsbedingungen before relying on this for a real membership contract.
 *
 * [VERSION]/[TEXT]/[SHA256] are all `const`/`val` (immutable at runtime) -- a future wording
 * change requires a NEW [VERSION] string, never an in-place edit of [TEXT] under the same version
 * (that would silently invalidate the audit trail's claim that a given registrant saw a given
 * version's exact wording).
 */
object MembershipAgreementDisclaimer {
    const val VERSION: String = "2026-07-23.v1"

    val TEXT: String =
        """
        Beitrittsvertrag und Satzung (Zusammenfassung)

        Mit Ihrer Mitgliedschaft schliessen Sie einen privatrechtlichen Vertrag mit der
        Organisation. Bevor Sie Ihre Mitgliedschaft beantragen, bestaetigen Sie, folgende Punkte
        zur Kenntnis genommen zu haben:

        - Die Satzung der Organisation in ihrer jeweils aktuellen Fassung ist Grundlage der
          Mitgliedschaft und fuer Sie als Mitglied verbindlich.
        - Die Aufnahme als Mitglied erfolgt erst nach Pruefung und ausdruecklicher Zustimmung durch
          den Vorstand -- ein Anspruch auf Aufnahme besteht nicht.
        - Mit der Mitgliedschaft koennen Beitragspflichten gemaess der jeweils gueltigen
          Beitragsordnung verbunden sein.
        - Der Austritt ist jederzeit durch einseitige Erklaerung moeglich -- niemand kann daran
          gehindert werden, die Mitgliedschaft zu kuendigen. Fristen/Formanforderungen richten
          sich nach der Satzung.
        - Ihre personenbezogenen Daten werden gemaess der Datenschutzerklaerung der Organisation
          verarbeitet.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Pruefung durch eine
        Rechtsanwaeltin/einen Rechtsanwalt und keine tatsaechliche, rechtlich gepruefte Satzung
        einer realen Organisation. Die Plattform selbst nimmt keine rechtliche Einordnung vor und
        trifft keine automatisierte Entscheidung ueber die Aufnahme -- diese trifft ausschliesslich
        der Vorstand. Ein reales Deployment MUSS diesen Text durch die tatsaechliche, rechtlich
        geprueft Satzung und Beitrittsbedingungen der eigenen Organisation ersetzen (unter einer
        neuen VERSION), bevor er fuer eine echte Mitgliedschaft verwendet wird.
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
