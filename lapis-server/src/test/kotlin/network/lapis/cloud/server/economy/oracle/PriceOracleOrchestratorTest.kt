package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceStatus
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
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
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? =
        price?.let { SourcePriceResult(sourceId = id, price = it, observedAt = Clock.System.now()) }
}

/** Like [FakeSource], but its price can be flipped between calls -- used to simulate "this source used to respond, now it doesn't" within a single orchestrator/cache lifetime. [callCount] (added Review Round 2 / NEW-1) lets a test additionally assert whether a given [fetchPrice] actually ran a real fan-out or was short-circuited/replayed. */
private class MutablePriceSource(
    override val id: String,
    var price: BigDecimal?,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    val callCount = AtomicInteger(0)

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        callCount.incrementAndGet()
        return price?.let { SourcePriceResult(sourceId = id, price = it, observedAt = Clock.System.now()) }
    }
}

/**
 * Like [CountingFakeSource], but suspends briefly before responding -- used by the Review Round 2 /
 * NEW-2 concurrency test to give N concurrent callers a real window to race each other before the
 * per-key [kotlinx.coroutines.sync.Mutex] (or its absence) would matter. A purely-synchronous fake
 * source resolves so fast that even a broken, unsynchronized fan-out could look single-flighted by
 * accident -- the delay is what makes "N concurrent callers actually overlap in time" true.
 */
private class SuspendingCountingFakeSource(
    override val id: String,
    private val price: BigDecimal,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
    private val delayMillis: Long = 50,
) : PriceOracleSource {
    val callCount = AtomicInteger(0)

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        callCount.incrementAndGet()
        delay(delayMillis)
        return SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
    }
}

/** A [FakeSource] sibling that counts how many times [fetchPrice] was actually invoked -- used to prove the refresh-interval short-circuit (§3.3) and anchor-routing (§3.1) never fan out more/less than expected. */
private class CountingFakeSource(
    override val id: String,
    private val price: BigDecimal?,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    val callCount = AtomicInteger(0)

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        callCount.incrementAndGet()
        return price?.let { SourcePriceResult(sourceId = id, price = it, observedAt = Clock.System.now()) }
    }
}

