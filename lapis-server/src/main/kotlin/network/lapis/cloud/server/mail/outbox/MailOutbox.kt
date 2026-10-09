package network.lapis.cloud.server.mail.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.mail.MailFailureKind
import network.lapis.cloud.server.mail.MailPurpose
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.budget.BudgetDecision
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.budget.MailLane
import network.lapis.cloud.server.mail.maskEmailForLogging
import network.lapis.cloud.server.mail.minusDuration
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.mail.secondsUntil
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The five states of a `mail_outbox` row (CHECK constraint `chk_mail_outbox_status`). */
object MailOutboxStatus {
    const val QUEUED = "QUEUED"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"

    /** Only these two states carry (encrypted) payload. */
    val OPEN = listOf(QUEUED, SENDING)
}

/**
 * Welle V1.9.81 -- the durable, encrypted outbox for system mails. Replaces the in-memory queue of [network.lapis.cloud.server.mail.MailDispatcher]
 * whenever a `LAPIS_SECRET_ENCRYPTION_KEY` is configured (see [MailOutboxConfig]).
 *
 * **Why.** A system mail that was accepted but not yet sent used to live only in a 64-slot in-memory channel: a restart, a stalled relay or an
 * exhausted hourly budget lost it for good. Now it is written (AES-GCM encrypted, see below) and delivered by a poller that respects the
 * hourly budget ([MailBudgetStore]) and retries transient failures with a backoff.
 *
 * **The caller is never slowed down.** [offer] only does a `trySend` into a bounded hand-off channel of [handoffCapacity] slots -- no
 * database work, no encryption, on the caller's thread. That keeps the timing of `POST /api/auth/password-reset/request` independent of
 * whether the address is registered (see `MailDispatcher` KDoc: the timing side channel). A single persister coroutine drains the channel in
 * batches and writes them in one transaction.
 *
 * **Encryption.** Recipient, subject, plain-text and HTML body exist only as [SecretBox] ciphertext (AAD `mail_outbox:<id>:<column>`, so a
 * ciphertext copied to another row or column does not open) and only while the row is open (`QUEUED`/`SENDING`). EVERY final state
 * (`SENT`/`FAILED`/`EXPIRED`) clears all payload columns and the lookup hash in the SAME `UPDATE` as the status change; two CHECK constraints
 * in V80 make a violation impossible at schema level. `last_error_class` holds a closed vocabulary, never an exception message.
 *
 * **Delivery, one poll pass ([tick]):**
 * 1. *Reaper* -- `SENDING` for longer than [staleClaim] (a crash mid-send) becomes `FAILED/INTERRUPTED`. NEVER re-sent: the relay may have
 *    accepted the mail.
 * 2. *Expiry* -- `QUEUED` past `expires_at` becomes `EXPIRED` (priority-0 mails have a 30 minute TTL: a late password-reset link is useless).
 * 3. *Delivery* -- up to [maxConcurrentSends] in flight. For each row: reserve a budget slot FIRST, then claim the row, then send. This
 *    deliberately differs from "claim, then reserve": a worker that claimed a row and then waited up to an hour for budget would leave a
 *    never-sent mail marked `SENDING` if the process died meanwhile. A slot whose claim fails is released (or, if that fails, simply stays
 *    used -- conservative and harmless).
 * 4. *Retention* (hourly) -- `SENT`/`EXPIRED` rows 7 days, `FAILED` rows 30 days (they hold no personal data); slots older than 2 hours.
 *
 * **Claim.** On PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED` inside one transaction followed by a conditional `UPDATE`; on H2 the conditional
 * `UPDATE ... WHERE status = 'QUEUED'` alone decides (`updated == 1`). The final state is written FENCED (`WHERE status = 'SENDING' AND
 * attempt_count = <claimed value>`), so a late completion after the reaper cannot overwrite the reaper's verdict.
 *
 * **Retry.** Transient failures (SMTP 4xx, connection failure, timeout) are re-queued with a backoff of 1/5/15/60 minutes, 5 attempts in all.
 * A timeout after the data transfer began is `deliveryUncertain`; system mails are retried anyway, so a duplicate is possible (documented).
 * A 4xx reply also pauses the BULK lane globally for 10 minutes. Everything else is final `FAILED`.
 *
 * **Limits (documented, see CHANGELOG).** A mail accepted by [offer] but not yet written (milliseconds, longer if the database is down)
 * is lost on a crash; if the database stays down past 3 write attempts the batch is dropped with an ERROR. Retention/recovery assume ONE
 * server instance; several instances are only correct for the budget.
 */
