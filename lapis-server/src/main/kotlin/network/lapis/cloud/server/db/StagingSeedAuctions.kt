package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.StagingSeedLedger.covers
import network.lapis.cloud.server.db.StagingSeedLedger.insertRows
import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.rpc.AuctionBidView
import network.lapis.cloud.server.rpc.computeAuctionOutcome
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Sample data for auctions (V1.9.205): five auctions in the states the model knows (open, settled by time, closed without a sale, settled by
 * instant purchase), with the exact ledger chain `AuctionService` writes: listing fee, hold on the current leader, release on a change of lead,
 * and at the end release + sale out/in at the second price. The chain is DERIVED from the scripted bids by the live [computeAuctionOutcome], so
 * rows, bids and prices cannot drift apart. The model has no "cancelled" status; the instant purchase stands in for it.
 *
 * `auctionEnabled` is deliberately NOT switched on (an administrator's decision with a compliance acknowledgement): the data waits for it.
 */
internal object StagingSeedAuctions : StagingSeedPart {
    override val name: String = "auctions"

    private val LISTING_FEE: BigDecimal = BigDecimal("0.01")
    private val MIN_INCREMENT: BigDecimal = BigDecimal("0.01")

    data class ScriptBid(
        val bidder: Int,
        val max: String,
        val daysAgo: Int,
        val hour: Int,
    )

    sealed interface Ending {
        data object Open : Ending

        data class SettledByTime(
            val endedDaysAgo: Int,
        ) : Ending

        data class NoSale(
            val endedDaysAgo: Int,
        ) : Ending

        data class InstantPurchase(
            val buyer: Int,
            val daysAgo: Int,
            val hour: Int,
        ) : Ending
    }

    data class PlannedAuction(
        val index: Int,
        val title: String,
        val description: String,
        val seller: Int,
        val startingBid: String,
        val buyNowPrice: String?,
        val createdDaysAgo: Int,
        /** Days from today until the auction ends (negative: it ended that many days ago). */
        val endsInDays: Int,
        val bids: List<ScriptBid>,
        val ending: Ending,
    ) {
        val id: Uuid get() = SeedIds.auctions(index)
    }

    /** The result of replaying the script: ledger rows, the final bid per bidder and the outcome. */
    data class Derived(
        val ledger: List<SeedLedgerRow>,
        val bids: List<DerivedBid>,
        val status: AuctionStatus,
        val winner: Int?,
        val finalPrice: BigDecimal?,
        val settledAt: LocalDateTime?,
        val participants: Set<Int>,
    )

    data class DerivedBid(
        val id: Uuid,
        val bidder: Int,
        val max: BigDecimal,
        val createdAt: LocalDateTime,
    )

    /** Append-only: the index is the id. Seller and bidders differ in every auction; every bid is at least the starting bid. */
    val auctions: List<PlannedAuction> =
        listOf(
            PlannedAuction(
                index = 1,
                title = "Gebrauchter Beamer (Demodaten)",
                description = "Ein gut erhaltener Beamer mit Tasche. Fiktives Beispiel der Demodaten, laeuft noch fuenf Tage.",
                seller = 2,
                startingBid = "5.00",
                buyNowPrice = "40.00",
                createdDaysAgo = 2,
                endsInDays = 5,
                bids =
                    listOf(
                        ScriptBid(bidder = 4, max = "12.00", daysAgo = 2, hour = 15),
                        ScriptBid(bidder = 9, max = "18.00", daysAgo = 1, hour = 10),
                        ScriptBid(bidder = 1, max = "25.00", daysAgo = 1, hour = 16),
                    ),
                ending = Ending.Open,
            ),
            PlannedAuction(
                index = 2,
                title = "Stehtisch fuer das Vereinsheim (Demodaten)",
                description = "Hoehenverstellbarer Stehtisch. Fiktives Beispiel der Demodaten, nach Ablauf der Frist verkauft.",
                seller = 1,
                startingBid = "10.00",
                buyNowPrice = null,
                createdDaysAgo = 14,
                endsInDays = -10,
                bids =
                    listOf(
                        ScriptBid(bidder = 3, max = "30.00", daysAgo = 13, hour = 10),
                        ScriptBid(bidder = 10, max = "22.00", daysAgo = 12, hour = 10),
                    ),
                ending = Ending.SettledByTime(endedDaysAgo = 10),
            ),
            PlannedAuction(
                index = 3,
                title = "Alte Vereinsfahne (Demodaten)",
                description = "Eine Fahne ohne Gebote. Fiktives Beispiel der Demodaten, ohne Verkauf geschlossen, die Gebuehr verfaellt.",
                seller = 3,
                startingBid = "8.00",
                buyNowPrice = null,
                createdDaysAgo = 10,
                endsInDays = -3,
                bids = emptyList(),
                ending = Ending.NoSale(endedDaysAgo = 3),
            ),
            PlannedAuction(
                index = 4,
                title = "Kaffeemaschine (Demodaten)",
                description = "Eine Filterkaffeemaschine. Fiktives Beispiel der Demodaten, per Sofortkauf abgeschlossen.",
                seller = 4,
                startingBid = "3.00",
                buyNowPrice = "15.00",
                createdDaysAgo = 6,
                endsInDays = 1,
                bids = listOf(ScriptBid(bidder = 6, max = "8.00", daysAgo = 5, hour = 10)),
                ending = Ending.InstantPurchase(buyer = 5, daysAgo = 4, hour = 11),
            ),
            PlannedAuction(
                index = 5,
                title = "Gartenwerkzeug-Set (Demodaten)",
                description = "Spaten, Harke und Handschaufel. Fiktives Beispiel der Demodaten, endet morgen, genau ein Gebot.",
                seller = 5,
                startingBid = "2.00",
                buyNowPrice = null,
                createdDaysAgo = 7,
                endsInDays = 1,
                bids = listOf(ScriptBid(bidder = 21, max = "4.00", daysAgo = 1, hour = 9)),
                ending = Ending.Open,
            ),
        )

    fun endsAtOf(
        auction: PlannedAuction,
        clock: SeedClock,
    ): LocalDateTime =
        if (auction.endsInDays > 0) {
            clock.utcShift(base = clock.now, byDays = auction.endsInDays)
        } else {
            clock.utcAt(daysAgo = -auction.endsInDays, hour = 12)
        }

    /** Replays the scripted bids like `placeBid` and the ending like `settleAuctionLocked` / `buyNow`. */
    fun derive(
        auction: PlannedAuction,
        clock: SeedClock,
    ): Derived {
        val auctionId = auction.id
        val startingBid = BigDecimal(auction.startingBid)
        val numberOfId = mutableMapOf<Uuid, Int>()
        var ledgerCounter = 0
        val ledger = mutableListOf<SeedLedgerRow>()

        fun ledgerAdd(
            member: Int,
            type: LtrLedgerEntryType,
            amount: BigDecimal,
            at: LocalDateTime,
            text: String,
        ) {
            ledger +=
                SeedLedgerRow(
                    id = SeedIds.auctions(0x1000 + auction.index * 0x20 + ledgerCounter++),
                    member = member,
                    type = type,
                    amount = amount,
                    referenceType = LtrLedgerReferenceType.AUCTION,
                    referenceId = auctionId,
                    note = StagingSeedLedger.note(text),
                    at = at,
                )
        }
        val created = clock.utcAt(daysAgo = auction.createdDaysAgo, hour = 10)
        ledgerAdd(auction.seller, LtrLedgerEntryType.AUCTION_LISTING_FEE, LISTING_FEE.negate(), created, "Einstellgebuehr Auktion")

        val bidState = linkedMapOf<Int, DerivedBid>()
        val slots = mutableMapOf<Int, Int>()

        fun views(): List<AuctionBidView> =
            bidState.values.map {
                AuctionBidView(bidId = it.id, bidderMemberId = SeedIds.member(it.bidder), maxBidLtr = it.max, createdAt = it.createdAt)
            }

        fun outcome() = computeAuctionOutcome(startingBidLtr = startingBid, minIncrementLtr = MIN_INCREMENT, bids = views())
        auction.bids.forEach { scripted ->
            val at = clock.utcAt(daysAgo = scripted.daysAgo, hour = scripted.hour)
            val max = BigDecimal(scripted.max)
            val before = outcome()
            val wasLeader = before.leaderMemberId == SeedIds.member(scripted.bidder)
            val slot = slots.getOrPut(scripted.bidder) { slots.size + 1 }
            val bidId = SeedIds.auctions(0x2000 + auction.index * 0x10 + slot)
            numberOfId[bidId] = scripted.bidder
            bidState[scripted.bidder] = DerivedBid(id = bidId, bidder = scripted.bidder, max = max, createdAt = at)
            val after = outcome()
            val isNowLeader = after.leaderMemberId == SeedIds.member(scripted.bidder)
            val formerLeader = before.leaderMemberId?.let { leaderId -> (1..40).first { SeedIds.member(it) == leaderId } }
            if (wasLeader) {
                ledgerAdd(
                    scripted.bidder,
                    LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                    before.leaderMaxBidLtr!!,
                    at,
                    "Reservierung freigegeben",
                )
                ledgerAdd(scripted.bidder, LtrLedgerEntryType.AUCTION_HOLD, max.negate(), at, "Reservierung fuer Hoechstgebot")
            } else if (isNowLeader) {
                if (formerLeader != null) {
                    ledgerAdd(
                        formerLeader,
                        LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                        before.leaderMaxBidLtr!!,
                        at,
                        "Reservierung freigegeben",
                    )
                }
                ledgerAdd(scripted.bidder, LtrLedgerEntryType.AUCTION_HOLD, max.negate(), at, "Reservierung fuer Hoechstgebot")
            }
        }

        val finalOutcome = outcome()
        val leaderNumber = finalOutcome.leaderMemberId?.let { leaderId -> (1..40).first { SeedIds.member(it) == leaderId } }
        var status = AuctionStatus.OPEN
        var winner: Int? = null
        var finalPrice: BigDecimal? = null
        var settledAt: LocalDateTime? = null
        when (val ending = auction.ending) {
            Ending.Open -> Unit
            is Ending.NoSale -> {
                check(leaderNumber == null) { "a closed-without-sale auction must have no bids" }
                status = AuctionStatus.CLOSED_NO_SALE
                settledAt = clock.utcAt(daysAgo = ending.endedDaysAgo, hour = 12)
            }
            is Ending.SettledByTime -> {
                val at = clock.utcAt(daysAgo = ending.endedDaysAgo, hour = 12)
                val price = finalOutcome.currentPriceLtr!!
                ledgerAdd(
                    leaderNumber!!,
                    LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                    finalOutcome.leaderMaxBidLtr!!,
                    at,
                    "Reservierung freigegeben",
                )
                ledgerAdd(leaderNumber, LtrLedgerEntryType.AUCTION_SALE_OUT, price.negate(), at, "Kauf zum Zweitpreis")
                ledgerAdd(auction.seller, LtrLedgerEntryType.AUCTION_SALE_IN, price, at, "Verkauf zum Zweitpreis")
                status = AuctionStatus.SETTLED
                winner = leaderNumber
                finalPrice = price
                settledAt = at
            }
            is Ending.InstantPurchase -> {
                val at = clock.utcAt(daysAgo = ending.daysAgo, hour = ending.hour)
                val price = BigDecimal(auction.buyNowPrice!!)
                if (leaderNumber != null) {
                    ledgerAdd(
                        leaderNumber,
                        LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                        finalOutcome.leaderMaxBidLtr!!,
                        at,
                        "Reservierung freigegeben",
                    )
                }
                ledgerAdd(ending.buyer, LtrLedgerEntryType.AUCTION_SALE_OUT, price.negate(), at, "Sofortkauf")
                ledgerAdd(auction.seller, LtrLedgerEntryType.AUCTION_SALE_IN, price, at, "Sofortkauf")
                status = AuctionStatus.SETTLED
                winner = ending.buyer
                finalPrice = price
                settledAt = at
            }
        }
        val participants =
            buildSet {
                add(auction.seller)
                addAll(auction.bids.map { it.bidder })
                (auction.ending as? Ending.InstantPurchase)?.let { add(it.buyer) }
            }
        return Derived(
            ledger = ledger,
            bids = bidState.values.toList(),
            status = status,
            winner = winner,
            finalPrice = finalPrice,
            settledAt = settledAt,
            participants = participants,
        )
    }

    /** All ledger rows the plan writes, for the balance simulation of the plan test. */
    fun ledgerPlan(clock: SeedClock): List<SeedLedgerRow> = auctions.flatMap { derive(auction = it, clock = clock).ledger }

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val derived = auctions.associateWith { derive(auction = it, clock = clock) }
        val statuses = statusesOf(derived.values.flatMap { it.participants })
        var inserted = 0
        var skipped = 0
        derived.forEach { (auction, result) ->
            val ok =
                AuctionTable.selectAll().where { AuctionTable.id eq auction.id }.empty() &&
                    result.participants.all { statuses[it] == MemberStatus.ACTIVE } &&
                    covers(result.ledger)
            if (!ok) {
                skipped++
                return@forEach
            }
            AuctionTable.insert {
                it[id] = auction.id
                it[title] = auction.title
                it[description] = auction.description
                it[startingBidLtr] = BigDecimal(auction.startingBid)
                it[buyNowPriceLtr] = auction.buyNowPrice?.let { price -> BigDecimal(price) }
                it[status] = result.status
                it[sellerMemberId] = SeedIds.member(auction.seller)
                it[winnerMemberId] = result.winner?.let { w -> SeedIds.member(w) }
                it[finalPriceLtr] = result.finalPrice
                it[listingFeeLtr] = LISTING_FEE
                it[createdAt] = clock.utcAt(daysAgo = auction.createdDaysAgo, hour = 10)
                it[AuctionTable.endsAt] = endsAtOf(auction = auction, clock = clock)
                it[settledAt] = result.settledAt
            }
            result.bids.forEach { bid ->
                AuctionBidTable.insert {
                    it[id] = bid.id
                    it[auctionId] = auction.id
                    it[bidderMemberId] = SeedIds.member(bid.bidder)
                    it[maxBidLtr] = bid.max
                    it[createdAt] = bid.createdAt
                }
            }
            insertRows(result.ledger)
            inserted++
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
