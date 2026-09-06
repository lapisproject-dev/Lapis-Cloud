package network.lapis.cloud.server.payment.bankstatement

import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Computes the per-line idempotency fingerprint stored in
 * `bank_statement_line.fingerprint` (`uq_bank_statement_line_fingerprint`) -- re-importing the same
 * (or an overlapping) statement file produces the SAME fingerprint for the SAME line, which
 * `BankStatementImportService`'s `insertIgnore` then silently skips as a duplicate.
 *
 * **Fresh [MessageDigest] instance every call** -- [MessageDigest] is not thread-safe, the exact
 * bug class this repo's own security checklist names (`AuditHashChain`/`SecretBox` are the
 * precedent this class follows).
 *
 * [occurrenceIndex] resolves the one real collision risk: two standing-order lines with an
 * IDENTICAL amount/purpose/counterparty on the SAME day (e.g. a monthly membership fee that always
 * reads "Mitgliedsbeitrag" from the same sender for the same amount) would otherwise fingerprint
 * identically and the second one would be wrongly treated as a re-import duplicate. Callers pass
 * the 0-based rank of this line among every OTHER line in the SAME FILE sharing the same
 * (accountIban, bookingDate, valueDate, amount, currency, counterpartyName, counterpartyIban,
 * purpose, endToEndReference) tuple, in file order -- so line N of a group of otherwise-identical
 * lines is always fingerprint-distinct from lines 0..N-1 of that same group.
 *
 * **[groupingKey] is exposed (not just [of]'s private helper)** so [BankStatementImportService] can
 * compute that same "which group of otherwise-identical lines does this line belong to" tuple with
 * the EXACT SAME normalization [of] itself uses to build the fingerprint -- Review fix (MAJOR):
 * `BankStatementImportService` used to build its own `occurrenceKeyBase` from the RAW,
 * un-normalized fields while [of] normalizes them (trim/uppercase/whitespace-collapse,
 * `amount.setScale(2)`), so two lines that only differ in whitespace/casing/amount-scale got
 * DIFFERENT occurrence-group keys but, once normalized, the SAME fingerprint -- the second
 * (genuine, non-duplicate) line silently lost its `occurrenceIndex 0` fingerprint slot to the
 * first, and `insertIgnore` then discarded it as a false "already imported" duplicate. Deriving
 * both the grouping key and the fingerprint payload from one shared, normalized function makes that
 * class of asymmetry structurally impossible.
 */
internal object BankStatementFingerprint {
    private const val FIELD_SEPARATOR = ''

    fun of(
        accountIban: String?,
        bookingDate: LocalDate,
        valueDate: LocalDate?,
        amount: BigDecimal,
        currency: String,
        counterpartyName: String?,
        counterpartyIban: String?,
        purpose: String?,
        endToEndReference: String?,
        occurrenceIndex: Int,
    ): String {
        val payload =
            groupingKey(
                accountIban = accountIban,
                bookingDate = bookingDate,
                valueDate = valueDate,
                amount = amount,
                currency = currency,
                counterpartyName = counterpartyName,
                counterpartyIban = counterpartyIban,
                purpose = purpose,
                endToEndReference = endToEndReference,
            ) + FIELD_SEPARATOR + occurrenceIndex.toString()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return hash.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * The normalized field tuple [of] hashes, WITHOUT [of]'s `occurrenceIndex` suffix -- the
     * "which group of otherwise-identical lines does this line belong to" key. Two lines with the
     * same [groupingKey] are exactly the lines that need distinct `occurrenceIndex`es from each
     * other before calling [of]; see class KDoc for why this must be the single source both
     * [BankStatementImportService]'s occurrence-counting AND [of] itself derive from.
     */
    fun groupingKey(
        accountIban: String?,
        bookingDate: LocalDate,
        valueDate: LocalDate?,
        amount: BigDecimal,
        currency: String,
        counterpartyName: String?,
        counterpartyIban: String?,
        purpose: String?,
        endToEndReference: String?,
    ): String =
        listOf(
            accountIban?.let { normalize(it) }.orEmpty(),
            bookingDate.toString(),
            valueDate?.toString().orEmpty(),
            amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
            currency.trim().uppercase(),
            counterpartyName?.let { normalize(it) }.orEmpty(),
            counterpartyIban?.let { normalize(it) }.orEmpty(),
            purpose?.let { normalize(it) }.orEmpty(),
            endToEndReference?.let { normalize(it) }.orEmpty(),
        ).joinToString(separator = FIELD_SEPARATOR.toString())

    /** Uppercase, whitespace-collapsed, trimmed -- so a re-export of the same file with cosmetic whitespace/casing differences still fingerprints identically. */
    private fun normalize(text: String): String = text.trim().uppercase().replace(Regex("""\s+"""), " ")
}
