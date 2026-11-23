package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/**
 * One bidder's CURRENT standing maximum on one auction -- the pure-function input shape for
 * [computeAuctionOutcome], deliberately decoupled from
 * [network.lapis.cloud.server.db.generated.AuctionBidTable] so this file has zero DB dependency
 * and can be property-tested directly (see `AuctionOutcomeTest`). [bids] passed to
 * [computeAuctionOutcome] must contain AT MOST ONE [AuctionBidView] per [bidderMemberId] -- this
 * always holds in production because `auction_bid` is itself an upsert table, one row per
 * (auction, bidder) (see `21-auction.kuml.kts` file header "auction_bid is an upsert table").
 */
data class AuctionBidView(
    val bidId: Uuid,
    val bidderMemberId: Uuid,
    val maxBidLtr: BigDecimal,
    val createdAt: LocalDateTime,
)

/**
 * Result of [computeAuctionOutcome]. [leaderMemberId]/[leaderMaxBidLtr]/[currentPriceLtr] are all
 * `null` together iff [bids] was empty (no bids at all yet). [leaderMaxBidLtr] is the actual
 * amount [network.lapis.cloud.server.rpc.AuctionService] holds (`AUCTION_HOLD`) for the leader --
 * NOT the same value as [currentPriceLtr], which is what they would actually pay if the auction
 * settled right now (see `21-auction.kuml.kts` file header "Reservation model").
 */
data class AuctionOutcome(
    val leaderMemberId: Uuid?,
    val leaderMaxBidLtr: BigDecimal?,
    val currentPriceLtr: BigDecimal?,
    val bidCount: Int,
)

/**
 * The englische Proxy-Bid/Second-Price auction settlement math -- see `21-auction.kuml.kts` file
 * header for why this is a deliberately separate, simpler function from
 * [computeVickreySettlement] (that one apportions a second price across MULTIPLE simultaneous
 * winning ballots in a basket vote; an auction has exactly ONE winning slot, no apportionment
 * needed). Pure function, no DB access -- the single most manipulation-sensitive piece of this
 * wave (a bug here directly changes who wins an auction and/or what they pay), exhaustively
 * property-tested via `AuctionOutcomeTest`.
 *
 * Algorithm:
 * 1. If [bids] is empty, the result is entirely `null`/`0` -- no leader, no price.
 * 2. Otherwise the LEADER is the bid with the strictly highest [AuctionBidView.maxBidLtr]. A tie
 *    at the highest maximum is broken by the EARLIEST [AuctionBidView.createdAt] (whoever reached
 *    that maximum first), then by the smallest [AuctionBidView.bidId] as a final, fully
 *    deterministic tie-break (extremely unlikely to ever matter in practice).
 * 3. `secondMax` is the highest [AuctionBidView.maxBidLtr] among every bid NOT placed by the
 *    leader (a leader's OWN other/earlier bids, if any survived as a distinct row, never count as
 *    competition against themselves -- moot in production since `auction_bid` holds at most one
 *    row per bidder, but this function does not rely on that invariant for its own correctness).
 * 4. [AuctionOutcome.currentPriceLtr] is [startingBidLtr] if there is no `secondMax` (an
 *    uncontested auction, the leader would pay only the floor), otherwise
 *    `min(leaderMaxBidLtr, secondMax + minIncrementLtr)`, floored again at [startingBidLtr] (a
 *    defensive floor -- unreachable in production, where every persisted bid is already validated
 *    `>= startingBidLtr` before it can reach this function, but this function makes no such
 *    assumption about its own [bids] input).
 *
 * Deterministic: the same [bids] input always produces the same [AuctionOutcome].
 */
fun computeAuctionOutcome(
    startingBidLtr: BigDecimal,
    minIncrementLtr: BigDecimal,
    bids: List<AuctionBidView>,
): AuctionOutcome {
    if (bids.isEmpty()) return AuctionOutcome(leaderMemberId = null, leaderMaxBidLtr = null, currentPriceLtr = null, bidCount = 0)

    val leader =
        bids
            .sortedWith(
                compareByDescending<AuctionBidView> { it.maxBidLtr }
                    .thenBy { it.createdAt }
                    .thenBy { it.bidId.toString() },
            ).first()

    val secondMax = bids.filter { it.bidderMemberId != leader.bidderMemberId }.maxOfOrNull { it.maxBidLtr }

    val currentPrice =
        if (secondMax == null) {
            startingBidLtr
        } else {
            val bumped = (secondMax + minIncrementLtr).setScale(2, RoundingMode.UNNECESSARY)
            val capped = if (bumped > leader.maxBidLtr) leader.maxBidLtr else bumped
            if (capped < startingBidLtr) startingBidLtr else capped
        }

    return AuctionOutcome(
        leaderMemberId = leader.bidderMemberId,
        leaderMaxBidLtr = leader.maxBidLtr,
        currentPriceLtr = currentPrice.setScale(2, RoundingMode.UNNECESSARY),
        bidCount = bids.size,
    )
}

// ── V1.9.320 Fiat (minor-unit) variant ────────────────────────────────────────────────────────────

/** One bidder's CURRENT standing maximum on one FIAT auction, in minor units. Same contract as [AuctionBidView] (at most one per bidder). */
data class FiatBidView(
    val bidId: Uuid,
    val bidderMemberId: Uuid,
    val maxBidMinor: Long,
    val createdAt: LocalDateTime,
)

/** Result of [computeFiatAuctionOutcome]; all three values are `null` together iff there is no bid. */
data class FiatAuctionOutcome(
    val leaderMemberId: Uuid?,
    val leaderMaxBidMinor: Long?,
    val currentPriceMinor: Long?,
    val bidCount: Int,
)

/**
 * The proxy-bid / second-price settlement math of [computeAuctionOutcome] for a Fiat auction, in exact `Long` minor units (no decimal scale at
 * all, so no rounding path exists). Identical rules: the leader has the strictly highest maximum, a tie goes to the EARLIEST
 * [FiatBidView.createdAt], then to the smallest bid id; the price is the starting price without a competitor, otherwise
 * `min(leaderMax, secondMax + minIncrement)` floored at the starting price. `Math.addExact` keeps an absurd input from wrapping silently.
 * Property-tested against [computeAuctionOutcome] (`AuctionFiatOutcomeTest`): the two must always agree.
 */
fun computeFiatAuctionOutcome(
    startingMinor: Long,
    minIncrementMinor: Long,
    bids: List<FiatBidView>,
): FiatAuctionOutcome {
    if (bids.isEmpty()) return FiatAuctionOutcome(leaderMemberId = null, leaderMaxBidMinor = null, currentPriceMinor = null, bidCount = 0)

    val leader =
        bids
            .sortedWith(
                compareByDescending<FiatBidView> { it.maxBidMinor }
                    .thenBy { it.createdAt }
                    .thenBy { it.bidId.toString() },
            ).first()

    val secondMax = bids.filter { it.bidderMemberId != leader.bidderMemberId }.maxOfOrNull { it.maxBidMinor }

    val currentPrice =
        if (secondMax == null) {
            startingMinor
        } else {
            val bumped = Math.addExact(secondMax, minIncrementMinor)
            val capped = if (bumped > leader.maxBidMinor) leader.maxBidMinor else bumped
            if (capped < startingMinor) startingMinor else capped
        }

    return FiatAuctionOutcome(
        leaderMemberId = leader.bidderMemberId,
        leaderMaxBidMinor = leader.maxBidMinor,
        currentPriceMinor = currentPrice,
        bidCount = bids.size,
    )
}
