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
 *
 * **[bankAccountDiscriminator] -- Review fix (CRITICAL, Welle V1.4.14 "Mehrere Bankkonten"):**
 * [accountIban] alone used to be the ONLY account-dimension in this fingerprint, but a plain CSV
 * export carries no statement-level IBAN of its own (see `BankCsvParser` KDoc) -- [accountIban] is
 * `null` for EVERY CSV import, regardless of which of possibly several `bank_account` rows it was
 * attributed to. Two different accounts' CSV exports that happen to share one otherwise-identical
 * line (same date/amount/purpose/counterparty -- not implausible for a recurring fee both accounts
 * pay, or two accounts at the same bank) therefore fingerprinted IDENTICALLY, and the second
 * account's line silently vanished as a false "already imported" duplicate -- `insertIgnore`
 * discarding real, distinct bookkeeping data with no error, no warning, nothing in the return DTO
 * that says a line went missing (see `BankStatementImportServiceTest`'s dedicated regression test).
 * [bankAccountDiscriminator] (the resolved `bank_account.id`, as a string) closes this by giving
 * every account its OWN fingerprint space once it actually matters.
 *
 * **Unconditional once resolved, no row-count gate (Review fix, MEDIUM finding, Review Round 3):**
 * this parameter defaults to `null`/blank (appended to NEITHER [groupingKey]'s nor [of]'s payload
 * at all, not even as an empty field -- the resulting string, and therefore the fingerprint, is
 * BYTE-IDENTICAL to the pre-wave formula whenever it stays `null`), but `BankStatementImportService`
 * no longer gates it on "MORE THAN ONE `bank_account` row currently exists" -- an EARLIER version of
 * this fix did, and that gate made the discriminator depend on a MUTABLE count that flips both ways
 * (a second account added, or later deleted again), silently changing every FUTURE fingerprint for
 * an account whose own identity never changed and re-opening the exact false-duplicate/silent-drop
 * window this fix exists to close, just on the opposite edge of the transition. The discriminator is
 * now unconditional whenever a `bank_account` row was actually resolved for the import; backward
 * compatibility with every already-stored (discriminator-less, or differently-discriminated)
 * fingerprint is instead handled explicitly, per line, by `BankStatementImportService`'s own
 * `alreadyImportedUnderLegacyFingerprint` check -- it additionally looks up THIS SAME line's
 * fingerprint computed WITHOUT a discriminator.
 *
 * **Correction (Review Round 4, MAJOR "Doppelbuchung realer Zahlungen im Upgrade-Pfad"):** an
 * earlier version of that check scoped the legacy lookup to `bank_statement_import.bank_account_id
 * eq <resolved account>` ONLY, reasoning it stayed within "the same resolved account" -- but
 * `bank_account_id` is written from that identical resolved value, so a NON-NULL `bank_account_id`
 * already implies the stored line's fingerprint carries a discriminator; a legacy
 * (discriminator-less) fingerprint can therefore ONLY ever be stored under a NULL `bank_account_id`
 * (every import made before the very first `bank_account` row existed -- i.e. every
 * pre-Welle-V1.4.14 import, see the V32 migration's deliberate no-backfill decision). That scoping
 * was consequently unreachable by construction and never matched anything, silently reopening the
 * exact double-import/double-booking window this parameter exists to close for every upgrading
 * installation's first overlapping re-import.
 *
 * **Correction (Review Round 5, residuum of finding #1 "Doppelbuchung realer Zahlungen UND stiller
 * Verlust echter Buchungen"):** the Round 4 fix above made the lookup additionally match
 * `bank_account_id IS NULL` rows whenever the resolved account was CURRENTLY the organization's
 * default one -- but `isDefault` is mutable (`BankAccountStore.setDefault`, and the
 * default-promotion in `BankAccountStore.delete`), while a `NULL`-`bank_account_id` legacy row's
 * true owner is not: moving the default elsewhere silently mismatched every legacy row in BOTH
 * directions (a genuine re-import of the legacy account's own history stopped deduplicating once it
 * was no longer default; a coincidentally-identical NEW booking on whichever account was default now
 * got wrongly swallowed as a duplicate of someone else's old line).
 *
 * Fixed at the root instead: `BankAccountStore.adoptLegacyBankStatementImports` (called from both
 * `create`'s `makeDefault` branch and `backfillLegacyDefaultAccountIfNeeded`) rewrites every
 * `bank_statement_import` row still carrying `bank_account_id IS NULL` to the organization's
 * first-ever `bank_account` row's own id, the INSTANT that row is created. This codebase is
 * single-tenant (one organization per server instance), and pre-wave's own FOREIGN_ACCOUNT rejection
 * already refused any statement whose valid IBAN did not match the organization's single configured
 * `organization_settings.bank_iban` -- so every `NULL`-`bank_account_id` row existing at that moment
 * unambiguously belongs to that one pre-wave identity, and no row can ever become `NULL` again
 * afterwards (`BankStatementImportService.import` only ever leaves it `NULL` while `bank_account` is
 * still completely empty). `bank_account_id` therefore permanently records each import's real,
 * immutable owner rather than a snapshot of whichever account happened to be default at import time
 * -- unaffected by a LATER `setDefault` or by deleting a different, non-owning account. The lookup
 * in [BankStatementImportService] can consequently scope the legacy match by the resolved account's
 * own id alone (`bank_account_id eq <resolved account>`), exactly like every other, non-legacy
 * fingerprint lookup already does: reachable (every legacy row is adopted, non-`NULL`, the moment
 * any `bank_account` row exists) and correct (a coincidentally-identical line on a DIFFERENT account
 * never gets absorbed into a legacy row it does not own, regardless of which account is currently
 * default).
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
        bankAccountDiscriminator: String? = null,
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
                bankAccountDiscriminator = bankAccountDiscriminator,
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
        bankAccountDiscriminator: String? = null,
    ): String {
        val base =
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
        // Deliberately NOT appended when blank -- see class KDoc "Backward-compatibility tradeoff,
        // deliberate": appending even an empty extra field would still change the joined string
        // (one more separator), which would change every already-stored fingerprint's hash too.
        return if (bankAccountDiscriminator.isNullOrEmpty()) base else base + FIELD_SEPARATOR + bankAccountDiscriminator
    }

    /** Uppercase, whitespace-collapsed, trimmed -- so a re-export of the same file with cosmetic whitespace/casing differences still fingerprints identically. */
    private fun normalize(text: String): String = text.trim().uppercase().replace(Regex("""\s+"""), " ")
}
