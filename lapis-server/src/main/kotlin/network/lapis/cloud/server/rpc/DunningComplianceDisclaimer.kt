package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". The versioned, hashed legal-risk disclaimer an ADMIN
 * must be shown -- and echo back verbatim (via [matches]) -- before [DunningService.enableDunning]
 * will flip `OrganizationSettings.dunningEnabled` on. Exact mirror of [SepaComplianceDisclaimer] --
 * see that object's own KDoc for the full mechanism this one reuses unchanged.
 *
 * **Legal-verification disclaimer, same class as [SepaComplianceDisclaimer]**: [TEXT] below names
 * the risk areas identified as relevant to an automated dunning process at the time it was written
 * (vault plan "Umsetzungsplan V1.2.7"). **This is NOT a reviewed legal conclusion and NOT
 * automated Rechtsberatung** -- the dunning letter wording itself
 * ([network.lapis.cloud.server.pdf.MahnungPdfGenerator]) is likewise not anwaltlich geprueft.
 */
object DunningComplianceDisclaimer {
    const val VERSION: String = "2026-08-26.v1"

    val TEXT: String =
        """
        Rechtshinweis zum automatisierten Mahnwesen fuer Mitgliedsbeitraege

        Bevor Sie das automatisierte Mahnwesen fuer Ihre Organisation aktivieren, bestaetigen Sie,
        dass folgende Risikobereiche geprueft und ggf. mit eigener rechtlicher Beratung geklaert
        wurden:

        - Verzug (§ 286 BGB): eine erste Zahlungserinnerung begruendet den Verzug in aller Regel
          erst -- eine Mahngebuehr auf diese erste Stufe ist daher unzulaessig. Diese Plattform
          verhindert das strukturell, indem eine Gebuehr auf der ersten konfigurierten Mahnstufe
          abgelehnt wird.
        - Mahngebuehren duerfen nur tatsaechlich entstandene Kosten abbilden, keinen pauschalen
          Aufschlag. Die konfigurierbare Obergrenze (25,00 EUR je Mahnung) ist eine technische
          Kappung, keine rechtliche Freigabe jeder Hoehe darunter.
        - Verzugszinsen werden von dieser Software NICHT berechnet und NICHT ausgewiesen -- ein
          etwaiger Anspruch darauf muss gesondert und manuell geltend gemacht werden.
        - Der Brieftext der automatisch erzeugten Mahnungen ist eine fachlich plausible
          Formulierung, aber NICHT anwaltlich geprueft.
        - Postversand (falls aktiviert): die Postanschrift eines Mitglieds wird an Letterxpress, einen
          Drittanbieter, uebermittelt -- ein Auftragsverarbeitungsvertrag (AVV) mit Letterxpress muss
          in Kraft sein, bevor automatisierter Postversand produktiv genutzt wird.
        - Satzungs-/Beitragsordnungsgrundlage: eine ausreichende Ermaechtigung zum automatisierten
          Mahnverfahren in der Satzung oder Beitragsordnung der Organisation.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Pruefung durch eine
        Rechtsanwaeltin/einen Rechtsanwalt. Die Plattform selbst nimmt keine rechtliche Einordnung vor
        und trifft keine automatisierte Entscheidung ueber die Zulaessigkeit einer Mahnung im
        Einzelfall. Die Verantwortung fuer die Einhaltung aller einschlaegigen Vorschriften liegt
        ausschliesslich beim Betreiber der jeweiligen Organisation.
        """.trimIndent()

    /** `SHA-256` over `"$VERSION\n$TEXT"` -- see [network.lapis.cloud.server.rpc.SepaComplianceDisclaimer.SHA256] KDoc. */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /** See [SepaComplianceDisclaimer.matches] KDoc -- identical constant-time comparison contract. */
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
