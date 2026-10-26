package network.lapis.cloud.server.dashboard.sources

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalGates
import network.lapis.cloud.server.dashboard.signal
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.rpc.AuctionBidView
import network.lapis.cloud.server.rpc.MIN_INCREMENT_LTR
import network.lapis.cloud.server.rpc.computeAuctionOutcome
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.224 -- auction signals. Only the caller's own bids and own auctions. "Outbid" is decided by [computeAuctionOutcome] (proxy bids and
 * a tie broken by the earlier bid), never by a naive maximum: the bids of other people are loaded for that one computation only and
 * nothing of them (not a name, not an amount) reaches the signal. Auctions are class A (`ends_at`, `settled_at` are UTC stamps).
 */
internal object AuctionSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.AUCTION

    private const val MAX_AUCTIONS = 50
    private const val SETTLE_LAG_DAYS = 14

    private data class RunningAuction(
        val id: Uuid,
        val startingBid: BigDecimal,
        val endsAt: LocalDateTime,
    )

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!ctx.flags.auctionEnabled || !SignalGates.showsMemberOnlyEconomy(ctx.status)) return emptyList()
        return (bidderSignals(ctx) + listOf(ownHasBids(ctx), won(ctx), sold(ctx))).filterNotNull()
    }

    private fun bidderSignals(ctx: SignalContext): List<DashboardSignalDto?> {
        val running =
            AuctionBidTable
                .innerJoin(AuctionTable)
                .select(AuctionTable.id, AuctionTable.startingBidLtr, AuctionTable.endsAt)
                .where {
                    (AuctionBidTable.bidderMemberId eq ctx.memberId) and
                        (AuctionTable.status eq AuctionStatus.OPEN) and
                        (AuctionTable.endsAt greater ctx.nowSystem)
                }.orderBy(AuctionTable.endsAt to SortOrder.ASC, AuctionTable.id to SortOrder.ASC)
                .limit(MAX_AUCTIONS)
                .map {
                    RunningAuction(
                        id = it[AuctionTable.id],
                        startingBid = it[AuctionTable.startingBidLtr],
                        endsAt = it[AuctionTable.endsAt],
                    )
                }
        if (running.isEmpty()) return emptyList()
        val bidsByAuction =
            AuctionBidTable
                .selectAll()
                .where { AuctionBidTable.auctionId inList running.map { it.id } }
                .groupBy({ it[AuctionBidTable.auctionId] }) {
                    AuctionBidView(
                        bidId = it[AuctionBidTable.id],
                        bidderMemberId = it[AuctionBidTable.bidderMemberId],
                        maxBidLtr = it[AuctionBidTable.maxBidLtr],
                        createdAt = it[AuctionBidTable.createdAt],
                    )
                }
        val soon = ctx.systemPlus(24.hours)
        val (outbid, leading) =
            running.partition { auction ->
                val outcome =
                    computeAuctionOutcome(
                        startingBidLtr = auction.startingBid,
                        minIncrementLtr = MIN_INCREMENT_LTR,
                        bids = bidsByAuction[auction.id].orEmpty(),
                    )
                outcome.leaderMemberId != ctx.memberId
            }
        val leadingSoon = leading.filter { it.endsAt <= soon }
        return listOf(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.AUCTION_OUTBID,
                count = outbid.size,
                priority = if (outbid.any { it.endsAt <= soon }) DashboardPriority.URGENT else DashboardPriority.ACTION,
                objectId = outbid.firstOrNull()?.id,
                deadlineWall = outbid.minOfOrNull { it.endsAt }?.let(ctx::systemToWall),
            ),
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.AUCTION_ENDING_SOON,
                count = leadingSoon.size,
                priority = DashboardPriority.INFO,
                objectId = leadingSoon.firstOrNull()?.id,
                deadlineWall = leadingSoon.minOfOrNull { it.endsAt }?.let(ctx::systemToWall),
            ),
        )
    }

    /** Own running auctions with at least one bid. The seller sees the number of bids on the own auction anyway. */
    private fun ownHasBids(ctx: SignalContext): DashboardSignalDto? {
        val own =
            AuctionTable
                .select(AuctionTable.id)
                .where {
                    (AuctionTable.sellerMemberId eq ctx.memberId) and
                        (AuctionTable.status eq AuctionStatus.OPEN) and
                        (AuctionTable.endsAt greater ctx.nowSystem)
                }.orderBy(AuctionTable.id to SortOrder.ASC)
                .limit(MAX_AUCTIONS)
                .map { it[AuctionTable.id] }
        if (own.isEmpty()) return null
        val bidCount = AuctionBidTable.id.count()
        val withBids =
            AuctionBidTable
                .select(AuctionBidTable.auctionId, bidCount)
                .where { AuctionBidTable.auctionId inList own }
                .groupBy(AuctionBidTable.auctionId)
                .orderBy(AuctionBidTable.auctionId to SortOrder.ASC)
                .associate { it[AuctionBidTable.auctionId] to it[bidCount].toInt() }
        return signal(
            module = moduleKey,
            reason = DashboardReasonKeys.AUCTION_OWN_HAS_BIDS,
            count = withBids.size,
            priority = DashboardPriority.INFO,
            params = if (withBids.size == 1) mapOf("bids" to withBids.values.single().toString()) else emptyMap(),
            objectId = withBids.keys.firstOrNull(),
        )
    }

    /**
     * A finished auction I won / sold, for seven days. The winner of a buy-now may have no bid row, so the winner query cannot start from
     * `auction_bid`; the `ends_at` bound keeps the
     * index on `ends_at` usable. ASSUMPTION: an auction is settled within [SETTLE_LAG_DAYS] days of ending (the settlement poller may be
     * down for a while); an auction settled later than that gets no winner signal, while [sold] has no such bound.
     */
    private fun won(ctx: SignalContext): DashboardSignalDto? {
        val since = ctx.systemPlus((-7).days)
        val rows =
            AuctionTable
                .select(AuctionTable.id)
                .where {
                    (AuctionTable.endsAt greaterEq ctx.systemPlus((-(7 + SETTLE_LAG_DAYS)).days)) and
                        (AuctionTable.status eq AuctionStatus.SETTLED) and
                        (AuctionTable.winnerMemberId eq ctx.memberId) and
                        (AuctionTable.settledAt greaterEq since)
                }.orderBy(AuctionTable.id to SortOrder.ASC)
                .limit(MAX_AUCTIONS)
                .map { it[AuctionTable.id] }
        return signal(
            module = moduleKey,
            reason = DashboardReasonKeys.AUCTION_WON,
            count = rows.size,
            priority = DashboardPriority.INFO,
            objectId = rows.firstOrNull(),
        )
    }

    private fun sold(ctx: SignalContext): DashboardSignalDto? {
        val since = ctx.systemPlus((-7).days)
        val rows =
            AuctionTable
                .select(AuctionTable.id)
                .where {
                    (AuctionTable.sellerMemberId eq ctx.memberId) and
                        (AuctionTable.status eq AuctionStatus.SETTLED) and
                        (AuctionTable.settledAt greaterEq since)
                }.orderBy(AuctionTable.id to SortOrder.ASC)
                .limit(MAX_AUCTIONS)
                .map { it[AuctionTable.id] }
        return signal(
            module = moduleKey,
            reason = DashboardReasonKeys.AUCTION_SOLD,
            count = rows.size,
            priority = DashboardPriority.INFO,
            objectId = rows.firstOrNull(),
        )
    }
}
