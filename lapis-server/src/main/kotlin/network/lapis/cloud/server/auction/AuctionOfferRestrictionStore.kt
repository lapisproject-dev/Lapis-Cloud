package network.lapis.cloud.server.auction

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AuctionOfferBuyerRestrictionTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuctionOfferRules
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.323 -- the decline counter and the time-limited block of ONE buyer on ONE auction (`auction_offer_buyer_restriction`). **Pure bookkeeping:
 * nothing here touches money or a ledger.**
 *
 * ## Rules
 *
 * - Every DECLINED proposal of the buyer (a counter-offer the buyer declines does not count) adds one to `declines_in_cycle`.
 * - The [AuctionOfferRules.MAX_DECLINES_BEFORE_BLOCK]th decline sets `blocked_until = now + DECLINE_BLOCK_DURATION` and the counter back to 0.
 * - A block holds while `blocked_until > now`. Afterwards a NEW cycle starts: the old `blocked_until` is simply in the past, the counter is 0.
 * - The buyer is blocked on THIS auction only. Nobody can lift the block early (no seller override).
 *
 * ## Locking
 *
 * **The only writer is [recordDeclineLocked], and it requires the AUCTION row lock** (`AuctionService.lockAndCloseIfDue`), which makes this
 * caller the only writer for that auction: a plain "SELECT ... FOR UPDATE, insert if absent" is then race-free (no MERGE / ON CONFLICT, which H2
 * and PostgreSQL treat differently). Lock order: auction -> offer -> restriction row -> audit chain row (last). Reading functions never write: an
 * expired block is not cleaned up, it is only read as "not blocked".
 */
internal object AuctionOfferRestrictionStore {
    private fun find(
        auctionId: Uuid,
        buyerId: Uuid,
    ): ResultRow? =
        AuctionOfferBuyerRestrictionTable
            .selectAll()
            .where {
                (AuctionOfferBuyerRestrictionTable.auctionId eq auctionId) and
                    (AuctionOfferBuyerRestrictionTable.buyerMemberId eq buyerId)
            }.singleOrNull()

    private fun blockedUntilOf(
        row: ResultRow?,
        now: LocalDateTime,
    ): LocalDateTime? = row?.get(AuctionOfferBuyerRestrictionTable.blockedUntil)?.takeIf { it > now }

    /** The end of the buyer's block on the auction, `null` if he is not blocked right now. Read only. */
    fun blockedUntil(
        auctionId: Uuid,
        buyerId: Uuid,
        now: LocalDateTime,
    ): LocalDateTime? = blockedUntilOf(row = find(auctionId = auctionId, buyerId = buyerId), now = now)

    /** Declines of the current cycle (0 while blocked: the counter was reset when the block started). Read only. */
    fun declinesInCycle(
        auctionId: Uuid,
        buyerId: Uuid,
        now: LocalDateTime,
    ): Int {
        val row = find(auctionId = auctionId, buyerId = buyerId) ?: return 0
        return if (blockedUntilOf(row = row, now = now) != null) 0 else row[AuctionOfferBuyerRestrictionTable.declinesInCycle]
    }

