package network.lapis.cloud.server.auction.federation.pull

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.auction.federation.AuctionFederationKey
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionPullCounters
import network.lapis.cloud.server.auction.federation.AuctionReceivedStore
import network.lapis.cloud.server.auction.federation.AuctionWithdrawals
import network.lapis.cloud.server.carpool.CarpoolOwnKeys
import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * V1.9.322 (step c3) -- pulls the auction teaser feed of every direct partner that shares with this server. A deliberate COPY of `CarpoolPullPoller`
 * (nothing shared was extracted in c2): application-scoped, one coroutine (`SupervisorJob() + Dispatchers.IO`), `start()` idempotent, [tick]
 * exception-safe over the whole tick, no in-memory state. Started ONLY in `Application.kt` (pinned by `AuctionFederationTripwireTest`); never from a
 * request path.
 *
 * The request is made OUTSIDE every transaction ([AuctionTeaserFeedClient]); the answer is parsed ([AuctionTeaserFeedReader], pure) and applied in ONE
 * short transaction ([AuctionReceivedStore.apply]). Interval: success = 30 minutes plus up to 10 minutes of jitter (the origin allows 12 requests per
 * hour per partner); a failure backs off from 15 minutes, doubling, to 24 hours (a `429` waits at least an hour).
 *
 * Housekeeping runs in EVERY tick whatever the switches say (an expired hint must not outlive its end because the exchange is off): expired copies and
 * blocks, expired tombstones (our own too -- nothing deleted them before c3), stale sources, and the consent sweep of the origin side. A pull happens
 * only while the instance switch AND the auction module are on.
 */
