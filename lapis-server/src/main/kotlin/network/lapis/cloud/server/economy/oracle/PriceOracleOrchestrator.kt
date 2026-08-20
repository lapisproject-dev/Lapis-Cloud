package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

private const val BPS_PER_UNIT = 10_000

/**
 * Security-Audit-Runde 1 / S1, tightened by Security-Audit-Runde 2 / S9: the divisor used to derive
 * the SEPARATE, always-enforced floor on how often a real network fan-out may happen for one
 * [CacheKey], independent of -- and never cleared by -- [PriceOracleOrchestrator.invalidateReplayState].
 * See [PriceOracleOrchestrator.lastFanoutAt] KDoc for the full rationale.
 *
 * **S9 finding**: Round 1's fix used a single flat `60`-second constant, chosen only to be "comfortably
 * below a human save/preview loop" -- it was never derived from the actual free-tier budget it exists
 * to protect. A flat 60s floor permits up to `86_400 / 60 = 1440` real fan-outs/day/key, against a
 * documented budget assumption (`GoldPriceSources.kt` "Free-tier request budget") of `<=2` fan-outs/day
 * for GOLD_XAU/FIAT -- roughly 700x looser than the cadence it was supposed to bound, and itself at
 * Alpha Vantage's 5/minute burst cap. "Finite" is not the same as "protective".
 *
 * **The fix**: derive the floor from [AnchorPolicy.refreshIntervalSeconds] itself instead of a
 * standalone constant, so it scales with whatever cadence each anchor's sources are actually budgeted
 * for: `floorSeconds = refreshIntervalSeconds(anchor) / HARD_FLOOR_FANOUT_DIVISOR`. For GOLD_XAU/FIAT
 * (`refreshIntervalSeconds` = 43 200s / 12h), a divisor of `3` yields a `14_400`s (4h) floor -- at most
 * `86_400 / 14_400 = 6` real fan-outs/day/key, a 3x multiple of the documented `<=2`/day cadence
 * assumption (not the previous ~700x), while still comfortably under Alpha Vantage's 25/day quota (6 is
 * 24% of it) and nowhere near its 5/minute cap (at most one fan-out per 4h touches Alpha Vantage at
 * all). `3` was chosen over a looser divisor (e.g. `1`, i.e. no tightening beyond the interval itself)
 * because it still lets a genuine rapid sequence of DIFFERENT admin config changes recover within a
 * bounded, human-scale window (4h, not 12h) rather than forcing every genuine change to wait out the
 * full refresh interval -- see [PriceOracleService.updateOracleConfig]'s compare-before-invalidate fix
 * (S1, first half) for why that recovery path matters. Completely inert for [AnchorAsset.BITCOIN_BTC]:
 * this divisor is only ever applied to `refreshIntervalSeconds(anchor)`, and BTC's is `0` -- the whole
 * `if (refreshInterval > 0)` gate in [PriceOracleOrchestrator.currentQuote] (mutex, [lastAttempts],
 * [lastFanoutAt], this floor) is skipped entirely for BTC, exactly as it was before S9, so BTC fan-out
 * frequency is byte-for-byte unaffected by this change.
 */
private const val HARD_FLOOR_FANOUT_DIVISOR = 3

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

/** Cache key -- V0.6.6 keys the cache by (anchor, donationCurrency) instead of a single unkeyed slot, fixing a latent bug where a BTC-priced quote could be served after an ADMIN switched the anchor to Gold, or the currency from EUR to USD. [CachedQuote] is looked up/stored under this key everywhere. */
private data class CacheKey(
    val anchor: AnchorAsset,
    val donationCurrency: String,
)

/** The last successful quote for one [CacheKey], kept purely to serve a [PriceStatus.CACHED] response within [PriceOracleConfigDto.cacheTtlSeconds] when a live quorum cannot be reached (or, for a refresh-interval-throttled anchor, to serve the refresh-interval short-circuit -- see [PriceOracleOrchestrator.currentQuote]). Per-JVM/single-server only -- a shared/federated cache is a later wave's concern, see `19-price-oracle.kuml.kts` file header. */
private data class CachedQuote(
    val price: BigDecimal,
    val sourceIds: List<String>,
    val timestamp: Instant,
    /**
     * The status this quote had when it was FETCHED. Served verbatim by the refresh-interval
     * short-circuit -- a 3-hour-old gold price inside its designed refresh window is the normal
     * operating mode, not a degradation, and [PriceQuote.priceTimestamp] already carries the real
     * freshness signal into the persisted provenance row. The quorum-FAILURE fallback
     * ([PriceOracleOrchestrator.cacheFallbackOrHalt]) still overrides this to [PriceStatus.CACHED],
     * which keeps that literal's documented meaning ("live quorum could not be reached") exactly
     * intact.
     */
    val status: PriceStatus,
)

