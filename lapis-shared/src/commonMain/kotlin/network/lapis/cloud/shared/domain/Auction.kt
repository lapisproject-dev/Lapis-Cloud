package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V0.6.2 LTR-Auktion (englische Proxy-Bid-Auktion, Second-Price-Zuschlag) -- see
 * `21-auction.kuml.kts` file header for the full fachlich model. Literal order is load-bearing:
 * `AuctionSchemaDriftTest` pins `ErmDataType.Enum.values` against this exact order.
 *
 * [OPEN] is the only status a fresh listing starts in. [SETTLED]/[CLOSED_NO_SALE] are both
 * terminal, reached either by an explicit [network.lapis.cloud.shared.rpc.IAuctionService.buyNow]
 * or by lazy-close (no scheduler exists in this codebase -- see file header "Lazy-Close") once
 * `endsAt` has passed: [SETTLED] if at least one bid existed, [CLOSED_NO_SALE] otherwise (the
 * listing fee is NOT refunded in that case -- pure spam-deterrent cost, see file header).
 */
@Serializable
enum class AuctionStatus { OPEN, SETTLED, CLOSED_NO_SALE }

/**
 * Role: MEMBER+, caller must be [MemberStatus.ACTIVE] and `OrganizationSettings.auctionEnabled`
 * must be `true` (see [network.lapis.cloud.shared.rpc.IAuctionService] KDoc "The auctionEnabled
 * gate"). [buyNowPriceLtr], if set, must be strictly greater than [startingBidLtr]. If an ADMIN
 * has configured `OrganizationSettings.auctionMaxValueLtr`, both [startingBidLtr] and
 * [buyNowPriceLtr] must not exceed it -- see that field's own KDoc. [durationHours] is bounded
 * server-side (spam/DoS guard), not modelled as a client-facing constant on this DTO.
 */
@Serializable
data class CreateAuctionListingInput(
    val title: String,
    val description: String,
    val startingBidLtr: Decimal,
    val buyNowPriceLtr: Decimal? = null,
    val durationHours: Int,
)

/**
 * One auction listing. [status] is the PERSISTED database status; [effectiveStatus] additionally
 * reflects a lazy-close that WOULD happen on the next mutating touch (`endsAt` already passed
 * while [status] is still [AuctionStatus.OPEN]) without actually writing it -- same "computed view
 * over a persisted PENDING state" idiom `CrowdfundingProjectDto.effectiveStatus`/`isAutoApproved`
 * already establish. [currentPriceLtr]/[currentLeaderDisplayName]/[leaderIsMe] reflect the live
 * proxy-bid outcome while [status]/[effectiveStatus] == OPEN, and the frozen, persisted sale
 * figures ([winnerMemberId]/[finalPriceLtr]) once SETTLED. **[AuctionBidDto.maxBidLtr] of any
 * OTHER member is never exposed anywhere, including here** -- proxy-auction integrity, see file
 * header "max bids never public".
 */
@Serializable
data class AuctionDto(
    val id: String,
    val title: String,
    val description: String,
    val sellerMemberId: String,
    val sellerDisplayName: String,
    val startingBidLtr: Decimal,
    val buyNowPriceLtr: Decimal?,
    val status: AuctionStatus,
    val effectiveStatus: AuctionStatus,
    val currentPriceLtr: Decimal?,
    val currentLeaderDisplayName: String?,
    val leaderIsMe: Boolean,
    val bidCount: Int,
    val winnerMemberId: String?,
    val winnerDisplayName: String?,
    val finalPriceLtr: Decimal?,
    val listingFeeLtr: Decimal,
    val createdAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val settledAt: LocalDateTime?,
)

/**
 * One of the CALLER's own bids -- returned only from
 * [network.lapis.cloud.shared.rpc.IAuctionService.listMyBids], never surfaced for any other
 * member's bids (see [AuctionDto] KDoc "max bids never public").
 */
@Serializable
data class AuctionBidDto(
    val id: String,
    val auctionId: String,
    val auctionTitle: String,
    val maxBidLtr: Decimal,
    val createdAt: LocalDateTime,
    val isCurrentLeader: Boolean,
    val auctionStatus: AuctionStatus,
)

/** Immediate result of [network.lapis.cloud.shared.rpc.IAuctionService.placeBid]. */
@Serializable
data class AuctionBidResultDto(
    val auctionId: String,
    val accepted: Boolean,
    val youAreLeader: Boolean,
    val currentPriceLtr: Decimal,
    val yourMaxBidLtr: Decimal,
    val endsAt: LocalDateTime,
)

/**
 * The current, versioned+hashed legal disclaimer text an ADMIN must echo back (unmodified) to
 * [network.lapis.cloud.shared.rpc.IAuctionService.enableAuction] -- see that method's KDoc for the
 * full acknowledgment mechanism.
 */
@Serializable
data class AuctionComplianceDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/**
 * Proof that the ADMIN calling [network.lapis.cloud.shared.rpc.IAuctionService.enableAuction] was
 * shown the CURRENT disclaimer text -- both fields must match
 * [AuctionComplianceDisclaimerDto.version]/[AuctionComplianceDisclaimerDto.sha256] exactly (server
 * re-verifies both, constant-time hash comparison), or the call is rejected with no side effect.
 */
@Serializable
data class AuctionComplianceAcknowledgmentInput(
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)

/**
 * Role: ADMIN (every field). [lastAcknowledgedByDisplayName]/[lastAcknowledgedAt]/
 * [lastDisclaimerVersion] are all `null` if [auctionEnabled] has never been switched on via
 * [network.lapis.cloud.shared.rpc.IAuctionService.enableAuction] -- the acknowledgment history
 * itself is append-only and outlives a later [network.lapis.cloud.shared.rpc.IAuctionService.disableAuction].
 */
@Serializable
data class AuctionSettingsDto(
    val auctionEnabled: Boolean,
    val auctionMaxValueLtr: Decimal?,
    val lastAcknowledgedByDisplayName: String?,
    val lastAcknowledgedAt: LocalDateTime?,
    val lastDisclaimerVersion: String?,
)
