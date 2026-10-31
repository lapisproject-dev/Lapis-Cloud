package network.lapis.cloud.server.partner

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
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * V1.9.244 -- the hourly refresh of the partner caches. Application-scoped, exactly the shape of `WebhookDeliveryPoller`: one coroutine
 * (`SupervisorJob() + Dispatchers.IO`), `start()` idempotent, [tick] public and exception-safe over the WHOLE tick, no in-memory state (every
 * tick re-queries its candidates).
 *
 * A tick purges expired cache rows, takes at most [PartnerServerStore.DUE_LIMIT] due partners (blocked hosts are filtered out BEFORE any DNS or
 * network access), fetches at most [MAX_PARALLEL] of them at once, and applies each result in its own short transaction. It contacts ONLY hosts
 * of `partner_server` -- the partners' own partner lists are stored, never followed (no recursion in P1). Operating assumption (as for the other
 * pollers): exactly one server instance runs it.
 *
 * Never started from a request path; the one fetch that a request causes is `PartnerServerService.probeAndSave`.
 */
internal class PartnerFeedPoller(
    private val fetcher: PartnerFeedFetcher = PartnerFeedFetcher(),
    private val tickInterval: Duration = 60.seconds,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
) {
    companion object {
        const val MAX_PARALLEL = 4
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

    @Suppress("TooGenericExceptionCaught")
    suspend fun tick() {
        try {
            val now = clock()
            transaction { PartnerServerStore.purgeExpired(now) }
            val due = transaction { PartnerServerStore.dueForFetch(now = now) }
            if (due.isEmpty()) return
            val ownServerId = transaction { PartnerServerStore.ensureServerId() }
            val instant = ServerClock.systemToInstant(now)
            val permits = Semaphore(MAX_PARALLEL)
            coroutineScope {
                due
                    .map { row ->
                        async(Dispatchers.IO) {
                            permits.withPermit {
                                fetchOneSafely(row = row, ownServerId = ownServerId, instant = instant, now = now)
                            }
                        }
                    }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "PartnerFeedPoller: tick failed" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchOneSafely(
        row: PartnerRow,
        ownServerId: kotlin.uuid.Uuid,
        instant: kotlin.time.Instant,
        now: LocalDateTime,
    ) {
        try {
            val outcome =
                fetcher.fetch(
                    host = row.host,
                    etag = row.etag,
                    ownServerId = ownServerId,
                    knownRemoteId = row.remoteServerId,
                    now = instant,
                )
            transaction {
                when (outcome) {
                    is PartnerFetchOutcome.Fetched ->
                        PartnerServerStore.applyFetchSuccess(id = row.id, parsed = outcome.parsed, etag = outcome.etag, now = now)
                    is PartnerFetchOutcome.NotModified -> PartnerServerStore.applyNotModified(id = row.id, now = now)
                    is PartnerFetchOutcome.Failed -> PartnerServerStore.applyFetchFailure(id = row.id, error = outcome.error, now = now)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "PartnerFeedPoller: host=${row.host} failed unexpectedly (${e.javaClass.simpleName})" }
        }
    }
}
