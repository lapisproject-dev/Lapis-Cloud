package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotSourceTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.rpc.PRICE_ORACLE_CONFIG_ID
import network.lapis.cloud.server.rpc.loadPriceOracleConfigOrNull
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.ConversionRateBasis
import network.lapis.cloud.shared.domain.PriceStatus
import network.lapis.cloud.shared.domain.ReferenceRateBasis
import network.lapis.cloud.shared.domain.ReferenceRateOutcome
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val PRICE = BigDecimal("2012.345678")
private val DAY = LocalDate(2026, 11, 20)

/**
 * V1.9.327 -- the legacy-snapshot rule of the reference-date resolver: a snapshot WITHOUT stored single values is accepted as a closing rate only when it
 * was captured before the raw-values cutoff (the time the poller started storing them); after the cutoff a missing or incomplete value set is a defect.
 */
class RawValuesCutoffTest :
    FunSpec({
        var originalSource: Clock = Clock.System

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            originalSource = ServerClock.source
            ServerClock.source =
                object : Clock {
                    override fun now(): Instant = Instant.parse("2026-11-26T10:00:00Z")
                }
        }
        afterSpec { ServerClock.source = originalSource }

        beforeTest {
            RawValuesCutoffHolder.current = RawValuesCutoff { NO_LEGACY_CUTOFF }
            transaction {
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[anchorAsset] = AnchorAsset.GOLD_XAU
                    it[donationCurrency] = "EUR"
                    it[anchorUnitsPerLtr] = BigDecimal("0.0001")
                    it[cacheTtlSeconds] = 172_800
                    it[minQuorum] = 2
                    it[conversionRateBasis] = ConversionRateBasis.REFERENCE_DATE
                }
            }
        }
        afterTest {
            RawValuesCutoffHolder.current = RawValuesCutoff { NO_LEGACY_CUTOFF }
            transaction {
                PriceOracleReferenceRateTable.deleteAll()
                PriceOracleSnapshotSourceTable.deleteAll()
                PriceOracleSnapshotTable.deleteAll()
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[anchorAsset] = AnchorAsset.BITCOIN_BTC
                    it[anchorUnitsPerLtr] = BigDecimal("0.000001")
                    it[cacheTtlSeconds] = 300
                    it[conversionRateBasis] = ConversionRateBasis.CURRENT
                }
            }
        }

        fun snapshot(
            at: String,
            sources: Int = 2,
            includedSources: Int = sources,
            status: PriceStatus = PriceStatus.LIVE,
            sourceCount: Int = 2,
            price: BigDecimal = PRICE,
        ): Uuid {
            val id = Uuid.random()
            val ts = LocalDateTime.parse(at)
            transaction {
                PriceOracleSnapshotTable.insert {
                    it[PriceOracleSnapshotTable.id] = id
                    it[anchorAsset] = AnchorAsset.GOLD_XAU
                    it[donationCurrency] = "EUR"
                    it[medianPrice] = price
                    it[priceStatus] = status
                    it[PriceOracleSnapshotTable.sourceCount] = sourceCount
                    it[sourcesUsed] = "goldapi,metalpriceapi"
                    it[priceTimestamp] = ts
                    it[capturedAt] = ts
                }
                repeat(sources) { i ->
                    PriceOracleSnapshotSourceTable.insert {
                        it[PriceOracleSnapshotSourceTable.id] = Uuid.random()
                        it[snapshotId] = id
                        it[sourceId] = "src-$i"
                        it[rawPrice] = price
                        it[observedAt] = ts
                        it[included] = i < includedSources
                    }
                }
            }
            return id
        }

        fun cutoff(at: String) {
            RawValuesCutoffHolder.current = RawValuesCutoff { LocalDateTime.parse(at) }
        }

        fun resolve() =
            transaction {
                PriceOracleReferenceRateResolver.resolve(config = loadPriceOracleConfigOrNull()!!, referenceDate = DAY)
            }

        test(
            "legacy snapshot (no stored values, captured before the cutoff): accepted as the closing rate, flagged rawValuesStored=false",
        ) {
            val id = snapshot("2026-11-20T10:00:00", sources = 0)
            cutoff("2026-11-25T00:00:00")
            val r = resolve()
            r.outcome shouldBe ReferenceRateOutcome.RESOLVED
            r.basis shouldBe ReferenceRateBasis.SNAPSHOT
            r.snapshot?.id shouldBe id
            r.rawValuesStored shouldBe false
            transaction { ReferenceRateConversion.toResolutionDto(resolution = r, recipient = null).rawValuesStored } shouldBe false
        }

        test("a complete snapshot reports rawValuesStored=true even when the cutoff lies after it") {
            snapshot("2026-11-20T10:00:00")
            cutoff("2026-11-25T00:00:00")
            val r = resolve()
            r.outcome shouldBe ReferenceRateOutcome.RESOLVED
            r.rawValuesStored shouldBe true
        }

        test("the default (no cutoff known) accepts no legacy snapshot: RAW_VALUES_MISSING with the median as hint") {
            snapshot("2026-11-20T10:00:00", sources = 0)
            val r = resolve()
            r.outcome shouldBe ReferenceRateOutcome.RAW_VALUES_MISSING
            r.legacyMedianHint?.compareTo(PRICE) shouldBe 0
        }

        test("refresh after a restore: older snapshots with values make a later sourceless snapshot RAW_VALUES_MISSING without a restart") {
            // Cutoff as read at startup of a fresh server (V171 just applied): a sourceless snapshot before it is a legacy snapshot.
            cutoff("2026-11-25T10:00:00")
            snapshot("2026-11-20T10:00:00", sources = 0)
            resolve().outcome shouldBe ReferenceRateOutcome.RESOLVED
            // A restore brings an older snapshot WITH values: after refresh the boundary drops to it.
            snapshot("2020-01-01T00:00:00")
            RawValuesCutoffHolder.refresh()
            resolve().outcome shouldBe ReferenceRateOutcome.RAW_VALUES_MISSING
        }

        test("a legacy snapshot that is DEGRADED or below the quorum is not accepted") {
            cutoff("2026-11-25T00:00:00")
            snapshot("2026-11-20T10:00:00", sources = 0, status = PriceStatus.DEGRADED)
            resolve().outcome shouldBe ReferenceRateOutcome.STATUS_NOT_LIVE
            transaction { PriceOracleSnapshotTable.deleteAll() }
            snapshot("2026-11-20T10:00:00", sources = 0, sourceCount = 1)
            resolve().outcome shouldBe ReferenceRateOutcome.QUORUM_MISSING
        }

        test("after the cutoff, a snapshot without values or with incomplete values is a defect: RAW_VALUES_MISSING, never a rate") {
            cutoff("2026-11-10T00:00:00")
            snapshot("2026-11-20T10:00:00", sources = 0)
            resolve().outcome shouldBe ReferenceRateOutcome.RAW_VALUES_MISSING
            transaction { PriceOracleSnapshotTable.deleteAll() }
            // two sources counted, only one stored as included
            snapshot("2026-11-20T10:00:00", sources = 2, includedSources = 1)
            resolve().outcome shouldBe ReferenceRateOutcome.RAW_VALUES_MISSING
        }

        test("the day of the deployment: a later complete snapshot beats an earlier legacy one") {
            cutoff("2026-11-20T12:00:00")
            snapshot("2026-11-20T10:00:00", sources = 0, price = BigDecimal("2000.1"))
            val later = snapshot("2026-11-20T15:00:00", price = BigDecimal("2001.2"))
            val r = resolve()
            r.snapshot?.id shouldBe later
            r.rawValuesStored shouldBe true
        }

        test(
            "the day of the deployment: a defective snapshot after the cutoff falls back to the earlier legacy one (latest QUALIFYING wins)",
        ) {
            cutoff("2026-11-20T12:00:00")
            val legacy = snapshot("2026-11-20T10:00:00", sources = 0, price = BigDecimal("2000.1"))
            snapshot("2026-11-20T15:00:00", sources = 0, price = BigDecimal("2999.9"))
            val r = resolve()
            r.outcome shouldBe ReferenceRateOutcome.RESOLVED
            r.snapshot?.id shouldBe legacy
            r.rawValuesStored shouldBe false
        }

        test("a manual rate still beats a legacy snapshot") {
            cutoff("2026-11-25T00:00:00")
            snapshot("2026-11-20T10:00:00", sources = 0)
            transaction {
                PriceOracleReferenceRateTable.insert {
                    it[id] = Uuid.random()
                    it[anchorAsset] = AnchorAsset.GOLD_XAU
                    it[donationCurrency] = "EUR"
                    it[effectiveDate] = DAY
                    it[price] = BigDecimal("2222.5")
                    it[evidenceText] = "Manual evidence text with enough length"
                    it[confirmedBy] = Uuid.parse("00000000-0000-0000-0000-000000000001")
                    it[confirmedAt] = LocalDateTime(2026, 11, 21, 10, 0)
                    it[referenceSnapshotMedian] = null
                    it[deviationAcknowledged] = false
                }
            }
            val r = resolve()
            r.basis shouldBe ReferenceRateBasis.MANUAL
            r.rawValuesStored shouldBe true
        }

        test(
            "the cutoff read: a missing V171 entry or an unreadable table gives the epoch, never a crash; a snapshot with values caps it",
        ) {
            val read = FlywayRawValuesCutoff.readCutoff()
            // The test database ran V171, so a real cutoff exists (not the epoch) and it is not in the future.
            (read > NO_LEGACY_CUTOFF) shouldBe true
            (read <= ServerClock.now().let { LocalDateTime(it.date.year + 1, 1, 1, 0, 0) }) shouldBe true
            // A snapshot with stored values captured long before "installed_on" proves V171 was active earlier: the cutoff drops to it.
            snapshot("2020-01-01T00:00:00")
            FlywayRawValuesCutoff.readCutoff() shouldBe LocalDateTime(2020, 1, 1, 0, 0)
        }
    })
