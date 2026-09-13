package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- outcome of a booking attempt, shared by
 * [VolunteerAllowanceExecution] and [VolunteerAllowancePostingBridge], mirroring
 * [TravelExpensePostingOutcome]. A DEDICATED type, not a reuse of [TravelExpensePostingOutcome] --
 * the two domains' [Failed.reason] code sets are disjoint and each `when` over them must stay
 * exhaustive independently.
 */
internal sealed interface VolunteerAllowancePostingOutcome {
    data class Posted(
        val journalEntryId: Uuid,
    ) : VolunteerAllowancePostingOutcome

    data class Failed(
        val reason: String,
    ) : VolunteerAllowancePostingOutcome
}

/**
 * Welle V1.4.12 -- runs inside the CALLER's already-open `transaction {}`
 * (`VolunteerAllowanceService.decidePayment`/`.retryPosting`), exactly like
 * [TravelExpenseExecution] -- never opens its own.
 *
 * **A throw here would roll back the caller's WHOLE transaction -- including the APPROVED
 * decision that was just written.** Re-checks, under the payment row lock the caller already
 * holds:
 *
 * 1. The self-declaration for `(subjectMemberId, category, paymentDate.year)` still exists --
 *    [FAILED_SELF_DECLARATION_MISSING] otherwise (the exact same gate `decidePayment` itself
 *    already enforces before ever reaching here, re-checked defensively).
 * 2. `priorPostedAllowanceTotalThisYear` recomputed NOW still matches `prior_total_snapshot`
 *    (via `compareTo`, never `equals`) -- [VOLUNTEER_ALLOWANCE_REASON_TOTAL_CHANGED] otherwise.
 *    This catches the SEQUENTIAL case -- substantial time passed between `decidePayment`'s
 *    snapshot and `retryPosting` actually posting, during which another, already-COMMITTED
 *    payment consumed cap. It is **not**, on its own, the guard against two fully overlapping
 *    concurrent decisions (TOCTOU-Race-Fix, Security-Fund V1.4.12): under READ COMMITTED, this
 *    recompute cannot see another transaction's still-uncommitted EXECUTED transition no matter
 *    when it runs. That guard is [VolunteerAllowanceService]'s region lock
 *    ([network.lapis.cloud.server.rpc.lockAllowanceYearRows], taken in `decidePayment`/
 *    `retryPosting` before either one reaches this class) -- it is held for the rest of the
 *    caller's transaction, so by the time THIS recompute runs, any sibling payment's decision is
 *    either already committed and visible, or still queued behind the very lock this call is
 *    running under.
 * 3. `amount > 0`, `amount` still equals the stored amount, and the `cap_*` columns are still
 *    internally consistent with `exceeding_amount_snapshot` -- [VOLUNTEER_ALLOWANCE_REASON_INCONSISTENT]
 *    otherwise (a generic code, deliberately not naming which invariant broke).
 *
 * Any failure returns [VolunteerAllowancePostingOutcome.Failed] rather than throwing -- the caller
 * persists that as `status = APPROVED, execution_error = <reason>`, a safe, retryable resting
 * state.
 */
