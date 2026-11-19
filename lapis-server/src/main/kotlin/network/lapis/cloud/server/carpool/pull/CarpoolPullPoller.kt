package network.lapis.cloud.server.carpool.pull

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
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolOwnKeys
import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.carpool.CarpoolReceivedStore
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * V1.9.314 "Mitfahrzentrale-Foederation" W4 -- pulls the carpool teaser feed of every direct partner that shares with this server. Application-scoped,
 * the shape of `PartnerFeedPoller`: one coroutine (`SupervisorJob() + Dispatchers.IO`), `start()` idempotent, [tick] exception-safe over the whole tick,
 * no in-memory state (every tick re-queries its candidates). Started ONLY in `Application.kt`; never from a request path.
 *
 * The request is made OUTSIDE every transaction ([CarpoolTeaserFeedClient]); the answer is parsed ([CarpoolTeaserFeedReader], pure) and applied in ONE short
 * transaction ([CarpoolReceivedStore.apply]). The interval is far below the origin's 12 requests per hour per partner: success = 30 minutes plus up to 10
 * minutes of jitter; a failure backs off from 15 minutes, doubling, to 24 hours (a `429` waits at least an hour). A tick with the instance switch off only
 * does housekeeping (stale copies), no request.
 */
internal class CarpoolPullPoller(
    private val client: CarpoolTeaserFeedClient = CarpoolTeaserFeedClient(),
    private val tickInterval: Duration = 60.seconds,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
    private val random: Random = Random.Default,
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

        /** 15 min, 30 min, 1 h ... capped at 24 h; a rate limit waits at least an hour. [jitter] is added on top. */
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
        val enabled: Boolean,
        val key: CarpoolOwnSigningKey?,
        val due: List<PartnerRow>,
    )

    @Suppress("TooGenericExceptionCaught")
    suspend fun tick() {
        try {
            val now = clock()
            runCatching { transaction { CarpoolReceivedStore.dropStale(now) } }
                .onFailure { logger.warn { "CarpoolPullPoller: stale sweep failed (${it::class.simpleName})" } }
            val state =
                transaction {
                    val enabled = CarpoolFederationStore.settings().enabled
                    TickState(
                        enabled = enabled,
                        key = if (enabled) CarpoolOwnKeys.load() else null,
                        due = if (enabled) PartnerServerStore.dueForCarpoolPull(now = now, limit = DUE_LIMIT) else emptyList(),
                    )
                }
            if (!state.enabled || state.due.isEmpty()) return
            val ownHost = PartnerHost.ownHostOf(FederationConfig.publicBaseUrl)
            if (state.key == null || ownHost == null) {
                val error = if (state.key == null) CarpoolPullErrorCode.NO_OWN_KEY else CarpoolPullErrorCode.INTERNAL
                transaction {
                    state.due.forEach {
                        PartnerServerStore.recordCarpoolPullFailure(
                            id = it.id,
                            error = error.name,
                            next = plus(now = now, delay = 1.hours),
                            now = now,
                        )
                    }
                }
                return
            }
            val permits = Semaphore(MAX_PARALLEL)
            coroutineScope {
                state.due
                    .map { row ->
                        async(Dispatchers.IO) {
                            permits.withPermit { pullOneSafely(row = row, key = state.key, ownHost = ownHost, now = now) }
                        }
                    }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "CarpoolPullPoller: tick failed (${e::class.simpleName})" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pullOneSafely(
        row: PartnerRow,
        key: CarpoolOwnSigningKey,
        ownHost: String,
        now: LocalDateTime,
    ) {
        try {
            when (val outcome = client.fetch(host = row.host, etag = row.carpoolPullEtag, key = key)) {
                is CarpoolPullOutcome.Failed -> fail(row = row, error = outcome.error, now = now)
                is CarpoolPullOutcome.NotModified ->
                    transaction {
                        // A restore brings the ETag back but not the copies (they are not backed up): "304" would then confirm an empty cache for good.
                        val copiesLost = (row.carpoolPullLastAccepted ?: 0) > 0 && (CarpoolReceivedStore.countBySource()[row.id] ?: 0) == 0
                        if (copiesLost) PartnerServerStore.clearCarpoolPullEtag(row.id)
                        PartnerServerStore.recordCarpoolPullSuccess(
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
                is CarpoolPullOutcome.Fetched -> {
                    val patterns = transaction { PartnerServerStore.blockPatterns() }
                    when (
                        val parsed =
                            CarpoolTeaserFeedReader.parse(
                                bytes = outcome.body,
                                sourceHost = row.host,
                                ownHost = ownHost,
                                today = OrganizationTimeZone.today(),
                                blockPatterns = patterns,
                            )
                    ) {
                        is TeaserFeedParse.Invalid -> fail(row = row, error = parsed.code, now = now)
                        is TeaserFeedParse.Ok ->
                            transaction {
                                CarpoolReceivedStore.apply(
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "CarpoolPullPoller: host=${row.host} failed unexpectedly (${e::class.simpleName})" }
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
        val next = plus(now = now, delay = failureDelay(failures = row.carpoolPullFailures + 1, error = error, jitter = jitter()))
        transaction { PartnerServerStore.recordCarpoolPullFailure(id = row.id, error = error.name, next = next, now = now) }
    }

    private fun plus(
        now: LocalDateTime,
        delay: Duration,
    ): LocalDateTime = (now.toInstant(TimeZone.UTC) + delay).toLocalDateTime(TimeZone.UTC)
}
