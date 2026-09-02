package network.lapis.cloud.server.webhook

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import network.lapis.cloud.shared.domain.WebhookFailureReason
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** How long a row may sit `DELIVERING` before [WebhookDeliveryPoller] treats it as an orphaned, crash-abandoned claim -- see that class's own KDoc "Phase A0". */
private const val STALE_CLAIM_MINUTES = 5L

/** Retention-sweep deletions per tick -- see [WebhookDeliveryPoller] KDoc "Phase C". */
private const val RETENTION_DELETES_PER_TICK = 500

/** Total attempts before a delivery is [network.lapis.cloud.shared.domain.WebhookDeliveryStatus.ABANDONED] -- see [WebhookDeliveryPoller] KDoc "Retry plan". */
private const val MAX_ATTEMPTS = 6

/** After-attempt-N wait, indexed `[N-1]` -- see [WebhookDeliveryPoller] KDoc "Retry plan" table. `N == MAX_ATTEMPTS` has no entry (that attempt's failure abandons instead of scheduling a retry). */
private val RETRY_BACKOFF =
    listOf(30.seconds, 2.minutes, 10.minutes, 45.minutes, 3.hours)

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- application-scoped poller, structurally wörtlich nach
 * `network.lapis.cloud.server.payment.dunning.DunningPoller`/`SepaBatchPoller` modelliert: ONE
 * coroutine (`SupervisorJob() + Dispatchers.IO`), `while (isActive) { tick(); delay(interval) }`,
 * `start()`/`stop()` idempotent, [tick] `public` and exception-safe at two levels, NO in-memory
 * state (every phase re-queries its candidates fresh every tick).
 *
 * **Operating assumption (plan O3): exactly ONE server instance runs this poller.** The atomic
 * per-row claim in [WebhookDeliveryQueue.claimForDelivery] makes DELIVERY itself safe under
 * multiple instances, but the deactivation-mail send ([WebhookDeactivationNotifier]) and the
 * retention sweep (Phase C) are NOT additionally guarded against running twice concurrently -- a
 * second instance would double-send the deactivation mail / redundantly attempt the same deletes
 * (harmless but wasteful). Documented here, not enforced -- an advisory lock would be the fix if
 * multi-instance operation is ever adopted.
 *
 * **Four phases per tick, in this order:**
 * - **A0 -- Stale-Claim-Reaper.** A row stuck `DELIVERING` for longer than [STALE_CLAIM_MINUTES]
 *   minutes (a crash mid-attempt) is reset to `PENDING`, due immediately. Runs FIRST every tick so
 *   Phase A's own candidate scan can pick such a row back up in the SAME tick.
 * - **A -- Delivery.** Up to [WebhookConfig.maxDeliveriesPerTick] due candidates,
 *   [WebhookConfig.maxConcurrentDeliveries] in flight at once (`async`/`awaitAll` batching over
 *   fixed-size chunks). Each row is atomically CLAIMED ([WebhookDeliveryQueue.claimForDelivery])
 *   before [WebhookDeliverySender.sendOnce] is even called -- only a caller that actually won the
 *   claim (`updatedRows == 1`) proceeds; a `null` claim (lost the race, e.g. to the reaper or a
 *   second instance) is silently skipped.
 * - **B -- Deactivation.** See [handleFailure] KDoc.
 * - **C -- Retention.** Deletes terminal (`DELIVERED`/`ABANDONED`/`FAILED`) rows older than
 *   [WebhookConfig.retentionDays], capped at [RETENTION_DELETES_PER_TICK] per tick --
 *   `PENDING`/`DELIVERING` rows are NEVER touched here.
 *
 * **Retry plan -- 6 attempts, no jitter** (`attemptCount` after [WebhookDeliveryQueue.claimForDelivery]'s
 * increment is the attempt number that JUST ran): 30s / 2min / 10min / 45min / 3h, then
 * `ABANDONED` + endpoint deactivated.
 *
 * **Classification** (see [handleFailure]/[handleResponse]): 2xx -> `DELIVERED`. `410 Gone` ->
 * immediately `ABANDONED` + deactivation (`RECEIVER_GONE`), no further attempt. 3xx (never
 * followed, see [webhookHttpClient]'s `followRedirects = false`) -> Fehlversuch. Everything else
 * (4xx/5xx/timeout/TLS/DNS/URL-rejected) -> Fehlversuch, deliberately with NO special case for
 * 401/404 (both can be transient on a receiver's side).
 */
internal class WebhookDeliveryPoller(
    private val config: WebhookConfig,
    private val secretBox: SecretBox,
    private val deactivationNotifier: WebhookDeactivationNotifier,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime(TimeZone.UTC) },
) {
    private val sender = WebhookDeliverySender(secretBox = secretBox, allowInsecureHttp = config.allowInsecureHttp)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. No-op entirely when [WebhookConfig.enabled] is `false`. */
    fun start() {
        if (!config.enabled) return
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(config.pollIntervalSeconds.seconds)
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** One poll pass, three phases (A0 folded into A below) -- see class KDoc. Exception-safe at the whole-tick level so callers (including tests) can invoke this directly with zero timing dependency. */
    suspend fun tick() {
        try {
            val now = clock()
            reapStaleClaims(now)
            runDeliveryPhase(now)
            runRetentionPhase(now)
        } catch (e: Throwable) {
            logger.warn(e) { "WebhookDeliveryPoller: tick failed" }
        }
    }

    private fun reapStaleClaims(now: LocalDateTime) {
        try {
            val staleCutoff = now.minusMinutes(STALE_CLAIM_MINUTES)
            val reaped = WebhookDeliveryQueue.reapStaleClaims(staleCutoff = staleCutoff, now = now)
            if (reaped > 0) logger.warn { "WebhookDeliveryPoller: reaped $reaped stale DELIVERING row(s)" }
        } catch (e: Throwable) {
            logger.warn(e) { "WebhookDeliveryPoller: stale-claim reaper failed" }
        }
    }

    private suspend fun runDeliveryPhase(now: LocalDateTime) {
        val dueIds = WebhookDeliveryQueue.dueForDelivery(now = now, limit = config.maxDeliveriesPerTick)
        if (dueIds.isEmpty()) return
        dueIds.chunked(config.maxConcurrentDeliveries.coerceAtLeast(1)).forEach { chunk ->
            chunk
                .map { id -> scope.async { deliverOneSafely(id = id, now = now) } }
                .awaitAll()
        }
    }

    private suspend fun deliverOneSafely(
        id: Uuid,
        now: LocalDateTime,
    ) {
        try {
            deliverOne(id = id, now = now)
        } catch (e: Throwable) {
            logger.warn(e) { "WebhookDeliveryPoller: delivery attempt failed for $id" }
        }
    }

    private suspend fun deliverOne(
        id: Uuid,
        now: LocalDateTime,
    ) {
        val delivery = WebhookDeliveryQueue.claimForDelivery(id = id, now = now) ?: return
        val endpoint = WebhookEndpointStore.getById(delivery.endpointId) ?: return

        // Security-Audit-Fund F3 (Runde 1, 2026-09-02, MINOR) -- an endpoint deactivated WHILE one
        // of its rows was DELIVERING (a race with WebhookService.removeWebhookUrl/setWebhookUrl's
        // own deactivation paths, or the poller's own abandonAndDeactivate for a DIFFERENT row of
        // the same endpoint in an earlier tick) must not run this claimed row through the retry
        // ladder at all -- abandonAllPendingForEndpoint only ever touches PENDING rows at the
        // moment of deactivation, so a row that was DELIVERING at that exact instant is missed by
        // it and would otherwise re-enter PENDING on its next Fehlversuch and retry for up to
        // ~4h (the full backoff ladder) against an endpoint everyone already believes is off.
        if (!endpoint.active) {
            WebhookDeliveryQueue.markAbandoned(
                id = delivery.id,
                httpStatus = null,
                errorCode = WebhookFailureReason.ENDPOINT_DEACTIVATED.name,
            )
            return
        }

        // Security-Audit-Fund F2 (Runde 1, 2026-09-02, MAJOR), part 3 -- defense in depth: claiming
        // a row whose attemptCount is ALREADY beyond MAX_ATTEMPTS should never happen (handleFailure
        // abandons+deactivates at exactly MAX_ATTEMPTS, see below), but if some future bug or a
        // still-uncaught exception class ever lets a row slip past that guard and back to PENDING
        // one attempt too many times, this stops it from sending yet another live HTTP request
        // instead of silently retrying forever. Routed through the SAME abandonAndDeactivate path
        // handleFailure's own MAX_ATTEMPTS branch uses -- idempotent if the endpoint is already
        // inactive (WebhookEndpointDeactivation.deactivate no-ops and abandonAndDeactivate skips the
        // notification mail in that case).
        if (delivery.attemptCount > MAX_ATTEMPTS) {
            logger.warn {
                "WebhookDeliveryPoller: delivery ${delivery.id} claimed with attemptCount=${delivery.attemptCount} " +
                    "> MAX_ATTEMPTS=$MAX_ATTEMPTS -- abandoning without another send attempt"
            }
            abandonAndDeactivate(
                endpoint = endpoint,
                delivery = delivery,
                httpStatus = null,
                errorCode = WebhookFailureReason.RETRIES_EXHAUSTED.name,
                reason = WebhookDeactivationReason.DELIVERY_FAILURES,
                now = now,
            )
            return
        }

        val outcome = sender.sendOnce(endpoint = endpoint, delivery = delivery, attempt = delivery.attemptCount)
        when (outcome) {
            is WebhookSendOutcome.Responded ->
                handleResponse(endpoint = endpoint, delivery = delivery, httpStatus = outcome.httpStatus, now = now)
            is WebhookSendOutcome.TransportFailure ->
                handleFailure(endpoint = endpoint, delivery = delivery, httpStatus = null, reason = outcome.reason, now = now)
            is WebhookSendOutcome.Rejected ->
                handleFailure(
                    endpoint = endpoint,
                    delivery = delivery,
                    httpStatus = null,
                    reason = WebhookFailureReason.URL_REJECTED,
                    now = now,
                )
        }
    }

    private fun handleResponse(
        endpoint: WebhookEndpointStore.EndpointRow,
        delivery: WebhookDeliveryQueue.DeliveryRow,
        httpStatus: Int,
        now: LocalDateTime,
    ) {
        when {
            httpStatus in 200..299 -> WebhookDeliveryQueue.markDelivered(id = delivery.id, httpStatus = httpStatus, now = now)
            httpStatus == GONE_STATUS ->
                abandonAndDeactivate(
                    endpoint = endpoint,
                    delivery = delivery,
                    httpStatus = httpStatus,
                    errorCode = WebhookFailureReason.HTTP_ERROR.name,
                    reason = WebhookDeactivationReason.RECEIVER_GONE,
                    now = now,
                )
            else ->
                handleFailure(
                    endpoint = endpoint,
                    delivery = delivery,
                    httpStatus = httpStatus,
                    reason = WebhookFailureReason.HTTP_ERROR,
                    now = now,
                )
        }
    }

    /**
     * Phase B -- a non-2xx (short of `410 Gone`, handled separately above), a transport failure, or
     * a re-validation rejection. Schedules a backed-off retry unless [MAX_ATTEMPTS] is already
     * reached, in which case the endpoint is auto-deactivated (`DELIVERY_FAILURES`) exactly like
     * the `410 Gone` path (`RECEIVER_GONE`) -- both funnel through [abandonAndDeactivate].
     */
    private fun handleFailure(
        endpoint: WebhookEndpointStore.EndpointRow,
        delivery: WebhookDeliveryQueue.DeliveryRow,
        httpStatus: Int?,
        reason: WebhookFailureReason,
        now: LocalDateTime,
    ) {
        val attempt = delivery.attemptCount
        if (attempt >= MAX_ATTEMPTS) {
            abandonAndDeactivate(
                endpoint = endpoint,
                delivery = delivery,
                httpStatus = httpStatus,
                errorCode = WebhookFailureReason.RETRIES_EXHAUSTED.name,
                reason = WebhookDeactivationReason.DELIVERY_FAILURES,
                now = now,
            )
            return
        }
        val backoff = RETRY_BACKOFF.getOrElse(attempt - 1) { RETRY_BACKOFF.last() }
        val nextAttemptAt = now.plusDuration(backoff)
        WebhookDeliveryQueue.markRetryScheduled(
            id = delivery.id,
            nextAttemptAt = nextAttemptAt,
            httpStatus = httpStatus,
            errorCode = reason.name,
        )
    }

    /** Marks [delivery] `ABANDONED`, deactivates [endpoint] (idempotent -- a no-op if already inactive), abandons every other still-`PENDING` delivery of that endpoint, records the audit entry, and fires the notification mail -- see plan §5.6 for the full ordering rationale. */
    private fun abandonAndDeactivate(
        endpoint: WebhookEndpointStore.EndpointRow,
        delivery: WebhookDeliveryQueue.DeliveryRow,
        httpStatus: Int?,
        errorCode: String,
        reason: WebhookDeactivationReason,
        now: LocalDateTime,
    ) {
        WebhookDeliveryQueue.markAbandoned(id = delivery.id, httpStatus = httpStatus, errorCode = errorCode)
        val result = WebhookEndpointDeactivation.deactivate(apiKeyId = endpoint.apiKeyId, reason = reason)
        if (result != null) {
            deactivationNotifier.notify(
                endpoint = result.endpoint,
                delivery = delivery,
                httpStatus = httpStatus,
                recipients = result.recipients,
            )
        }
    }

    private fun runRetentionPhase(now: LocalDateTime) {
        try {
            val cutoff = now.minusDays(config.retentionDays.toLong())
            WebhookDeliveryQueue.deleteExpired(olderThan = cutoff, limit = RETENTION_DELETES_PER_TICK)
        } catch (e: Throwable) {
            logger.warn(e) { "WebhookDeliveryPoller: retention sweep failed" }
        }
    }

    private companion object {
        const val GONE_STATUS = 410
    }
}

private fun LocalDateTime.minusMinutes(minutes: Long): LocalDateTime = plusDuration(-minutes.minutes)

// Plain 24h-multiple Duration rather than kotlinx.datetime's calendar-aware DateTimeUnit.DAY
// arithmetic -- a retention cutoff has no DST-correctness requirement (it only needs to be
// "roughly N days ago", never a calendar-exact day boundary), so the simpler Instant+Duration path
// is used instead of chasing the exact kotlinx-datetime/kotlin.time.Instant overload this project's
// pinned dependency versions expose.
private fun LocalDateTime.minusDays(days: Long): LocalDateTime = plusDuration(-(days * 24).hours)

private fun LocalDateTime.plusDuration(duration: kotlin.time.Duration): LocalDateTime =
    toInstant(TimeZone.UTC).plus(duration).toLocalDateTime(TimeZone.UTC)
