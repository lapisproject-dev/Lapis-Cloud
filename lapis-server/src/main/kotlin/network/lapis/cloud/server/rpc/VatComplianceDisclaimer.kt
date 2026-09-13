package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)". The versioned, hashed legal-risk disclaimer an
 * ADMIN must be shown -- and echo back verbatim (via [matches]) -- before [VatService.enableVat]
 * will flip `OrganizationSettings.vatEnabled` on. Exact mirror of [DunningComplianceDisclaimer] --
 * see that object's own KDoc for the full mechanism this one reuses unchanged.
 *
 * **NOT automated Rechtsberatung** -- same "Selbstauskunft" framing [DunningComplianceDisclaimer]/
 * [AuctionComplianceDisclaimer] already establish. [TEXT] below names the risk areas identified as
 * relevant to the USt-Voranmeldung-Nachweishilfe feature at the time it was written (vault plan
 * "Umsetzungsplan V1.4.13").
 */
object VatComplianceDisclaimer {
    const val VERSION: String = "2026-09-13.v1"

    val TEXT: String =
        """
        Rechtshinweis zur USt-Voranmeldung (Nachweishilfe)

        Bevor Sie das Umsatzsteuer-Modul fuer Ihre Organisation aktivieren, bestaetigen Sie, dass Sie
        folgende Punkte geprueft und ggf. mit eigener steuerlicher Beratung geklaert haben:

        - Diese Funktion uebermittelt KEINE Daten an ELSTER/ERiC. Sie ist ausschliesslich eine
          strukturierte Nachweishilfe aus in Lapis Cloud erfassten Buchungen fuer Vorstand und
          Steuerberatung -- keine Voranmeldung im Sinne des § 18 UStG.
        - Die Einstufung einer Buchung als "7 % (Zweckbetrieb)" ist Vorstands-Ermessen. Der
          Wettbewerbsvorbehalt (§ 12 Abs. 2 Nr. 8a UStG) wird von dieser Plattform NICHT geprueft --
          die Plattform validiert keine Kombination aus Gemeinnuetzigkeits-Sphaere und Steuersatz.
        - Kleinunternehmergrenzen (§ 19 UStG, Reform zum 01.01.2025): 25.000 EUR Vorjahresumsatz und
          100.000 EUR laufender Jahresumsatz. Das Ueberschreiten der laufenden Grenze beendet die
          Kleinunternehmereigenschaft SOFORT ab dem ueberschreitenden Umsatz, NICHT rueckwirkend
          fuer das ganze Jahr. Die Ueberwachung dieser Grenzen liegt beim Vorstand -- es findet KEINE
          automatische Schwellenwertpruefung statt.
        - Buchungsbetraege bleiben in Lapis Cloud BRUTTO. Es findet KEINE automatische
          USt-Splitbuchung statt (kein Netto-Erloes gegen eine USt-Verbindlichkeit). Die
          Vier-Sphaeren-Ergebnisrechnung zeigt Einnahmen deshalb weiterhin brutto inklusive USt und
          widerspricht der USt-Vorschau zwangslaeufig in der Zahlenhoehe -- beide Zahlen sind fuer
          sich richtig, sie beantworten verschiedene Fragen.
        - Buchungen mit einem Steuersatz von 7 % oder 19 % werden von den lexoffice-/sevDesk-
          Live-Exporten AUSGESCHLOSSEN (blockiert, nicht stillschweigend mit 0 % uebertragen). Der
          DATEV-Dateiexport uebertraegt weiterhin keinen BU-Schluessel.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Pruefung durch eine
        Steuerberaterin/einen Steuerberater. Die Plattform selbst nimmt keine steuerliche Einordnung
        vor und trifft keine automatisierte Entscheidung ueber die Zulaessigkeit eines Steuersatzes
        im Einzelfall. Die Verantwortung fuer die Einhaltung aller einschlaegigen Vorschriften liegt
        ausschliesslich beim Betreiber der jeweiligen Organisation bzw. dessen Steuerberatung.
        """.trimIndent()

    /** `SHA-256` over `"$VERSION\n$TEXT"` -- see [SepaComplianceDisclaimer.SHA256] KDoc. */
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
