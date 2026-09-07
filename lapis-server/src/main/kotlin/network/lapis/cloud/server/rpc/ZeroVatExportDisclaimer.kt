package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- the versioned, hashed legal-risk disclaimer a
 * TREASURER/ADMIN must be shown -- and echo back verbatim (via [matches]) -- before
 * [network.lapis.cloud.server.rpc.AccountingExportService.startExport] will accept the first
 * export for a newly connected provider. Same shape/mechanism as [AuctionComplianceDisclaimer]/
 * [DunningComplianceDisclaimer] -- see [AuctionComplianceDisclaimer] KDoc for the full rationale
 * this reuses unchanged.
 *
 * **Why this disclaimer exists at all**: every voucher this wave ever sends travels with
 * `taxType = "gross"` and `taxRatePercent = 0` on every line (see
 * `network.lapis.cloud.server.accounting.export.lexoffice.LexofficeVoucherMapper` KDoc) -- Lapis
 * Cloud carries no USt-Schlüssel anywhere in its own data model, so every exported voucher makes an
 * explicit, unconditional "0% Umsatzsteuer" claim regardless of the organization's REAL tax
 * situation. A Verein/Partei that is actually subject to Umsatzsteuer on some of its income (e.g. a
 * wirtschaftlicher Geschäftsbetrieb) would have that fact silently misrepresented in the external
 * bookkeeping unless a human -- who understands the organization's own tax posture -- has
 * consciously accepted this before the first voucher is ever sent.
 *
 * **NOT automated Rechtsberatung** -- same "Selbstauskunft" framing [AuctionComplianceDisclaimer]
 * already establishes. Quittiert EINMAL pro Verbindung (`AccountingExportConnectionDto
 * .zeroVatAcknowledged`), nicht bei jedem einzelnen Export-Lauf.
 */
object ZeroVatExportDisclaimer {
    const val VERSION: String = "2026-09-07.v1"

    val TEXT: String =
        """
        Hinweis zur Umsatzsteuer beim Buchhaltungs-Live-Export

        Lapis Cloud fuehrt in seiner eigenen Buchhaltung keine Umsatzsteuer-Schluessel. Jeder an
        einen externen Buchhaltungsdienst (z. B. Lexware Office) uebertragene Beleg wird deshalb
        AUSNAHMSLOS mit 0 % Umsatzsteuer (Steuerbetrag 0,00 EUR je Position) uebermittelt --
        unabhaengig davon, ob der zugrunde liegende Vorgang tatsaechlich umsatzsteuerfrei ist.

        Bevor Sie den Live-Export fuer Ihre Organisation aktivieren, bestaetigen Sie, dass Sie
        geprueft haben:

        - Ob Ihre Organisation umsatzsteuerpflichtige Einnahmen hat (z. B. aus einem
          wirtschaftlichen Geschaeftsbetrieb) und falls ja, dass die 0-%-Uebertragung fuer diese
          Faelle NICHT ausreicht und die betroffenen Belege manuell in der Zielsoftware korrigiert
          werden muessen.
        - Dass die uebertragenen Belege ausschliesslich als Rohdaten fuer Ihre Buchhaltung bzw.
          Ihren Steuerberater dienen und keine eigenstaendige steuerliche Bewertung durch Lapis
          Cloud stattfindet.
        - Dass die Verantwortung fuer die materielle Richtigkeit der Buchungen -- einschliesslich
          der Umsatzsteuer -- ausschliesslich bei Ihrer Organisation bzw. deren Steuerberater liegt.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Pruefung durch eine
        Steuerberaterin/einen Steuerberater. Die Plattform selbst nimmt keine steuerliche Einordnung
        vor.
        """.trimIndent()

    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

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
