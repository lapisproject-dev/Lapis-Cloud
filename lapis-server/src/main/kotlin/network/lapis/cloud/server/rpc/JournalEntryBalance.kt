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
        val nonPositive = postings.filter { it.amount <= ZERO }
        if (nonPositive.isNotEmpty()) {
            return BalanceResult.invalid("Every posting amount must be strictly positive, got ${nonPositive.map { it.amount }}")
        }
        val tooFinelyScaled = postings.filter { it.amount.scale() > MAX_AMOUNT_SCALE }
        if (tooFinelyScaled.isNotEmpty()) {
            return BalanceResult.invalid(
                "Every posting amount must have at most $MAX_AMOUNT_SCALE fractional digits, got " +
                    tooFinelyScaled.map { it.amount.toPlainString() },
            )
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
