package network.lapis.cloud.server.payment.bankstatement

import network.lapis.cloud.server.bootstrap.DelimitedCsvParser
import network.lapis.cloud.shared.domain.BankCsvDialect

/** Outcome of [BankStatementFormatDetector.detect]. */
internal sealed interface FormatDetection {
    data class Mt940Detected(
        val text: String,
    ) : FormatDetection

    data class CsvDetected(
        val text: String,
        val dialect: BankCsvDialect,
        val headerRowIndex: Int,
        val delimiter: Char,
    ) : FormatDetection

    /** [observedHeaderFields] is surfaced in the 422 rejection body so an operator can see WHY nothing matched -- never persisted, never logged (see `BankStatementImportService` KDoc "Privacy"). */
    data class Unrecognized(
        val observedHeaderFields: List<String>,
    ) : FormatDetection
}

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Decides MT940 vs. CSV, and for CSV which
 * [BankCsvDialect]/delimiter/header-row applies -- BEFORE any parsing happens, so a genuinely
 * unrecognized file is rejected with a clear diagnostic instead of silently misparsing.
 *
 * MT940 is detected by a `:20:` tag at the start of any of the first few lines (the mandatory
 * "Transaction Reference Number" field every MT940 message opens with). Everything else is
 * attempted as CSV: the first [MAX_PREAMBLE_LINES] rows are searched for a row whose fields
 * satisfy one of [BankCsvDialects.CATALOGUED]'s header signatures, trying `;` then `,` as the
 * delimiter for each candidate row. No match within that search window -> [FormatDetection
 * .Unrecognized], carrying the first candidate header row's own fields (not a fabricated example)
 * so the 422 response is genuinely diagnostic.
 *
 * **`headerRowIndex` is a LOGICAL CSV row index, not a physical line index (Review fix, MINOR)** --
 * [detect] parses the (per-delimiter) preamble window with [DelimitedCsvParser] itself, the exact
 * same RFC4180-aware tokenizer [BankCsvParser] later re-parses the WHOLE file with. Splitting the
 * preamble on physical newlines instead (as this used to do) disagrees with that logical-row index
 * the moment a quoted field anywhere in the preamble contains an embedded newline (a multi-line
 * address in a Vorspann, a `Verwendungszweck` with a line break) -- [BankCsvParser] would then
 * either index the wrong physical line as the header (silently wrong columns) or, if the offset
 * pushes `headerRowIndex` past the re-parsed table's actual size, fail its own `check()` with an
 * uncaught [IllegalStateException] (HTTP 500 instead of the intended 422). Only the MT940 sniff
 * above still looks at physical lines -- a `:20:` tag can never appear inside a quoted CSV field in
 * any file this detector would otherwise accept as CSV, so no such ambiguity exists there.
 */
internal object BankStatementFormatDetector {
    private const val MAX_PREAMBLE_LINES = 20
    private val CSV_DELIMITERS = charArrayOf(';', ',')

    fun detect(decoded: DecodedText): FormatDetection {
        val physicalPreambleLines =
            decoded.text
                .lineSequence()
                .take(MAX_PREAMBLE_LINES + 1)
                .toList()
        if (physicalPreambleLines.any { it.trimStart().startsWith(":20:") }) {
            return FormatDetection.Mt940Detected(text = decoded.text)
        }

        // One BOUNDED logical-row table per candidate delimiter -- same tokenizer, same input
        // [BankCsvParser] will re-parse with whichever delimiter [FormatDetection.CsvDetected]
        // below ends up returning, so `headerRowIndex` is guaranteed to index the SAME row there.
        //
        // Review fix (MEDIUM): this used to call `DelimitedCsvParser.parse` with no `maxRows`,
        // materializing the ENTIRE file as a `List<List<String>>` -- TWICE (once per delimiter
        // candidate), both held in memory simultaneously via `tablesByDelimiter`, even though only
        // the first `MAX_PREAMBLE_LINES + 1` logical rows below are ever inspected. A multi-megabyte
        // annual export (the route's own upload cap allows up to `MAX_UPLOAD_BYTES`, and the
        // line-count cap only rejects AFTER this detector -- and `BankCsvParser` -- have both already
        // run) turned every upload into a heap spike proportional to the whole file, three full
        // parses deep (this detector's two, plus `BankCsvParser`'s own later one). Passing `maxRows`
        // here caps the detector's OWN two parses to the preamble window it actually searches --
        // `DelimitedCsvParser.parse` stops scanning `text` itself the instant that many logical rows
        // are complete, rather than truncating an already-fully-built table.
        val tablesByDelimiter =
            CSV_DELIMITERS.associateWith { delimiter ->
                DelimitedCsvParser.parse(text = decoded.text, delimiter = delimiter, maxRows = MAX_PREAMBLE_LINES + 1)
            }
        val searchRowCount = tablesByDelimiter.values.maxOfOrNull { it.size } ?: 0

        var firstCandidateFields: List<String>? = null
        for (rowIndex in 0 until searchRowCount) {
            for (delimiter in CSV_DELIMITERS) {
                val fields = tablesByDelimiter.getValue(delimiter).getOrNull(rowIndex) ?: continue
                if (fields.size < 2) continue
                if (firstCandidateFields == null) firstCandidateFields = fields
                val dialect = BankCsvDialects.matchDialect(fields)
                if (dialect != null) {
                    return FormatDetection.CsvDetected(
                        text = decoded.text,
                        dialect = dialect,
                        headerRowIndex = rowIndex,
                        delimiter = delimiter,
                    )
                }
            }
        }
        return FormatDetection.Unrecognized(observedHeaderFields = firstCandidateFields.orEmpty())
    }
}
