package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * The versioned, hashed legal-risk disclaimer an ADMIN must be shown -- and echo back verbatim
 * (via [matches]) -- before [SepaService.enableSepaDebit] will flip
 * `OrganizationSettings.sepaDebitEnabled` on. Exact mirror of [AuctionComplianceDisclaimer] --
 * see that object's own KDoc for the full mechanism this one reuses unchanged.
 *
 * **Legal-verification disclaimer, same class as [AuctionComplianceDisclaimer]/
 * [PartyDonationComplianceCalculator]'s own KDoc**: [TEXT] below names the risk areas identified as
 * relevant to SEPA-Lastschrift at the time it was written (vault plan "Lapis Cloud V1.2 --
 * Zahlungsverkehr" § 2.4). **This is NOT a reviewed legal conclusion and NOT automated
 * Rechtsberatung.**
 *
 * Welle V1.2.1 "Zahlungs-Fundament" ships ONLY this disclaimer and the enable/disable gate --
 * `sepaDebitEnabled` has no real functionality behind it yet (mandate management/pain.008
 * generation/batch runs are V1.2.2). The gate exists now so that sub-wave finds it already built
 * and reviewed.
 */
object SepaComplianceDisclaimer {
    const val VERSION: String = "2026-08-19.v1"

    val TEXT: String =
        """
        Rechtshinweis zum SEPA-Lastschrifteinzug für Mitgliedsbeiträge

        Bevor Sie den SEPA-Lastschrifteinzug für Ihre Organisation aktivieren, bestätigen Sie, dass
        folgende Risikobereiche geprüft und ggf. mit eigener rechtlicher Beratung geklärt wurden:

        - Gläubiger-Identifikationsnummer (Creditor Identifier): eine gültige, bei der Deutschen
          Bundesbank beantragte Gläubiger-ID ist zwingende Voraussetzung für jede SEPA-Lastschrift.
        - Mandatsschriftform/-textform: die rechtlichen Anforderungen an ein wirksames
          SEPA-Lastschriftmandat (Unterschrift bzw. zulässige elektronische Form) müssen erfüllt sein.
        - Vorabankündigungsfrist: die gesetzliche 14-Tage-Frist (bzw. eine wirksam verkürzte Frist
          gemäß Satzung/Beitragsordnung) vor jedem Einzug muss eingehalten werden.
        - Aufbewahrungspflichten für erteilte Mandate über deren gesamte Gültigkeitsdauer und darüber
          hinaus.
        - Rücklastschriftentgelt-Weiterbelastung: ob und in welcher Höhe ein von der Bank erhobenes
          Rücklastschriftentgelt an das Mitglied weiterbelastet werden darf.
        - Satzungs-/Beitragsordnungsgrundlage: eine ausreichende Ermächtigung zum Lastschrifteinzug
          in der Satzung oder Beitragsordnung der Organisation.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Prüfung durch eine
        Rechtsanwältin/einen Rechtsanwalt. Die Plattform selbst nimmt keine rechtliche Einordnung vor
        und trifft keine automatisierte Entscheidung über die Zulässigkeit des Einzugs. Die
        Verantwortung für die Einhaltung aller einschlägigen Vorschriften liegt ausschließlich beim
        Betreiber der jeweiligen Organisation.
        """.trimIndent()

    /** `SHA-256` over `"$VERSION\n$TEXT"` -- see [AuctionComplianceDisclaimer.SHA256] KDoc. */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /** See [AuctionComplianceDisclaimer.matches] KDoc -- identical constant-time comparison contract. */
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
