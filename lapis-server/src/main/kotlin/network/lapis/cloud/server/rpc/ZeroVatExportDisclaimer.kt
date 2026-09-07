package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.displayName
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
 *
 * **Welle V1.4.5.4 "sevDesk-Live-Anbindung"**: [textFor]/[sha256For]/[matches] wurden von
 * Konstanten auf Funktionen umgestellt, PARAMETRISIERT über [AccountingExportProvider] -- der
 * Text nennt jetzt den tatsächlichen Ziel-Anbieter über [network.lapis.cloud.shared.domain
 * .displayName] statt pauschal "z. B. Lexware Office" zu sagen. **Wichtiger Hinweis zur
 * Einordnung**: die Quittung ist SCHON HEUTE strukturell pro Anbieter --
 * `accounting_export_connection` trägt `uq_accounting_export_connection_provider`, jede
 * Verbindung ihre eigenen `zero_vat_*`-Spalten. Diese Parametrisierung schließt also KEINE
 * Sicherheitslücke, sie ist eine reine Ehrlichkeits-/UX-Verbesserung: ohne sie würde ein
 * sevDesk-Nutzer einen Hinweistext lesen, der von "Lexware Office" spricht, obwohl seine Belege
 * an sevDesk gehen. [VERSION] bleibt anbieterübergreifend EIN Wert (bewusst NICHT
 * providerskopiert, siehe Umsetzungsplan §5 "Offene Frage (2)") -- der [VERSION]-Bump auf
 * `"2026-09-08.v2"` bedeutet, dass jede bestehende (lexoffice-)Verbindung den Hinweis einmalig
 * erneut quittieren muss, sobald sie das nächste Mal `previewExport`/`startExport` aufruft
 * (`buildPreview` verlangt ausdrücklich "acknowledgment must match the CURRENT version").
 */
object ZeroVatExportDisclaimer {
    const val VERSION: String = "2026-09-08.v2"

    private val TEXT_TEMPLATE: String =
        """
        Hinweis zur Umsatzsteuer beim Buchhaltungs-Live-Export

        Lapis Cloud fuehrt in seiner eigenen Buchhaltung keine Umsatzsteuer-Schluessel. Jeder an
        %s uebertragene Beleg wird deshalb AUSNAHMSLOS mit 0 %% Umsatzsteuer (Steuerbetrag 0,00 EUR
        je Position) uebermittelt -- unabhaengig davon, ob der zugrunde liegende Vorgang
        tatsaechlich umsatzsteuerfrei ist.

        Bevor Sie den Live-Export fuer Ihre Organisation aktivieren, bestaetigen Sie, dass Sie
        geprueft haben:

        - Ob Ihre Organisation umsatzsteuerpflichtige Einnahmen hat (z. B. aus einem
          wirtschaftlichen Geschaeftsbetrieb) und falls ja, dass die 0-%%-Uebertragung fuer diese
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

    /** Der vollständige Hinweistext für [provider] -- der Platzhalter im Template wird durch
     * "einen externen Buchhaltungsdienst (${provider.displayName})" ersetzt, sodass der Nutzer bei
     * jedem Anbieter tatsächlich eine neue Aussage über ein neues Ziel liest. */
    fun textFor(provider: AccountingExportProvider): String =
        TEXT_TEMPLATE.format("einen externen Buchhaltungsdienst (${provider.displayName})")

    /** `sha256Hex("$VERSION\n${textFor(provider)}")` -- unterscheidet sich zwangsläufig pro
     * Anbieter, weil [textFor] den Anbieternamen einsetzt. */
    fun sha256For(provider: AccountingExportProvider): String = sha256Hex("$VERSION\n${textFor(provider)}")

    fun matches(
        provider: AccountingExportProvider,
        version: String,
        sha256: String,
    ): Boolean {
        if (version != VERSION) return false
        val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
        val expected = hexToBytes(sha256For(provider))
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
