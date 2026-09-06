package network.lapis.cloud.server.payment.bankstatement

import network.lapis.cloud.server.bootstrap.DelimitedCsvParser

/** Header-name -> column-index lookup, one logical field may accept several alias spellings (see [DialectColumns]). Deliberately its own tiny class rather than reusing `network.lapis.cloud.server.bootstrap.CsvHeader` -- that class's alternate-spelling table is hard-coded to the member-import column set, not this domain's. */
private class AliasedCsvHeader(
    headerRow: List<String>,
) {
    private val indexByName: Map<String, Int> = headerRow.mapIndexed { index, name -> name.trim() to index }.toMap()

    fun indexOf(aliases: List<String>): Int? = aliases.firstNotNullOfOrNull { indexByName[it] }

    fun require(
        aliases: List<String>,
        canonicalName: String,
    ): Int = indexOf(aliases) ?: error("Required CSV column '$canonicalName' not found in header")
}

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Turns a [FormatDetection.CsvDetected] into a
 * [ParsedStatement], using [network.lapis.cloud.server.bootstrap.DelimitedCsvParser] (reused, not
 * reimplemented) for tokenizing.
 *
 * **Vorspann/Nachspann**: rows between the file start and [FormatDetection.CsvDetected
 * .headerRowIndex] are simply never read (the header search already skipped past them). AFTER the
 * header, a row that is SHORTER than the header row's own field count is treated as a Nachspann
 * (trailer/summary line, e.g. a "Summe" row some exports append) and ends the data section --
 * unless it still carries a value in the amount column, in which case it is genuinely malformed
 * and the WHOLE import is rejected (Alles-oder-nichts, see class KDoc on
 * [BankStatementImportService]).
 */
