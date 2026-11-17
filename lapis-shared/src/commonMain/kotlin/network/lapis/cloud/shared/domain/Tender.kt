package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/*
 * V1.9.201 "Ausschreibungen" (tenders), wave 1: the foundation, LTR only, no screens yet. See `docs/features/tenders.adoc` for the rules and
 * `docs/architecture/tenders.adoc` for the state machine, the visibility matrix and the lock order. Literal order of every enum is load-bearing
 * (the Exposed columns are sized from the longest literal and the drift test pins them); new literals are only ever appended.
 */

/**
 * Lifecycle of a tender. `PUBLISHED` is the STORED status; once the deadline has passed every reader sees [CLOSED] (the effective status), and the
 * next writing path persists it (lazy close, no scheduler). [CANCELLED] is reachable from every other status except [COMPLETED], [PAID] and
 * [PAYOUT_FAILED]. V1.9.308 (payout, wave 3): [PAID] is final (the LTR was issued to the winner); [PAYOUT_FAILED] means the payout was attempted
 * and the winner could not receive LTR -- a retry is possible, and ONLY the board's view ever shows this status (everybody else reads COMPLETED).
 */
@Serializable
enum class TenderStatus { DRAFT, PUBLISHED, CLOSED, AWARDED, COMPLETED, CANCELLED, PAID, PAYOUT_FAILED }

/** V1.9.308: why the payout of an accepted tender could not be booked on the winner's side. Board view only. */
@Serializable
enum class TenderPayoutFailureReason { WINNER_GUEST, WINNER_NOT_LTR_ELIGIBLE }

/** V1.9.308: why the caller cannot pay out right now (`null` = may pay out). Computed by the server, the client only shows the matching text. */
@Serializable
enum class TenderPayoutBlockedReason { NOT_TREASURY_ROLE, OWN_BID, FOUR_EYES, NOT_PAYABLE }

/** Status of ONE bid. [WITHDRAWN] is the bidder's own retraction before the deadline; [REJECTED] is set on the losers when the tender is awarded. */
@Serializable
enum class TenderBidStatus { SUBMITTED, WITHDRAWN, AWARDED, REJECTED }

/**
 * Who sees the bids of others. Fixed when the tender is PUBLISHED and never changeable afterwards (there is no writing path).
 * [HIDDEN_UNTIL_DEADLINE] is the factory default of the organisation.
 */
@Serializable
enum class TenderBidVisibility { HIDDEN_UNTIL_DEADLINE, OPEN, OWN_ONLY }

/**
 * How the winner is determined. [LOWEST_PRICE]: an act of the board without a vote, the server enforces that the chosen bid is the cheapest.
 * [BEST_OFFER]: a judgement, so a resolution of the committee of the binding resolution is needed (the "award motion").
 */
@Serializable
enum class TenderAwardCriterion { LOWEST_PRICE, BEST_OFFER }

/** Currency of a tender. **EUR is reserved and unreachable in wave 1**: the server refuses it everywhere and the database CHECK allows only LTR. */
@Serializable
enum class TenderCurrency { LTR, EUR }

@Serializable
enum class TenderAcceptanceDecision { ACCEPTED, REJECTED }

/** V1.9.202: format of the description. [PLAIN] = legacy text (shown as element text), [MARKDOWN] = rendered to sanitized HTML by the SERVER. */
@Serializable
enum class TenderDescriptionFormat { PLAIN, MARKDOWN }

/** V1.9.202: why the caller cannot bid right now (`null` = may bid). Computed by the server, the client only shows the matching text. */
@Serializable
enum class TenderBidBlockedReason { GUEST_LTR, NOT_PARTICIPANT, NOT_OPEN, MAX_BIDS_REACHED }

/** V1.9.202: why a motion cannot serve as the BINDING motion of a tender. */
@Serializable
enum class TenderMotionIneligibility { NOT_RESOLVED, AMENDMENT, RESOLUTION_NOT_ADOPTED, WRONG_COMMITTEE }

/** V1.9.202: why a motion cannot serve as the AWARD motion of a tender (its own rules, NOT the rules of the binding motion). */
@Serializable
enum class TenderAwardMotionIneligibility {
    SAME_AS_BINDING,
    WRONG_COMMITTEE,
    AMENDMENT,
    NOT_OPEN,
    ALREADY_BOUND,
    HAS_AMENDMENT,
    DECISION_STARTED,
}

/** V1.9.202: display mirror of the server-side bid view (what the viewer may see of other bids). Decided by the server policy only. */
@Serializable
enum class TenderBidView { NONE, OWN, ALL }

