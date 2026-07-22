package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Scale used for every median/deviation `BigDecimal` computation -- generous headroom above the DECIMAL(38,18) column precision, never `Double`, so no floating-point drift ever enters an amount that ends up minted as LTR. */
private const val ORACLE_MATH_SCALE = 20

private const val BPS_PER_UNIT = 10_000

/** A successful oracle price quote for the active anchor -- see [PriceOracleOrchestrator.currentQuote]. */
data class PriceQuote(
    val status: PriceStatus,
    val medianPrice: BigDecimal,
    val contributingSourceIds: List<String>,
    val priceTimestamp: Instant,
)

/** The result of [PriceOracleOrchestrator.currentQuote] -- either a usable [PriceQuote], or [Halt] (no LTR may be minted from this call). */
sealed interface QuoteOutcome {
    data class Ok(
        val quote: PriceQuote,
    ) : QuoteOutcome

    data class Halt(
        val reason: String,
    ) : QuoteOutcome
}

/** The last successful quote, kept purely to serve a [PriceStatus.CACHED] response within [PriceOracleConfigDto.cacheTtlSeconds] when a live quorum cannot be reached. Per-JVM/single-server only -- a shared/federated cache is a later wave's concern, see `19-price-oracle.kuml.kts` file header. */
private data class CachedQuote(
    val price: BigDecimal,
    val sourceIds: List<String>,
    val timestamp: Instant,
)

/**
 * The median/outlier-rejection/cache/halt core of the Price-Oracle -- see
 * `19-price-oracle.kuml.kts` file header for the full fachlich model and
 * `network.lapis.cloud.shared.rpc.IPriceOracleService` KDoc for the scope-cuts this implements
 * (no persistent halt-queue -- HALT rejects instead of queueing).
 *
 * **Singleton lifecycle**: constructed exactly once, by `Application.module`, and held for the
 * whole application lifetime -- both because it owns [sources] (whose [oracleHttpClient] should
 * never be constructed per-request) and because the in-memory [cache] field must survive across
 * calls to actually serve a [PriceStatus.CACHED] fallback.
 *
 * **Algorithm** (pure -- fully unit-testable with fake [PriceOracleSource]s, no network):
 * 1. Query every configured [sources] entry in parallel with each source's own bounded timeout
 *    (see [oracleHttpClient]'s [io.ktor.client.plugins.HttpTimeout] install); collect the
 *    responses that did not return `null`.
 * 2. If fewer than [PriceOracleConfigDto.minQuorum] sources responded, fall back to the cache
 *    (step 6).
 * 3. Compute a provisional median over every responded price, then drop any price whose deviation
 *    from that provisional median exceeds [PriceOracleConfigDto.outlierThresholdBps] -- the
 *    survivors.
 * 4. If fewer than [PriceOracleConfigDto.minQuorum] survivors remain, fall back to the cache (step
 *    6). Otherwise, if the survivors' own spread `(max-min)/median` exceeds
 *    [PriceOracleConfigDto.maxSpreadBps], ALSO fall back to the cache (step 6) -- an untrustworthy
 *    spread must never be silently accepted just because enough sources nominally agreed.
 * 5. Compute the true median over the survivors (average of the two middle values for an even
 *    count), update the cache, and return [QuoteOutcome.Ok] with status [PriceStatus.LIVE] (every
 *    configured source for the anchor survived) or [PriceStatus.DEGRADED] (fewer than all did, but
 *    at least [PriceOracleConfigDto.minQuorum]).
 * 6. **Cache fallback**: if a cached quote exists and is no older than
 *    [PriceOracleConfigDto.cacheTtlSeconds], return [QuoteOutcome.Ok] with status
 *    [PriceStatus.CACHED] and the cached price/sources/timestamp. Otherwise, [QuoteOutcome.Halt]
 *    -- no LTR may be minted from this call.
 */
