package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.server.rpc.PRICE_ORACLE_CONFIG_ID
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Welle "Price-Oracle-Preishistorie". Application-scoped poller that persists ONE
 * [PriceOracleSnapshotStore] row per [AnchorAsset] on every tick, for EVERY anchor -- not only the
 * currently ACTIVE one (that is the one deliberate difference from
 * [PriceOracleOrchestrator.currentQuote]'s own "only the active anchor" behaviour, see [tick] KDoc).
 * Loosely mirrors `network.lapis.cloud.server.payment.sepa.SepaBatchPoller`: ONE coroutine
 * (`SupervisorJob() + Dispatchers.IO`), [tick] public and exception-safe at TWO levels (whole tick
 * + each anchor individually), [start]/[stop] idempotent, NO in-memory state of its own (every
 * tick re-reads `price_oracle_config` fresh). **Unlike** `SepaBatchPoller`, the loop `delay`s
 * BEFORE it `tick()`s (`while (isActive) { delay(interval); tick() }`), not after -- see [start]
 * KDoc "Sofort-Tick beim Start" for why an immediate first tick is unsafe here specifically (a
 * process restart must never itself cost real API-quota against the gold/fiat sources' free-tier
 * budgets).
 *
 * **[orchestrator] MUST be the application's singleton instance** (the same one
 * `Application.module` passes into every `PriceOracleService`), never a poller-owned one -- a
 * second orchestrator would have its own cache, its own `lastAttempts`/`lastFanoutAt` maps and its
 * own pooled HTTP client, DOUBLING the real network fan-out frequency against the gold/fiat
 * sources' free-tier quotas and completely bypassing the floors [PriceOracleOrchestrator] exists to
 * enforce. This is the single most important security invariant of this class -- see
 * `Application.kt`'s wiring comment for the concrete "reuse the existing val" instruction.
 *
 * **Operational invariant (Security-Audit finding "Kontingent-Erschoepfung durch
 * Multi-Instanz-Betrieb")**: [PriceOracleOrchestrator]'s `lastAttempts`/`lastFanoutAt` throttles are
 * PROCESS-LOCAL, not DB-coordinated -- they protect a single JVM's fan-out rate, never the
 * aggregate rate across multiple instances sharing the same organization API key. `LAPIS_ORACLE_
 * SNAPSHOT_ENABLED` must therefore be set on **at most one** running instance, exactly like
 * `LAPIS_SEPA_POLLER_ENABLED` -- see `deploy/example/README.adoc`'s "Price-Oracle Snapshot
 * Poller" section.
 */
class PriceOracleSnapshotPoller(
    private val orchestrator: PriceOracleOrchestrator,
    private val config: PriceOracleSnapshotConfig,
    private val clock: () -> kotlinx.datetime.LocalDateTime = { DbClock.nowLocalDateTime() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /**
     * Idempotent -- a second call while already running is a no-op.
     *
     * **Security-Audit finding "Sofort-Tick beim Start"**: the loop `delay`s FIRST, then [tick]s --
     * deliberately NOT the more obvious `tick(); delay(...)` order. [PriceOracleOrchestrator]'s
     * `lastAttempts`/`lastFanoutAt` maps (this poller's ONLY real-fan-out throttle for the
     * free-tier gold/fiat sources, see `GoldPriceSources.kt`'s "Free-tier request budget" KDoc) are
     * pure in-memory and start EMPTY on every process boot -- they do not survive a restart. A
     * `tick()`-first loop therefore turns every container start/crash-restart into a guaranteed
     * real network fan-out across every sufficiently-provisioned anchor, with NO throttle
     * protecting it: a crash-restart loop (e.g. a misconfigured health check restarting the
     * container every 30s) would exhaust GoldAPI.io's/MetalpriceAPI's ~100-request/month free tier
     * within roughly a hundred restarts and blow past Alpha Vantage's 5-requests/minute cap within
     * a handful of restarts in the same minute -- the exact "organization's API key rate-limited or
     * banned" risk that KDoc warns about. Delaying first means a process that never survives a full
     * `intervalSeconds` window costs ZERO API-quota, matching [PriceOracleSnapshotConfig
     * .intervalSeconds]'s own KDoc ("floored at 300s -- a misconfigured near-zero interval must
     * never busy-spin the loop"): the floor now also bounds the worst-case restart-storm fan-out
     * rate, not only the steady-state one. The one-off cost is that a freshly enabled poller's
     * first snapshot lands one `intervalSeconds` later instead of immediately -- an acceptable
     * trade for a background history poller with no caller waiting on its first row.
     */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    delay(config.intervalSeconds.seconds)
                    tick()
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * One poll pass over every [AnchorAsset]. Exception-safe at two levels (whole tick + each
     * anchor individually) so one broken anchor never stops the others, and tests can call this
     * directly with zero timing dependency -- same contract as `SepaBatchPoller.tick`.
     *
     * For each anchor: skipped entirely (no fan-out, `logger.debug` not `warn` -- see class KDoc
     * "no warn-log-noise") when this deployment has fewer configured sources than
     * [AnchorPolicy.quorumFloor] for that anchor (e.g. GOLD_XAU with no `LAPIS_ORACLE_*` keys
     * configured). Otherwise, a config DERIVED from the persisted `price_oracle_config` row is
     * built -- overriding [PriceOracleConfigDto.anchorAsset] and REPLACING
     * [PriceOracleConfigDto.minQuorum] outright with this anchor's own [AnchorPolicy.quorumFloor]
     * (never `baseConfig.minQuorum`, which was tuned for a DIFFERENT anchor's source count -- see
     * [deriveConfig] KDoc for why this is a replace, not a clamp), while
     * [PriceOracleConfigDto.cacheTtlSeconds] IS clamped UP to this anchor's own recommended value,
     * never down. `outlierThresholdBps`/`maxSpreadBps`/`donationCurrency` are passed through
     * UNCHANGED from the persisted policy -- the poller never loosens THOSE thresholds the ADMIN
     * configured. See [deriveConfig] KDoc for the exact rule.
     */
    suspend fun tick() {
        try {
            val baseConfig = loadBaseConfig() ?: return
            for (anchor in AnchorAsset.entries) {
                try {
                    tickOneAnchor(anchor = anchor, baseConfig = baseConfig)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cooperative cancellation (stop()/loopJob.cancel(), or testApplication teardown)
                    // must propagate immediately, not be logged as an anchor failure and swallowed --
                    // see Review Round finding "inner catch(Throwable) swallows CancellationException".
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "PriceOracleSnapshotPoller: tick failed for anchor $anchor" }
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "PriceOracleSnapshotPoller: tick failed" }
        }
    }

    private suspend fun tickOneAnchor(
        anchor: AnchorAsset,
        baseConfig: PriceOracleConfigDto,
    ) {
        val floor = AnchorPolicy.quorumFloor(anchor)
        if (orchestrator.configuredSourceCount(anchor) < floor) {
            logger.debug { "PriceOracleSnapshotPoller: skipping $anchor -- fewer than $floor configured source(s) on this deployment" }
            return
        }
        val derived = deriveConfig(anchor = anchor, baseConfig = baseConfig)
        when (val outcome = orchestrator.currentQuote(derived)) {
            is QuoteOutcome.Halt ->
                logger.warn { "PriceOracleSnapshotPoller: $anchor quote halted, no snapshot written -- ${outcome.reason}" }
            is QuoteOutcome.Ok -> {
                val priceTimestampLocal =
                    outcome.quote.priceTimestamp
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .truncatedToDbPrecision()
                val wrote =
                    PriceOracleSnapshotStore.recordIfAbsent(
                        anchorAsset = anchor,
                        donationCurrency = derived.donationCurrency,
                        quote = outcome.quote,
                        priceTimestamp = priceTimestampLocal,
                        capturedAt = clock(),
                    )
                if (wrote) {
                    logger.debug { "PriceOracleSnapshotPoller: wrote $anchor snapshot (status=${outcome.quote.status})" }
                }
            }
        }
    }

    private fun loadBaseConfig(): PriceOracleConfigDto? =
        transaction {
            PriceOracleConfigTable
                .selectAll()
                .where { PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }
                .singleOrNull()
                ?.let { row ->
                    PriceOracleConfigDto(
                        id = row[PriceOracleConfigTable.id].toString(),
                        anchorAsset = row[PriceOracleConfigTable.anchorAsset],
                        donationCurrency = row[PriceOracleConfigTable.donationCurrency],
                        anchorUnitsPerLtr = row[PriceOracleConfigTable.anchorUnitsPerLtr],
                        cacheTtlSeconds = row[PriceOracleConfigTable.cacheTtlSeconds],
                        minQuorum = row[PriceOracleConfigTable.minQuorum],
                        outlierThresholdBps = row[PriceOracleConfigTable.outlierThresholdBps],
                        maxSpreadBps = row[PriceOracleConfigTable.maxSpreadBps],
                        updatedAt = row[PriceOracleConfigTable.updatedAt],
                    )
                }
        }
}

/**
 * Derives the per-[anchor] [PriceOracleConfigDto] the poller passes into
 * [PriceOracleOrchestrator.currentQuote] for a NON-active anchor -- see [PriceOracleSnapshotPoller
 * .tick] KDoc. Extracted as its own top-level, directly testable function (not a private method)
 * so a test can assert the "never loosens a threshold" invariant without going through a full
 * [PriceOracleSnapshotPoller.tick] call.
 *
 * **Safety invariant**: for a NON-active anchor, [PriceOracleConfigDto.minQuorum] is this anchor's
 * OWN [AnchorPolicy.quorumFloor] (never `baseConfig.minQuorum`, which was tuned for a DIFFERENT
 * anchor's source count and could easily exceed how many sources this anchor even has -- e.g. an
 * ADMIN-tuned `minQuorum=3` for BTC would permanently halt a FIAT derivation that only ever has
 * ONE configured source). [PriceOracleConfigDto.cacheTtlSeconds] is raised (never lowered) to at
 * least [AnchorPolicy.recommendedCacheTtlSeconds] for that anchor. `outlierThresholdBps`/
 * `maxSpreadBps`/`donationCurrency`/`anchorUnitsPerLtr` are carried over UNCHANGED -- the poller
 * never loosens a threshold the ADMIN configured. For the anchor that already IS
 * `baseConfig.anchorAsset`, this returns [baseConfig] verbatim (no derivation needed, and
 * `PriceOracleOrchestrator.currentQuote` clamps `minQuorum` up to the floor itself anyway).
 */
internal fun deriveConfig(
    anchor: AnchorAsset,
    baseConfig: PriceOracleConfigDto,
): PriceOracleConfigDto {
    if (anchor == baseConfig.anchorAsset) return baseConfig
    return baseConfig.copy(
        anchorAsset = anchor,
        minQuorum = AnchorPolicy.quorumFloor(anchor),
        cacheTtlSeconds = maxOf(baseConfig.cacheTtlSeconds, AnchorPolicy.recommendedCacheTtlSeconds(anchor)),
    )
}
