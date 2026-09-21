package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal

/**
 * Pure double-entry balance-invariant check (Σdebit = Σcredit), extracted so it is unit-testable
 * without a database -- same "pure logic extracted to a sibling file" idiom as
 * [ResolutionBook]/[CommitteeEligibility]/[SystemicConsensusTally].
 *
 * **BigDecimal pitfall, deliberately guarded against**: two [BigDecimal] values with the same
 * mathematical value but different scale (`100.00` vs `100.0`) are NOT [BigDecimal.equals] but ARE
 * [BigDecimal.compareTo]-equal -- comparing sums with `==`/`.equals()` is *the* classic
 * double-entry-bookkeeping bug (a balanced entry gets rejected as unbalanced purely because one
 * side accumulated an extra trailing zero of scale along the way). [validateBalanced] always
 * compares with `compareTo(...) == 0`, never `equals`/`==`.
 *
 * **Sub-cent rounding guard**: [PostingTable][network.lapis.cloud.server.db.generated.PostingTable]
 * persists `amount` as `DECIMAL(15,2)` -- at most two fractional digits. If [validateBalanced] were
 * to sum client-supplied amounts at their full, unrounded scale, a scale>2 amount could pass the
 * balance check pre-persistence yet round to a *different*, unbalanced value once the DB coerces it
 * to scale 2 (e.g. `HALF_EVEN` on read via Exposed's `DecimalColumnType`), leaving a permanently
 * unbalanced, immutable POSTED entry. [validateBalanced] therefore rejects any posting whose
 * [PostingInput.amount] has scale > 2 *before* summing, so the values it validates are always
 * exactly the values that get persisted.
 */
internal object JournalEntryBalance {
    private val ZERO = BigDecimal.ZERO

    /** Postings must not carry more than this many fractional digits -- see class KDoc. */
    private const val MAX_AMOUNT_SCALE = 2

    /**
     * Upper bound of a single posting amount (one trillion). `posting.amount` is `DECIMAL(15,2)` and overflows from 10^13 on -- an
     * unhandled HTTP 500 (`numeric field overflow`). The client sends `Decimal` as a JSON double, so `99999999999999999999` arrives as
     * `1.0E20` (scale -19) and passes the `scale() > 2`, `<= 0` and Σdebit = Σcredit checks; only this bound stops it. Same error class
     * as `OpenItemService.MAX_AMOUNT` and `SepaService.MAX_RETURN_FEE`. Mirrored (never as the security boundary) by
     * `MAX_POSTING_AMOUNT` in the client's `FormRules.kt`.
     */
    val MAX_POSTING_AMOUNT: BigDecimal = BigDecimal("1000000000000.00")

    /** Column width of `journal_entry.description` (`varchar(500)`). */
    const val MAX_DESCRIPTION_LENGTH = 500

    /** Column width of `journal_entry.voucher_reference` (`varchar(100)`). */
    const val MAX_VOUCHER_REFERENCE_LENGTH = 100

    /**
     * Validates [postings] as a complete (non-draft) double-entry set: at least two lines, at
     * least one [PostingSide.DEBIT] and one [PostingSide.CREDIT] line, every [PostingInput.amount]
     * strictly positive with a scale of at most [MAX_AMOUNT_SCALE] (see class KDoc), and
     * Σdebit = Σcredit (compared via [BigDecimal.compareTo], not [BigDecimal.equals] -- see class
     * KDoc). Returns a [BalanceResult] describing the outcome; never throws -- callers decide how
     * to surface an unbalanced/invalid result (e.g. as a `ConflictException`).
     */
    fun validateBalanced(postings: List<PostingInput>): BalanceResult {
        if (postings.size < MIN_POSTING_LINES) {
            return BalanceResult.invalid("A journal entry requires at least $MIN_POSTING_LINES postings, got ${postings.size}")
        }
        val nonPositive = nonPositiveAmounts(postings)
        if (nonPositive.isNotEmpty()) {
            return BalanceResult.invalid(nonPositiveViolationMessage(nonPositive))
        }
        val tooFinelyScaled = tooFinelyScaledAmounts(postings)
        if (tooFinelyScaled.isNotEmpty()) {
            return BalanceResult.invalid(scaleViolationMessage(tooFinelyScaled))
        }
        val tooLarge = tooLargeAmounts(postings)
        if (tooLarge.isNotEmpty()) {
            return BalanceResult.invalid(tooLargeViolationMessage(tooLarge))
        }

        val debitTotal = postings.filter { it.side == PostingSide.DEBIT }.sumAmounts()
        val creditTotal = postings.filter { it.side == PostingSide.CREDIT }.sumAmounts()

        if (debitTotal.compareTo(ZERO) == 0) {
            return BalanceResult.invalid("A journal entry requires at least one DEBIT posting")
        }
        if (creditTotal.compareTo(ZERO) == 0) {
            return BalanceResult.invalid("A journal entry requires at least one CREDIT posting")
        }

        return if (debitTotal.compareTo(creditTotal) == 0) {
            BalanceResult.balanced(debitTotal = debitTotal, creditTotal = creditTotal)
        } else {
            BalanceResult.invalid("Journal entry not balanced: debits $debitTotal != credits $creditTotal")
        }
    }