internal class AuctionPullPoller(
    private val client: AuctionTeaserFeedClient = AuctionTeaserFeedClient(),
    private val tickInterval: Duration = 60.seconds,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
    private val random: Random = Random.Default,
    /** Only a test lowers it (to 1: strictly one pull at a time makes the per-tick budget deterministic). */
    private val maxParallel: Int = MAX_PARALLEL,
) {
    companion object {
        const val MAX_PARALLEL = 2
        const val DUE_LIMIT = 20
        val INTERVAL = 30.minutes
        val JITTER = 10.minutes
        val FAILURE_BASE = 15.minutes
        val FAILURE_MAX = 24.hours
        val RATE_LIMITED_MIN = 1.hours
        private const val MAX_BACKOFF_EXPONENT = 7

        /** Items + withdrawals that ONE tick may take over, summed over all partners; a pull that would start after it is spent waits. */
        const val TICK_ITEM_BUDGET = 5000

        /** Expired rows deleted per tick and table. */
        const val EXPIRED_SWEEP_LIMIT = 2000

        /** The consent sweep of the origin side, per tick. */
        private const val CONSENT_SWEEP_LIMIT = 50

        /** 15 min, 30 min, 1 h ... capped at 24 h; a rate limit waits at least an hour. [jitter] is added on top. From 8 failures on the interval sits at the cap ("paused"). */
        fun failureDelay(
            failures: Int,
            error: CarpoolPullErrorCode,
            jitter: Duration,
        ): Duration {
            val exponent = (failures - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
            var delay = minOf(FAILURE_BASE * (1 shl exponent), FAILURE_MAX)
            if (error == CarpoolPullErrorCode.RATE_LIMITED && delay < RATE_LIMITED_MIN) delay = RATE_LIMITED_MIN
            return delay + jitter
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(tickInterval)
                }
            }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private class TickState(
        val active: Boolean,
        val key: CarpoolOwnSigningKey?,
        val due: List<PartnerRow>,
        val hopLimit: Int,
    )

    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod")
    suspend fun tick() {
        try {
            val now = clock()
            runCatching { transaction { AuctionReceivedStore.deleteExpired(now = now, limit = EXPIRED_SWEEP_LIMIT) } }
                .onSuccess { if (it > 0) AuctionPullCounters.expiredDeleted.addAndGet(it.toLong()) }
                .onFailure { logger.warn { "AuctionPullPoller: expired sweep failed (${it::class.simpleName})" } }
            runCatching { transaction { AuctionFederationStore.deleteExpiredTombstones(now = now, limit = EXPIRED_SWEEP_LIMIT) } }
                .onFailure { logger.warn { "AuctionPullPoller: tombstone sweep failed (${it::class.simpleName})" } }
            runCatching { transaction { AuctionFederationStore.deleteStaleSnapshots(now = now, limit = EXPIRED_SWEEP_LIMIT) } }
                .onFailure { logger.warn { "AuctionPullPoller: snapshot sweep failed (${it::class.simpleName})" } }
            runCatching { transaction { AuctionReceivedStore.dropStale(now) } }
                .onFailure { logger.warn { "AuctionPullPoller: stale sweep failed (${it::class.simpleName})" } }
            runCatching {
                transaction {
                    AuctionWithdrawals.sweep(
                        now = now,
                        ids = AuctionFederationKey.current(),
                        limit = CONSENT_SWEEP_LIMIT,
                    )
                }
            }.onFailure { logger.warn { "AuctionPullPoller: consent sweep failed (${it::class.simpleName})" } }
            val tickState =
                transaction {
                    val settings = AuctionFederationStore.settings()
                    val active = settings.enabled && AuctionFederationStore.auctionModuleOn()
                    TickState(
                        active = active,
                        key = if (active) CarpoolOwnKeys.load() else null,
                        due = if (active) PartnerServerStore.dueForAuctionPull(now = now, limit = DUE_LIMIT) else emptyList(),
                        hopLimit = settings.hopLimit,
                    )
                }
            if (!tickState.active || tickState.due.isEmpty()) return
            val ownHost = PartnerHost.ownHostOf(FederationConfig.publicBaseUrl)
            if (tickState.key == null || ownHost == null) {
                val error = if (tickState.key == null) CarpoolPullErrorCode.NO_OWN_KEY else CarpoolPullErrorCode.INTERNAL
                transaction {
                    tickState.due.forEach {
                        PartnerServerStore.recordAuctionPullFailure(
                            id = it.id,
                            error = error.name,
                            next = plus(now = now, delay = 1.hours),
                            now = now,
                        )
                    }
                }
                return
            }
            val permits = Semaphore(maxParallel)
            val budget = AtomicInteger(TICK_ITEM_BUDGET)
            coroutineScope {
                tickState.due
                    .map { row ->
                        async(Dispatchers.IO) {
                            permits.withPermit {
                                pullOneSafely(
                                    row = row,
                                    key = tickState.key,
                                    ownHost = ownHost,
                                    ownHopLimit = tickState.hopLimit,
                                    now = now,
                                    budget = budget,
                                )
                            }
                        }
                    }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "AuctionPullPoller: tick failed (${e::class.simpleName})" }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun pullOneSafely(
        row: PartnerRow,
        key: CarpoolOwnSigningKey,
        ownHost: String,
        ownHopLimit: Int,
        now: LocalDateTime,
        budget: AtomicInteger,
    ) {
        try {
            if (budget.get() <= 0) {
                // Spent: nothing is written (no failure, `next` stays the oldest), so this partner is first in line at the next tick.
                AuctionPullCounters.budgetDeferred.incrementAndGet()
                return
            }
            when (val outcome = client.fetch(host = row.host, etag = row.auctionPullEtag, key = key)) {
                is AuctionPullOutcome.Failed -> fail(row = row, error = outcome.error, now = now)
                is AuctionPullOutcome.NotModified ->
                    transaction {
                        // A restore brings the ETag back but not the copies (they are not backed up): "304" would then confirm an empty cache for good.
                        val copiesLost = (row.auctionPullLastAccepted ?: 0) > 0 && (AuctionReceivedStore.countBySource()[row.id] ?: 0) == 0
                        if (copiesLost) PartnerServerStore.clearAuctionPullEtag(row.id)
                        PartnerServerStore.recordAuctionPullSuccess(
                            id = row.id,
                            status = "NOT_MODIFIED",
                            accepted = null,
                            rejected = null,
                            etag = null,
                            keepEtag = true,
                            next = if (copiesLost) plus(now = now, delay = 1.minutes) else nextAfterSuccess(now),
                            now = now,
                        )
                    }
                is AuctionPullOutcome.Fetched -> {
                    val patterns = transaction { PartnerServerStore.blockPatterns() }
                    when (
                        val parsed =
                            AuctionTeaserFeedReader.parse(
                                bytes = outcome.body,
                                sourceHost = row.host,
                                ownHost = ownHost,
                                ownHopLimit = ownHopLimit,
                                now = now.toInstant(TimeZone.UTC),
                                blockPatterns = patterns,
                            )
                    ) {
                        is AuctionFeedParse.Invalid -> fail(row = row, error = parsed.code, now = now)
                        is AuctionFeedParse.Ok -> {
                            budget.addAndGet(-(parsed.feed.items.size + parsed.feed.withdrawnIds.size))
                            transaction {
                                AuctionReceivedStore.apply(
                                    source = row,
                                    feed = parsed.feed,
                                    etag = outcome.etag,
                                    next = nextAfterSuccess(now),
                                    now = now,
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "AuctionPullPoller: host=${row.host} failed unexpectedly (${e::class.simpleName})" }
            runCatching { fail(row = row, error = CarpoolPullErrorCode.INTERNAL, now = now) }
        }
    }

    private fun nextAfterSuccess(now: LocalDateTime): LocalDateTime = plus(now = now, delay = INTERVAL + jitter())

    private fun jitter(): Duration = random.nextLong(0, JITTER.inWholeSeconds + 1).seconds

    private fun fail(
        row: PartnerRow,
        error: CarpoolPullErrorCode,
        now: LocalDateTime,
    ) {
        val next = plus(now = now, delay = failureDelay(failures = row.auctionPullFailures + 1, error = error, jitter = jitter()))
        transaction { PartnerServerStore.recordAuctionPullFailure(id = row.id, error = error.name, next = next, now = now) }
    }

    private fun plus(
        now: LocalDateTime,
        delay: Duration,
    ): LocalDateTime = (now.toInstant(TimeZone.UTC) + delay).toLocalDateTime(TimeZone.UTC)
}
