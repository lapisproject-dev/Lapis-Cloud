package network.lapis.cloud.server.payment.bankstatement

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/** One transaction line, already normalized into the shape both [BankCsvParser] and [Mt940Parser] agree on. */
internal data class ParsedLine(
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    /** Signed -- positive is a credit (Gutschrift), negative a debit. */
    val amount: BigDecimal,
    val currency: String,
    val counterpartyName: String?,
    val counterpartyIban: String?,
    val purpose: String?,
    val endToEndReference: String?,
    val bookingText: String?,
)

/**
 * Security finding fix (Review MAJOR): a raw C0/DEL control character in any free-text field --
 * most plausibly a stray NUL byte from an exported bank statement, or (MT940-specific) a `?NN`-less
 * continuation line folded via `\n` into the same subfield that then survives `normalize()`, which
 * only strips whitespace/uppercases and does not filter control characters -- would otherwise reach
 * `BankStatementLineTable.insertIgnore`/`PaymentTransactionTable.insert` uncaught: PostgreSQL's
 * `text`/`varchar` columns reject a raw NUL byte outright (SQLSTATE 22021), which is not among the
 * SQLSTATEs [network.lapis.cloud.server.payment.bankstatement.BankStatementImportService] narrows
 * to a diagnosable rejection -- the whole Phase 1 transaction would abort with an opaque 500 and no
 * per-line diagnosis. Same defense as the sibling CSV importer's
 * `network.lapis.cloud.server.bootstrap.MemberCsvImport.containsControlCharacter`, ported here for
 * every free-text field a [ParsedLine] carries. `\t`/`\n`/`\r` are deliberately exempt, same as
 * there -- a genuine tab/newline inside a purpose/reference field is unusual but not the DB-crash
 * risk a raw NUL/other C0 control or DEL is.
 */
internal fun ParsedLine.containsControlCharacter(): Boolean =
    listOfNotNull(counterpartyName, counterpartyIban, purpose, endToEndReference, bookingText)
        .any { field -> field.any { c -> (c.code in 0x00..0x1F || c.code == 0x7F) && c != '\t' && c != '\n' && c != '\r' } }

/** Whole-statement result of [BankCsvParser.parse]/[Mt940Parser.parse]. */
internal data class ParsedStatement(
    val accountIban: String?,
    val statementFrom: LocalDate?,
    val statementTo: LocalDate?,
    /** MT940-only (`:60F:`/`:60M:`) -- always `null` for a CSV import, which carries no statement-level balance. */
    val openingBalance: BigDecimal?,
    /** MT940-only (`:62F:`/`:62M:`). */
    val closingBalance: BigDecimal?,
    val lines: List<ParsedLine>,
)

/**
 * Thrown by [BankCsvParser]/[Mt940Parser]/[BankStatementFormatDetector] callers for every "reject
 * the whole import" condition -- [lineNumber]/[rawLineExcerpt] (max. 200 characters) surface ONLY
 * in the HTTP 422 response body ([network.lapis.cloud.server.routes.BankStatementRoutes]), NEVER
 * in a log line and NEVER persisted -- see `BankStatementImportService` KDoc "Privacy".
 */
internal class BankStatementParseException(
    message: String,
    val lineNumber: Int? = null,
    val rawLineExcerpt: String? = null,
) : Exception(message)

/** Parses a `dd.MM.yyyy`, `dd.MM.yy` (pivot 2000-2099), or ISO `yyyy-MM-dd` date. Blank input returns `null`; anything else that fails to parse returns `null` too -- callers turn that into a [BankStatementParseException] themselves, since only they know the line number. */
internal fun parseFlexibleGermanDate(raw: String): LocalDate? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    return try {
        when {
            ISO_DATE.matches(s) -> LocalDate.parse(s)
            LONG_GERMAN_DATE.matches(s) -> {
                val (d, m, y) = s.split(".").map { it.toInt() }
                LocalDate(y, m, d)
            }
            SHORT_GERMAN_DATE.matches(s) -> {
                val (d, m, yy) = s.split(".").map { it.toInt() }
                LocalDate(2000 + yy, m, d)
            }
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val LONG_GERMAN_DATE = Regex("""^\d{2}\.\d{2}\.\d{4}$""")
private val SHORT_GERMAN_DATE = Regex("""^\d{2}\.\d{2}\.\d{2}$""")

/**
 * Parses a German-locale bank amount: decimal comma OR decimal point, an optional thousands
 * separator (whichever of `,`/`.` is NOT the decimal separator), a leading `+`/`-` sign, and a
 * trailing `S`/`H` (Soll/Haben) suffix some CSV exports use instead of a leading sign (`S` negates,
 * `H` is a no-op). Returns `null` for blank or unparseable input.
 */
internal fun parseGermanAmount(raw: String): BigDecimal? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    var sign = 1
    val lastChar = s.last()
    if (lastChar == 'S' || lastChar == 's') {
        sign *= -1
        s = s.dropLast(1).trim()
    } else if (lastChar == 'H' || lastChar == 'h') {
        s = s.dropLast(1).trim()
    }
    if (s.isEmpty()) return null
    if (s.startsWith("+")) {
        s = s.substring(1)
    } else if (s.startsWith("-")) {
        sign *= -1
        s = s.substring(1)
    }
    if (s.isEmpty()) return null

    val lastComma = s.lastIndexOf(',')
    val lastDot = s.lastIndexOf('.')
    val normalized =
        when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) {
                    s.replace(".", "").replace(',', '.')
                } else {
                    s.replace(",", "")
                }
            lastComma >= 0 -> s.replace(',', '.')
            else -> s
        }
    return try {
        BigDecimal(normalized).let { if (sign < 0) it.negate() else it }
    } catch (e: NumberFormatException) {
        null
    }
}
