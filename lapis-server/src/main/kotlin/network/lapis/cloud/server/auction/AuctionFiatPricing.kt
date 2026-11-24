package network.lapis.cloud.server.auction

import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.rpc.FiatBidView
import network.lapis.cloud.server.rpc.computeFiatAuctionOutcome
import network.lapis.cloud.shared.domain.AuctionCurrencies
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** The visible price of a Fiat auction: the second price, or the starting price without a bid. */
internal class FiatPrice(
    val minor: Long,
    val hasBids: Boolean,
)

/**
 * V1.9.322 (step c2): the ONE source of the visible price of a Fiat auction. The detail view, `acceptOffer` and the partner feed all read it, so the
 * price a partner sees is derived from exactly the value the origin shows (only later, throttled). Runs inside the caller's transaction.
 */
internal object AuctionFiatPricing {
    fun bidViews(auctionId: Uuid): List<FiatBidView> =
        AuctionBidTable
            .selectAll()
            .where { AuctionBidTable.auctionId eq auctionId }
            .mapNotNull { row ->
                val maxBid = row[AuctionBidTable.maxBidMinor] ?: return@mapNotNull null
                FiatBidView(
                    bidId = row[AuctionBidTable.id],
                    bidderMemberId = row[AuctionBidTable.bidderMemberId],
                    maxBidMinor = maxBid,
                    createdAt = row[AuctionBidTable.createdAt],
                )
            }

    fun visiblePrice(auction: ResultRow): FiatPrice {
        val outcome =
            computeFiatAuctionOutcome(
                startingMinor = auction[AuctionTable.startingPriceMinor]!!,
                minIncrementMinor = AuctionCurrencies.MIN_INCREMENT_MINOR,
                bids = bidViews(auction[AuctionTable.id]),
            )
        return FiatPrice(
            minor = outcome.currentPriceMinor ?: auction[AuctionTable.startingPriceMinor]!!,
            hasBids = outcome.leaderMemberId != null,
        )
    }
}
