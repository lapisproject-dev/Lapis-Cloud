package network.lapis.cloud.server.payment.bankstatement

import network.lapis.cloud.shared.domain.BankCsvDialect

/** Which logical field maps to which possible header-name spelling(s), per [BankCsvDialect]. */
internal data class DialectColumns(
    val bookingDate: List<String>,
    val valueDate: List<String>,
    val amount: List<String>,
    val currency: List<String>,
    val counterpartyName: List<String>,
    val counterpartyIban: List<String>,
    val purpose: List<String>,
    val endToEndReference: List<String>,
    val bookingText: List<String>,
)

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Per-dialect CSV header signatures and column-name alias
 * sets.
 *
 * **Only [BankCsvDialect.SPARKASSE_CAMT] and [BankCsvDialect.GENERIC] are actually recognized this
 * wave** (plan OF-2) -- [BankCsvDialect.VR_BANK]/[BankCsvDialect.DKB]/[BankCsvDialect.POSTBANK]/
 * [BankCsvDialect.COMDIRECT] exist as enum literals for a future wave to fill in, but carry no
 * entry in [REQUIRED_SIGNATURE] and are therefore NEVER matched by [matchDialect] -- a *guessed*
 * header signature for an institute this wave has no real (anonymized) export sample for would
 * risk silently shifting columns and booking the wrong amount, which is strictly worse than
 * falling through to [BankCsvDialect.GENERIC] or an honest [FormatDetection.Unrecognized]
 * rejection. Add a signature here (and to [columnsFor]) once a real export sample exists.
 */
internal object BankCsvDialects {
    val SPARKASSE_CAMT_COLUMNS =
        DialectColumns(
            bookingDate = listOf("Buchungstag"),
            valueDate = listOf("Valutadatum"),
            amount = listOf("Betrag"),
            currency = listOf("Waehrung", "Währung"),
            counterpartyName = listOf("Beguenstigter/Zahlungspflichtiger", "Begünstigter/Zahlungspflichtiger"),
            counterpartyIban = listOf("Kontonummer/IBAN"),
            purpose = listOf("Verwendungszweck"),
            endToEndReference = listOf("Kundenreferenz (End-to-End)"),
            bookingText = listOf("Buchungstext"),
        )

    /** Broad, best-effort alias set -- the auffangnetz for an institute not (yet) individually catalogued. */
    val GENERIC_COLUMNS =
        DialectColumns(
            bookingDate = listOf("Buchungstag", "Buchungsdatum", "Datum"),
            valueDate = listOf("Valutadatum", "Wertstellung"),
            amount = listOf("Betrag", "Umsatz"),
            currency = listOf("Waehrung", "Währung"),
            counterpartyName =
                listOf(
                    "Beguenstigter/Zahlungspflichtiger",
                    "Begünstigter/Zahlungspflichtiger",
                    "Name",
                    "Auftraggeber/Empfaenger",
                    "Auftraggeber/Empfänger",
                ),
            counterpartyIban = listOf("Kontonummer/IBAN", "IBAN"),
            purpose = listOf("Verwendungszweck", "Buchungstext"),
            endToEndReference = listOf("Kundenreferenz (End-to-End)", "Referenz"),
            bookingText = listOf("Buchungstext", "Vorgang"),
        )

    /** Pflicht-Spaltennamen, ALLE muessen im Header vorhanden sein, damit dieser Dialekt zutrifft. Reihenfolge der Map-Eintraege ist Pruef-Reihenfolge -- der spezifischere Dialekt (SPARKASSE_CAMT) wird VOR dem breiten Fallback (GENERIC) geprueft. */
    private val REQUIRED_SIGNATURE: Map<BankCsvDialect, List<String>> =
        linkedMapOf(
            BankCsvDialect.SPARKASSE_CAMT to listOf("Buchungstag", "Valutadatum", "Betrag", "Verwendungszweck", "Buchungstext"),
            BankCsvDialect.GENERIC to listOf("Buchungstag", "Betrag", "Verwendungszweck"),
        )

    fun matchDialect(headerFields: List<String>): BankCsvDialect? {
        val trimmed = headerFields.map { it.trim() }.toSet()
        REQUIRED_SIGNATURE.forEach { (dialect, requiredColumns) ->
            if (requiredColumns.all { it in trimmed }) return dialect
        }
        return null
    }

    fun columnsFor(dialect: BankCsvDialect): DialectColumns =
        when (dialect) {
            BankCsvDialect.SPARKASSE_CAMT -> SPARKASSE_CAMT_COLUMNS
            BankCsvDialect.GENERIC -> GENERIC_COLUMNS
            BankCsvDialect.VR_BANK, BankCsvDialect.DKB, BankCsvDialect.POSTBANK, BankCsvDialect.COMDIRECT ->
                error("$dialect is not catalogued this wave -- see class KDoc (plan OF-2)")
        }
}