/** V1.9.202: how far a CLOSED BEST_OFFER / LOWEST_PRICE tender is from being awardable. */
@Serializable
enum class TenderAwardReadiness { READY, NEEDS_AWARD_MOTION, AWARD_MOTION_PENDING, AWARD_MOTION_NOT_ADOPTED, AWARD_MOTION_TOO_EARLY }

@Serializable
data class TenderMotionOptionDto(
    val motionId: String,
    val title: String,
    val committeeName: String,
    val selectable: Boolean,
    val reason: TenderMotionIneligibility? = null,
)

@Serializable
data class TenderAwardMotionOptionDto(
    val motionId: String,
    val title: String,
    val selectable: Boolean,
    val reason: TenderAwardMotionIneligibility? = null,
)

/** Input of [network.lapis.cloud.shared.rpc.ITenderService.createTender]. */
@Serializable
data class TenderCreateInput(
    /** The adopted resolution (a motion with status RESOLVED) that this tender implements. One resolution may carry several tenders. */
    val bindingMotionId: String,
    val title: String,
    val description: String,
    val expectedResult: String,
    /** Class B: wall-clock deadline in the organisation zone. Strictly the last instant at which a bid is still accepted. */
    val deadline: LocalDateTime,
    val awardCriterion: TenderAwardCriterion,
    /** `null` = the organisation default ([TenderDefaultsDto.defaultVisibility]). */
    val bidVisibility: TenderBidVisibility? = null,
    val currency: TenderCurrency = TenderCurrency.LTR,
    val descriptionFormat: TenderDescriptionFormat = TenderDescriptionFormat.PLAIN,
)

/** Input of [network.lapis.cloud.shared.rpc.ITenderService.updateTenderDraft]: every field of a draft may change while it is a DRAFT. */
@Serializable
data class TenderDraftUpdateInput(
    val bindingMotionId: String,
    val title: String,
    val description: String,
    val expectedResult: String,
    val deadline: LocalDateTime,
    val awardCriterion: TenderAwardCriterion,
    val bidVisibility: TenderBidVisibility? = null,
    val currency: TenderCurrency = TenderCurrency.LTR,
    val descriptionFormat: TenderDescriptionFormat = TenderDescriptionFormat.PLAIN,
)

/** Input of [network.lapis.cloud.shared.rpc.ITenderService.submitBid]: creates the caller's bid or replaces it (one bid per member and tender). */
@Serializable
data class TenderBidInput(
    /** At most two decimal places (the server never rounds) and at least 0.01. */
    val price: Decimal,
    val text: String,
    val currency: TenderCurrency = TenderCurrency.LTR,
)

@Serializable
data class TenderAwardInput(
    val tenderId: String,
    val bidId: String,
    /** Mandatory for [TenderAwardCriterion.BEST_OFFER] and for a price tie under [TenderAwardCriterion.LOWEST_PRICE]. */
    val justification: String? = null,
)

@Serializable
data class TenderAcceptanceInput(
    val tenderId: String,
    val decision: TenderAcceptanceDecision,
    /** Mandatory for [TenderAcceptanceDecision.REJECTED]. */
    val reason: String? = null,
)

/** The public winner of an awarded tender; visible to every participant from AWARDED on, in every visibility mode. */
@Serializable
data class TenderAwardPublicDto(
    val winnerDisplayName: String,
    val price: Decimal,
    val currency: TenderCurrency,
    val awardedAt: LocalDateTime,
)

/** One line of the tender list. [visibleBidCount] is `null` whenever the viewer may not see how many bids exist. */
@Serializable
data class TenderSummaryDto(
    val id: String,
    val title: String,
    /** The EFFECTIVE status (a PUBLISHED tender past its deadline reads as CLOSED). */
    val status: TenderStatus,
    val deadline: LocalDateTime,
    val awardCriterion: TenderAwardCriterion,
    val currency: TenderCurrency,
    val bidVisibility: TenderBidVisibility,
    val bindingMotionId: String,
    val visibleBidCount: Int? = null,
    /** The status of the CALLER's own bid; `null` = the caller has none. */
    val myBidStatus: TenderBidStatus? = null,
    val award: TenderAwardPublicDto? = null,
)

/** One bid. [bidderDisplayName] and the price of a foreign bid only ever leave the server when the visibility rule shows the bid. */
@Serializable
data class TenderBidDto(
    val id: String,
    val tenderId: String,
    val bidderDisplayName: String?,
    val price: Decimal,
    val currency: TenderCurrency,
    val text: String,
    val status: TenderBidStatus,
    val submittedAt: LocalDateTime,
    val priceSetAt: LocalDateTime,
    val isMine: Boolean = false,
    /** V1.9.202: `true` = the board may award exactly this bid (server truth; only set for a non-conflicted manager). */
    val awardable: Boolean = false,
)