class MailOutbox(
    private val secretBox: SecretBox,
    private val lookupHasher: MailRecipientHasher,
    private val budget: MailBudgetStore,
    private val transport: MailTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    handoffCapacity: Int = DEFAULT_HANDOFF_CAPACITY,
    private val maxQueued: Int = DEFAULT_MAX_QUEUED,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val maxConcurrentSends: Int = DEFAULT_MAX_CONCURRENT_SENDS,
    private val perSendTimeout: Duration = DEFAULT_PER_SEND_TIMEOUT,
    private val staleClaim: Duration = DEFAULT_STALE_CLAIM,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
) {
    private val handoff = Channel<OutboundMail>(capacity = handoffCapacity)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var persisterJob: Job? = null
    private var pollerJob: Job? = null
    private var nextRetentionAt: LocalDateTime? = null

    /** Result of one [tick]: how many rows were handed to the transport, and until when the budget (or the bulk pause) blocks the next one. */
    data class TickResult(
        val processed: Int,
        val waitUntil: LocalDateTime?,
    )

    /**
     * Hands [mail] to the persister and returns immediately; never throws, never touches the database. `false` when the mail was NOT
     * accepted (hand-off full, payload too large, outbox shut down) -- logged as ERROR, same as the in-memory dispatcher always did.
     */
    fun offer(mail: OutboundMail): Boolean {
        val masked = maskedFor(mail)
        if (payloadSize(mail) > maxPayloadBytes) {
            logger.error { "Mail dropped, payload too large: purpose=${mail.purpose} to=$masked" }
            return false
        }
        val result = handoff.trySend(mail)
        if (result.isFailure) {
            logger.error { "Mail dropped, outbox hand-off saturated: purpose=${mail.purpose} to=$masked" }
        }
        return result.isSuccess
    }

    /**
     * Writes all [mails] in ONE transaction, bypassing the hand-off channel -- for callers that send many mails at once and have no
     * timing side channel to protect (e.g. an event cancellation to hundreds of registrants), where the 256-slot channel would otherwise
     * drop the overflow. NEVER for the password-reset / registration paths (a tripwire guards that). Throws if the database write fails.
     */
    suspend fun persistAll(mails: List<OutboundMail>) {
        if (mails.isEmpty()) return
        val sealed = mails.filter { payloadSize(it) <= maxPayloadBytes }.map(::seal)
        val dropped = mails.size - sealed.size
        if (dropped > 0) logger.error { "$dropped mail(s) dropped, payload too large" }
        val inserted = insertRows(rows = sealed, now = clock())
        if (inserted < sealed.size) logger.error { "${sealed.size - inserted} mail(s) dropped, outbox full ($maxQueued queued)" }
        wake.trySend(Unit)
    }

    fun start() {
        if (persisterJob != null) return
        persisterJob = scope.launch { persistLoop() }
        pollerJob = scope.launch { pollLoop() }
    }

    /**
     * Stops accepting mail, gives the persister up to [drainTimeout] to write what is already in the channel, then cancels everything.
     * Idempotent. Blocking (called from an `ApplicationStopping` hook).
     */
    fun shutdown(drainTimeout: Duration = 5.seconds) {
        handoff.close()
        val persister = persisterJob
        if (persister != null) {
            runBlocking { withTimeoutOrNull(drainTimeout) { persister.join() } }
        }
        scope.cancel()
    }

    // ---- persisting ------------------------------------------------------------------------------------------------------------------

    private suspend fun persistLoop() {
        for (first in handoff) {
            val batch = ArrayList<OutboundMail>(PERSIST_BATCH)
            batch += first
            while (batch.size < PERSIST_BATCH) {
                val next = handoff.tryReceive().getOrNull() ?: break
                batch += next
            }
            persistWithRetry(batch)
        }
    }

    private suspend fun persistWithRetry(batch: List<OutboundMail>) {
        val rows =
            try {
                batch.map(::seal)
            } catch (t: Throwable) {
                logger.error { "Mail batch dropped, encryption failed (${t::class.simpleName}): ${batch.size} mail(s)" }
                return
            }
        var attempt = 0
        while (true) {
            attempt++
            try {
                val inserted = insertRows(rows = rows, now = clock())
                if (inserted < rows.size) {
                    logger.error { "${rows.size - inserted} mail(s) dropped, outbox full ($maxQueued queued)" }
                }
                wake.trySend(Unit)
                return
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (attempt >= PERSIST_ATTEMPTS) {
                    logger.error {
                        "Mail batch dropped, outbox write failed after $attempt attempts (${t::class.simpleName}): " +
                            "${batch.size} mail(s), purposes=${batch.map { it.purpose }.distinct()}"
                    }
                    return
                }
                delay(PERSIST_BACKOFF[attempt - 1])
            }
        }
    }

    private class SealedRow(
        val id: Uuid,
        val purpose: String,
        val priority: Int,
        val ttl: Duration?,
        val recipientEnc: String,
        val subjectEnc: String,
        val textEnc: String,
        val htmlEnc: String,
        val lookupHash: String,
        val logRecipient: Boolean,
    )

    private fun seal(mail: OutboundMail): SealedRow {
        val id = Uuid.random()
        val policy = MailPurpose.policyFor(mail.purpose)
        return SealedRow(
            id = id,
            purpose = mail.purpose.take(PURPOSE_MAX),
            priority = policy.priority,
            ttl = policy.outboxTtl,
            recipientEnc = secretBox.seal(plaintext = mail.to, aad = aad(id = id, column = "recipient_enc")),
            subjectEnc = secretBox.seal(plaintext = mail.subject, aad = aad(id = id, column = "subject_enc")),
            textEnc = secretBox.seal(plaintext = mail.plainTextBody, aad = aad(id = id, column = "text_enc")),
            htmlEnc = secretBox.seal(plaintext = mail.htmlBody, aad = aad(id = id, column = "html_enc")),
            lookupHash = lookupHasher.hash(mail.to),
            logRecipient = mail.logRecipient,
        )
    }

    /** Inserts as many of [rows] as the [maxQueued] cap allows (one transaction); returns the number inserted. */
    private fun insertRows(
        rows: List<SealedRow>,
        now: LocalDateTime,
    ): Int =
        transaction {
            val queued =
                MailOutboxTable
                    .selectAll()
                    .where { MailOutboxTable.status eq MailOutboxStatus.QUEUED }
                    .count()
                    .toInt()
            val room = (maxQueued - queued).coerceAtLeast(0)
            val toInsert = rows.take(room)
            toInsert.forEach { row ->
                MailOutboxTable.insert {
                    it[id] = row.id
                    it[purpose] = row.purpose
                    it[priority] = row.priority.toShort()
                    it[status] = MailOutboxStatus.QUEUED
                    it[attemptCount] = 0
                    it[nextAttemptAt] = now
                    it[createdAt] = now
                    it[expiresAt] = row.ttl?.let { ttl -> now.plusDuration(ttl) }
                    it[recipientEnc] = row.recipientEnc
                    it[subjectEnc] = row.subjectEnc
                    it[textEnc] = row.textEnc
                    it[htmlEnc] = row.htmlEnc
                    it[recipientLookupHash] = row.lookupHash
                    it[logRecipient] = row.logRecipient
                }
            }
            toInsert.size
        }

    // ---- polling ---------------------------------------------------------------------------------------------------------------------

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val result =
                try {
                    tick(clock())
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    logger.warn { "MailOutbox: tick failed (${t::class.simpleName})" }
                    null
                }
            val wait =
                when {
                    result == null -> pollInterval
                    result.waitUntil != null ->
                        clock().secondsUntil(result.waitUntil).coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS).seconds
                    result.processed > 0 -> Duration.ZERO
                    else -> pollInterval
                }
            if (wait > Duration.ZERO) withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private sealed interface Step {
        data class Claimed(
            val row: ClaimedRow,
        ) : Step

        data object Idle : Step

        data class Wait(
            val at: LocalDateTime,
        ) : Step
    }

    private class ClaimedRow(
        val id: Uuid,
        val purpose: String,
        val attempt: Int,
        val expiresAt: LocalDateTime?,
        val logRecipient: Boolean,
        val recipientEnc: String,
        val subjectEnc: String,
        val textEnc: String,
        val htmlEnc: String,
    )

    /** One poll pass, see the class KDoc. `internal` so tests can drive it deterministically. */
    internal suspend fun tick(now: LocalDateTime): TickResult {
        reapStale(now)
        expireDue(now)
        retentionIfDue(now)

        var processed = 0
        var waitUntil: LocalDateTime? = null
        var currentNow = now
        while (processed < MAX_PER_TICK && waitUntil == null) {
            val wave = ArrayList<ClaimedRow>(maxConcurrentSends)
            for (slot in 0 until maxConcurrentSends) {
                when (val step = claimNext(currentNow)) {
                    is Step.Claimed -> wave += step.row
                    is Step.Wait -> {
                        waitUntil = step.at
                        break
                    }
                    Step.Idle -> break
                }
            }
            if (wave.isEmpty()) break
            coroutineScope { wave.map { row -> async { deliver(row) } }.awaitAll() }
            processed += wave.size
            currentNow = clock()
        }
        return TickResult(processed = processed, waitUntil = waitUntil)
    }

    private fun claimNext(now: LocalDateTime): Step {
        var bulkBlockedUntil: LocalDateTime? = null
        // At most two passes: a blocked BULK head-of-line row must not hold back SYSTEM rows behind it.
        repeat(2) {
            val excludeBulk = bulkBlockedUntil != null
            val candidate = peekDue(now = now, excludeBulk = excludeBulk) ?: return bulkBlockedUntil?.let { Step.Wait(it) } ?: Step.Idle
            val policy = MailPurpose.policyFor(candidate)
            if (policy.lane == MailLane.BULK) {
                val paused = budget.bulkPausedUntil()
                if (paused != null && paused > now) {
                    bulkBlockedUntil = paused
                    return@repeat
                }
            }
            val reservation = budget.reserveSlot(lane = policy.lane, now = now)
            val decision = reservation.decision
            if (decision is BudgetDecision.WaitUntil) {
                if (policy.lane == MailLane.BULK) {
                    bulkBlockedUntil = decision.at
                    return@repeat
                }
                return Step.Wait(decision.at)
            }
            val claimed = claim(now = now, excludeBulk = excludeBulk)
            if (claimed == null) {
                budget.release(reservation.slotId)
                return bulkBlockedUntil?.let { Step.Wait(it) } ?: Step.Idle
            }
            if (MailPurpose.policyFor(claimed.purpose).lane == MailLane.BULK && policy.lane == MailLane.SYSTEM) {
                // The head of the queue changed between peek and claim and the slot was reserved against the wrong lane: put the row back.
                unclaim(claimed)
                budget.release(reservation.slotId)
                return Step.Idle
            }
            return Step.Claimed(claimed)
        }
        return bulkBlockedUntil?.let { Step.Wait(it) } ?: Step.Idle
    }

    private fun bulkOnlyPurposes(): List<String> = MailPurpose.KNOWN_PURPOSES.filter { MailPurpose.policyFor(it).lane == MailLane.BULK }

    private fun peekDue(
        now: LocalDateTime,
        excludeBulk: Boolean,
    ): String? =
        transaction {
            val bulk = bulkOnlyPurposes()
            MailOutboxTable
                .selectAll()
                .where {
                    val due = (MailOutboxTable.status eq MailOutboxStatus.QUEUED) and (MailOutboxTable.nextAttemptAt lessEq now)
                    if (excludeBulk && bulk.isNotEmpty()) due and (MailOutboxTable.purpose notInList bulk) else due
                }.orderBy(MailOutboxTable.priority to SortOrder.ASC, MailOutboxTable.createdAt to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.get(MailOutboxTable.purpose)
        }

    private fun claim(
        now: LocalDateTime,
        excludeBulk: Boolean,
    ): ClaimedRow? =
        transaction {
            maxAttempts = 1
            val bulk = bulkOnlyPurposes()
            val query =
                MailOutboxTable
                    .selectAll()
                    .where {
                        val due = (MailOutboxTable.status eq MailOutboxStatus.QUEUED) and (MailOutboxTable.nextAttemptAt lessEq now)
                        if (excludeBulk && bulk.isNotEmpty()) due and (MailOutboxTable.purpose notInList bulk) else due
                    }.orderBy(MailOutboxTable.priority to SortOrder.ASC, MailOutboxTable.createdAt to SortOrder.ASC)
                    .limit(1)
            val locked =
                if (currentDialect is PostgreSQLDialect) {
                    // row-lock: FOR UPDATE SKIP LOCKED (mail_outbox claim; two pollers never block each other nor take the same row)
                    query.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                } else {
                    query
                }
            val row = locked.singleOrNull() ?: return@transaction null
            val id = row[MailOutboxTable.id]
            val attempt = row[MailOutboxTable.attemptCount] + 1
            val updated =
                MailOutboxTable.update({ (MailOutboxTable.id eq id) and (MailOutboxTable.status eq MailOutboxStatus.QUEUED) }) {
                    it[status] = MailOutboxStatus.SENDING
                    it[claimedAt] = now
                    it[attemptCount] = attempt
                }
            if (updated != 1) return@transaction null
            ClaimedRow(
                id = id,
                purpose = row[MailOutboxTable.purpose],
                attempt = attempt,
                expiresAt = row[MailOutboxTable.expiresAt],
                logRecipient = row[MailOutboxTable.logRecipient],
                recipientEnc = row[MailOutboxTable.recipientEnc].orEmpty(),
                subjectEnc = row[MailOutboxTable.subjectEnc].orEmpty(),
                textEnc = row[MailOutboxTable.textEnc].orEmpty(),
                htmlEnc = row[MailOutboxTable.htmlEnc].orEmpty(),
            )
        }

    private fun unclaim(row: ClaimedRow) {
        transaction {
            MailOutboxTable.update({
                (MailOutboxTable.id eq row.id) and
                    (MailOutboxTable.status eq MailOutboxStatus.SENDING) and
                    (MailOutboxTable.attemptCount eq row.attempt)
            }) {
                it[status] = MailOutboxStatus.QUEUED
                it[claimedAt] = null
                it[attemptCount] = row.attempt - 1
            }
        }
    }

    private suspend fun deliver(row: ClaimedRow) {
        var masked = "(withheld)"
        try {
            val to: String
            val subject: String
            val text: String
            val html: String
            try {
                to = secretBox.open(sealed = row.recipientEnc, aad = aad(id = row.id, column = "recipient_enc"))
                subject = secretBox.open(sealed = row.subjectEnc, aad = aad(id = row.id, column = "subject_enc"))
                text = secretBox.open(sealed = row.textEnc, aad = aad(id = row.id, column = "text_enc"))
                html = secretBox.open(sealed = row.htmlEnc, aad = aad(id = row.id, column = "html_enc"))
            } catch (e: SecretBoxException) {
                logger.error { "Mail delivery FAILED: purpose=${row.purpose} outboxId=${row.id} errorClass=DECRYPT" }
                finalize(row = row, status = MailOutboxStatus.FAILED, errorClass = "DECRYPT")
                return
            }
            masked = if (row.logRecipient) maskEmailForLogging(to) else "(withheld)"
            if (row.expiresAt != null && row.expiresAt <= clock()) {
                logger.warn { "Mail expired before sending: purpose=${row.purpose} to=$masked outboxId=${row.id}" }
                finalize(row = row, status = MailOutboxStatus.EXPIRED, errorClass = null)
                return
            }
            val outcome = sendGuarded(to = to, subject = subject, text = text, html = html)
            when (outcome) {
                is MailSendOutcome.Sent -> {
                    logger.info { "Mail delivered: purpose=${row.purpose} to=$masked outboxId=${row.id}" }
                    finalize(row = row, status = MailOutboxStatus.SENT, errorClass = null)
                }
                is MailSendOutcome.Skipped -> {
                    logger.info { "Mail NOT delivered (no SMTP configured): purpose=${row.purpose} to=$masked outboxId=${row.id}" }
                    finalize(row = row, status = MailOutboxStatus.FAILED, errorClass = "SKIPPED")
                }
                is MailSendOutcome.Failed -> handleFailed(row = row, failure = outcome, masked = masked)
            }
        } catch (c: CancellationException) {
            // Shutdown mid-send: the row stays SENDING and the reaper turns it into FAILED/INTERRUPTED -- never re-sent.
            throw c
        } catch (t: Throwable) {
            logger.error {
                "Mail delivery FAILED: purpose=${row.purpose} to=$masked outboxId=${row.id} errorClass=UNKNOWN (${t::class.simpleName})"
            }
            runCatching { finalize(row = row, status = MailOutboxStatus.FAILED, errorClass = "UNKNOWN") }
        }
    }

    private suspend fun sendGuarded(
        to: String,
        subject: String,
        text: String,
        html: String,
    ): MailSendOutcome =
        try {
            withTimeout(perSendTimeout) { transport.send(to = to, subject = subject, plainTextBody = text, htmlBody = html) }
        } catch (e: TimeoutCancellationException) {
            MailSendOutcome.Failed(
                sanitizedErrorMessage = "send timed out",
                kind = MailFailureKind.TRANSIENT,
                deliveryUncertain = true,
                errorClass = "TIMEOUT",
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            MailSendOutcome.Failed(sanitizedErrorMessage = "send failed (${t::class.simpleName})", errorClass = "UNKNOWN")
        }

    private fun handleFailed(
        row: ClaimedRow,
        failure: MailSendOutcome.Failed,
        masked: String,
    ) {
        val now = clock()
        if (failure.smtpReplyCode != null && failure.smtpReplyCode in 400..499) {
            // The provider is telling us to slow down: hold the bulk lane back globally for a while.
            runCatching { budget.pauseBulk(now.plusDuration(BULK_PAUSE)) }
        }
        val retry = failure.kind == MailFailureKind.TRANSIENT && row.attempt < MAX_ATTEMPTS
        if (retry) {
            val backoff = RETRY_BACKOFF[(row.attempt - 1).coerceIn(0, RETRY_BACKOFF.lastIndex)]
            logger.warn {
                "Mail delivery deferred: purpose=${row.purpose} to=$masked outboxId=${row.id} " +
                    "errorClass=${failure.errorClass} attempt=${row.attempt}"
            }
            finalize(row = row, status = MailOutboxStatus.QUEUED, errorClass = failure.errorClass, requeueAt = now.plusDuration(backoff))
        } else {
            logger.error {
                "Mail delivery FAILED: purpose=${row.purpose} to=$masked outboxId=${row.id} errorClass=${failure.errorClass} attempt=${row.attempt}"
            }
            finalize(row = row, status = MailOutboxStatus.FAILED, errorClass = failure.errorClass)
        }
    }

    /**
     * Fenced state change of a claimed row: only while it is still `SENDING` with the attempt number we claimed. A final status clears the
     * payload and the lookup hash in the SAME statement. Returns `false` (and logs) when the fence rejected it, e.g. after the reaper.
     */
    private fun finalize(
        row: ClaimedRow,
        status: String,
        errorClass: String?,
        requeueAt: LocalDateTime? = null,
    ): Boolean {
        val now = clock()
        val updated =
            transaction {
                val fence =
                    (MailOutboxTable.id eq row.id) and
                        (MailOutboxTable.status eq MailOutboxStatus.SENDING) and
                        (MailOutboxTable.attemptCount eq row.attempt)
                if (requeueAt != null) {
                    MailOutboxTable.update({ fence }) {
                        it[MailOutboxTable.status] = MailOutboxStatus.QUEUED
                        it[nextAttemptAt] = requeueAt
                        it[claimedAt] = null
                        it[lastErrorClass] = errorClass
                    }
                } else {
                    MailOutboxTable.update({ fence }) {
                        it[MailOutboxTable.status] = status
                        it[finishedAt] = now
                        it[lastErrorClass] = errorClass
                        clearPayload(it)
                    }
                }
            }
        if (updated != 1) logger.warn { "Mail outbox late completion ignored (row no longer owned): outboxId=${row.id}" }
        return updated == 1
    }

    // ---- housekeeping ----------------------------------------------------------------------------------------------------------------

    private fun reapStale(now: LocalDateTime) {
        runCatching {
            val reaped =
                transaction {
                    MailOutboxTable.update({
                        (MailOutboxTable.status eq MailOutboxStatus.SENDING) and
                            (MailOutboxTable.claimedAt less now.minusDuration(staleClaim))
                    }) {
                        it[status] = MailOutboxStatus.FAILED
                        it[finishedAt] = now
                        it[lastErrorClass] = "INTERRUPTED"
                        clearPayload(it)
                    }
                }
            if (reaped > 0) logger.warn { "MailOutbox: $reaped interrupted mail(s) closed as FAILED/INTERRUPTED (never re-sent)" }
        }.onFailure { logger.warn { "MailOutbox: reaper failed (${it::class.simpleName})" } }
    }

    private fun expireDue(now: LocalDateTime) {
        runCatching {
            val expired =
                transaction {
                    MailOutboxTable.update({
                        (MailOutboxTable.status eq MailOutboxStatus.QUEUED) and
                            MailOutboxTable.expiresAt.isNotNull() and
                            (MailOutboxTable.expiresAt lessEq now)
                    }) {
                        it[status] = MailOutboxStatus.EXPIRED
                        it[finishedAt] = now
                        clearPayload(it)
                    }
                }
            if (expired > 0) logger.warn { "MailOutbox: $expired mail(s) expired unsent" }
        }.onFailure { logger.warn { "MailOutbox: expiry failed (${it::class.simpleName})" } }
    }

    private fun retentionIfDue(now: LocalDateTime) {
        val due = nextRetentionAt
        if (due != null && now < due) return
        nextRetentionAt = now.plusDuration(RETENTION_INTERVAL)
        runCatching {
            transaction {
                MailOutboxTable.deleteWhere {
                    (status inList listOf(MailOutboxStatus.SENT, MailOutboxStatus.EXPIRED)) and
                        (finishedAt less now.minusDuration(SENT_RETENTION))
                }
                MailOutboxTable.deleteWhere {
                    (status eq MailOutboxStatus.FAILED) and (finishedAt less now.minusDuration(FAILED_RETENTION))
                }
            }
            budget.purgeSlotsOlderThan(now.minusDuration(MailBudgetStore.SLOT_RETENTION))
        }.onFailure { logger.warn { "MailOutbox: retention sweep failed (${it::class.simpleName})" } }
    }

    private fun maskedFor(mail: OutboundMail): String = if (mail.logRecipient) maskEmailForLogging(mail.to) else "(withheld)"

    private fun payloadSize(mail: OutboundMail): Int =
        mail.to.length + mail.subject.encodeToByteArray().size + mail.plainTextBody.encodeToByteArray().size +
            mail.htmlBody.encodeToByteArray().size

    companion object {
        const val DEFAULT_HANDOFF_CAPACITY = 256
        const val DEFAULT_MAX_QUEUED = 5000
        const val DEFAULT_MAX_PAYLOAD_BYTES = 512 * 1024
        const val DEFAULT_MAX_CONCURRENT_SENDS = 4
        const val MAX_ATTEMPTS = 5
        private const val PURPOSE_MAX = 64
        private const val PERSIST_BATCH = 50
        private const val PERSIST_ATTEMPTS = 3
        private const val MAX_PER_TICK = 200
        private const val MIN_WAIT_SECONDS = 1L
        private const val MAX_WAIT_SECONDS = 60L
        private val PERSIST_BACKOFF = listOf(200.milliseconds, 1.seconds)
        val DEFAULT_POLL_INTERVAL: Duration = 2.seconds
        val DEFAULT_PER_SEND_TIMEOUT: Duration = 60.seconds
        val DEFAULT_STALE_CLAIM: Duration = 10.minutes

        /** After attempt N failed transiently: wait `RETRY_BACKOFF[N-1]` (attempt 5 is final). */
        val RETRY_BACKOFF: List<Duration> = listOf(1.minutes, 5.minutes, 15.minutes, 60.minutes)
        val BULK_PAUSE: Duration = 10.minutes
        val SENT_RETENTION: Duration = 7.days
        val FAILED_RETENTION: Duration = 30.days
        private val RETENTION_INTERVAL: Duration = 60.minutes

        internal fun aad(
            id: Uuid,
            column: String,
        ): String = "mail_outbox:$id:$column"

        /** Sets all four payload columns and the lookup hash to NULL inside an `UPDATE` statement. */
        private fun clearPayload(it: UpdateBuilder<*>) {
            it[MailOutboxTable.recipientEnc] = null
            it[MailOutboxTable.subjectEnc] = null
            it[MailOutboxTable.textEnc] = null
            it[MailOutboxTable.htmlEnc] = null
            it[MailOutboxTable.recipientLookupHash] = null
        }

        /**
         * Start without an outbox (no key / no SMTP): rows an earlier run left open can never be delivered. They become
         * `FAILED/OUTBOX_DISABLED` with NULL payload, so the data-subject paths never see an open row. Returns the number closed.
         */
        fun closeOrphanedRows(now: LocalDateTime): Int =
            transaction {
                MailOutboxTable.update({ MailOutboxTable.status inList MailOutboxStatus.OPEN }) {
                    it[status] = MailOutboxStatus.FAILED
                    it[finishedAt] = now
                    it[lastErrorClass] = "OUTBOX_DISABLED"
                    clearPayload(it)
                }
            }
    }
}
