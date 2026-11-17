package network.lapis.cloud.server.tender

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderPayoutBlockedReason
import network.lapis.cloud.shared.domain.TenderPayoutFailureReason
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ConflictException
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * V1.9.308 -- the pure rules of the tender payout (wave 3). No database access, no ledger access: the booking itself lives in `TenderPayout`
 * (the ONE tender file that may touch the ledger, `TenderAccessTripwireTest` pins this), so every decision here can be tested exhaustively.
 */

/** Who may pay out an accepted tender: the same set as the board's read view, deliberately its own constant. */
internal val TENDER_PAYOUT_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** The stored statuses a payout may start from (a failed attempt may be retried). */
internal val PAYABLE_STATUSES: Set<TenderStatus> = setOf(TenderStatus.COMPLETED, TenderStatus.PAYOUT_FAILED)

private val MAX_PAYOUT = BigDecimal("9999999999999999.99")
private val MIN_PAYOUT = BigDecimal("0.01")

internal const val PAYOUT_AMOUNT_INVALID = "The payout amount is invalid"
internal const val PAYOUT_LTR_ONLY = "Only LTR can be paid out"

/**
 * The amount booked for a winning price: EXACTLY the price, at two decimal places. Never rounds: a price with more than two decimal places
 * (impossible through `submitBid` and the `DECIMAL(18,2)` column, defence in depth), below 0.01 or above the column maximum is refused.
 */
internal fun tenderPayoutAmount(price: BigDecimal): BigDecimal {
    if (price.stripTrailingZeros().scale() > 2) throw ConflictException(PAYOUT_AMOUNT_INVALID)
    val normalized = price.setScale(2, RoundingMode.UNNECESSARY)
    if (normalized < MIN_PAYOUT || normalized > MAX_PAYOUT) throw ConflictException(PAYOUT_AMOUNT_INVALID)
    return normalized
}

/** Only LTR can be paid out; EUR is reserved for a later wave that needs legal clearance first. Does not name the reserved currency. */
internal fun requireLtrPayout(currency: TenderCurrency) {
    if (currency != TenderCurrency.LTR) throw ConflictException(PAYOUT_LTR_ONLY)
}

/**
 * Why the WINNER cannot receive LTR right now (`null` = can). A guest keeps the LTR account on the home server; every other status outside
 * [MemberStatusSets.LTR_ELIGIBLE] (withdrawn, rejected, deceased, anonymised, still an applicant) cannot hold LTR either.
 */
internal fun payoutFailureFor(status: MemberStatus): TenderPayoutFailureReason? =
    when {
        status == MemberStatus.GUEST -> TenderPayoutFailureReason.WINNER_GUEST
        status !in MemberStatusSets.LTR_ELIGIBLE -> TenderPayoutFailureReason.WINNER_NOT_LTR_ELIGIBLE
        else -> null
    }

/**
 * Why the caller cannot pay out (`null` = may). Order: NOT_TREASURY_ROLE, OWN_BID (a bidder, which covers the winner), FOUR_EYES (the caller
 * recorded the acceptance), NOT_PAYABLE (the tender is not COMPLETED/PAYOUT_FAILED; a PAID tender is "not payable" for the button, while the RPC
 * itself answers a repeated call idempotently).
 */
internal fun payoutBlockedReason(
    role: AccountRole,
    callerHasBid: Boolean,
    callerIsAcceptor: Boolean,
    status: TenderStatus,
): TenderPayoutBlockedReason? =
    when {
        role !in TENDER_PAYOUT_ROLES -> TenderPayoutBlockedReason.NOT_TREASURY_ROLE
        callerHasBid -> TenderPayoutBlockedReason.OWN_BID
        callerIsAcceptor -> TenderPayoutBlockedReason.FOUR_EYES
        status !in PAYABLE_STATUSES -> TenderPayoutBlockedReason.NOT_PAYABLE
        else -> null
    }
