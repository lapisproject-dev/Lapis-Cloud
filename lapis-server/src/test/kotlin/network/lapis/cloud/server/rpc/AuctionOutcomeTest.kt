package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Pure property tests of [computeAuctionOutcome] -- the algorithmically novel, outcome-affecting
 * core of the V0.6.2 LTR-Auktion (who wins, what they pay). No DB access anywhere in this file
 * (matches [AuctionBidView]/[AuctionOutcome] being plain data classes) -- see that file's KDoc for
 * the algorithm this exercises.
 */
private val MIN_INCREMENT: BigDecimal = BigDecimal("0.01")
private val BASE_TIME = LocalDateTime(2026, 1, 1, 12, 0, 0)

private fun bid(
    bidderMemberId: Uuid,
    maxBidLtr: String,
    minutesAfterBase: Int = 0,
    bidId: Uuid = Uuid.random(),
): AuctionBidView =
    AuctionBidView(
        bidId = bidId,
        bidderMemberId = bidderMemberId,
        maxBidLtr = BigDecimal(maxBidLtr).setScale(2),
        createdAt = LocalDateTime(BASE_TIME.year, BASE_TIME.month, BASE_TIME.day, BASE_TIME.hour, minutesAfterBase.coerceIn(0, 59), 0),
    )

private data class AuctionScenario(
    val startingBidLtr: BigDecimal,
    val bids: List<AuctionBidView>,
)

/** Random scenario: 0..8 distinct bidders, each with one standing maxBidLtr (1 cent .. 10 000.00 LTR) at or above a random startingBidLtr floor. */
private fun scenarioArb(): Arb<AuctionScenario> =
    arbitrary { rs ->
        val random = rs.random
        val startingCents = random.nextInt(1, 100_00)
        val startingBid = BigDecimal(startingCents).movePointLeft(2).setScale(2)
        val bidderCount = random.nextInt(0, 9)
        val bids =
            (0 until bidderCount).map { idx ->
                val extraCents = random.nextInt(0, 2_000_00)
                val amount = startingBid + BigDecimal(extraCents).movePointLeft(2)
                bid(bidderMemberId = Uuid.random(), maxBidLtr = amount.toPlainString(), minutesAfterBase = idx)
            }
        AuctionScenario(startingBidLtr = startingBid, bids = bids)
    }

