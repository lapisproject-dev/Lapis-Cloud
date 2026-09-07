package network.lapis.cloud.server.accounting.export

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.shared.domain.AccountingExportProvider
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** How long an item may sit `SENDING` before being reaped -- see [AccountingExportPoller] KDoc
 * "Phase A0". */
private const val STALE_CLAIM_MINUTES = 5L

/** Sequential sends per tick -- see class KDoc "Phase A". */
internal const val MAX_ITEMS_PER_TICK = 3

/** Attempts before an item is marked `FAILED` -- see class KDoc "Retry plan". */
private const val MAX_ATTEMPTS = 3

/** After-attempt-N backoff, indexed `[N-1]` -- 2s / 4s / 8s. Fallback ONLY -- see
 * [VoucherPushOutcome.Retryable.retryAfter] handling in `sendOneSafely`, which prefers a
 * provider-supplied `Retry-After` when present. */
private val RETRY_BACKOFF = listOf(2.seconds, 4.seconds, 8.seconds)

/** Ceiling on an HONORED [VoucherPushOutcome.Retryable.retryAfter] -- a provider-supplied
 * `Retry-After` is trusted (Fund 2026-09-07: the fixed 2s/4s/8s table was previously used
 * UNCONDITIONALLY even when lexoffice returned e.g. `Retry-After: 60`, exhausting [MAX_ATTEMPTS] in
 * ~14s and marking the item permanently `FAILED` well before the provider's own requested window
 * elapsed), but capped here so a malformed or absurd header value cannot stall a single item for an
 * unreasonable stretch of wall-clock time -- the item just stays `PENDING`, so nothing besides that
 * one item's own progress is at risk either way.  */