    /**
     * One more declined proposal of [buyerId]. Caller holds the AUCTION row lock. Returns the new end of the block if THIS decline started one, else
     * `null`. A block that is still running is left alone (it cannot be extended, and a proposal cannot be declined while the buyer is blocked
     * anyway: he cannot have one open).
     */
    fun recordDeclineLocked(
        auctionId: Uuid,
        buyerId: Uuid,
        now: LocalDateTime,
    ): LocalDateTime? {
        val row =
            AuctionOfferBuyerRestrictionTable
                .selectAll()
                .where {
                    (AuctionOfferBuyerRestrictionTable.auctionId eq auctionId) and
                        (AuctionOfferBuyerRestrictionTable.buyerMemberId eq buyerId)
                }.forUpdate()
                .singleOrNull()
        val activeBlock = blockedUntilOf(row = row, now = now)
        if (activeBlock != null) return null
        val declines = (row?.get(AuctionOfferBuyerRestrictionTable.declinesInCycle) ?: 0) + 1
        val blocks = declines >= AuctionOfferRules.MAX_DECLINES_BEFORE_BLOCK
        val newCounter = if (blocks) 0 else declines
        val newBlock = if (blocks) plus(now = now) else null
        if (row == null) {
            AuctionOfferBuyerRestrictionTable.insert {
                it[AuctionOfferBuyerRestrictionTable.id] = Uuid.random()
                it[AuctionOfferBuyerRestrictionTable.auctionId] = auctionId
                it[AuctionOfferBuyerRestrictionTable.buyerMemberId] = buyerId
                it[AuctionOfferBuyerRestrictionTable.declinesInCycle] = newCounter
                it[AuctionOfferBuyerRestrictionTable.blockedUntil] = newBlock
                it[AuctionOfferBuyerRestrictionTable.updatedAt] = now
            }
        } else {
            AuctionOfferBuyerRestrictionTable.update(
                { AuctionOfferBuyerRestrictionTable.id eq row[AuctionOfferBuyerRestrictionTable.id] },
            ) {
                it[AuctionOfferBuyerRestrictionTable.declinesInCycle] = newCounter
                // An expired block of an earlier cycle is replaced (a new cycle) or cleared; it never lingers next to a fresh counter.
                it[AuctionOfferBuyerRestrictionTable.blockedUntil] = newBlock
                it[AuctionOfferBuyerRestrictionTable.updatedAt] = now
            }
        }
        return newBlock
    }

    /** `now + DECLINE_BLOCK_DURATION` as a UTC local date-time (the whole schema stores UTC `TIMESTAMP`s). */
    private fun plus(now: LocalDateTime): LocalDateTime =
        now.toInstant(ServerClock.zone).plus(AuctionOfferRules.DECLINE_BLOCK_DURATION).toLocalDateTime(ServerClock.zone)

    /** The seller's view of ONE auction: per buyer the declines of the current cycle and the end of a running block. Read only. */
    fun listForAuction(
        auctionId: Uuid,
        now: LocalDateTime,
    ): Map<Uuid, Pair<Int, LocalDateTime?>> =
        AuctionOfferBuyerRestrictionTable
            .selectAll()
            .where { AuctionOfferBuyerRestrictionTable.auctionId eq auctionId }
            .associate { row ->
                val blocked = blockedUntilOf(row = row, now = now)
                row[AuctionOfferBuyerRestrictionTable.buyerMemberId] to
                    ((if (blocked != null) 0 else row[AuctionOfferBuyerRestrictionTable.declinesInCycle]) to blocked)
            }

    /** For a list of auctions: the running block of [buyerId] on each of them (absent = not blocked). One query, read only. */
    fun blockedUntilFor(
        auctionIds: Collection<Uuid>,
        buyerId: Uuid,
        now: LocalDateTime,
    ): Map<Uuid, LocalDateTime> {
        if (auctionIds.isEmpty()) return emptyMap()
        return AuctionOfferBuyerRestrictionTable
            .selectAll()
            .where {
                (AuctionOfferBuyerRestrictionTable.buyerMemberId eq buyerId) and
                    (AuctionOfferBuyerRestrictionTable.auctionId inList auctionIds.toList())
            }.mapNotNull { row ->
                blockedUntilOf(row = row, now = now)?.let { row[AuctionOfferBuyerRestrictionTable.auctionId] to it }
            }.toMap()
    }

    /** Member erasure (Art. 17): the buyer's counters and blocks are only useful while an auction runs. */
    fun deleteForMember(memberId: Uuid): Int =
        AuctionOfferBuyerRestrictionTable.deleteWhere { AuctionOfferBuyerRestrictionTable.buyerMemberId eq memberId }

    /** Export (Art. 15): the member's own rows. */
    fun rowsOfMember(memberId: Uuid): List<ResultRow> =
        AuctionOfferBuyerRestrictionTable.selectAll().where { AuctionOfferBuyerRestrictionTable.buyerMemberId eq memberId }.toList()
}
