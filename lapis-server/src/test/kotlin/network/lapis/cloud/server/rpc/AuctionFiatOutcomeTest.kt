package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val BASE = LocalDateTime(2026, 1, 1, 12, 0, 0)

private data class FiatScenario(
    val startingMinor: Long,
    val bids: List<FiatBidView>,
)

private fun fiatScenarioArb(): Arb<FiatScenario> =
    arbitrary { rs ->
        val random = rs.random
        val starting = random.nextLong(100, 100_000)
        val count = random.nextInt(0, 9)
        val bids =
            (0 until count).map { idx ->
                FiatBidView(
                    bidId = Uuid.random(),
                    bidderMemberId = Uuid.random(),
                    // few distinct values on purpose: ties exercise the earliest-first tie-break
                    maxBidMinor = starting + random.nextLong(0, 6) * 100,
                    createdAt = LocalDateTime(BASE.year, BASE.month, BASE.day, BASE.hour, idx, 0),
                )
            }
        FiatScenario(startingMinor = starting, bids = bids)
    }

/** Fiat (minor unit) outcome math (V1.9.320): it must agree with the BigDecimal [computeAuctionOutcome] on every input. */
class AuctionFiatOutcomeTest :
    FunSpec({
        test("no bid -> everything null") {
            val outcome = computeFiatAuctionOutcome(startingMinor = 1000, minIncrementMinor = 100, bids = emptyList())
            outcome.leaderMemberId shouldBe null
            outcome.currentPriceMinor shouldBe null
            outcome.bidCount shouldBe 0
        }

        test(
            "one bidder pays the starting price, two bidders pay the second maximum plus one increment capped at the leader's own maximum",
        ) {
            val a = Uuid.random()
            val b = Uuid.random()

            fun bid(
                who: Uuid,
                max: Long,
                minute: Int,
            ) = FiatBidView(
                bidId = Uuid.random(),
                bidderMemberId = who,
                maxBidMinor = max,
                createdAt = LocalDateTime(2026, 1, 1, 12, minute, 0),
            )
            computeFiatAuctionOutcome(
                startingMinor = 1000,
                minIncrementMinor = 100,
                bids = listOf(bid(a, 3000, 0)),
            ).currentPriceMinor shouldBe
                1000
            computeFiatAuctionOutcome(
                startingMinor = 1000,
                minIncrementMinor = 100,
                bids = listOf(bid(a, 3000, 0), bid(b, 2000, 1)),
            ).currentPriceMinor shouldBe
                2100
            computeFiatAuctionOutcome(
                startingMinor = 1000,
                minIncrementMinor = 100,
                bids = listOf(bid(a, 2050, 0), bid(b, 2000, 1)),
            ).currentPriceMinor shouldBe
                2050
            // a tie goes to the earlier bid, who then pays his own maximum
            val tie =
                computeFiatAuctionOutcome(startingMinor = 1000, minIncrementMinor = 100, bids = listOf(bid(a, 2000, 0), bid(b, 2000, 1)))
            tie.leaderMemberId shouldBe a
            tie.currentPriceMinor shouldBe 2000
        }

        test("property: the Long variant agrees with the BigDecimal variant on every random scenario") {
            checkAll(500, fiatScenarioArb()) { scenario ->
                val fiat = computeFiatAuctionOutcome(startingMinor = scenario.startingMinor, minIncrementMinor = 100, bids = scenario.bids)
                val ltr =
                    computeAuctionOutcome(
                        startingBidLtr = BigDecimal.valueOf(scenario.startingMinor, 2),
                        minIncrementLtr = BigDecimal("1.00"),
                        bids =
                            scenario.bids.map {
                                AuctionBidView(
                                    bidId = it.bidId,
                                    bidderMemberId = it.bidderMemberId,
                                    maxBidLtr = BigDecimal.valueOf(it.maxBidMinor, 2),
                                    createdAt = it.createdAt,
                                )
                            },
                    )
                fiat.leaderMemberId shouldBe ltr.leaderMemberId
                fiat.bidCount shouldBe ltr.bidCount
                fiat.leaderMaxBidMinor shouldBe ltr.leaderMaxBidLtr?.movePointRight(2)?.longValueExact()
                fiat.currentPriceMinor shouldBe ltr.currentPriceLtr?.movePointRight(2)?.longValueExact()
            }
        }
    })