private val MAX_HONORED_RETRY_AFTER = 5.minutes

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- application-scoped poller, structurally the same
 * shape [network.lapis.cloud.server.webhook.WebhookDeliveryPoller] establishes: ONE coroutine
 * (`SupervisorJob() + Dispatchers.IO`), `while (isActive) { tick(); delay(interval) }`,
 * `start()`/`stop()` idempotent, [tick] `public` and exception-safe, NO in-memory state (every
 * phase re-queries its candidates fresh).
 *
 * **One decisive difference from `WebhookDeliveryPoller`: NO concurrency.** `WebhookDeliveryPoller`
 * batches sends with `async`/`awaitAll`; this poller sends [MAX_ITEMS_PER_TICK] items STRICTLY
 * SEQUENTIALLY, because lexoffice's rate limit (2 req/s) is a per-account ceiling a burst of
 * parallel requests would trivially exceed. At [network.lapis.cloud.server.accounting.export
 * .AccountingExportConfig.pollIntervalSeconds] == 2 (default) and [MAX_ITEMS_PER_TICK] == 3, the
 * poller's OWN cadence already sends at ~1.5 req/s -- comfortably under the limit BEFORE
 * [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeRateLimiter] (the second,
 * defense-in-depth layer that also covers the synchronous `testConnection`/`listCategories` RPC
 * path) ever has to intervene.
 *
 * **Operating assumption: exactly ONE server instance runs this poller** (same posture
 * `WebhookDeliveryPoller` documents for itself). The atomic per-item claim ([AccountingExportStore
 * .claim]) makes sending itself safe under multiple instances, but
 * [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeRateLimiter] is process-local --
 * two instances would each independently rate-limit to ~1.5 req/s, summing to ~3 req/s, over the
 * documented 2 req/s ceiling. Documented, not enforced -- a DB advisory lock would be the fix if
 * multi-instance operation is ever adopted (same conclusion `WebhookDeliveryPoller` reaches).
 *
 * **Phases per tick, in order:**
 * - **A0 -- Stale-claim reaper.** An item stuck `SENDING` longer than [STALE_CLAIM_MINUTES] is
 *   reset to `UNKNOWN`, **NEVER back to `PENDING`** -- unlike `WebhookDeliveryPoller.reapStaleClaims`.
 *   lexoffice has no idempotency key on `POST /v1/vouchers`: resending after a crash mid-attempt (the
 *   only way an item stays `SENDING` this long) risks creating a genuine DUPLICATE voucher rather
 *   than merely retrying a delivery. `UNKNOWN` puts the burden of resolution on a human (search for
 *   the deterministic `voucherNumber` inside lexoffice itself), which is the safer failure mode.
 * - **A -- Sending.** Up to [MAX_ITEMS_PER_TICK] due `PENDING` items across every provider with an
 *   active run, claimed one at a time ([AccountingExportStore.claim] -- only a caller that actually
 *   won the row, `updatedRows == 1`, proceeds), sent SEQUENTIALLY. Immediately before sending, the
 *   item is re-checked against [AccountingExportStore.isAlreadyExported] (defensive -- see that
 *   function's own call site KDoc) -- a hit skips the HTTP call entirely
 *   (`SKIPPED_ALREADY_EXPORTED`).
 * - **B -- Run bookkeeping.** After every item this tick (sent or reaped), the owning run's counts
 *   are recomputed from the actual item rows and, if none remain `PENDING`/`SENDING`, the run is
 *   finalized -- see [AccountingExportStore.recomputeRunCounts]. UNCONDITIONALLY, every
 *   [AccountingExportStore.activeRuns] id is fed into this same step too (Fund 2026-09-07 review
 *   Runde 2, Befund 4) -- a self-healing backstop for a run that started `RUNNING` with zero
 *   `PENDING` items and was never touched by A0 or A at all, not a third phase with its own
 *   ordering.
 *
 * **Classification** (see [network.lapis.cloud.server.accounting.export.VoucherPushOutcome]):
 * `Succeeded` -> `SUCCEEDED`. `Rejected` -> `FAILED`, no retry. `Retryable` -> backed-off retry
 * (the provider's own `Retry-After` when present, capped at [MAX_HONORED_RETRY_AFTER]; the fixed
 * 2s/4s/8s table otherwise) unless [MAX_ATTEMPTS] already reached, then `FAILED`. `Indeterminate` -> `UNKNOWN`
 * immediately, no retry (same reasoning as the stale-claim reaper above). A `Rejected` with
 * `errorCode == "UNAUTHORIZED"` additionally calls [AccountingExportStore.markTestDue] for that
 * item's provider, so the connection screen stops claiming a verified connection.
 */
internal class AccountingExportPoller(
    private val config: AccountingExportConfig,
    private val secretBox: SecretBox?,
    private val adaptersByProvider: Map<AccountingExportProvider, AccountingExportProviderAdapter>,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime(TimeZone.UTC) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. No-op entirely when
     * [AccountingExportConfig.enabled] is `false`. */
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

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** One poll pass -- exception-safe at the whole-tick level, same posture
     * `WebhookDeliveryPoller.tick`. Public and blocking-callable (via [runBlocking] internally for
     * each item) so tests can invoke it directly with zero timing dependency. */
    fun tick() {
        try {
            val now = clock()
            // Fund 2026-09-07: Phase A0's touched runs used to be dropped entirely -- only Phase A's
            // own `touchedRunIds` (built from `sendOneSafely` below) ever reached
            // `recomputeRunCounts`. A run whose last remaining item got reaped here (never sent)
            // stayed RUNNING/active_key set forever, contradicting the class KDoc "After every item
            // this tick (sent or reaped), the owning run's counts are recomputed". Both phases now
            // feed the SAME finalization step.
            val touchedRunIds = mutableSetOf<Uuid>()
            touchedRunIds += reapStaleClaims(now)
            touchedRunIds += runSendPhase(now)
            // Fund 2026-09-07 review Runde 2 (Befund 4): self-healing safety net, not a third
            // "phase" in its own right -- every ACTIVE run's counts are recomputed every tick
            // regardless of whether this tick's reap/send phases touched any of its items, closing
            // the one window neither covers (see AccountingExportStore.activeRuns KDoc). Cheap: at
            // most one active run per provider.
            touchedRunIds += activeRunIdsSafely()
            finalizeTouchedRuns(runIds = touchedRunIds, now = now)
        } catch (e: Throwable) {
            logger.warn(e) { "AccountingExportPoller: tick failed" }
        }
    }

    /** Returns the (possibly empty) set of run ids that owned a reaped item -- see [tick] for why
     * this feeds Phase B just like Phase A's own sends do. */
    private fun reapStaleClaims(now: LocalDateTime): Set<Uuid> =
        try {
            val staleCutoff = now.minusMinutes(STALE_CLAIM_MINUTES)
            val reapedRunIds = AccountingExportStore.reapStaleClaims(staleCutoff = staleCutoff, now = now)
            val distinctRunIds = reapedRunIds.toSet()
            if (reapedRunIds.isNotEmpty()) {
                logger.warn {
                    "AccountingExportPoller: reaped ${reapedRunIds.size} stale SENDING item(s) across ${distinctRunIds.size} run(s)"
                }
            }
            distinctRunIds
        } catch (e: Throwable) {
            logger.warn(e) { "AccountingExportPoller: stale-claim reaper failed" }
            emptySet()
        }

    /** Every currently-active run's id, across every provider -- see [AccountingExportStore
     * .activeRuns] KDoc for why [tick] feeds this into Phase B unconditionally every tick. */
    private fun activeRunIdsSafely(): Set<Uuid> =
        try {
            AccountingExportStore.activeRuns().map { it.id }.toSet()
        } catch (e: Throwable) {
            logger.warn(e) { "AccountingExportPoller: activeRuns() lookup failed" }
            emptySet()
        }

    /** Returns the (possibly empty) set of run ids [sendOneSafely] touched this tick. */
    private fun runSendPhase(now: LocalDateTime): Set<Uuid> {
        val dueIds = AccountingExportStore.duePendingItemIds(providers = adaptersByProvider.keys, now = now, limit = MAX_ITEMS_PER_TICK)
        val touchedRunIds = mutableSetOf<Uuid>()
        dueIds.forEach { id ->
            try {
                sendOneSafely(id = id, now = now)?.let { touchedRunIds += it }
            } catch (e: Throwable) {
                logger.warn(e) { "AccountingExportPoller: send attempt failed for $id" }
            }
        }
        return touchedRunIds
    }

    private fun finalizeTouchedRuns(
        runIds: Set<Uuid>,
        now: LocalDateTime,
    ) {
        runIds.forEach { runId ->
            try {
                AccountingExportStore.recomputeRunCounts(runId = runId, now = now)
            } catch (e: Throwable) {
                logger.warn(e) { "AccountingExportPoller: recomputeRunCounts failed for $runId" }
            }
        }
    }

    /** Returns the touched run's id (for Phase B), or `null` if the claim was lost. */
    private fun sendOneSafely(
        id: Uuid,
        now: LocalDateTime,
    ): Uuid? {
        val item = AccountingExportStore.claim(id = id, now = now) ?: return null

        if (AccountingExportStore.isAlreadyExported(provider = item.provider, journalEntryId = item.journalEntryId)) {
            AccountingExportStore.markSkippedAlreadyExported(id = item.id, now = now)
            return item.runId
        }

        val adapter = adaptersByProvider[item.provider]
        val box = secretBox
        if (adapter == null || box == null) {
            AccountingExportStore.markUnknown(id = item.id, errorCode = "NO_ADAPTER_OR_KEY", now = now)
            return item.runId
        }
        val token = AccountingExportStore.readToken(provider = item.provider, secretBox = box)
        if (token == null) {
            AccountingExportStore.markFailed(id = item.id, errorCode = "NOT_CONNECTED", errorMessage = "Kein Token hinterlegt", now = now)
            return item.runId
        }

        val voucher =
            OutboundVoucher(
                voucherDate = item.entryDate,
                voucherNumber = item.voucherNumber,
                direction = item.direction,
                grossAmount = item.grossAmount,
                externalCategoryId = item.externalCategoryId,
                remark = "Lapis Cloud Export ${item.voucherNumber}",
            )

        val outcome = runBlocking { adapter.pushVoucher(token = token, voucher = voucher) }
        when (outcome) {
            is VoucherPushOutcome.Succeeded ->
                AccountingExportStore.markSucceeded(
                    id = item.id,
                    provider = item.provider,
                    journalEntryId = item.journalEntryId,
                    externalVoucherId = outcome.externalVoucherId,
                    now = now,
                )
            is VoucherPushOutcome.Rejected -> {
                AccountingExportStore.markFailed(id = item.id, errorCode = outcome.errorCode, errorMessage = outcome.message, now = now)
                if (outcome.errorCode == "UNAUTHORIZED") AccountingExportStore.markTestDue(item.provider)
            }
            is VoucherPushOutcome.Indeterminate -> AccountingExportStore.markUnknown(id = item.id, errorCode = outcome.errorCode, now = now)
            is VoucherPushOutcome.Retryable -> {
                if (item.attempts >= MAX_ATTEMPTS) {
                    AccountingExportStore.markFailed(id = item.id, errorCode = outcome.errorCode, errorMessage = outcome.message, now = now)
                } else {
                    // Provider-supplied Retry-After wins over the fixed table when present -- see
                    // MAX_HONORED_RETRY_AFTER KDoc for the Fund 2026-09-07 rationale and the cap.
                    val backoff =
                        outcome.retryAfter
                            ?.takeIf { it > kotlin.time.Duration.ZERO }
                            ?.coerceAtMost(MAX_HONORED_RETRY_AFTER)
                            ?: RETRY_BACKOFF.getOrElse(item.attempts - 1) { RETRY_BACKOFF.last() }
                    AccountingExportStore.markRetryScheduled(
                        id = item.id,
                        nextAttemptAt = now.plusDuration(backoff),
                        errorCode = outcome.errorCode,
                        errorMessage = outcome.message,
                    )
                }
            }
        }
        return item.runId
    }
}

private fun LocalDateTime.minusMinutes(minutes: Long): LocalDateTime = plusDuration(-minutes.minutes)

private fun LocalDateTime.plusDuration(duration: kotlin.time.Duration): LocalDateTime =
    toInstant(TimeZone.UTC).plus(duration).toLocalDateTime(TimeZone.UTC)
