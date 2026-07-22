package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceStatus
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val BASE_INSTANT: Instant = LocalDateTime(2026, 7, 22, 12, 0, 0).toInstant(TimeZone.UTC)

/** A [Clock] whose [now] can be advanced explicitly -- used to exercise [PriceOracleOrchestrator]'s cache-TTL boundary without real wall-clock sleeps. */
private class FakeClock(
    private var instant: Instant = BASE_INSTANT,
) : Clock {
    override fun now(): Instant = instant

    fun advanceBy(seconds: Long) {
        instant = instant.plus(seconds.seconds)
    }
}

/** A fixed-price/always-failing [PriceOracleSource] test double -- never performs real network I/O. */
private class FakeSource(
    override val id: String,
    private val price: BigDecimal?,
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? =
        price?.let { SourcePriceResult(sourceId = id, price = it, observedAt = Clock.System.now()) }
}

/** Like [FakeSource], but its price can be flipped between calls -- used to simulate "this source used to respond, now it doesn't" within a single orchestrator/cache lifetime. */
private class MutablePriceSource(
    override val id: String,
    var price: BigDecimal?,
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? =
        price?.let { SourcePriceResult(sourceId = id, price = it, observedAt = Clock.System.now()) }
}

private fun testConfig(
    minQuorum: Int = 2,
    outlierThresholdBps: Int = 300,
    maxSpreadBps: Int = 1000,
    cacheTtlSeconds: Int = 300,
): PriceOracleConfigDto =
    PriceOracleConfigDto(
        id = "00000000-0000-0000-0000-0000000000f5",
        anchorAsset = AnchorAsset.BITCOIN_BTC,
        donationCurrency = "EUR",
        anchorUnitsPerLtr = BigDecimal("0.000001"),
        cacheTtlSeconds = cacheTtlSeconds,
        minQuorum = minQuorum,
        outlierThresholdBps = outlierThresholdBps,
        maxSpreadBps = maxSpreadBps,
        updatedAt = LocalDate(2026, 1, 1).atTime(0, 0),
    )

/**
 * Exercises [PriceOracleOrchestrator]'s median/outlier-rejection/cache/halt algorithm entirely
 * with fake [PriceOracleSource]s -- no real network I/O, no `MockEngine` needed at this layer
 * (that lives in [network.lapis.cloud.server.economy.oracle.BitcoinPriceSourceTest] instead).
 */