/**
 * V1.9.308: the booked payout. [paidByDisplayName] and [ledgerEntryId] only ever leave the server for the board's read view and the winner;
 * everybody else gets the amount and the date.
 */
@Serializable
data class TenderPayoutDto(
    val amount: Decimal,
    val currency: TenderCurrency,
    val executedAt: LocalDateTime,
    val paidByDisplayName: String? = null,
    val ledgerEntryId: String? = null,
)

/** V1.9.308: the last failed payout attempt; board view only. */
@Serializable
data class TenderPayoutFailureDto(
    val reason: TenderPayoutFailureReason,
    val failedAt: LocalDateTime,
    val failedByDisplayName: String? = null,
)

@Serializable
data class TenderAcceptanceDto(
    val decision: TenderAcceptanceDecision,
    val reason: String?,
    val decidedAt: LocalDateTime,
)

/** Detail of one tender. [bids] is already filtered by the visibility policy; a foreign bid that the viewer may not see is simply absent. */
@Serializable
data class TenderDetailDto(
    val tender: TenderSummaryDto,
    val description: String,
    val expectedResult: String,
    val bindingMotionTitle: String,
    val awardMotionId: String? = null,
    val bids: List<TenderBidDto> = emptyList(),
    val myBid: TenderBidDto? = null,
    val acceptances: List<TenderAcceptanceDto> = emptyList(),
    /** `true` = the viewer has a bid of their own on this tender and therefore may neither award, accept nor register an award motion. */
    val viewerIsConflicted: Boolean = false,
    val canManage: Boolean = false,
    val descriptionFormat: TenderDescriptionFormat = TenderDescriptionFormat.PLAIN,
    /** Sanitized HTML of a MARKDOWN description, rendered by the server; `null` for PLAIN. */
    val descriptionHtml: String? = null,
    /** `null` = the viewer may bid. */
    val bidBlockedReason: TenderBidBlockedReason? = null,
    val bidView: TenderBidView = TenderBidView.NONE,
    /** Whole minutes until the deadline, computed from the organisation wall clock on the server; `null` once the deadline has passed. */
    val minutesUntilDeadline: Long? = null,
    val cancelReason: String? = null,
    val awardMotionTitle: String? = null,
    /** Only for a non-conflicted manager and an effectively CLOSED tender. */
    val awardReadiness: TenderAwardReadiness? = null,
    val awardJustificationRequired: Boolean = false,
    /** V1.9.308: the booked payout (status PAID). */
    val payout: TenderPayoutDto? = null,
    /** V1.9.308: the last failed payout attempt; BOARD VIEW ONLY (everybody else sees COMPLETED and `null`). */
    val payoutFailure: TenderPayoutFailureDto? = null,
    /** V1.9.308: WINNER ONLY -- the tender is accepted and the payout is still to come. */
    val payoutPending: Boolean = false,
    /** V1.9.308: server truth, only for the board's read view while the viewer is an ACTIVE organisation member. */
    val canPayOut: Boolean = false,
    val payoutBlockedReason: TenderPayoutBlockedReason? = null,
    /** V1.9.308: board view only -- what the payout would be (the award price) and to whom, for the confirmation dialog. */
    val payoutAmount: Decimal? = null,
    val payoutWinnerDisplayName: String? = null,
    /** V1.9.308: board view only -- who recorded the acceptance (the four-eyes text names them). */
    val acceptedByDisplayName: String? = null,
)

@Serializable
data class TenderDefaultsDto(
    val defaultVisibility: TenderBidVisibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
    /** V1.9.202: the caller may manage tenders (BOARD/ADMIN). */
    val canManage: Boolean = false,
    /** V1.9.202: the caller may read drafts (BOARD/ADMIN/TREASURER). */
    val canReadInternal: Boolean = false,
)

/**
 * After-snapshot of an [AuditEntityType.TENDER] audit entry: the event and ids/enums only -- never a title, a description, a name, a price or a
 * justification. The append-only hash chain must not carry free text.
 */
@Serializable
data class TenderAuditSnapshot(
    val event: String,
    val status: String? = null,
    val visibility: String? = null,
    val criterion: String? = null,
    val currency: String? = null,
    val deadline: String? = null,
    val bindingMotionId: String? = null,
    val motionId: String? = null,
    val bidId: String? = null,
    val decision: String? = null,
    val fromStatus: String? = null,
    val conflictedCount: Int? = null,
    /** V1.9.308: the LTR ledger row of a PAYOUT event (an id, never an amount). */
    val ledgerEntryId: String? = null,
    /** V1.9.308: the enum name of a PAYOUT_FAILED event. */
    val failureReason: String? = null,
)