class PriceOracleOrchestrator(
    private val sources: List<PriceOracleSource>,
    private val clock: Clock = Clock.System,
) {
    private val cache = AtomicReference<CachedQuote?>(null)

    suspend fun currentQuote(config: PriceOracleConfigDto): QuoteOutcome {
        val currency = config.donationCurrency
        val responded =
            coroutineScope {
                sources
                    .map { source -> async { runCatching { source.fetchPrice(currency) }.getOrNull() } }
                    .awaitAll()
                    .filterNotNull()
            }

        if (responded.size < config.minQuorum) {
            return cacheFallbackOrHalt(
                config,
                "Only ${responded.size}/${sources.size} configured sources responded, below minQuorum ${config.minQuorum}",
            )
        }

        val provisionalMedian = median(responded.map { it.price })
        val survivors = responded.filter { deviationBps(it.price, provisionalMedian) <= config.outlierThresholdBps.toBigDecimal() }

        if (survivors.size < config.minQuorum) {
            return cacheFallbackOrHalt(
                config,
                "Only ${survivors.size}/${sources.size} sources survived outlier rejection (threshold ${config.outlierThresholdBps}bps), below minQuorum ${config.minQuorum}",
            )
        }

        val survivorPrices = survivors.map { it.price }
        val finalMedian = median(survivorPrices)
        val spreadBps = spreadBps(survivorPrices, finalMedian)
        if (spreadBps > config.maxSpreadBps.toBigDecimal()) {
            return cacheFallbackOrHalt(
                config,
                "Survivor price spread ${spreadBps}bps exceeds maxSpreadBps ${config.maxSpreadBps}",
            )
        }

        val status = if (survivors.size == sources.size) PriceStatus.LIVE else PriceStatus.DEGRADED
        val now = clock.now()
        val sourceIds = survivors.map { it.sourceId }
        cache.set(CachedQuote(price = finalMedian, sourceIds = sourceIds, timestamp = now))
        return QuoteOutcome.Ok(
            PriceQuote(status = status, medianPrice = finalMedian, contributingSourceIds = sourceIds, priceTimestamp = now),
        )
    }

    private fun cacheFallbackOrHalt(
        config: PriceOracleConfigDto,
        reason: String,
    ): QuoteOutcome {
        val cached = cache.get()
        if (cached == null) {
            logger.warn { "Price-Oracle halted (no cache available): $reason" }
            return QuoteOutcome.Halt(reason)
        }
        val age = clock.now() - cached.timestamp
        if (age <= config.cacheTtlSeconds.seconds) {
            return QuoteOutcome.Ok(
                PriceQuote(
                    status = PriceStatus.CACHED,
                    medianPrice = cached.price,
                    contributingSourceIds = cached.sourceIds,
                    priceTimestamp = cached.timestamp,
                ),
            )
        }
        val expiredReason = "$reason; cached price expired (age=$age, ttl=${config.cacheTtlSeconds}s)"
        logger.warn { "Price-Oracle halted: $expiredReason" }
        return QuoteOutcome.Halt(expiredReason)
    }

    private fun median(prices: List<BigDecimal>): BigDecimal {
        val sorted = prices.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2].setScale(ORACLE_MATH_SCALE, RoundingMode.HALF_UP)
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]).divide(BigDecimal(2), ORACLE_MATH_SCALE, RoundingMode.HALF_UP)
        }
    }

    private fun deviationBps(
        price: BigDecimal,
        median: BigDecimal,
    ): BigDecimal {
        if (median.signum() == 0) return BigDecimal.ZERO
        return (price - median).abs().multiply(BigDecimal(BPS_PER_UNIT)).divide(median, ORACLE_MATH_SCALE, RoundingMode.HALF_UP)
    }

    private fun spreadBps(
        prices: List<BigDecimal>,
        median: BigDecimal,
    ): BigDecimal {
        if (median.signum() == 0) return BigDecimal.ZERO
        val max = prices.max()
        val min = prices.min()
        return (max - min).multiply(BigDecimal(BPS_PER_UNIT)).divide(median, ORACLE_MATH_SCALE, RoundingMode.HALF_UP)
    }
}