    private fun List<PostingInput>.sumAmounts(): BigDecimal = fold(ZERO) { acc, posting -> acc + posting.amount }

    /**
     * The scale portion of [validateBalanced], extracted so [AccountingService.saveDraftEntry]
     * can enforce it WITHOUT the rest of the balance check -- a draft need not balance yet, but
     * must still never carry a client-supplied amount with more than [MAX_AMOUNT_SCALE] fractional
     * digits: a scale-3+ `posting.amount` reaching [VatCalculator.vatAmountOf]'s
     * `RoundingMode.UNNECESSARY` guard throws an uncaught `ArithmeticException` (HTTP 500) instead
     * of the clean validation error this function lets a caller raise instead. Never throws --
     * same "callers decide" posture as [validateBalanced] itself.
     */
    fun tooFinelyScaledAmounts(postings: List<PostingInput>): List<PostingInput> = postings.filter { it.amount.scale() > MAX_AMOUNT_SCALE }

    /**
     * The upper-bound portion of [validateBalanced], extracted so [AccountingService.saveDraftEntry] can enforce it WITHOUT the rest
     * of the balance check (see [MAX_POSTING_AMOUNT]). Compares via [BigDecimal.compareTo], which is scale-independent, so a
     * negative-scale value like `1.0E20` is caught. Never throws.
     */
    fun tooLargeAmounts(postings: List<PostingInput>): List<PostingInput> = postings.filter { it.amount > MAX_POSTING_AMOUNT }

    /** Shared message text for [validateBalanced] and [AccountingService.requireWithinMaxAmount] -- one wording, two callers. */
    fun tooLargeViolationMessage(tooLarge: List<PostingInput>): String =
        "Every posting amount must be at most ${MAX_POSTING_AMOUNT.toPlainString()}, got " +
            tooLarge.map { it.amount.toPlainString() }

    /** Shared message text for [validateBalanced] and [AccountingService.requireValidScale] -- one wording, two callers. */
    fun scaleViolationMessage(tooFinelyScaled: List<PostingInput>): String =
        "Every posting amount must have at most $MAX_AMOUNT_SCALE fractional digits, got " +
            tooFinelyScaled.map { it.amount.toPlainString() }

    /**
     * Security Round 2 (MINOR, regression of the same class [requireValidScale]/[tooFinelyScaledAmounts]
     * already fixed for scale): the non-positive-amount portion of [validateBalanced], extracted
     * so [AccountingService.saveDraftEntry] can enforce it WITHOUT the rest of the balance check --
     * a draft need not balance yet, but a non-positive [PostingInput.amount] still reaches
     * [network.lapis.cloud.server.accounting.vat.VatCalculator.vatAmountOf] with `vatRate` set,
     * which can then compute a NEGATIVE `vat_amount` -- violating the DB's
     * `chk_posting_vat_amount_non_negative` CHECK constraint (`V31__vat.sql`) with an uncaught
     * `ExposedSQLException` (HTTP 500) instead of the clean validation error this function lets a
     * caller raise instead. Never throws -- same "callers decide" posture as [validateBalanced]/
     * [tooFinelyScaledAmounts].
     */
    fun nonPositiveAmounts(postings: List<PostingInput>): List<PostingInput> = postings.filter { it.amount <= ZERO }

    /** Shared message text for [validateBalanced] and [AccountingService.requireNonNegativeAmounts] -- one wording, two callers. */
    fun nonPositiveViolationMessage(nonPositive: List<PostingInput>): String =
        "Every posting amount must be strictly positive, got ${nonPositive.map { it.amount.toPlainString() }}"

    private const val MIN_POSTING_LINES = 2
}

/** Outcome of [JournalEntryBalance.validateBalanced]. */
internal data class BalanceResult(
    val balanced: Boolean,
    val debitTotal: BigDecimal,
    val creditTotal: BigDecimal,
    val reason: String?,
) {
    companion object {
        fun balanced(
            debitTotal: BigDecimal,
            creditTotal: BigDecimal,
        ): BalanceResult = BalanceResult(balanced = true, debitTotal = debitTotal, creditTotal = creditTotal, reason = null)

        fun invalid(reason: String): BalanceResult =
            BalanceResult(balanced = false, debitTotal = BigDecimal.ZERO, creditTotal = BigDecimal.ZERO, reason = reason)
    }
}