internal object BankCsvParser {
    fun parse(detection: FormatDetection.CsvDetected): ParsedStatement {
        val table = DelimitedCsvParser.parse(text = detection.text, delimiter = detection.delimiter)
        check(detection.headerRowIndex < table.size) { "headerRowIndex out of range for the re-parsed table" }
        val headerRow = table[detection.headerRowIndex].map { it.trim() }
        val columns = BankCsvDialects.columnsFor(detection.dialect)
        val header = AliasedCsvHeader(headerRow)

        val bookingDateIdx = header.require(aliases = columns.bookingDate, canonicalName = "Buchungstag")
        val amountIdx = header.require(aliases = columns.amount, canonicalName = "Betrag")
        val valueDateIdx = header.indexOf(columns.valueDate)
        val currencyIdx = header.indexOf(columns.currency)
        val counterpartyNameIdx = header.indexOf(columns.counterpartyName)
        val counterpartyIbanIdx = header.indexOf(columns.counterpartyIban)
        val purposeIdx = header.indexOf(columns.purpose)
        val endToEndIdx = header.indexOf(columns.endToEndReference)
        val bookingTextIdx = header.indexOf(columns.bookingText)

        val lines = mutableListOf<ParsedLine>()
        val dataRows = table.drop(detection.headerRowIndex + 1)
        for ((offset, row) in dataRows.withIndex()) {
            val lineNumber = detection.headerRowIndex + 2 + offset // 1-based, human-facing
            if (row.isEmpty()) continue // a genuinely blank line -- not a Nachspann, not data, just skipped

            if (row.size < headerRow.size) {
                val allBlank = row.all { it.isBlank() }
                val looksLikeTrailer = row.any { field -> TRAILER_KEYWORDS.any { keyword -> field.contains(keyword, ignoreCase = true) } }
                if (allBlank || looksLikeTrailer) {
                    // A trailer/summary line (or a stray blank-ish short row) -- stops the data
                    // section entirely (any row after it is presumed part of the same trailer).
                    break
                }
                throw BankStatementParseException(
                    message = "Zeile $lineNumber hat zu wenige Felder (${row.size} statt ${headerRow.size})",
                    lineNumber = lineNumber,
                    rawLineExcerpt = row.joinToString(";").take(MAX_EXCERPT_LENGTH),
                )
            }

            val bookingDate =
                parseFlexibleGermanDate(row.getOrElse(bookingDateIdx) { "" })
                    ?: throw BankStatementParseException(
                        message = "Zeile $lineNumber: Buchungstag nicht lesbar",
                        lineNumber = lineNumber,
                        rawLineExcerpt = row.joinToString(";").take(MAX_EXCERPT_LENGTH),
                    )
            val amount =
                parseGermanAmount(row.getOrElse(amountIdx) { "" })
                    ?: throw BankStatementParseException(
                        message = "Zeile $lineNumber: Betrag nicht lesbar",
                        lineNumber = lineNumber,
                        rawLineExcerpt = row.joinToString(";").take(MAX_EXCERPT_LENGTH),
                    )
            val valueDate = valueDateIdx?.let { row.getOrNull(it) }?.let { parseFlexibleGermanDate(it) }

            lines +=
                ParsedLine(
                    bookingDate = bookingDate,
                    valueDate = valueDate,
                    amount = amount,
                    // Review fix (MINOR): capped like every other free-text CSV field below --
                    // bank_statement_line.currency is VARCHAR(3) NOT NULL, and an uncapped value from
                    // a GENERIC-dialect export (e.g. a "Waehrung" column spelled out as "Euro") would
                    // otherwise reach the Phase 1 insertIgnore raw and fail with an uncaught
                    // ExposedSQLException ("value too long"), rolling back the whole import with a
                    // 500 instead of the usual, diagnosable 422/warning path.
                    currency =
                        currencyIdx?.let {
                            row
                                .getOrNull(it)
                                ?.trim()
                                ?.ifEmpty { null }
                                ?.take(3)
                        } ?: DEFAULT_CURRENCY,
                    counterpartyName = counterpartyNameIdx?.let { row.getOrNull(it)?.trim()?.ifEmpty { null } },
                    counterpartyIban =
                        counterpartyIbanIdx?.let {
                            row
                                .getOrNull(it)
                                ?.trim()
                                ?.replace(" ", "")
                                ?.ifEmpty { null }
                        },
                    purpose =
                        purposeIdx?.let {
                            row
                                .getOrNull(it)
                                ?.trim()
                                ?.ifEmpty { null }
                                ?.take(MAX_PURPOSE_LENGTH)
                        },
                    // Review fix (MINOR): capped like every other free-text CSV field above --
                    // bank_statement_line.end_to_end_reference is VARCHAR(140) NOT NULL-capable, and
                    // payment_transaction.provider_payment_id (which postOneLine feeds this SAME
                    // value into, uncapped, on the auto-post path) is only VARCHAR(255) -- 140 is the
                    // tighter of the two, so capping here bounds both. An uncapped value (e.g. a
                    // GENERIC-dialect export whose "Kundenreferenz (End-to-End)" column holds a whole
                    // SEPA remittance block) previously reached the INSERT uncapped, failed with
                    // "value too long", and that failure was then misdiagnosed as "already booked"
                    // (see BankStatementImportService.postOneLine's own review-fix comment).
                    endToEndReference =
                        endToEndIdx?.let {
                            row
                                .getOrNull(it)
                                ?.trim()
                                ?.ifEmpty { null }
                                ?.take(MAX_END_TO_END_REFERENCE_LENGTH)
                        },
                    bookingText = bookingTextIdx?.let { row.getOrNull(it)?.trim()?.ifEmpty { null } },
                )
        }

        return ParsedStatement(
            accountIban = null, // a plain CSV export carries no statement-level IBAN of its own
            statementFrom = lines.minOfOrNull { it.bookingDate },
            statementTo = lines.maxOfOrNull { it.bookingDate },
            openingBalance = null,
            closingBalance = null,
            lines = lines,
        )
    }

    private const val DEFAULT_CURRENCY = "EUR"
    private const val MAX_EXCERPT_LENGTH = 200
    private const val MAX_PURPOSE_LENGTH = 2000
    private const val MAX_END_TO_END_REFERENCE_LENGTH = 140

    /** Case-insensitive substrings that mark a short row as a trailer/summary line rather than malformed data -- see class KDoc "Vorspann/Nachspann". */
    private val TRAILER_KEYWORDS = listOf("summe", "saldo", "gesamt", "kontostand", "endsumme")
}