class PriceOracleOrchestratorTest :
    FunSpec({
        test("normal median: three agreeing sources -> LIVE, true median, all three contributing") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource("a", BigDecimal("100")),
                            FakeSource("b", BigDecimal("101")),
                            FakeSource("c", BigDecimal("102")),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig()) as QuoteOutcome.Ok
            outcome.quote.status shouldBe PriceStatus.LIVE
            outcome.quote.medianPrice.compareTo(BigDecimal("101")) shouldBe 0
            outcome.quote.contributingSourceIds shouldContainExactlyInAnyOrder listOf("a", "b", "c")
        }

        test("even-count median is the true midpoint average of the two middle survivors") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources = listOf(FakeSource("a", BigDecimal("100")), FakeSource("b", BigDecimal("102"))),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok
            outcome.quote.medianPrice.compareTo(BigDecimal("101")) shouldBe 0
            outcome.quote.status shouldBe PriceStatus.LIVE
        }

        test("outlier rejection: a far-off source is dropped, median computed over survivors, status DEGRADED") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource("a", BigDecimal("100")),
                            FakeSource("b", BigDecimal("101")),
                            FakeSource("c", BigDecimal("150")),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2, outlierThresholdBps = 300)) as QuoteOutcome.Ok
            outcome.quote.status shouldBe PriceStatus.DEGRADED
            outcome.quote.contributingSourceIds shouldContainExactlyInAnyOrder listOf("a", "b")
            outcome.quote.medianPrice.compareTo(BigDecimal("100.5")) shouldBe 0
        }

        test("quorum-halt: too few sources responded, no cache available -> Halt") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources = listOf(FakeSource("a", BigDecimal("100")), FakeSource("b", null), FakeSource("c", null)),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2))
            (outcome is QuoteOutcome.Halt) shouldBe true
        }

        test("quorum-halt after outlier rejection: survivors drop below minQuorum, no cache -> Halt") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource("a", BigDecimal("100")),
                            FakeSource("b", BigDecimal("200")),
                            FakeSource("c", BigDecimal("300")),
                        ),
                )
            // provisional median 200; deviations of 100 and 300 from 200 are both 50% >> 1% threshold -> both dropped, 1 survivor < minQuorum 2.
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2, outlierThresholdBps = 100))
            (outcome is QuoteOutcome.Halt) shouldBe true
        }

        test("spread-too-wide halt: survivors pass quorum but their own spread exceeds maxSpreadBps, no cache -> Halt") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource("a", BigDecimal("100")),
                            FakeSource("b", BigDecimal("120")),
                            FakeSource("c", BigDecimal("140")),
                        ),
                )
            // Generous outlier threshold (50%) so all three survive outlier rejection, but the
            // resulting spread (140-100)/120 ~= 33% exceeds a tight 5% (500bps) maxSpreadBps cap.
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2, outlierThresholdBps = 5000, maxSpreadBps = 500))
            (outcome is QuoteOutcome.Halt) shouldBe true
        }

        test(
            "cache hit avoids needing a live quorum: prime the cache, then every source fails within TTL -> Ok/CACHED with the cached price",
        ) {
            val clock = FakeClock()
            val livePrice = BigDecimal("101")
            val a = MutablePriceSource("a", livePrice)
            val b = MutablePriceSource("b", livePrice)
            val c = MutablePriceSource("c", livePrice)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b, c), clock = clock)

            val primed = orchestrator.currentQuote(testConfig()) as QuoteOutcome.Ok
            primed.quote.status shouldBe PriceStatus.LIVE

            a.price = null
            b.price = null
            c.price = null
            val cached = orchestrator.currentQuote(testConfig()) as QuoteOutcome.Ok
            cached.quote.status shouldBe PriceStatus.CACHED
            cached.quote.medianPrice.compareTo(livePrice) shouldBe 0
            cached.quote.contributingSourceIds shouldContainExactlyInAnyOrder listOf("a", "b", "c")
        }

        test("cache expiry: cached price older than cacheTtlSeconds, no live quorum -> Halt") {
            val clock = FakeClock()
            val livePrice = BigDecimal("101")
            val a = MutablePriceSource("a", livePrice)
            val b = MutablePriceSource("b", livePrice)
            val c = MutablePriceSource("c", livePrice)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b, c), clock = clock)

            (orchestrator.currentQuote(testConfig(cacheTtlSeconds = 60)) as QuoteOutcome.Ok).quote.status shouldBe PriceStatus.LIVE

            a.price = null
            b.price = null
            c.price = null
            clock.advanceBy(61)

            val outcome = orchestrator.currentQuote(testConfig(cacheTtlSeconds = 60))
            (outcome is QuoteOutcome.Halt) shouldBe true
        }

        test("status boundary: full source agreement -> LIVE, reduced (but sufficient) quorum -> DEGRADED") {
            val orchestratorFull =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource("a", BigDecimal("100")),
                            FakeSource("b", BigDecimal("100")),
                            FakeSource("c", BigDecimal("100")),
                        ),
                )
            (orchestratorFull.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok).quote.status shouldBe PriceStatus.LIVE

            val orchestratorReduced =
                PriceOracleOrchestrator(
                    sources = listOf(FakeSource("a", BigDecimal("100")), FakeSource("b", BigDecimal("100")), FakeSource("c", null)),
                )
            (orchestratorReduced.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok).quote.status shouldBe PriceStatus.DEGRADED
        }

        test("BigDecimal precision: many-decimal-place prices produce an exact median, no Double drift") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource("a", BigDecimal("100.123456789012345678")),
                            FakeSource("b", BigDecimal("100.123456789012345679")),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok
            outcome.quote.medianPrice.compareTo(BigDecimal("100.1234567890123456785")) shouldBe 0
        }
    })