private fun testConfig(
    minQuorum: Int = 2,
    outlierThresholdBps: Int = 300,
    maxSpreadBps: Int = 1000,
    cacheTtlSeconds: Int = 300,
    anchorAsset: AnchorAsset = AnchorAsset.BITCOIN_BTC,
    donationCurrency: String = "EUR",
): PriceOracleConfigDto =
    PriceOracleConfigDto(
        id = "00000000-0000-0000-0000-0000000000f5",
        anchorAsset = anchorAsset,
        donationCurrency = donationCurrency,
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
        // NOTE (V0.6.6, corrected Review Round 1 / NIT-1): every BTC price literal below is the
        // pre-V0.6.6 illustrative value (100/101/102/...) multiplied by a constant 500 -- this
        // pushes every price comfortably inside the new plausibility band (see
        // AnchorSourcePolicy.kt: BITCOIN_BTC is 1000..10000000), which the pre-V0.6.6 toy values
        // (100, 101, ...) would otherwise fall below and be dropped BEFORE the quorum/outlier/spread
        // logic these tests exist to exercise ever runs. Uniform scaling is deliberate:
        // deviationBps/spreadBps are scale-invariant ratios, so every test's LIVE/DEGRADED/Halt
        // outcome and threshold-boundary reasoning is byte-for-byte the same as before this wave --
        // only the absolute expected-median literals change (also x500). ONE exception: the
        // "BigDecimal precision" test below (~line 260) uses an ADDITIVE offset
        // (58000.123456789012345678 / ...679, one ULP apart) instead of the x500 scaling, because
        // its entire point is to prove exact-decimal median computation over many fractional digits
        // -- multiplying by 500 would still be exact, but would no longer be the same illustrative
        // "adjacent-priced" pair the test's own literals are meant to visually read as.
        test("normal median: three agreeing sources -> LIVE, true median, all three contributing") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("50000")),
                            FakeSource(id = "b", price = BigDecimal("50500")),
                            FakeSource(id = "c", price = BigDecimal("51000")),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig()) as QuoteOutcome.Ok
            outcome.quote.status shouldBe PriceStatus.LIVE
            outcome.quote.medianPrice.compareTo(BigDecimal("50500")) shouldBe 0
            outcome.quote.contributingSourceIds shouldContainExactlyInAnyOrder listOf("a", "b", "c")
        }

        test("even-count median is the true midpoint average of the two middle survivors") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources = listOf(FakeSource(id = "a", price = BigDecimal("50000")), FakeSource(id = "b", price = BigDecimal("51000"))),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok
            outcome.quote.medianPrice.compareTo(BigDecimal("50500")) shouldBe 0
            outcome.quote.status shouldBe PriceStatus.LIVE
        }

        test("outlier rejection: a far-off source is dropped, median computed over survivors, status DEGRADED") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("50000")),
                            FakeSource(id = "b", price = BigDecimal("50500")),
                            FakeSource(id = "c", price = BigDecimal("75000")),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2, outlierThresholdBps = 300)) as QuoteOutcome.Ok
            outcome.quote.status shouldBe PriceStatus.DEGRADED
            outcome.quote.contributingSourceIds shouldContainExactlyInAnyOrder listOf("a", "b")
            outcome.quote.medianPrice.compareTo(BigDecimal("50250")) shouldBe 0
        }

        test("quorum-halt: too few sources responded, no cache available -> Halt") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("50000")),
                            FakeSource(id = "b", price = null),
                            FakeSource(id = "c", price = null),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2))
            (outcome is QuoteOutcome.Halt) shouldBe true
        }

        test("quorum-halt after outlier rejection: survivors drop below minQuorum, no cache -> Halt") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("50000")),
                            FakeSource(id = "b", price = BigDecimal("100000")),
                            FakeSource(id = "c", price = BigDecimal("150000")),
                        ),
                )
            // provisional median 100000; deviations of 50000 and 150000 from 100000 are both 50% >> 1% threshold -> both dropped, 1 survivor < minQuorum 2.
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2, outlierThresholdBps = 100))
            (outcome is QuoteOutcome.Halt) shouldBe true
        }

        test("spread-too-wide halt: survivors pass quorum but their own spread exceeds maxSpreadBps, no cache -> Halt") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("50000")),
                            FakeSource(id = "b", price = BigDecimal("60000")),
                            FakeSource(id = "c", price = BigDecimal("70000")),
                        ),
                )
            // Generous outlier threshold (50%) so all three survive outlier rejection, but the
            // resulting spread (70000-50000)/60000 ~= 33% exceeds a tight 5% (500bps) maxSpreadBps cap.
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2, outlierThresholdBps = 5000, maxSpreadBps = 500))
            (outcome is QuoteOutcome.Halt) shouldBe true
        }

        test(
            "cache hit avoids needing a live quorum: prime the cache, then every source fails within TTL -> Ok/CACHED with the cached price",
        ) {
            val clock = FakeClock()
            val livePrice = BigDecimal("50500")
            val a = MutablePriceSource(id = "a", price = livePrice)
            val b = MutablePriceSource(id = "b", price = livePrice)
            val c = MutablePriceSource(id = "c", price = livePrice)
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
            val livePrice = BigDecimal("50500")
            val a = MutablePriceSource(id = "a", price = livePrice)
            val b = MutablePriceSource(id = "b", price = livePrice)
            val c = MutablePriceSource(id = "c", price = livePrice)
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
                            FakeSource(id = "a", price = BigDecimal("50000")),
                            FakeSource(id = "b", price = BigDecimal("50000")),
                            FakeSource(id = "c", price = BigDecimal("50000")),
                        ),
                )
            (orchestratorFull.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok).quote.status shouldBe PriceStatus.LIVE

            val orchestratorReduced =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("50000")),
                            FakeSource(id = "b", price = BigDecimal("50000")),
                            FakeSource(id = "c", price = null),
                        ),
                )
            (orchestratorReduced.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok).quote.status shouldBe PriceStatus.DEGRADED
        }

        test("BigDecimal precision: many-decimal-place prices produce an exact median, no Double drift") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("58000.123456789012345678")),
                            FakeSource(id = "b", price = BigDecimal("58000.123456789012345679")),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok
            outcome.quote.medianPrice.compareTo(BigDecimal("58000.1234567890123456785")) shouldBe 0
        }

        // ── V0.6.6 "Price-Oracle: Gold- und Fiat-Anker" ──────────────────────────────────────────

        test("THE regression-safety test: a persisted minQuorum of 1 is clamped back up to the anchor's quorum floor for BTC and GOLD") {
            val btcOrchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC),
                            FakeSource(id = "b", price = null, anchor = AnchorAsset.BITCOIN_BTC),
                            FakeSource(id = "c", price = null, anchor = AnchorAsset.BITCOIN_BTC),
                        ),
                )
            (
                btcOrchestrator.currentQuote(testConfig(minQuorum = 1, anchorAsset = AnchorAsset.BITCOIN_BTC)) is QuoteOutcome.Halt
            ) shouldBe true

            val goldOrchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "goldapi", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU),
                            FakeSource(id = "metalpriceapi", price = null, anchor = AnchorAsset.GOLD_XAU),
                            FakeSource(id = "alphavantage", price = null, anchor = AnchorAsset.GOLD_XAU),
                        ),
                )
            (
                goldOrchestrator.currentQuote(testConfig(minQuorum = 1, anchorAsset = AnchorAsset.GOLD_XAU)) is QuoteOutcome.Halt
            ) shouldBe true
        }

        test("FIAT's quorum floor of 1 is a per-anchor exception, not a global relaxation: one source, minQuorum=1 -> Ok/LIVE") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources = listOf(FakeSource(id = "ecb", price = BigDecimal("1.1605"), anchor = AnchorAsset.FIAT)),
                )
            val outcome =
                orchestrator.currentQuote(testConfig(minQuorum = 1, anchorAsset = AnchorAsset.FIAT)) as QuoteOutcome.Ok
            outcome.quote.status shouldBe PriceStatus.LIVE
            outcome.quote.contributingSourceIds shouldContainExactlyInAnyOrder listOf("ecb")
        }

        test("anchor routing: an orchestrator holding BTC + Gold + Fiat sources only fans out to the active anchor's subset") {
            val btcA = CountingFakeSource(id = "btc-a", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val btcB = CountingFakeSource(id = "btc-b", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val goldA = CountingFakeSource(id = "gold-a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val goldB = CountingFakeSource(id = "gold-b", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val fiat = CountingFakeSource(id = "ecb", price = BigDecimal("1"), anchor = AnchorAsset.FIAT)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(btcA, btcB, goldA, goldB, fiat))

            val outcome =
                orchestrator.currentQuote(testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU)) as QuoteOutcome.Ok
            outcome.quote.status shouldBe PriceStatus.LIVE
            goldA.callCount.get() shouldBe 1
            goldB.callCount.get() shouldBe 1
            btcA.callCount.get() shouldBe 0
            btcB.callCount.get() shouldBe 0
            fiat.callCount.get() shouldBe 0
        }

        test(
            "cache isolation: a quote primed under BITCOIN_BTC/EUR is never served after switching to GOLD_XAU with all-failing gold sources",
        ) {
            val btc = FakeSource(id = "btc", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val btc2 = FakeSource(id = "btc2", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val goldFailing1 = FakeSource(id = "gold-a", price = null, anchor = AnchorAsset.GOLD_XAU)
            val goldFailing2 = FakeSource(id = "gold-b", price = null, anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(btc, btc2, goldFailing1, goldFailing2))

            (
                orchestrator.currentQuote(testConfig(anchorAsset = AnchorAsset.BITCOIN_BTC)) as QuoteOutcome.Ok
            ).quote.status shouldBe PriceStatus.LIVE

            val goldOutcome = orchestrator.currentQuote(testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU))
            (goldOutcome is QuoteOutcome.Halt) shouldBe true
        }

        test("cache isolation: a quote primed under EUR is never served after switching donationCurrency to USD") {
            val a = MutablePriceSource(id = "a", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val b = MutablePriceSource(id = "b", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b))

            (
                orchestrator.currentQuote(testConfig(donationCurrency = "EUR")) as QuoteOutcome.Ok
            ).quote.status shouldBe PriceStatus.LIVE

            a.price = null
            b.price = null
            val usdOutcome = orchestrator.currentQuote(testConfig(donationCurrency = "USD"))
            (usdOutcome is QuoteOutcome.Halt) shouldBe true
        }

        test(
            "refresh-interval short-circuit: GOLD_XAU, 100 sequential calls inside the window -> each source called exactly once, status/timestamp preserved",
        ) {
            val clock = FakeClock()
            val a = CountingFakeSource(id = "a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val b = CountingFakeSource(id = "b", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b), clock = clock)
            val config = testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 172_800)

            val first = orchestrator.currentQuote(config) as QuoteOutcome.Ok
            first.quote.status shouldBe PriceStatus.LIVE

            repeat(99) {
                val repeated = orchestrator.currentQuote(config) as QuoteOutcome.Ok
                repeated.quote.status shouldBe PriceStatus.LIVE
                repeated.quote.priceTimestamp shouldBe first.quote.priceTimestamp
            }
            a.callCount.get() shouldBe 1
            b.callCount.get() shouldBe 1
        }

        test("refresh-interval elapsing triggers exactly one further fan-out") {
            val clock = FakeClock()
            val a = CountingFakeSource(id = "a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val b = CountingFakeSource(id = "b", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b), clock = clock)
            val config = testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 172_800)

            orchestrator.currentQuote(config)
            a.callCount.get() shouldBe 1

            clock.advanceBy(43_200 + 1)
            orchestrator.currentQuote(config)
            a.callCount.get() shouldBe 2
            b.callCount.get() shouldBe 2
        }

        // ── Review Round 1 / MAJOR-1: refresh-interval gate must also cover a FAILED fan-out ──────

        test(
            "MAJOR-1 regression: a fan-out that fails to reach quorum is NOT re-attempted on the next call inside the " +
                "refresh interval -- same Halt outcome, source call counts unchanged",
        ) {
            val clock = FakeClock()
            // Only one of two configured GOLD_XAU sources responds -- below quorumFloor(GOLD_XAU)=2,
            // and no cache exists yet (first-ever call), so this MUST Halt.
            val a = CountingFakeSource(id = "a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val b = CountingFakeSource(id = "b", price = null, anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b), clock = clock)
            val config = testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 172_800)

            val first = orchestrator.currentQuote(config)
            (first is QuoteOutcome.Halt) shouldBe true
            a.callCount.get() shouldBe 1
            b.callCount.get() shouldBe 1

            // Before the MAJOR-1 fix: the cached-quote-based short-circuit could never engage here
            // (there is no successful CachedQuote to check the age of), so every one of these calls
            // fanned out again, re-hitting BOTH sources on every single call.
            repeat(10) {
                val repeated = orchestrator.currentQuote(config)
                (repeated is QuoteOutcome.Halt) shouldBe true
                (repeated as QuoteOutcome.Halt).reason shouldBe (first as QuoteOutcome.Halt).reason
            }
            a.callCount.get() shouldBe 1
            b.callCount.get() shouldBe 1
        }

        test(
            "MAJOR-1 concrete scenario: 20 rapid calls during a partial gold source outage cause exactly ONE fan-out, " +
                "never 20x the request volume across the healthy sources",
        ) {
            val clock = FakeClock()
            // Simulates GoldAPI.io's monthly quota running out (id 'a' never responds) while the
            // two healthy sources ('b', 'c') are still up -- but quorumFloor(GOLD_XAU)=2 needs both
            // of them plus one more, so 2 plausible responses < effectiveQuorum 3 here on purpose
            // (minQuorum raised to 3 so the healthy pair alone still isn't enough) -- Halt every time.
            val a = CountingFakeSource(id = "a", price = null, anchor = AnchorAsset.GOLD_XAU)
            val b = CountingFakeSource(id = "b", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val c = CountingFakeSource(id = "c", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b, c), clock = clock)
            val config = testConfig(minQuorum = 3, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 172_800)

            repeat(20) {
                val outcome = orchestrator.currentQuote(config)
                (outcome is QuoteOutcome.Halt) shouldBe true
            }

            // The whole point: 20 calls, ONE real fan-out -- not 20 fan-outs x 3 sources = 60 calls.
            a.callCount.get() shouldBe 1
            b.callCount.get() shouldBe 1
            c.callCount.get() shouldBe 1
        }

        test(
            "BTC's refresh interval is 0: 5 sequential calls always fan out (byte-for-byte unchanged " +
                "behaviour) -- confirms neither the S1 hard floor NOR its S9 tightening (a fraction of " +
                "refreshIntervalSeconds) ever throttles BTC, since AnchorPolicy.refreshIntervalSeconds(BTC) " +
                "== 0 skips the entire `if (refreshInterval > 0)` gate (mutex, lastAttempts, lastFanoutAt, " +
                "hard floor) currentQuote() never even computes a hardFloorSeconds for BTC",
        ) {
            val a = CountingFakeSource(id = "a", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val b = CountingFakeSource(id = "b", price = BigDecimal("50000"), anchor = AnchorAsset.BITCOIN_BTC)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b))
            val config = testConfig()

            // No clock advances between calls at all -- if the S9 floor (or any floor) accidentally
            // applied to BTC, these back-to-back calls with zero elapsed time would be blocked.
            repeat(5) { orchestrator.currentQuote(config) }
            a.callCount.get() shouldBe 5
            b.callCount.get() shouldBe 5
        }

        test("plausibility band: an implausible gold price is dropped, and a normal BTC price passes untouched") {
            val goldOrchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("0.00046"), anchor = AnchorAsset.GOLD_XAU),
                            FakeSource(id = "b", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU),
                        ),
                )
            val goldOutcome = goldOrchestrator.currentQuote(testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU))
            (goldOutcome is QuoteOutcome.Halt) shouldBe true

            val btcOrchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "a", price = BigDecimal("58123.45"), anchor = AnchorAsset.BITCOIN_BTC),
                            FakeSource(id = "b", price = BigDecimal("58200.10"), anchor = AnchorAsset.BITCOIN_BTC),
                        ),
                )
            val btcOutcome = btcOrchestrator.currentQuote(testConfig(minQuorum = 2)) as QuoteOutcome.Ok
            btcOutcome.quote.status shouldBe PriceStatus.LIVE
        }

        // ── Security-Audit-Runde 1 / S4: the FIAT plausibility band is quorum=1's ONLY numeric guard ─

        test(
            "S4 regression: a manipulated ~0.1-scale ECB rate (the old band's edge, an ~11.6x over-mint risk for " +
                "FIAT+USD) is now rejected as implausible, while the real ~1.16 EUR/USD rate still passes",
        ) {
            val manipulatedOrchestrator =
                PriceOracleOrchestrator(
                    sources = listOf(FakeSource(id = "ecb", price = BigDecimal("0.1"), anchor = AnchorAsset.FIAT)),
                )
            val manipulatedOutcome =
                manipulatedOrchestrator.currentQuote(
                    testConfig(minQuorum = 1, anchorAsset = AnchorAsset.FIAT, donationCurrency = "USD"),
                )
            (manipulatedOutcome is QuoteOutcome.Halt) shouldBe true

            val realRateOrchestrator =
                PriceOracleOrchestrator(
                    sources = listOf(FakeSource(id = "ecb", price = BigDecimal("1.1605"), anchor = AnchorAsset.FIAT)),
                )
            val realRateOutcome =
                realRateOrchestrator.currentQuote(
                    testConfig(minQuorum = 1, anchorAsset = AnchorAsset.FIAT, donationCurrency = "USD"),
                ) as QuoteOutcome.Ok
            realRateOutcome.quote.status shouldBe PriceStatus.LIVE
            realRateOutcome.quote.medianPrice.compareTo(BigDecimal("1.1605")) shouldBe 0
        }

        test("an anchor with zero configured sources halts with a reason naming the anchor") {
            val orchestrator = PriceOracleOrchestrator(sources = listOf(FakeSource(id = "btc", price = BigDecimal("50000"))))
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 1, anchorAsset = AnchorAsset.FIAT))
            (outcome is QuoteOutcome.Halt) shouldBe true
            (outcome as QuoteOutcome.Halt).reason.contains("FIAT") shouldBe true
        }

        test("gold 3-of-3 -> 2-of-3 degradation: a fully-configured gold deployment survives one source outage") {
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FakeSource(id = "goldapi", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU),
                            FakeSource(id = "metalpriceapi", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU),
                            FakeSource(id = "alphavantage", price = null, anchor = AnchorAsset.GOLD_XAU),
                        ),
                )
            val outcome = orchestrator.currentQuote(testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU)) as QuoteOutcome.Ok
            outcome.quote.status shouldBe PriceStatus.DEGRADED
            outcome.quote.contributingSourceIds shouldContainExactlyInAnyOrder listOf("goldapi", "metalpriceapi")
        }

        // ── Review Round 2 / NEW-1: a replayed CACHED outcome must re-check its OWN TTL ───────────

        test(
            "NEW-1 regression: a CACHED outcome recorded near its own TTL boundary is not replayed once the underlying " +
                "price has since aged past cacheTtlSeconds -- a fresh fan-out runs instead of serving a doubly-stale price",
        ) {
            val clock = FakeClock()
            val a = MutablePriceSource(id = "a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val b = MutablePriceSource(id = "b", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b), clock = clock)
            // cacheTtlSeconds is intentionally larger than GOLD_XAU's fixed 43_200s refresh interval,
            // so there is room for a failed attempt to be recorded near (but inside) the cache's own
            // TTL boundary, and later -- still within ONE refresh-interval window of THAT attempt --
            // for the cache to age past its own TTL. (With cacheTtlSeconds == refreshIntervalSeconds
            // exactly, as in the review's own minimal narrative, the two windows abut too tightly for
            // a FakeClock-driven test to land cleanly on; the bug and the fix are identical either way
            // -- see the class KDoc's algorithm step 0.)
            val config = testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 50_000)

            val first = orchestrator.currentQuote(config) as QuoteOutcome.Ok
            first.quote.status shouldBe PriceStatus.LIVE
            a.callCount.get() shouldBe 1

            // Past the refresh interval (gate expires -> a real fan-out runs), but still inside
            // cacheTtlSeconds: sources are now down, so this fan-out fails quorum and falls back to
            // the still-fresh cache, recording an Ok(CACHED) LastAttempt whose underlying
            // priceTimestamp is the ORIGINAL fetch time (T0), not this attempt's time.
            clock.advanceBy(43_201)
            a.price = null
            b.price = null
            val cachedAttempt = orchestrator.currentQuote(config) as QuoteOutcome.Ok
            cachedAttempt.quote.status shouldBe PriceStatus.CACHED
            cachedAttempt.quote.priceTimestamp shouldBe first.quote.priceTimestamp
            a.callCount.get() shouldBe 2

            // Still well within the refresh interval of THAT attempt (14_401s < 43_200s), but the
            // underlying cached price is now 57_602s old -- past its own 50_000s cacheTtlSeconds.
            // Before the NEW-1 fix, this would replay the CACHED outcome above verbatim, serving a
            // price up to (cacheTtlSeconds + refreshIntervalSeconds) old, roughly double the ceiling.
            // 14_401s is also deliberately > the S9 hard floor (14_400s, refreshIntervalSeconds/
            // HARD_FLOOR_FANOUT_DIVISOR) measured from the SECOND call's real fan-out above -- this
            // test specifically exercises the NEW-1 own-TTL-recheck branch, which requires clearing
            // BOTH gates: still within the refresh interval (step 0) AND past the S9 hard floor
            // (step 0a), so a real third fan-out is actually attempted rather than being blocked by
            // the floor before ever reaching the NEW-1 logic.
            clock.advanceBy(14_401)
            val afterOwnTtlExpired = orchestrator.currentQuote(config)
            (afterOwnTtlExpired is QuoteOutcome.Halt) shouldBe true
            // The fix: a fresh fan-out actually ran (sources called a third time) instead of a silent
            // replay -- proving the stale CACHED outcome was NOT served past its own TTL.
            a.callCount.get() shouldBe 3
            b.callCount.get() shouldBe 3
        }

        // ── Review Round 2 / NEW-2: the per-key Mutex must be exercised by genuine concurrency ─────

        test(
            "NEW-2: the per-key Mutex genuinely single-flights concurrent callers -- 20 concurrent currentQuote() calls " +
                "for the SAME CacheKey call each underlying source exactly once, not 20 times",
        ) {
            val a = SuspendingCountingFakeSource(id = "a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU, delayMillis = 50)
            val b = SuspendingCountingFakeSource(id = "b", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU, delayMillis = 50)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b))
            val config = testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 172_800)

            val outcomes =
                coroutineScope {
                    (1..20).map { async { orchestrator.currentQuote(config) } }.awaitAll()
                }

            outcomes.forEach { outcome -> (outcome is QuoteOutcome.Ok) shouldBe true }
            outcomes.map { (it as QuoteOutcome.Ok).quote.medianPrice }.toSet().size shouldBe 1
            // The whole point: 20 concurrent callers, but the mutex serializes/dedupes them down to
            // exactly ONE real fan-out -- without it, this would be 20 (or some non-deterministic
            // count > 1, depending on scheduling), and this test would fail intermittently instead of
            // reliably catching a future refactor that silently drops the mutex.
            a.callCount.get() shouldBe 1
            b.callCount.get() shouldBe 1
        }

        // ── Security-Audit-Runde 1 / S1: a hard floor on real fan-out frequency that invalidateReplayState() cannot bypass ──
        // ── Tightened by Security-Audit-Runde 2 / S9: the floor is now GOLD_XAU/FIAT's own
        //    refreshIntervalSeconds (43_200s) / HARD_FLOOR_FANOUT_DIVISOR (3) = 14_400s (4h), not a
        //    flat 60s -- these tests use 14_400 wherever the old test used 60.

        test(
            "S1/S9 hard floor: invalidateReplayState() clears lastAttempts/cache (as a genuine updateOracleConfig " +
                "change would) but does NOT reset the real fan-out floor -- 9 rapid invalidate+currentQuote cycles " +
                "inside the 14_400s (4h) floor window produce ZERO further real fan-outs, only the priming call counts",
        ) {
            val clock = FakeClock()
            val a = CountingFakeSource(id = "a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val b = CountingFakeSource(id = "b", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b), clock = clock)
            val config = testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 172_800)

            // First call: a real fan-out, priming lastFanoutAt (which, unlike lastAttempts/cache, no
            // invalidateReplayState() call below can ever reset).
            val first = orchestrator.currentQuote(config) as QuoteOutcome.Ok
            first.quote.status shouldBe PriceStatus.LIVE
            a.callCount.get() shouldBe 1
            b.callCount.get() shouldBe 1

            // Simulates a rapid "tune a field, save, click preview" loop -- each iteration
            // invalidates lastAttempts/cache exactly like updateOracleConfig does for a genuine
            // quote-affecting change, then immediately re-queries, all still comfortably inside the
            // 14_400s (4h) hard floor from the priming call above.
            repeat(9) {
                clock.advanceBy(1)
                orchestrator.invalidateReplayState()
                val outcome = orchestrator.currentQuote(config)
                // cache was just cleared by invalidateReplayState() and the floor blocks a real
                // fan-out, so there is nothing left to fall back to -- this MUST Halt. The important
                // assertion is below: it halts WITHOUT ever touching the network.
                (outcome is QuoteOutcome.Halt) shouldBe true
            }

            // The whole point: 1 priming fan-out + 9 rapid save/preview cycles, still exactly ONE
            // real network fan-out total -- not 10.
            a.callCount.get() shouldBe 1
            b.callCount.get() shouldBe 1

            // Once the floor window has genuinely elapsed, a real fan-out is allowed again --
            // the floor throttles, it does not permanently wedge the oracle. 9s already elapsed
            // above; this advance comfortably clears the remaining 14_400s floor.
            clock.advanceBy(14_400)
            orchestrator.invalidateReplayState()
            val afterFloor = orchestrator.currentQuote(config) as QuoteOutcome.Ok
            afterFloor.quote.status shouldBe PriceStatus.LIVE
            a.callCount.get() shouldBe 2
            b.callCount.get() shouldBe 2
        }

        test(
            "S9: the tightened floor caps real fan-outs at 6/day/key (one per 14_400s window), not 1440/day -- " +
                "simulating a FULL DAY of continuous rapid config-save abuse (one attempt every 15 minutes) " +
                "produces exactly the number of real fan-outs the new floor predicts, never more",
        ) {
            val clock = FakeClock()
            val a = CountingFakeSource(id = "a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU)
            val b = CountingFakeSource(id = "b", price = BigDecimal("2001"), anchor = AnchorAsset.GOLD_XAU)
            val orchestrator = PriceOracleOrchestrator(sources = listOf(a, b), clock = clock)
            val config = testConfig(minQuorum = 2, anchorAsset = AnchorAsset.GOLD_XAU, cacheTtlSeconds = 172_800)

            // 97 attempts spaced 900s (15 min) apart span exactly one 86_400s (24h) day (96 intervals
            // of 900s, from t=0 to t=86_400), each preceded by a genuine invalidateReplayState() (as a
            // real config change would trigger) -- the old flat 60s floor would have let every single
            // one of these 97 calls fan out for real (900s >> 60s). The new 14_400s floor must instead
            // cap this at floor(86_400 / 14_400) + 1 = 7 real fan-outs -- at t=0, 14_400, 28_800,
            // 43_200, 57_600, 72_000, 86_400 (the +1 is the initial priming call at t=0; one more real
            // fan-out becomes eligible every time a full floor window has elapsed since the last one).
            var realFanouts = 0
            repeat(97) { i ->
                if (i > 0) clock.advanceBy(900)
                orchestrator.invalidateReplayState()
                val before = a.callCount.get()
                orchestrator.currentQuote(config)
                if (a.callCount.get() > before) realFanouts++
            }

            // The whole point: nowhere near 97 (one per attempt, the pre-S9 flat-60s-floor behaviour
            // for 900s-spaced calls) and nowhere near 1440 (the old floor's own theoretical daily
            // ceiling) -- a small, single-digit number consistent with the new, tightened 4h floor.
            realFanouts shouldBe 7
            a.callCount.get() shouldBe 7
            b.callCount.get() shouldBe 7
        }
    })