/**
 * When [PriceOracleOrchestrator] last actually ATTEMPTED a live fan-out for one [CacheKey],
 * together with the exact [QuoteOutcome] that attempt produced -- written on EVERY real fan-out,
 * success OR failure, unlike [CachedQuote] which is only ever written on a successful median (see
 * that class KDoc). This is the Review Round 1 / MAJOR-1 fix: gating the refresh-interval
 * short-circuit on THIS instead of on [CachedQuote] closes a failure-path re-fan-out bug -- before
 * this fix, the short-circuit could only ever be REACHED once the cached quote was already older
 * than the refresh interval, so a fan-out that then failed to reach quorum left [CachedQuote]
 * untouched and every subsequent call (however soon after) fanned out again, unboundedly, burning
 * through the healthy sources' free-tier quota in lockstep with the unhealthy one's outage. A
 * failed attempt now "uses up" its refresh-interval window exactly like a successful one does --
 * see [PriceOracleOrchestrator.currentQuote]'s own KDoc-in-code comment.
 */
private data class LastAttempt(
    val at: Instant,
    val outcome: QuoteOutcome,
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
 * **Anchor-aware source selection (V0.6.6)**: [sources] is grouped by [PriceOracleSource.anchor]
 * into [sourcesByAnchor]; [currentQuote] only ever fans out to the ACTIVE anchor's subset
 * (`config.anchorAsset`). Before this wave the orchestrator queried EVERY configured source
 * regardless of the active anchor and cached one unkeyed quote -- both were latent bugs (a BTC
 * price served as a gold price) that only became reachable once a second anchor existed; both are
 * fixed here.
 *
 * **Algorithm** (pure -- fully unit-testable with fake [PriceOracleSource]s, no network):
 * 0. If [AnchorPolicy.refreshIntervalSeconds] for the active anchor is `> 0` and a [LastAttempt]
 *    exists for this [CacheKey] whose age is within that refresh interval, return its [QuoteOutcome]
 *    verbatim (short-circuit, no fan-out at all) -- REGARDLESS of whether that last attempt
 *    succeeded or failed (Review Round 1 / MAJOR-1 fix, see [LastAttempt] KDoc) -- UNLESS that
 *    outcome is an `Ok` quote with status [PriceStatus.CACHED], in which case its OWN
 *    [PriceQuote.priceTimestamp] is re-checked against [PriceOracleConfigDto.cacheTtlSeconds] first;
 *    a `CACHED` outcome that has since aged past its own TTL is never replayed, a fresh fan-out runs
 *    instead (Review Round 2 / NEW-1 fix, see the check in [currentQuote]). This step also
 *    single-flights concurrent callers for the same [CacheKey] via a per-key [Mutex], so simultaneous
 *    calls arriving during an in-flight fan-out do not each independently fan out. `0` for
 *    [AnchorAsset.BITCOIN_BTC] means this step never applies to BTC -- neither the short-circuit nor
 *    the mutex -- the BTC code path is provably byte-for-byte unchanged by this wave.
 * 0a. (Security-Audit-Runde 1 / S1, tightened by Security-Audit-Runde 2 / S9) Still inside the same
 *    per-key mutex, and only once step 0 above did NOT already return: if a real fan-out for this
 *    [CacheKey] happened less than `AnchorPolicy.refreshIntervalSeconds(anchor) / [HARD_FLOOR_FANOUT_DIVISOR]`
 *    ago (tracked by [lastFanoutAt], which [invalidateReplayState] can never reset), fall back to the
 *    cache (step 7) instead of fanning out -- a hard, config-invalidation-proof floor on real fan-out
 *    frequency, see [lastFanoutAt] KDoc.
 * 1. Query every source configured for the active anchor in parallel with each source's own bounded
 *    timeout (see [oracleHttpClient]'s [io.ktor.client.plugins.HttpTimeout] install); collect the
 *    responses that did not return `null`.
 * 2. Drop any response whose price falls outside [plausibilityBand] for the active anchor (an
 *    inversion/structural-error guard, counted as "did not respond" for the quorum) -- see that
 *    function's KDoc.
 * 3. Compute `effectiveQuorum = maxOf(config.minQuorum, AnchorPolicy.quorumFloor(anchor))`. If fewer
 *    than `effectiveQuorum` plausible responses remain, fall back to the cache (step 7).
 * 4. Compute a provisional median over every plausible price, then drop any price whose deviation
 *    from that provisional median exceeds [PriceOracleConfigDto.outlierThresholdBps] -- the
 *    survivors.
 * 5. If fewer than `effectiveQuorum` survivors remain, fall back to the cache (step 7). Otherwise, if
 *    the survivors' own spread `(max-min)/median` exceeds [PriceOracleConfigDto.maxSpreadBps], ALSO
 *    fall back to the cache (step 7) -- an untrustworthy spread must never be silently accepted just
 *    because enough sources nominally agreed.
 * 6. Compute the true median over the survivors (average of the two middle values for an even
 *    count), update the cache (keyed by anchor + donation currency), and return [QuoteOutcome.Ok]
 *    with status [PriceStatus.LIVE] (every configured source for the anchor survived) or
 *    [PriceStatus.DEGRADED] (fewer than all did, but at least `effectiveQuorum`).
 * 7. **Cache fallback**: if a cached quote exists for this (anchor, currency) key and is no older
 *    than [PriceOracleConfigDto.cacheTtlSeconds], return [QuoteOutcome.Ok] with status
 *    [PriceStatus.CACHED] and the cached price/sources/timestamp. Otherwise, [QuoteOutcome.Halt] --
 *    no LTR may be minted from this call.
 *
 * **The quorum-floor clamp is the single most important safety invariant of this wave**: the
 * ADMIN-tunable [PriceOracleConfigDto.minQuorum] can only ever be raised above the anchor's
 * code-fixed [AnchorPolicy.quorumFloor], never lowered below it. [AnchorAsset.FIAT]'s floor of 1 is
 * therefore a per-anchor EXCEPTION, not a global relaxation -- if [AnchorAsset.BITCOIN_BTC] or
 * [AnchorAsset.GOLD_XAU] ever end up with a persisted `minQuorum` of 1 (direct SQL edit, restored
 * backup, a future validation bug), this clamp still forces `>= 2` and the quote HALTS rather than
 * minting LTR off a single source. There is deliberately no `if (survivors.size == 1) skip` branch
 * anywhere in the median/outlier/spread math below -- it is already degenerate-safe (a single
 * survivor's provisional median equals its own price, its deviation is 0, its spread is 0), so
 * special-casing `n == 1` would be exactly the blanket weakening this invariant must avoid.
 */
class PriceOracleOrchestrator(
    private val sources: List<PriceOracleSource>,
    private val clock: Clock = Clock.System,
) {
    private val sourcesByAnchor: Map<AnchorAsset, List<PriceOracleSource>> = sources.groupBy { it.anchor }
    private val cache = ConcurrentHashMap<CacheKey, CachedQuote>()

    /** Written on every real fan-out attempt (success OR failure) for a [CacheKey] whose anchor has a non-zero [AnchorPolicy.refreshIntervalSeconds] -- see [LastAttempt] KDoc / Review Round 1 MAJOR-1. Never touched for BITCOIN_BTC. */
    private val lastAttempts = ConcurrentHashMap<CacheKey, LastAttempt>()

    /** One [Mutex] per [CacheKey] that has ever needed the refresh-interval gate -- single-flights concurrent callers so simultaneous calls during an in-flight fan-out do not each independently fan out (Review Round 1 MAJOR-1, "nice to have" half of the fix). The key space is bounded ([AnchorAsset.entries] x a handful of donation currencies), so this map cannot grow unbounded. */
    private val keyMutexes = ConcurrentHashMap<CacheKey, Mutex>()

    /**
     * Security-Audit-Runde 1 / S1: when a REAL network fan-out last actually ran for a [CacheKey] --
     * unlike [lastAttempts] and [cache], this map is **never** touched by [invalidateReplayState].
     * That asymmetry is the entire point: [invalidateReplayState] exists so a genuine config change
     * (a tightened `outlierThresholdBps`, a lowered `cacheTtlSeconds`) takes effect immediately
     * instead of waiting out the refresh interval -- but it also means an ADMIN who saves the config
     * repeatedly (in the ordinary "tune a field, save, click preview to see the effect" workflow, or
     * in a hijacked-session rapid save-loop) can force [lastAttempts]/[cache] to `clear()` on every
     * single save, defeating the refresh-interval short-circuit's entire purpose of bounding fan-out
     * frequency against the gold/fiat sources' free-tier quotas. [currentQuote] additionally checks
     * THIS map -- which no config save can ever reset -- against a per-anchor floor derived from
     * [AnchorPolicy.refreshIntervalSeconds] (see [HARD_FLOOR_FANOUT_DIVISOR] KDoc) before allowing a
     * real fan-out.
     *
     * **Security-Audit-Runde 2 / S9 (tightened)**: Round 1's floor was a flat 60s constant, which
     * bounded fan-out frequency to *some* finite rate but not to anything close to the actual
     * documented budget -- up to 1440 real fan-outs/day/key versus the `<=2`/day the free-tier
     * arithmetic in `GoldPriceSources.kt` assumes. The floor is now derived from
     * `AnchorPolicy.refreshIntervalSeconds(anchor) / HARD_FLOOR_FANOUT_DIVISOR`: for GOLD_XAU/FIAT
     * (12h refresh interval, divisor 3) that is a 4h floor, i.e. at most 6 real fan-outs/day/key --
     * still headroom for a genuine rapid sequence of DIFFERENT admin config changes to recover sooner
     * than the full 12h refresh interval, but only a 3x multiple of the documented cadence assumption
     * instead of ~700x. So even an unbounded sequence of `updateOracleConfig` calls can never drive
     * real fan-out frequency for one key above roughly once per 4h (for GOLD_XAU/FIAT as currently
     * configured). Never touched for BITCOIN_BTC, exactly like [lastAttempts]/[keyMutexes] -- the
     * floor only applies where [AnchorPolicy.refreshIntervalSeconds] is non-zero, so BTC's fan-out
     * frequency is completely unaffected by this map or by the S9 tightening.
     */
    private val lastFanoutAt = ConcurrentHashMap<CacheKey, Instant>()

    /** How many sources this deployment has wired for [anchor] -- used by `PriceOracleService.validateConfigInput` to reject an anchor switch that would produce a permanently-halted oracle, and by `PriceOracleStartupCheck`. */
    fun configuredSourceCount(anchor: AnchorAsset): Int = sourcesByAnchor[anchor].orEmpty().size

    /** Stable source ids wired for [anchor], for the startup log and the config-rejection message. Never includes any API key material. */
    fun configuredSourceIds(anchor: AnchorAsset): List<String> = sourcesByAnchor[anchor].orEmpty().map { it.id }

    /**
     * Clears all replay ([lastAttempts]) and cache ([cache]) state, for every [CacheKey] -- called by
     * `PriceOracleService.updateOracleConfig` once a config change is durably persisted (Review Round
     * 2 / NEW-1, second facet). Without this, an ADMIN tightening `outlierThresholdBps` or lowering
     * `cacheTtlSeconds` in direct response to a bad quote would see no effect for up to a full
     * `refreshIntervalSeconds` -- [LastAttempt] is only ever invalidated by its own age, not by the
     * config fields that determined the outcome it holds. Clears the whole map rather than just the
     * (possibly changed) key, since an anchor/currency switch means the OLD key's entries are equally
     * stale and irrelevant afterwards; the key space is bounded, so this is cheap. [cache] is cleared
     * too, for symmetry -- clearing only [lastAttempts] could let the very next fan-out's
     * quorum-failure path fall back to a [CachedQuote] that was recorded under the now-superseded
     * config (e.g. a price accepted under a looser `outlierThresholdBps` before the tightening this
     * call exists to enforce).
     *
     * **Deliberately does NOT clear [lastFanoutAt]** (Security-Audit-Runde 1 / S1) -- see that map's
     * own KDoc. `PriceOracleService.updateOracleConfig` also no longer calls this unconditionally on
     * every save (Security-Audit-Runde 1 / S1, first half of the fix): it only invalidates when a
     * field that actually influences a quote outcome genuinely changed, so a no-op re-save costs
     * nothing. Both halves are complementary -- the compare-before-invalidate check in
     * `updateOracleConfig` protects the ordinary "save an unrelated field, then preview" workflow;
     * [lastFanoutAt] protects against a rapid sequence of GENUINE config changes each forcing a fresh
     * invalidation.
     */
    fun invalidateReplayState() {
        lastAttempts.clear()
        cache.clear()
    }

    suspend fun currentQuote(config: PriceOracleConfigDto): QuoteOutcome {
        val currency = config.donationCurrency.uppercase()
        val key = CacheKey(anchor = config.anchorAsset, donationCurrency = currency)

        // Refresh-interval short-circuit + single-flight (V0.6.6, D5 in the plan doc; gating moved
        // from CachedQuote to LastAttempt in Review Round 1 / MAJOR-1, see that class's KDoc) -- the
        // free-tier guard for key-gated gold/fiat sources. Skipped entirely for BITCOIN_BTC
        // (refreshIntervalSeconds == 0), which is the property that makes the BTC path provably
        // unchanged by this wave -- BTC never takes the mutex and never touches lastAttempts.
        val refreshInterval = AnchorPolicy.refreshIntervalSeconds(config.anchorAsset)
        if (refreshInterval > 0) {
            return keyMutexes.computeIfAbsent(key) { Mutex() }.withLock {
                val last = lastAttempts[key]
                if (last != null && clock.now() - last.at <= refreshInterval.seconds) {
                    // Replay the last real attempt's outcome verbatim -- success, quorum-halt,
                    // spread-halt, or a cache-fallback Ok, whatever it was. cacheTtlSeconds is
                    // guaranteed >= refreshIntervalSeconds by validateConfigInput, so a LIVE/DEGRADED
                    // outcome this fresh (by attempt time) is never considered stale by that check
                    // either -- ITS OWN priceTimestamp IS the attempt time.
                    //
                    // A CACHED outcome is different (Review Round 2 / NEW-1): its priceTimestamp can
                    // already have been up to cacheTtlSeconds old AT THE MOMENT the failed attempt
                    // that produced it was recorded, so replaying it here without re-checking would
                    // let a price up to cacheTtlSeconds + refreshIntervalSeconds old slip through.
                    // Re-check its own TTL before replaying; if it has since expired, fall through to
                    // a real fan-out below instead of replaying (or silently halting) -- the whole
                    // point is to give recovery a genuine chance once the fallback itself has expired.
                    val outcome = last.outcome
                    val cachedReplayStillWithinOwnTtl =
                        outcome !is QuoteOutcome.Ok ||
                            outcome.quote.status != PriceStatus.CACHED ||
                            clock.now() - outcome.quote.priceTimestamp <= config.cacheTtlSeconds.seconds
                    if (cachedReplayStillWithinOwnTtl) {
                        return@withLock outcome
                    }
                }

                // Hard floor (Security-Audit-Runde 1 / S1, tightened by Security-Audit-Runde 2 / S9)
                // -- see [lastFanoutAt] / [HARD_FLOOR_FANOUT_DIVISOR] KDoc. Checked AFTER the
                // lastAttempts replay above (a normal replay never needs this at all) but BEFORE the
                // real fan-out below, and keyed off [lastFanoutAt], which -- unlike [lastAttempts] --
                // `invalidateReplayState()` can never reset. This is what makes the floor a genuine
                // backstop against a rapid `updateOracleConfig` save-loop: even if every single save
                // clears [lastAttempts]/[cache] (because each one is a genuine, quote-affecting
                // change), a real network fan-out still cannot happen more than once per
                // `hardFloorSeconds` for this key. Derived from THIS anchor's own
                // `refreshIntervalSeconds` rather than a flat constant (S9) -- see
                // [HARD_FLOOR_FANOUT_DIVISOR] KDoc for the exact numbers this yields for GOLD_XAU/FIAT.
                // When the floor is active, this falls back to [cacheFallbackOrHalt] exactly like a
                // real fan-out that failed to reach quorum would -- no network call is made, so it
                // costs nothing against the source quotas, but it also does not fabricate a fake
                // LIVE/DEGRADED result.
                val hardFloorSeconds = refreshInterval / HARD_FLOOR_FANOUT_DIVISOR
                val lastFanout = lastFanoutAt[key]
                if (lastFanout != null && clock.now() - lastFanout < hardFloorSeconds.seconds) {
                    return@withLock cacheFallbackOrHalt(
                        config = config,
                        key = key,
                        reason =
                            "Price-Oracle fan-out rate floor active for ${config.anchorAsset} " +
                                "(last real fan-out ${clock.now() - lastFanout} ago, floor is " +
                                "${hardFloorSeconds}s) -- serving cache instead of " +
                                "re-querying sources so soon after the previous attempt",
                    )
                }

                val outcome = fetchAndEvaluate(config = config, key = key, currency = currency)
                val now = clock.now()
                lastFanoutAt[key] = now
                lastAttempts[key] = LastAttempt(at = now, outcome = outcome)
                outcome
            }
        }

        return fetchAndEvaluate(config = config, key = key, currency = currency)
    }

    /** The actual network fan-out + median/outlier/spread/cache-fallback algorithm (steps 1-7 of the class KDoc) -- factored out of [currentQuote] so the refresh-interval gate above can wrap it uniformly for both the "first attempt for this key" and "gate expired" cases. */
    private suspend fun fetchAndEvaluate(
        config: PriceOracleConfigDto,
        key: CacheKey,
        currency: String,
    ): QuoteOutcome {
        val anchorSources = sourcesByAnchor[config.anchorAsset].orEmpty()
        // The quorum-floor clamp -- see class KDoc "single most important safety invariant".
        val effectiveQuorum = maxOf(config.minQuorum, AnchorPolicy.quorumFloor(config.anchorAsset))

        val responded =
            coroutineScope {
                anchorSources
                    .map { source -> async { runCatching { source.fetchPrice(currency) }.getOrNull() } }
                    .awaitAll()
                    .filterNotNull()
            }

        val band = plausibilityBand(config.anchorAsset)
        val plausible = responded.filter { it.price in band }
        responded.filterNot { it.price in band }.forEach { dropped ->
            logger.warn {
                "Oracle source '${dropped.sourceId}' returned an implausible ${config.anchorAsset} price " +
                    "${dropped.price} -- dropped (plausibility band $band)"
            }
        }

        if (plausible.size < effectiveQuorum) {
            return cacheFallbackOrHalt(
                config = config,
                key = key,
                reason =
                    "Only ${plausible.size}/${anchorSources.size} configured ${config.anchorAsset} sources responded " +
                        "plausibly, below minQuorum $effectiveQuorum",
            )
        }

        val provisionalMedian = median(plausible.map { it.price })
        val survivors =
            plausible.filter {
                deviationBps(price = it.price, median = provisionalMedian) <=
                    config.outlierThresholdBps.toBigDecimal()
            }

        if (survivors.size < effectiveQuorum) {
            return cacheFallbackOrHalt(
                config = config,
                key = key,
                reason =
                    "Only ${survivors.size}/${anchorSources.size} ${config.anchorAsset} sources survived outlier " +
                        "rejection (threshold ${config.outlierThresholdBps}bps), below minQuorum $effectiveQuorum",
            )
        }

        val survivorPrices = survivors.map { it.price }
        val finalMedian = median(survivorPrices)
        val spreadBps = spreadBps(prices = survivorPrices, median = finalMedian)
        if (spreadBps > config.maxSpreadBps.toBigDecimal()) {
            return cacheFallbackOrHalt(
                config = config,
                key = key,
                reason = "Survivor price spread ${spreadBps}bps exceeds maxSpreadBps ${config.maxSpreadBps}",
            )
        }

        val status = if (survivors.size == anchorSources.size) PriceStatus.LIVE else PriceStatus.DEGRADED
        val now = clock.now()
        val sourceIds = survivors.map { it.sourceId }
        cache[key] = CachedQuote(price = finalMedian, sourceIds = sourceIds, timestamp = now, status = status)
        return QuoteOutcome.Ok(
            PriceQuote(status = status, medianPrice = finalMedian, contributingSourceIds = sourceIds, priceTimestamp = now),
        )
    }

    private fun cacheFallbackOrHalt(
        config: PriceOracleConfigDto,
        key: CacheKey,
        reason: String,
    ): QuoteOutcome {
        val cached = cache[key]
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
