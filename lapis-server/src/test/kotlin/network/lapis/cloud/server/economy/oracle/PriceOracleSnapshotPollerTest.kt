package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.rpc.PRICE_ORACLE_CONFIG_ID
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant

/** A [PriceOracleSource] test double that always returns [price] -- never performs real network I/O. */
private class FixedPriceSource(
    override val id: String,
    private val price: BigDecimal,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    val callCount = AtomicInteger(0)

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        callCount.incrementAndGet()
        return SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
    }
}

/** A [PriceOracleSource] test double that always fails. */
private class NeverRespondingSource(
    override val id: String,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    val callCount = AtomicInteger(0)

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        callCount.incrementAndGet()
        return null
    }
}

/** Two agreeing BTC sources + two agreeing GOLD_XAU sources + one FIAT source -- enough to reach quorum for every anchor. */
private fun fullyProvisionedOrchestrator(): PriceOracleOrchestrator =
    PriceOracleOrchestrator(
        sources =
            listOf(
                FixedPriceSource(id = "btc-a", price = BigDecimal("50000")),
                FixedPriceSource(id = "btc-b", price = BigDecimal("50100")),
                FixedPriceSource(id = "gold-a", price = BigDecimal("2000"), anchor = AnchorAsset.GOLD_XAU),
                FixedPriceSource(id = "gold-b", price = BigDecimal("2010"), anchor = AnchorAsset.GOLD_XAU),
                FixedPriceSource(id = "fiat-a", price = BigDecimal.ONE, anchor = AnchorAsset.FIAT),
            ),
    )

private fun setOracleConfig(
    anchorAsset: AnchorAsset = AnchorAsset.BITCOIN_BTC,
    donationCurrency: String = "EUR",
    cacheTtlSeconds: Int = 300,
    minQuorum: Int = 2,
) {
    transaction {
        PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
            it[PriceOracleConfigTable.anchorAsset] = anchorAsset
            it[PriceOracleConfigTable.donationCurrency] = donationCurrency
            it[anchorUnitsPerLtr] = BigDecimal("0.000001")
            it[PriceOracleConfigTable.cacheTtlSeconds] = cacheTtlSeconds
            it[PriceOracleConfigTable.minQuorum] = minQuorum
            it[outlierThresholdBps] = 300
            it[maxSpreadBps] = 1000
            it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
        }
    }
}

private fun snapshotCountFor(anchor: AnchorAsset): Long =
    transaction {
        PriceOracleSnapshotTable.selectAll().where { PriceOracleSnapshotTable.anchorAsset eq anchor }.count()
    }

/**
 * Welle "Price-Oracle-Preishistorie". Calls [PriceOracleSnapshotPoller.tick] directly (never
 * [PriceOracleSnapshotPoller.start]) -- zero timing dependency, same house style as
 * `network.lapis.cloud.server.payment.sepa.SepaBatchPollerTest`.
 */
class PriceOracleSnapshotPollerTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction { PriceOracleSnapshotTable.deleteAll() }
            setOracleConfig()
        }

        test("tick() writes exactly one row per anchor with sufficient sources, and none for an underprovisioned anchor") {
            setOracleConfig(anchorAsset = AnchorAsset.BITCOIN_BTC, donationCurrency = "EUR")
            // Only BTC + FIAT provisioned -- GOLD_XAU has zero sources on this deployment.
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FixedPriceSource(id = "btc-a", price = BigDecimal("50000")),
                            FixedPriceSource(id = "btc-b", price = BigDecimal("50100")),
                            FixedPriceSource(id = "fiat-a", price = BigDecimal.ONE, anchor = AnchorAsset.FIAT),
                        ),
                )
            val poller = PriceOracleSnapshotPoller(orchestrator = orchestrator, config = PriceOracleSnapshotConfig.load { null })

            runBlocking { poller.tick() }

            snapshotCountFor(AnchorAsset.BITCOIN_BTC) shouldBe 1L
            snapshotCountFor(AnchorAsset.FIAT) shouldBe 1L
            snapshotCountFor(AnchorAsset.GOLD_XAU) shouldBe 0L
        }

        test("tick() writes nothing for an anchor whose quote HALTs") {
            setOracleConfig(anchorAsset = AnchorAsset.BITCOIN_BTC, donationCurrency = "EUR")
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            NeverRespondingSource(id = "btc-a"),
                            NeverRespondingSource(id = "btc-b"),
                        ),
                )
            val poller = PriceOracleSnapshotPoller(orchestrator = orchestrator, config = PriceOracleSnapshotConfig.load { null })

            runBlocking { poller.tick() }

            snapshotCountFor(AnchorAsset.BITCOIN_BTC) shouldBe 0L
        }

        test("a second tick() with an identical priceTimestamp writes no duplicate row (dedupe)") {
            setOracleConfig(anchorAsset = AnchorAsset.BITCOIN_BTC, donationCurrency = "EUR")
            val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FixedPriceSource(id = "btc-a", price = BigDecimal("50000")),
                            FixedPriceSource(id = "btc-b", price = BigDecimal("50100")),
                        ),
                    clock = fixedInstantClock(fixedInstant),
                )
            val poller = PriceOracleSnapshotPoller(orchestrator = orchestrator, config = PriceOracleSnapshotConfig.load { null })

            runBlocking {
                poller.tick()
                poller.tick()
            }

            snapshotCountFor(AnchorAsset.BITCOIN_BTC) shouldBe 1L
        }

        test("one anchor throwing does not prevent the other anchors' snapshots (two-level exception safety)") {
            setOracleConfig(anchorAsset = AnchorAsset.BITCOIN_BTC, donationCurrency = "EUR")
            val throwingSource =
                object : PriceOracleSource {
                    override val id = "gold-throws"
                    override val anchor = AnchorAsset.GOLD_XAU

                    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? = error("boom")
                }
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FixedPriceSource(id = "btc-a", price = BigDecimal("50000")),
                            FixedPriceSource(id = "btc-b", price = BigDecimal("50100")),
                            throwingSource,
                            FixedPriceSource(id = "gold-b", price = BigDecimal("2010"), anchor = AnchorAsset.GOLD_XAU),
                            FixedPriceSource(id = "fiat-a", price = BigDecimal.ONE, anchor = AnchorAsset.FIAT),
                        ),
                )
            val poller = PriceOracleSnapshotPoller(orchestrator = orchestrator, config = PriceOracleSnapshotConfig.load { null })

            runBlocking { poller.tick() }

            snapshotCountFor(AnchorAsset.BITCOIN_BTC) shouldBe 1L
            snapshotCountFor(AnchorAsset.FIAT) shouldBe 1L
        }

        test(
            "deriveConfig uses the anchor's OWN quorumFloor (never baseConfig.minQuorum) for a non-active anchor, never lowers cacheTtlSeconds, and passes other fields through unchanged",
        ) {
            val base =
                PriceOracleConfigDto(
                    id = PRICE_ORACLE_CONFIG_ID.toString(),
                    anchorAsset = AnchorAsset.BITCOIN_BTC,
                    donationCurrency = "EUR",
                    anchorUnitsPerLtr = BigDecimal("0.000001"),
                    cacheTtlSeconds = 300,
                    minQuorum = 2,
                    outlierThresholdBps = 300,
                    maxSpreadBps = 1000,
                    updatedAt = LocalDateTime(2026, 1, 1, 0, 0),
                )

            val derivedGold = deriveConfig(anchor = AnchorAsset.GOLD_XAU, baseConfig = base)
            derivedGold.anchorAsset shouldBe AnchorAsset.GOLD_XAU
            derivedGold.minQuorum shouldBe AnchorPolicy.quorumFloor(AnchorAsset.GOLD_XAU)
            derivedGold.cacheTtlSeconds shouldBe AnchorPolicy.recommendedCacheTtlSeconds(AnchorAsset.GOLD_XAU)
            derivedGold.outlierThresholdBps shouldBe base.outlierThresholdBps
            derivedGold.maxSpreadBps shouldBe base.maxSpreadBps
            derivedGold.donationCurrency shouldBe base.donationCurrency

            // Active anchor -- returned verbatim, no derivation.
            deriveConfig(anchor = AnchorAsset.BITCOIN_BTC, baseConfig = base) shouldBe base

            // A base config's minQuorum (tuned for the ACTIVE anchor, e.g. BTC=5) must NEVER be
            // inherited by a derivation for a different anchor with fewer sources -- it always uses
            // that anchor's own floor instead (see deriveConfig KDoc). cacheTtlSeconds, in contrast,
            // is only ever raised, never lowered.
            val strictBase = base.copy(minQuorum = 5, cacheTtlSeconds = 999_999)
            val derivedGoldStrict = deriveConfig(anchor = AnchorAsset.GOLD_XAU, baseConfig = strictBase)
            derivedGoldStrict.minQuorum shouldBe AnchorPolicy.quorumFloor(AnchorAsset.GOLD_XAU)
            derivedGoldStrict.cacheTtlSeconds shouldBe 999_999
        }

        test(
            "tick() normalizes a lower-case donationCurrency at the write boundary, so the upper-case read path (getPriceHistory) still finds it",
        ) {
            // Review Round 1 finding "stille Leseinkonsistenz": PriceOracleSnapshotStore.recordIfAbsent
            // upper-cases donationCurrency before writing (PriceOracleService.getPriceHistory always
            // queries with .uppercase()). A persisted price_oracle_config.donation_currency is not
            // itself guaranteed upper case (direct SQL edit, restored backup, foreign-system
            // migration all bypass the RPC's membership check) -- this test pins that write-side
            // normalization so a future refactor that drops the .uppercase() call fails loudly here
            // instead of silently desyncing poller writes from getPriceHistory reads.
            setOracleConfig(anchorAsset = AnchorAsset.BITCOIN_BTC, donationCurrency = "eur")
            val orchestrator =
                PriceOracleOrchestrator(
                    sources =
                        listOf(
                            FixedPriceSource(id = "btc-a", price = BigDecimal("50000")),
                            FixedPriceSource(id = "btc-b", price = BigDecimal("50100")),
                        ),
                )
            val poller = PriceOracleSnapshotPoller(orchestrator = orchestrator, config = PriceOracleSnapshotConfig.load { null })

            runBlocking { poller.tick() }

            PriceOracleSnapshotStore
                .loadHistory(
                    anchorAsset = AnchorAsset.BITCOIN_BTC,
                    donationCurrency = "EUR",
                    since = null,
                    limit = 10,
                ).shouldNotBeEmpty()
        }

        test("start() is idempotent and stop() ends the loop") {
            val orchestrator = fullyProvisionedOrchestrator()
            val poller =
                PriceOracleSnapshotPoller(
                    orchestrator = orchestrator,
                    config = PriceOracleSnapshotConfig.load { key -> if (key == "LAPIS_ORACLE_SNAPSHOT_INTERVAL_SECONDS") "300" else null },
                )
            poller.start()
            poller.start() // no-op, must not throw or start a second loop
            poller.stop()
            poller.stop() // no-op
        }
    })

/** A [Clock] fixed at [instant] -- avoids any real wall-clock dependency in dedupe tests. */
private fun fixedInstantClock(instant: Instant): Clock =
    object : Clock {
        override fun now(): Instant = instant
    }
