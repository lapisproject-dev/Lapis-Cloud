package network.lapis.cloud.server.tender

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.TenderBidBlockedReason
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderStatus
import kotlin.uuid.Uuid

/*
 * V1.9.201 -- THE ONE place that decides who sees which bids of a tender. A pure function without any database access, so the whole decision space
 * can be tested exhaustively (`TenderVisibilityPolicyTest`). Every tender read path (detail, list, "my bids") goes through it; nothing else may
 * decide on `bid_visibility`, a deadline or a role to show or hide a foreign bid. Counters are as sensitive as prices (a bid count reveals
 * whether anybody bid at all), so the count is governed by the very same decision as the bids themselves.
 *
 * The matrix lives in `docs/architecture/tenders.adoc`; "manager view" = BOARD, ADMIN or TREASURER who has NO bid row of their own on that tender.
 */

/** What a viewer may see of the bids of OTHER people. [OWN] = only the viewer's own bid, [ALL] = every bid that is not withdrawn (plus the own one). */
internal enum class BidView { NONE, OWN, ALL }

/**
 * The viewer of one tender. [hasBid] is `true` if ANY bid row of the viewer exists on that tender, a withdrawn one included: a bidder who
 * withdrew is still a bidder (the conflict-of-interest rule uses the same definition).
 */
internal data class TenderViewer(
    val memberId: Uuid,
    val role: AccountRole,
    val status: MemberStatus,
    val hasBid: Boolean,
)

internal val TENDER_MANAGER_ROLES: Set<AccountRole> = setOf(AccountRole.BOARD, AccountRole.ADMIN)

/** The board's read view also includes the TREASURER (the payout of wave 3 is theirs). */
internal val TENDER_READ_ROLES: Set<AccountRole> = TENDER_MANAGER_ROLES + AccountRole.TREASURER

private fun TenderViewer.hasManagerView(): Boolean = role in TENDER_READ_ROLES && !hasBid

/**
 * The status a READER sees: a tender that is stored as PUBLISHED but whose deadline has been reached is CLOSED for every reader, whether or not a
 * writing path has persisted that yet. The boundary is strict: a bid is valid only while `wallNow < deadline`.
 */
internal fun effectiveTenderStatus(
    stored: TenderStatus,
    deadline: LocalDateTime,
    wallNow: LocalDateTime,
): TenderStatus = if (stored == TenderStatus.PUBLISHED && wallNow >= deadline) TenderStatus.CLOSED else stored

/**
 * Whether the viewer may know that this tender exists. Drafts and tenders cancelled before their publication are visible to the manager view
 * only; everything else is visible to the participant statuses ([MemberStatusSets.TENDER_PARTICIPANT]) and to the board's read view.
 * An invisible tender must read exactly like a missing one (no existence oracle).
 */
internal fun tenderVisibleTo(
    viewer: TenderViewer,
    status: TenderStatus,
    everPublished: Boolean,
): Boolean {
    val internalOnly = status == TenderStatus.DRAFT || (status == TenderStatus.CANCELLED && !everPublished)
    if (internalOnly) return viewer.role in TENDER_READ_ROLES && viewer.status in MemberStatusSets.ORGANIZATION_MEMBER
    return viewer.status in MemberStatusSets.TENDER_PARTICIPANT
}

/**
 * Which bids of other people the viewer sees. [revealed] = the deadline has been reached AND the tender was not cancelled before it (see
 * [tenderBidsRevealed]); a tender cancelled early never reveals its bids in the modes that hide them.
 */
internal fun bidViewFor(
    viewer: TenderViewer,
    visibility: TenderBidVisibility,
    revealed: Boolean,
): BidView {
    if (visibility == TenderBidVisibility.OPEN) return BidView.ALL
    val manager = viewer.hasManagerView()
    return when (visibility) {
        TenderBidVisibility.HIDDEN_UNTIL_DEADLINE ->
            when {
                revealed && (viewer.hasBid || manager) -> BidView.ALL
                viewer.hasBid -> BidView.OWN
                else -> BidView.NONE
            }
        TenderBidVisibility.OWN_ONLY ->
            when {
                viewer.hasBid -> BidView.OWN
                manager && revealed -> BidView.ALL
                else -> BidView.NONE
            }
        TenderBidVisibility.OPEN -> BidView.ALL
    }
}

/** The number of bids is shown exactly when the bids themselves are shown. */
internal fun bidCountVisible(
    viewer: TenderViewer,
    visibility: TenderBidVisibility,
    revealed: Boolean,
): Boolean = bidViewFor(viewer = viewer, visibility = visibility, revealed = revealed) == BidView.ALL

/**
 * `deadline <= wallNow` and the tender was not cancelled before the deadline. [cancelledAtWall] is the cancellation instant re-expressed as the
 * organisation wall-clock (the stored `cancelled_at` is a class A value), or `null` for a tender that was not cancelled.
 */
internal fun tenderBidsRevealed(
    deadline: LocalDateTime,
    wallNow: LocalDateTime,
    cancelledAtWall: LocalDateTime?,
): Boolean = deadline <= wallNow && (cancelledAtWall == null || cancelledAtWall >= deadline)

/** Refusal text of `submitBid` for a guest on an LTR tender (V1.9.202: a guest keeps the LTR account on the home server and is paid in euro only). */
internal const val GUEST_LTR_REFUSAL = "Guests can only bid on tenders paid in euro"

/**
 * V1.9.202 -- whether a member with [status] may HOLD a bid on a tender paid in [currency]: the participant statuses, except that a GUEST
 * never bids (or wins) on an LTR tender. The one definition that `submitBid`, `awardTender` (and with it the "awardable" mark) and the
 * blocked-reason text of the screens all use.
 */
internal fun tenderBidderEligible(
    status: MemberStatus,
    currency: TenderCurrency,
): Boolean = status in MemberStatusSets.TENDER_PARTICIPANT && !(status == MemberStatus.GUEST && currency == TenderCurrency.LTR)

/**
 * V1.9.202 -- why the viewer cannot place a bid right now; `null` = may bid. Order: NOT_PARTICIPANT, GUEST_LTR, NOT_OPEN, MAX_BIDS_REACHED
 * (the last one only without an own bid row, an own row is replaced and never counts against the cap). [bidCount] is used for this decision
 * only and must never be shipped.
 */
internal fun tenderBidBlockedReason(
    status: MemberStatus,
    currency: TenderCurrency,
    effective: TenderStatus,
    hasBid: Boolean,
    bidCount: Int,
    maxBids: Int,
): TenderBidBlockedReason? =
    when {
        status !in MemberStatusSets.TENDER_PARTICIPANT -> TenderBidBlockedReason.NOT_PARTICIPANT
        !tenderBidderEligible(status = status, currency = currency) -> TenderBidBlockedReason.GUEST_LTR
        effective != TenderStatus.PUBLISHED -> TenderBidBlockedReason.NOT_OPEN
        !hasBid && bidCount >= maxBids -> TenderBidBlockedReason.MAX_BIDS_REACHED
        else -> null
    }