class AuctionOutcomeTest :
    FunSpec({
        test("no bids -> everything null/zero") {
            val outcome = computeAuctionOutcome(startingBidLtr = BigDecimal("10.00"), minIncrementLtr = MIN_INCREMENT, bids = emptyList())
            outcome.leaderMemberId shouldBe null
            outcome.leaderMaxBidLtr shouldBe null
            outcome.currentPriceLtr shouldBe null
            outcome.bidCount shouldBe 0
        }

        test("one bidder -> leader pays the starting bid") {
            val a = Uuid.random()
            val outcome =
                computeAuctionOutcome(
                    startingBidLtr = BigDecimal("10.00"),
                    minIncrementLtr = MIN_INCREMENT,
                    bids = listOf(bid(bidderMemberId = a, maxBidLtr = "50.00")),
                )
            outcome.leaderMemberId shouldBe a
            outcome.leaderMaxBidLtr shouldBe BigDecimal("50.00")
            outcome.currentPriceLtr shouldBe BigDecimal("10.00")
            outcome.bidCount shouldBe 1
        }

        test("two bidders -> leader pays second-highest plus one increment") {
            val a = Uuid.random()
            val b = Uuid.random()
            val outcome =
                computeAuctionOutcome(
                    startingBidLtr = BigDecimal("1.00"),
                    minIncrementLtr = MIN_INCREMENT,
                    bids = listOf(bid(bidderMemberId = a, maxBidLtr = "100.00"), bid(bidderMemberId = b, maxBidLtr = "50.00")),
                )
            outcome.leaderMemberId shouldBe a
            outcome.currentPriceLtr shouldBe BigDecimal("50.01")
        }

        test("second price is capped at the leader's own maximum") {
            val a = Uuid.random()
            val b = Uuid.random()
            val outcome =
                computeAuctionOutcome(
                    startingBidLtr = BigDecimal("1.00"),
                    minIncrementLtr = MIN_INCREMENT,
                    bids = listOf(bid(bidderMemberId = a, maxBidLtr = "60.00"), bid(bidderMemberId = b, maxBidLtr = "100.00")),
                )
            outcome.leaderMemberId shouldBe b
            outcome.leaderMaxBidLtr shouldBe BigDecimal("100.00")
            outcome.currentPriceLtr shouldBe BigDecimal("60.01")
        }

        test("exact tie at the maximum -> earlier createdAt wins, price equals the tied maximum") {
            val earlier = Uuid.random()
            val later = Uuid.random()
            val outcome =
                computeAuctionOutcome(
                    startingBidLtr = BigDecimal("1.00"),
                    minIncrementLtr = MIN_INCREMENT,
                    bids =
                        listOf(
                            bid(bidderMemberId = earlier, maxBidLtr = "50.00", minutesAfterBase = 0),
                            bid(bidderMemberId = later, maxBidLtr = "50.00", minutesAfterBase = 5),
                        ),
                )
            outcome.leaderMemberId shouldBe earlier
            outcome.currentPriceLtr shouldBe BigDecimal("50.00")
        }

        test("a bidder's own other bid never counts as competition against themselves") {
            val a = Uuid.random()
            val b = Uuid.random()
            // Two rows for A is not a production shape (auction_bid is an upsert table), but this
            // function must still behave sanely if ever handed such input -- see its own KDoc.
            val outcome =
                computeAuctionOutcome(
                    startingBidLtr = BigDecimal("1.00"),
                    minIncrementLtr = MIN_INCREMENT,
                    bids =
                        listOf(
                            bid(bidderMemberId = a, maxBidLtr = "100.00", bidId = Uuid.random()),
                            bid(bidderMemberId = a, maxBidLtr = "30.00", bidId = Uuid.random()),
                            bid(bidderMemberId = b, maxBidLtr = "40.00"),
                        ),
                )
            outcome.leaderMemberId shouldBe a
            outcome.currentPriceLtr shouldBe BigDecimal("40.01")
        }

        test("deterministic: identical input always produces an identical outcome") {
            checkAll(200, scenarioArb()) { scenario ->
                val first =
                    computeAuctionOutcome(startingBidLtr = scenario.startingBidLtr, minIncrementLtr = MIN_INCREMENT, bids = scenario.bids)
                val second =
                    computeAuctionOutcome(startingBidLtr = scenario.startingBidLtr, minIncrementLtr = MIN_INCREMENT, bids = scenario.bids)
                first shouldBe second
            }
        }

        test("currentPriceLtr is always >= startingBidLtr whenever there is a leader") {
            checkAll(300, scenarioArb()) { scenario ->
                val outcome =
                    computeAuctionOutcome(startingBidLtr = scenario.startingBidLtr, minIncrementLtr = MIN_INCREMENT, bids = scenario.bids)
                if (outcome.leaderMemberId != null) {
                    (outcome.currentPriceLtr!! >= scenario.startingBidLtr) shouldBe true
                }
            }
        }

        test("currentPriceLtr never exceeds the leader's own maxBidLtr") {
            checkAll(300, scenarioArb()) { scenario ->
                val outcome =
                    computeAuctionOutcome(startingBidLtr = scenario.startingBidLtr, minIncrementLtr = MIN_INCREMENT, bids = scenario.bids)
                if (outcome.leaderMemberId != null) {
                    (outcome.currentPriceLtr!! <= outcome.leaderMaxBidLtr!!) shouldBe true
                }
            }
        }

        test("the leader always holds the (a) highest maxBidLtr among all bids") {
            checkAll(300, scenarioArb()) { scenario ->
                val outcome =
                    computeAuctionOutcome(startingBidLtr = scenario.startingBidLtr, minIncrementLtr = MIN_INCREMENT, bids = scenario.bids)
                if (outcome.leaderMemberId != null) {
                    val maxAmongAll = scenario.bids.maxOf { it.maxBidLtr }
                    outcome.leaderMaxBidLtr shouldBe maxAmongAll
                }
            }
        }

        test("bidCount always equals the number of bids passed in") {
            checkAll(300, scenarioArb()) { scenario ->
                val outcome =
                    computeAuctionOutcome(startingBidLtr = scenario.startingBidLtr, minIncrementLtr = MIN_INCREMENT, bids = scenario.bids)
                outcome.bidCount shouldBe scenario.bids.size
            }
        }
    })