internal object VolunteerAllowanceExecution {
    fun execute(
        payment: ResultRow,
        actor: CurrentMember,
        now: LocalDateTime,
    ): VolunteerAllowancePostingOutcome {
        val paymentId = payment[VolunteerAllowancePaymentTable.id]
        val subjectMemberId = payment[VolunteerAllowancePaymentTable.subjectMemberId]
        val category = payment[VolunteerAllowancePaymentTable.category]
        val paymentDate = payment[VolunteerAllowancePaymentTable.paymentDate]
        val amount = payment[VolunteerAllowancePaymentTable.amount]
        val priorTotalSnapshot = payment[VolunteerAllowancePaymentTable.priorTotalSnapshot]
        val freeAmountSnapshot = payment[VolunteerAllowancePaymentTable.freeAmountSnapshot]
        val exceedingAmountSnapshot = payment[VolunteerAllowancePaymentTable.exceedingAmountSnapshot]
        val capDisclaimerVersion = payment[VolunteerAllowancePaymentTable.capDisclaimerVersion]

        if (!selfDeclarationExists(memberId = subjectMemberId, category = category, year = paymentDate.year)) {
            return VolunteerAllowancePostingOutcome.Failed(VOLUNTEER_ALLOWANCE_REASON_SELF_DECLARATION_MISSING)
        }

        if (amount <= BigDecimal.ZERO) return VolunteerAllowancePostingOutcome.Failed(VOLUNTEER_ALLOWANCE_REASON_INCONSISTENT)
        if (priorTotalSnapshot == null) return VolunteerAllowancePostingOutcome.Failed(VOLUNTEER_ALLOWANCE_REASON_INCONSISTENT)
        // freeAmountSnapshot == null while exceedingAmountSnapshot is non-null/positive would
        // otherwise fall through to `freeAmount = amount` below, and
        // VolunteerAllowancePostingBridge.postVolunteerAllowance's
        // require(freeAmount + exceedingAmount == amount) would then throw -- rolling back the
        // caller's WHOLE transaction (see this class's own KDoc: "A throw here would roll back
        // the caller's WHOLE transaction") instead of yielding a retryable Failed(INCONSISTENT).
        if (freeAmountSnapshot == null && exceedingAmountSnapshot != null && exceedingAmountSnapshot.signum() > 0) {
            return VolunteerAllowancePostingOutcome.Failed(VOLUNTEER_ALLOWANCE_REASON_INCONSISTENT)
        }
        // exceedingAmountSnapshot non-null-and-positive => a cap disclaimer version MUST be
        // present, mirroring chk_vap_exceeding_needs_ack.
        val hasExceedingAmount = exceedingAmountSnapshot != null && exceedingAmountSnapshot.signum() > 0
        if (hasExceedingAmount && capDisclaimerVersion == null) {
            return VolunteerAllowancePostingOutcome.Failed(VOLUNTEER_ALLOWANCE_REASON_INCONSISTENT)
        }

        val recomputedPriorTotal =
            priorPostedAllowanceTotalThisYear(
                memberId = subjectMemberId,
                category = category,
                year = paymentDate.year,
                excludePaymentId = paymentId,
            )
        if (recomputedPriorTotal.compareTo(priorTotalSnapshot) != 0) {
            return VolunteerAllowancePostingOutcome.Failed(VOLUNTEER_ALLOWANCE_REASON_TOTAL_CHANGED)
        }

        // Digest, not the raw activity description -- kept short and defensively truncated.
        val activityDigest = payment[VolunteerAllowancePaymentTable.activityDescription].take(ACTIVITY_DIGEST_MAX_LENGTH)
        val freeAmount = freeAmountSnapshot ?: amount
        val exceedingAmount = exceedingAmountSnapshot ?: BigDecimal.ZERO
        // The actual invariant VolunteerAllowancePostingBridge.postVolunteerAllowance enforces via
        // `require(...)` -- checked HERE too (via compareTo, never equals -- scale trap) so a
        // mismatch yields a retryable Failed(INCONSISTENT) instead of that `require` throwing and
        // rolling back the caller's WHOLE transaction (see this function's own KDoc point 3 and the
        // class KDoc "A throw here would roll back the caller's WHOLE transaction"). The individual
        // null/sign checks above catch the common shapes; this is the general backstop for any
        // other snapshot corruption of the three columns.
        if (freeAmount.add(exceedingAmount).compareTo(amount) != 0) {
            return VolunteerAllowancePostingOutcome.Failed(VOLUNTEER_ALLOWANCE_REASON_INCONSISTENT)
        }

        return VolunteerAllowancePostingBridge.postVolunteerAllowance(
            paymentId = paymentId,
            subjectMemberId = subjectMemberId,
            category = category,
            activityDigest = activityDigest,
            amount = amount,
            freeAmount = freeAmount,
            exceedingAmount = exceedingAmount,
            decidedAt = now,
            actorMemberId = actor.memberId,
            actorRole = actor.role,
        )
    }

    private fun selfDeclarationExists(
        memberId: Uuid,
        category: VolunteerAllowanceCategory,
        year: Int,
    ): Boolean =
        VolunteerAllowanceSelfDeclarationTable
            .selectAll()
            .where {
                (VolunteerAllowanceSelfDeclarationTable.memberId eq memberId) and
                    (VolunteerAllowanceSelfDeclarationTable.category eq category) and
                    (VolunteerAllowanceSelfDeclarationTable.calendarYear eq year)
            }.count() > 0
}

/** Re-check failures -- deliberately short, technical wire codes, never derived from free text. */
internal const val VOLUNTEER_ALLOWANCE_REASON_SELF_DECLARATION_MISSING = "self_declaration_missing"
internal const val VOLUNTEER_ALLOWANCE_REASON_TOTAL_CHANGED = "allowance_total_changed_since_decision"
internal const val VOLUNTEER_ALLOWANCE_REASON_INCONSISTENT = "payment_no_longer_consistent"

private const val ACTIVITY_DIGEST_MAX_LENGTH = 120
