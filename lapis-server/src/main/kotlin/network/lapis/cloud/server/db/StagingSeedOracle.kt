package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceStatus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Price history for the oracle charts (V1.9.205): 90 days of EUR quotes -- Bitcoin hourly, gold every 12 hours, the fiat anchor daily -- as the
 * poller would have recorded them (`status` LIVE, one row per anchor and instant; the unique index on anchor, currency and instant is respected).
 *
 * Written ONLY into an empty `price_oracle_snapshot` table: staging instances carry hand-made rows, which this part never mixes with a synthetic
 * series (it then writes nothing). The prices are a deterministic random walk (fixed seed) around plausible EUR levels -- Bitcoin per coin, gold per
 * troy ounce, the fiat anchor is exactly one EUR in EUR. `sourcesUsed` starts with the seed marker, the source count is the length of the list after it.
 * The newest instant lies before the current hour, so the live poller's next quote never collides with a seeded instant.
 */
internal object StagingSeedOracle : StagingSeedPart {
    override val name: String = "oracle"

    private const val CURRENCY = "EUR"
    private const val DAYS = 90

    data class Quote(
        val id: Uuid,
        val anchor: AnchorAsset,
        val price: BigDecimal,
        val sources: List<String>,
        val priceTimestamp: LocalDateTime,
    ) {
        val capturedAt: LocalDateTime get() = (priceTimestamp.toInstant(TimeZone.UTC) + 5.seconds).toLocalDateTime(TimeZone.UTC)
        val sourcesUsed: String get() = "${StagingSeedLedger.NOTE_PREFIX} ${sources.joinToString(",")}"
    }

    /** The newest quote instant: the start of the previous full hour (UTC), so that the capture stamp is never in the future. */
    private fun newest(clock: SeedClock): LocalDateTime {
        val thisHour = clock.now.date.atTime(clock.now.hour, 0)
        return (thisHour.toInstant(TimeZone.UTC) - 1.hours).toLocalDateTime(TimeZone.UTC)
    }

    private fun walk(
        seed: Long,
        count: Int,
        start: Double,
        volatility: Double,
        floor: Double,
        ceiling: Double,
    ): List<BigDecimal> {
        val random = Random(seed)
        var current = start
        return List(count) {
            current = (current * (1.0 + random.nextGaussian() * volatility)).coerceIn(floor, ceiling)
            BigDecimal(current).setScale(2, RoundingMode.HALF_UP)
        }
    }

    /** The whole synthetic series, oldest first within each anchor; deterministic. */
    fun series(clock: SeedClock): List<Quote> {
        val newest = newest(clock)

        fun at(
            stepHours: Int,
            stepsBack: Int,
        ) = (newest.toInstant(TimeZone.UTC) - (stepHours * stepsBack).hours).toLocalDateTime(TimeZone.UTC)
        val btcCount = DAYS * 24
        val goldCount = DAYS * 2
        val bitcoin =
            walk(seed = 205L, count = btcCount, start = 88_000.0, volatility = 0.004, floor = 40_000.0, ceiling = 150_000.0)
                .mapIndexed { i, price ->
                    Quote(
                        id = SeedIds.oracle(i),
                        anchor = AnchorAsset.BITCOIN_BTC,
                        price = price,
                        sources = listOf("coinbase", "kraken", "bitstamp"),
                        priceTimestamp =
                            at(
                                1,
                                btcCount - 1 - i,
                            ),
                    )
                }
        val gold =
            walk(seed = 2051L, count = goldCount, start = 3_350.0, volatility = 0.003, floor = 2_000.0, ceiling = 6_000.0)
                .mapIndexed { i, price ->
                    Quote(
                        id = SeedIds.oracle(0x1000 + i),
                        anchor = AnchorAsset.GOLD_XAU,
                        price = price,
                        sources = listOf("goldapi", "metalpriceapi"),
                        priceTimestamp =
                            at(
                                12,
                                goldCount - 1 - i,
                            ),
                    )
                }
        val fiat =
            List(DAYS) { i ->
                Quote(
                    id = SeedIds.oracle(0x2000 + i),
                    anchor = AnchorAsset.FIAT,
                    price = BigDecimal.ONE.setScale(2),
                    sources = listOf("ecb"),
                    priceTimestamp =
                        at(
                            24,
                            DAYS - 1 - i,
                        ),
                )
            }
        return bitcoin + gold + fiat
    }

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        if (!PriceOracleSnapshotTable.selectAll().empty()) return StagingSeedPartResult(inserted = 0, skipped = 1)
        val quotes = series(clock)
        PriceOracleSnapshotTable.batchInsert(quotes, shouldReturnGeneratedValues = false) { quote ->
            this[PriceOracleSnapshotTable.id] = quote.id
            this[PriceOracleSnapshotTable.anchorAsset] = quote.anchor
            this[PriceOracleSnapshotTable.donationCurrency] = CURRENCY
            this[PriceOracleSnapshotTable.medianPrice] = quote.price
            this[PriceOracleSnapshotTable.priceStatus] = PriceStatus.LIVE
            this[PriceOracleSnapshotTable.sourceCount] = quote.sources.size
            this[PriceOracleSnapshotTable.sourcesUsed] = quote.sourcesUsed
            this[PriceOracleSnapshotTable.priceTimestamp] = quote.priceTimestamp
            this[PriceOracleSnapshotTable.capturedAt] = quote.capturedAt
        }
        return StagingSeedPartResult(inserted = quotes.size, skipped = 0)
    }
}
