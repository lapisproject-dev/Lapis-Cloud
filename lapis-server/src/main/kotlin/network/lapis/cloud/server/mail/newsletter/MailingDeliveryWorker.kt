package network.lapis.cloud.server.mail.newsletter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailAttachment
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailFailureKind
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.budget.BudgetDecision
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.budget.MailLane
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.mail.maskEmailForLogging
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.mail.secondsUntil
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingDynamicSendSnapshot
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MailingNotice
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.rules.InvalidMemberRuleException
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.7 "SuperMailer" -- decouples the actual per-recipient send from the
 * `sendMailingMessage` RPC call, same `MailDispatcher`-idiom (own dedicated
 * `SupervisorJob() + Dispatchers.IO` [CoroutineScope], own bounded [Channel], long-lived worker
 * coroutine(s)) this codebase already establishes for the transactional mailers -- but with its OWN
 * tuning: exactly ONE worker (bulk sends are throttled by [sendDelay] anyway, so there is no
 * concurrency to gain and every extra worker would just be another SMTP connection racing the same
 * relay), and a queue of whole MESSAGES (one row = one whole mailing-list send), not individual mails.
 *
 * **Fixes the pre-existing bug this wave's plan documents as finding #1**: the previous
 * `MailingService.sendMailingMessage` never called any [MailTransport] at all -- it wrote a `SENT`
 * delivery-log row per subscriber inside the SAME transaction that flipped the message to `SENT`,
 * synchronously, with no real transport call and no recipient-eligibility filtering. This worker is
 * the actual fix: [MailTransport.send] is now genuinely called (in [MailingDeliveryMode.SMTP]) for
 * each recipient, one row's status transition at a time, entirely OFF the RPC handler's own
 * coroutine.
 *
 * **D1/D2 (plan) -- why [DeliveryStatus.PENDING] exists and how a crash recovers.** The queuing
 * transaction (`MailingService.sendMailingMessage`) inserts one `PENDING` delivery-log row per
 * eligible recipient BEFORE this worker ever runs -- so a server crash between "message marked
 * `QUEUED`" and "every recipient's row updated" leaves an honest, inspectable trail instead of
 * silently losing which recipients were already handled.
 *
 * **V1.9.81 -- resumable, at most once.** Before a row is sent it is CLAIMED (`claimed_at`, conditional update, after its hourly
 * budget slot was reserved). After a restart ([recoverInterrupted]) a `PENDING` row WITHOUT a claim is simply delivered (exactly once, the
 * send had not started); a `PENDING` row WITH a claim becomes [DeliveryStatus.INTERRUPTED] and is never sent again -- the mail may already
 * have reached the relay, and mailing real members twice is strictly worse than a manual re-check by the board. Retries happen only
 * when nothing could have been delivered (a clear SMTP 4xx, a failed connection); a timeout after the data transfer began is `FAILED`.
 * The message queue is the `mailing_message` table itself (`QUEUED`, ordered by `queued_at`), not an in-memory channel: it can no
 * longer be "saturated", and it survives a restart. See `docs/architecture/mail-delivery-budget-and-outbox.adoc`.
 *
 * **D3 (plan) -- recipient eligibility of a MANUAL list is decided ONCE, at queuing time**, by
 * `MailingService.sendMailingMessage` (active subscription, `MemberStatus.ACTIVE`, not anonymized,
 * not deceased) -- this worker only re-validates the mailbox ADDRESS shape per recipient (an
 * address can theoretically change between queuing and send, however small that window), never the
 * membership eligibility itself.
 *
 * **V1.9.111 -- a DYNAMIC list is the exception: its recipients are resolved HERE, at send time**
 * ([resolveRecipientsIfNeeded], one transaction, idempotent through `mailing_message.recipients_resolved_at`, audited once with the COUNT
 * only). `sendMailingMessage` writes no delivery rows for such a message. Because a send can last hours (hourly budget), every recipient
 * of a DYNAMIC list is re-checked just before the mail is rendered (still ACTIVE, not anonymised, not deceased, not opted out unless the
 * list is mandatory) and a member who left meanwhile becomes `SKIPPED_UNSUBSCRIBED`. Messages of a DYNAMIC list that are not resolved yet
 * have no rows and must NEVER be closed or "recovered" as empty ([finishMessageIfDone], [recoverInterrupted]).
 *
 * **Never logs a raw email address, a raw body or a member id** -- only
 * [network.lapis.cloud.server.mail.maskEmailForLogging] and counts, same discipline
 * `MailDispatcher`/`SmtpPasswordResetMailer` already establish.
 *
 * **Lifecycle**: `Application.module()` calls [recoverInterrupted] once at startup (before serving
 * any request) and registers [shutdown] on `ApplicationStopping`, exactly like `MailDispatcher`.
 */
class MailingDeliveryWorker(
    private val transport: MailTransport,
    private val branding: MailBranding,
    private val mode: MailingDeliveryMode,
    private val trackingToken: MailingTrackingToken,
    /** Public base URL (no trailing slash) the click/open tracking URLs are built from. */
    private val baseUrl: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val perSendTimeout: Duration = DEFAULT_PER_SEND_TIMEOUT,
    private val sendDelay: Duration = DEFAULT_SEND_DELAY,
    /** Hourly budget (V1.9.81). The default has no budget, hence no throttling -- but the global bulk pause still applies. */
    private val budget: MailBudgetStore = MailBudgetStore(null),
    /** Longest single sleep while waiting for budget / a deferral to pass; the state is re-evaluated at least this often. */
    private val maxWait: Duration = DEFAULT_MAX_WAIT,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
    /**
     * V1.9.232 -- root the attachment storage keys resolve against (the document storage root). `null` == no attachment storage: a
     * message WITH attachments then fails closed ([ATTACHMENT_INTEGRITY_ERROR_CLASS]) instead of going out without them.
     */
    private val attachmentStorageRoot: File? = null,
) {
    /** Wake-up signal only (V1.9.81): the queue itself is the database (`QUEUED` messages in `queued_at` order), so it can never be "full". */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        // Exactly ONE long-lived worker -- see class KDoc for why more would not help.
        //
        // **Review fix (finding #1, W-SuperMailer round 1)**: processMessage's own per-recipient
        // try/catch only guards the send loop BODY -- anything thrown by loadSendPlan (e.g. a
        // re-sanitize of oversized stored HTML, or a transient DB error), or by the final status
        // UPDATE, escapes processMessage entirely. Without this try/catch around the loop
        // itself, that exception would propagate out of the coroutine launched here and kill this
        // single long-lived worker for good -- SupervisorJob only isolates SIBLING coroutines, it
        // does not restart a failed child, and this scope launches exactly one worker. Every
        // enqueue() after that would keep succeeding while NOTHING is ever processed again, until the
        // next server restart. Catching Throwable (not just Exception) and rethrowing
        // CancellationException mirrors the exact discipline processMessage's own per-recipient catch
        // already documents.
        scope.launch {
            var lastMessageId: Uuid? = null
            // Starts idle: the first scan happens on the first wake-up (an enqueue, or recoverInterrupted() at server startup, which
            // is what resumes a send after a restart). A worker nobody ever woke never touches the queue.
            if (wake.receiveCatching().isClosed) return@launch
            while (isActive) {
                val messageId =
                    try {
                        nextQueuedMessage()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        logger.error { "Mailing worker could not read the send queue (${t::class.simpleName})" }
                        null
                    }
                if (messageId == null) {
                    lastMessageId = null
                    // Re-check periodically even without a wake-up: costs one cheap query and heals a missed signal.
                    if (wake.receiveCatching().isClosed) break
                    continue
                }
                if (messageId == lastMessageId) {
                    // Still QUEUED after a full pass (rows claimed by another process): do not spin.
                    delay(maxWait)
                }
                lastMessageId = messageId
                try {
                    processMessage(messageId)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    logger.error(t) {
                        "processMessage crashed outside its own per-recipient try/catch " +
                            "(unexpected ${t::class.simpleName}), marking message FAILED: messageId=$messageId"
                    }
                    runCatching {
                        transaction {
                            MailingMessageTable.update({ MailingMessageTable.id eq messageId }) {
                                it[status] = MailingMessageStatus.FAILED
                                it[sentAt] = DbClock.nowLocalDateTime()
                            }
                        }
                    }.onFailure { dbError ->
                        // Worst case (e.g. the DB itself is down): the message is left QUEUED --
                        // recoverInterrupted() on the next restart will still close it out.
                        logger.error(dbError) {
                            "Failed to mark message FAILED after a processMessage crash: messageId=$messageId"
                        }
                    }
                }
            }
        }
    }

    /**
     * Wakes the worker; the message itself is taken from the database (`status = QUEUED`, ordered by `queued_at`), so [messageId]
     * only documents the caller's intent. Never suspends. Since V1.9.81 the send queue is the table, not a bounded in-memory channel:
     * twenty messages in a row can no longer saturate anything. Throws [ConflictException] only when the worker has been shut down
     * (`MailingService.sendMailingMessage` then rolls its queuing back, exactly as before for a saturated queue).
     */
    fun enqueue(messageId: Uuid) {
        val result = wake.trySend(Unit)
        if (result.isClosed) {
            logger.error { "Mailing worker shut down, message NOT picked up: messageId=$messageId" }
            throw ConflictException(
                "Versand-Warteschlange ist nicht verfügbar -- die Nachricht bleibt in der Warteschlange, bitte in Kürze erneut versuchen.",
            )
        }
    }

    /** The oldest `QUEUED` message (FIFO by `queued_at`, rows without it last), or `null`. */
    private fun nextQueuedMessage(): Uuid? =
        transaction {
            MailingMessageTable
                .selectAll()
                .where { MailingMessageTable.status eq MailingMessageStatus.QUEUED }
                .orderBy(MailingMessageTable.queuedAt to SortOrder.ASC_NULLS_LAST, MailingMessageTable.id to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.get(MailingMessageTable.id)
        }

    /**
     * Processes exactly one message end to end: one recipient at a time, claim BEFORE send (at most once), within the hourly budget.
     * `internal`, not `private`, so `MailingDeliveryWorkerTest` can call it directly.
     *
     * Per recipient: (1) the global bulk pause, (2) a BULK budget slot, (3) the claim `PENDING -> claimed_at = now`, (4) render + send,
     * (5) the outcome. A claimed row that never got an outcome (crash) becomes `INTERRUPTED` on restart and is NEVER sent again.
     * Retryable: a clear SMTP 4xx or a connection failure before anything was sent -- re-queued after 5/15/60 minutes (at most 3
     * retries), with a global 10 minute bulk pause. NOT retried (-> `FAILED`): 5xx, authentication, and a timeout after the data
     * transfer began (outcome unknown) -- so "at most once" has no exception.
     */
    internal suspend fun processMessage(messageId: Uuid) {
        val plan = loadSendPlanWithRetry(messageId)
        if (plan == null) {
            logger.error { "processMessage: mailing message $messageId no longer exists, skipping" }
            return
        }
        // V1.9.232: never send a mail without the attachments it announces. Checked at EVERY start, including a resume after a restart.
        if (!attachmentsIntact(plan)) {
            failForAttachmentIntegrity(messageId)
            return
        }
        if (plan.dynamic && !resolveRecipientsWithRetry(messageId)) return

        while (true) {
            // A transient database error in any per-iteration call must not end a (possibly hours-long) send: log, wait, carry on.
            // Only the bounded steps below can leave state behind, and each of them is idempotent or conditional.
            try {
                val now = clock()
                val due = nextDueRow(messageId = messageId, now = now)
                if (due == null) {
                    val nextAt = earliestDeferral(messageId = messageId, now = now)
                    if (nextAt == null) {
                        // Nothing due, nothing deferred: a PENDING row that is still claimed has no owner (this is the only worker; its
                        // outcome could not be written) -> INTERRUPTED, exactly as recoverInterrupted() would, so the message can close.
                        interruptOrphanedClaims(messageId)
                        break
                    }
                    delay(waitFor(from = now, until = nextAt))
                    continue
                }
                val (deliveryLogId, memberId) = due

                // (1) + (2): bulk pause and budget -- SMTP only; LOG mode never touches either.
                var slotId: Uuid? = null
                if (mode == MailingDeliveryMode.SMTP) {
                    val paused = budget.bulkPausedUntil()
                    if (paused != null && paused > now) {
                        delay(waitFor(from = now, until = paused))
                        continue
                    }
                    val reservation = budget.reserveSlot(lane = MailLane.BULK, now = now)
                    val decision = reservation.decision
                    if (decision is BudgetDecision.WaitUntil) {
                        delay(waitFor(from = now, until = decision.at))
                        continue
                    }
                    slotId = reservation.slotId
                }

                // (3) claim: exactly one worker can win; a row erased meanwhile (Art. 17) or claimed elsewhere updates 0 rows.
                val attempt = claimRow(deliveryLogId = deliveryLogId, now = now)
                if (attempt == null) {
                    budget.release(slotId)
                    continue
                }

                processClaimedRow(plan = plan, messageId = messageId, deliveryLogId = deliveryLogId, memberId = memberId, attempt = attempt)
                delay(sendDelay)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logger.error { "Mailing send step failed (${t::class.simpleName}), retrying in $maxWait: messageId=$messageId" }
                delay(maxWait)
            }
        }

        try {
            finishMessageIfDone(messageId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The message stays QUEUED; the worker loop visits it again and closes it then.
            logger.error { "Could not close mailing message (${t::class.simpleName}), will retry: messageId=$messageId" }
        }
    }

    /** [loadSendPlan] with a few retries against a transient database error; a permanent failure (e.g. sanitize) is rethrown. */
    private suspend fun loadSendPlanWithRetry(messageId: Uuid): SendPlan? {
        var attempt = 1
        while (true) {
            try {
                return loadSendPlan(messageId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (attempt >= LOAD_PLAN_ATTEMPTS) throw t
                logger.warn { "Loading the send plan failed (${t::class.simpleName}), attempt $attempt: messageId=$messageId" }
                attempt++
                delay(maxWait)
            }
        }
    }

    /** [resolveRecipientsIfNeeded] with a few retries against a transient database error; a permanent failure is rethrown. */
    private suspend fun resolveRecipientsWithRetry(messageId: Uuid): Boolean {
        var attempt = 1
        while (true) {
            try {
                return resolveRecipientsIfNeeded(messageId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (attempt >= LOAD_PLAN_ATTEMPTS) throw t
                logger.warn { "Resolving the recipients failed (${t::class.simpleName}), attempt $attempt: messageId=$messageId" }
                attempt++
                delay(maxWait)
            }
        }
    }

    /**
     * V1.9.111 -- resolves the recipients of a message to a DYNAMIC list, ONCE, at send time. One transaction: lock the message row,
     * skip if it was resolved already (idempotent: a retry or a second pass never doubles the rows or the audit entry), parse the stored
     * rule, resolve through the one shared function, insert one `PENDING` row per recipient, stamp `recipients_resolved_at` and write ONE
     * audit entry with the COUNT. No external effect inside (Exposed may retry the block). More than [MailingHtmlPolicy.MAX_RECIPIENTS]
     * closes the message `FAILED` (the list may have grown since the board's confirmation). Returns `false` if the message was closed.
     * Logs only numbers and ids of the list/message, never a member id or an address.
     */
    internal fun resolveRecipientsIfNeeded(messageId: Uuid): Boolean =
        transaction {
            val message =
                MailingMessageTable
                    .selectAll()
                    .where { MailingMessageTable.id eq messageId }
                    .forUpdate()
                    .singleOrNull()
                    ?: return@transaction false
            if (message[MailingMessageTable.status] != MailingMessageStatus.QUEUED) return@transaction false
            if (message[MailingMessageTable.recipientsResolvedAt] != null) return@transaction true
            // V1.9.234: deliberately WITHOUT deleted_at filter -- a QUEUED message can never belong to a deleted list (deleteMailingList
            // refuses while a message is QUEUED, and sendMailingMessage locks the live list row before DRAFT -> QUEUED), and a SENT/FAILED
            // message keeps pointing at its archived list. Filtering here could only strand a QUEUED message.
            val list =
                MailingListTable.selectAll().where { MailingListTable.id eq message[MailingMessageTable.mailingListId] }.singleOrNull()
                    ?: return@transaction false
            if (list[MailingListTable.kind] != MailingListKind.DYNAMIC) return@transaction true
            val mandatory = list[MailingListTable.mandatory]
            val ruleJson = requireNotNull(list[MailingListTable.ruleJson]) { "DYNAMIC list without a rule" }
            val rule =
                try {
                    MemberRuleCodec.parse(text = ruleJson, context = RuleContext.MAILING)
                } catch (e: InvalidMemberRuleException) {
                    logger.error { "Stored rule of a mailing list is invalid (${e.reason}), message FAILED: messageId=$messageId" }
                    failUnresolved(messageId)
                    return@transaction false
                }
            val resolution =
                resolveDynamicRecipients(
                    rule = rule,
                    listId = list[MailingListTable.id],
                    mandatory = mandatory,
                    today = OrganizationTimeZone.today(),
                )
            val now = DbClock.nowLocalDateTime()
            if (resolution.recipientIds.size > MailingHtmlPolicy.MAX_RECIPIENTS) {
                logger.error {
                    "Dynamic mailing list resolved to ${resolution.recipientIds.size} recipients " +
                        "(max ${MailingHtmlPolicy.MAX_RECIPIENTS}), message FAILED: messageId=$messageId"
                }
                failUnresolved(messageId)
                return@transaction false
            }
            resolution.recipientIds.forEach { recipientId ->
                MailingDeliveryLogTable.insert {
                    it[MailingDeliveryLogTable.id] = Uuid.random()
                    it[MailingDeliveryLogTable.mailingMessageId] = messageId
                    it[MailingDeliveryLogTable.memberId] = recipientId
                    it[deliveredAt] = now
                    it[deliveryStatus] = DeliveryStatus.PENDING
                }
            }
            MailingMessageTable.update({ MailingMessageTable.id eq messageId }) { it[recipientsResolvedAt] = now }
            val sender = message[MailingMessageTable.sentBy]
            // Audit chain lock last. Actor: the sender of the message, with the role they hold now (null if they have no account).
            AuditLogRecorder.record(
                actorMemberId = sender,
                actorRole = currentRoleOf(sender),
                entityType = AuditEntityType.MAILING_MESSAGE,
                entityId = messageId,
                action = AuditAction.POST,
                after =
                    Json.encodeToString(
                        MailingDynamicSendSnapshot.serializer(),
                        MailingDynamicSendSnapshot(
                            listId = list[MailingListTable.id].toString(),
                            ruleJson = ruleJson,
                            recipientCount = resolution.recipientIds.size,
                            mandatory = mandatory,
                        ),
                    ),
                occurredAt = now,
            )
            logger.info { "Dynamic mailing recipients resolved: ${resolution.recipientIds.size} (messageId=$messageId)" }
            true
        }

    private fun failUnresolved(messageId: Uuid) {
        val now = DbClock.nowLocalDateTime()
        MailingMessageTable.update(
            { (MailingMessageTable.id eq messageId) and (MailingMessageTable.status eq MailingMessageStatus.QUEUED) },
        ) {
            it[status] = MailingMessageStatus.FAILED
            it[sentAt] = now
        }
    }

    private fun currentRoleOf(memberId: Uuid): AccountRole? {
        val roles =
            AccountTable
                .selectAll()
                .where { AccountTable.memberId eq memberId }
                .map { it[AccountTable.role] }
                .toSet()
        return listOf(AccountRole.ADMIN, AccountRole.BOARD, AccountRole.TREASURER, AccountRole.MEMBER).firstOrNull { it in roles }
    }

    /** `PENDING` rows of the message that still carry `claimed_at` and have no running owner become `INTERRUPTED` (never re-sent). */
    private fun interruptOrphanedClaims(messageId: Uuid) {
        val count =
            transaction {
                MailingDeliveryLogTable.update({
                    (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                        (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING) and
                        MailingDeliveryLogTable.claimedAt.isNotNull()
                }) {
                    it[deliveryStatus] = DeliveryStatus.INTERRUPTED
                    it[deliveredAt] = DbClock.nowLocalDateTime()
                }
            }
        if (count > 0) logger.warn { "$count claimed mailing row(s) without an outcome marked INTERRUPTED: messageId=$messageId" }
    }

    private suspend fun processClaimedRow(
        plan: SendPlan,
        messageId: Uuid,
        deliveryLogId: Uuid,
        memberId: Uuid,
        attempt: Int,
    ) {
        try {
            // Welle V1.9.15 -- ONE render per recipient: the tracking snapshot (consent at send
            // time) is decided and persisted here, in its own small transaction, BEFORE the mail
            // is rendered and sent. The nonce inside the URLs exists only in memory from here on.
            val recipient = prepareRecipient(plan = plan, deliveryLogId = deliveryLogId, memberId = memberId)
            val email = recipient.email
            val renderedMail =
                MailingMailRenderer.render(
                    subject = plan.subject,
                    content = plan.sanitized,
                    legacyBodyText = plan.legacyBodyText,
                    branding = branding,
                    tracking = recipient.tracking,
                    notice = if (plan.mandatory) MailingNotice.MANDATORY else MailingNotice.NONE,
                    dynamicList = plan.dynamic,
                )
            var retryAfter: kotlin.time.Duration? = null
            val outcomeStatus =
                if (recipient.leftTheList) {
                    // V1.9.111 -- left (or opted out of) a DYNAMIC list between resolution and this send: nothing is sent.
                    DeliveryStatus.SKIPPED_UNSUBSCRIBED
                } else if (email == null || !isValidMailboxAddress(email)) {
                    DeliveryStatus.SKIPPED_NO_ADDRESS
                } else if (mode == MailingDeliveryMode.LOG) {
                    // LOG mode: the full pipeline (sanitize/render) already ran above -- only
                    // the actual transport call is skipped, honestly reflected to the board via
                    // IMailingService.getMailingDeliveryMode()/the compose screen's caption.
                    DeliveryStatus.SENT
                } else {
                    when (
                        val outcome =
                            try {
                                withTimeout(perSendTimeout + attachmentSendAllowance(plan.attachmentBytes)) {
                                    transport.sendWithAttachments(
                                        to = email,
                                        subject = renderedMail.subject,
                                        plainTextBody = renderedMail.plainText,
                                        htmlBody = renderedMail.html,
                                        attachments = plan.mailAttachments,
                                    )
                                }
                            } catch (e: TimeoutCancellationException) {
                                // Timed out after the request may have gone out: outcome UNKNOWN. A mailing-list mail is never re-sent.
                                MailSendOutcome.Failed(
                                    sanitizedErrorMessage = "send timed out",
                                    kind = MailFailureKind.TRANSIENT,
                                    deliveryUncertain = true,
                                    errorClass = "TIMEOUT",
                                )
                            }
                    ) {
                        is MailSendOutcome.Sent -> DeliveryStatus.SENT
                        is MailSendOutcome.Skipped -> DeliveryStatus.FAILED
                        is MailSendOutcome.Failed -> {
                            val reply = outcome.smtpReplyCode
                            if (reply != null && reply in 400..499) {
                                // The provider asks us to slow down: hold the whole bulk lane back.
                                runCatching { budget.pauseBulk(clock().plusDuration(PROVIDER_PAUSE)) }
                            }
                            val retryable =
                                outcome.kind == MailFailureKind.TRANSIENT && !outcome.deliveryUncertain && attempt <= MAX_RETRIES
                            if (retryable) {
                                retryAfter = RETRY_BACKOFF[attempt - 1]
                                null
                            } else {
                                logger.warn { "Mailing delivery failed for good: errorClass=${outcome.errorClass} (messageId=$messageId)" }
                                DeliveryStatus.FAILED
                            }
                        }
                    }
                } ?: DeliveryStatus.PENDING
            if (outcomeStatus == DeliveryStatus.PENDING) {
                // Re-queue: release the claim, schedule the next attempt.
                val nextAt = clock().plusDuration(requireNotNull(retryAfter))
                transaction {
                    MailingDeliveryLogTable.update({
                        (MailingDeliveryLogTable.id eq deliveryLogId) and (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING)
                    }) {
                        it[claimedAt] = null
                        it[nextAttemptAt] = nextAt
                    }
                }
                logger.info { "Mailing delivery deferred (attempt $attempt): messageId=$messageId" }
                return
            }
            transaction {
                MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq deliveryLogId }) {
                    it[deliveryStatus] = outcomeStatus
                    it[deliveredAt] = DbClock.nowLocalDateTime()
                }
            }
            logger.info {
                "Mailing delivery ${if (email != null) {
                    maskEmailForLogging(
                        email,
                    )
                } else {
                    "?"
                }}: $outcomeStatus (messageId=$messageId)"
            }
        } catch (c: CancellationException) {
            if (c is TimeoutCancellationException) throw c
            // Shutdown mid-send: the row stays PENDING + claimed; recoverInterrupted() makes it INTERRUPTED -- never re-sent.
            throw c
        } catch (t: Throwable) {
            // Catches Throwable, not just Exception -- this is a long-lived worker loop (see
            // MailDispatcher.sendOne KDoc for the identical reasoning); one recipient's failure
            // must never take the whole worker coroutine down.
            logger.error { "Mailing delivery FAILED (unexpected ${t::class.simpleName}): messageId=$messageId" }
            try {
                transaction {
                    MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq deliveryLogId }) {
                        it[deliveryStatus] = DeliveryStatus.FAILED
                        it[deliveredAt] = DbClock.nowLocalDateTime()
                    }
                }
            } catch (inner: CancellationException) {
                throw inner
            } catch (inner: Throwable) {
                // Database down as well: the row stays PENDING + claimed; processMessage turns it INTERRUPTED once nothing else is due.
                logger.error { "Could not record the failure (${inner::class.simpleName}), row stays claimed: messageId=$messageId" }
            }
        }
    }

    /** How long to sleep until [until]: at least 10 ms, at most [maxWait]. */
    private fun waitFor(
        from: LocalDateTime,
        until: LocalDateTime,
    ): Duration =
        from
            .secondsUntil(until)
            .coerceAtLeast(0)
            .seconds
            .coerceIn(MIN_WAIT, maxWait)

    /** One `PENDING`, unclaimed row that is due, or `null`. */
    private fun nextDueRow(
        messageId: Uuid,
        now: LocalDateTime,
    ): PendingRow? =
        transaction {
            MailingDeliveryLogTable
                .selectAll()
                .where {
                    (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                        (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING) and
                        MailingDeliveryLogTable.claimedAt.isNull() and
                        (MailingDeliveryLogTable.nextAttemptAt.isNull() or (MailingDeliveryLogTable.nextAttemptAt lessEq now))
                }.orderBy(MailingDeliveryLogTable.id to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.let { PendingRow(deliveryLogId = it[MailingDeliveryLogTable.id], memberId = it[MailingDeliveryLogTable.memberId]) }
        }

    /** The earliest future `next_attempt_at` among the message's unclaimed `PENDING` rows, or `null` if none is deferred. */
    private fun earliestDeferral(
        messageId: Uuid,
        now: LocalDateTime,
    ): LocalDateTime? =
        transaction {
            MailingDeliveryLogTable
                .selectAll()
                .where {
                    (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                        (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING) and
                        MailingDeliveryLogTable.claimedAt.isNull() and
                        MailingDeliveryLogTable.nextAttemptAt.isNotNull() and
                        (MailingDeliveryLogTable.nextAttemptAt greater now)
                }.orderBy(MailingDeliveryLogTable.nextAttemptAt to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.get(MailingDeliveryLogTable.nextAttemptAt)
        }

    /** Atomic claim; returns the attempt number now running (1-based), or `null` if the row was not claimable. */
    private fun claimRow(
        deliveryLogId: Uuid,
        now: LocalDateTime,
    ): Int? =
        transaction {
            val current =
                MailingDeliveryLogTable
                    .selectAll()
                    .where { MailingDeliveryLogTable.id eq deliveryLogId }
                    .singleOrNull()
                    ?.get(MailingDeliveryLogTable.attemptCount) ?: return@transaction null
            val updated =
                MailingDeliveryLogTable.update({
                    (MailingDeliveryLogTable.id eq deliveryLogId) and
                        (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING) and
                        MailingDeliveryLogTable.claimedAt.isNull()
                }) {
                    it[claimedAt] = now
                    it[attemptCount] = current + 1
                    it[nextAttemptAt] = null
                }
            if (updated == 1) current + 1 else null
        }

    /**
     * Closes the message once no `PENDING` row is left: `SENT` if at least one recipient was reached, else `FAILED` (`INTERRUPTED` and
     * `FAILED` rows do not count as reached). Conditional on `status = QUEUED`. Returns whether the message was closed.
     */
    private fun finishMessageIfDone(messageId: Uuid): Boolean =
        transaction {
            // V1.9.111: a message to a DYNAMIC list whose recipients are not resolved yet has no rows -- that is NOT "done".
            if (awaitsResolution(messageId)) return@transaction false
            val stillPending =
                MailingDeliveryLogTable
                    .selectAll()
                    .where {
                        (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                            (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING)
                    }.count() > 0
            if (stillPending) return@transaction false
            val anySent =
                MailingDeliveryLogTable
                    .selectAll()
                    .where {
                        (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                            (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.SENT)
                    }.count() > 0
            MailingMessageTable.update(
                { (MailingMessageTable.id eq messageId) and (MailingMessageTable.status eq MailingMessageStatus.QUEUED) },
            ) {
                it[status] = if (anySent) MailingMessageStatus.SENT else MailingMessageStatus.FAILED
                it[sentAt] = DbClock.nowLocalDateTime()
            } == 1
        }

    /** Must run inside a transaction: `true` for a DYNAMIC-list message whose recipients were not resolved yet. */
    private fun awaitsResolution(messageId: Uuid): Boolean {
        val message = MailingMessageTable.selectAll().where { MailingMessageTable.id eq messageId }.singleOrNull() ?: return false
        if (message[MailingMessageTable.recipientsResolvedAt] != null) return false
        return MailingListTable
            .selectAll()
            .where { MailingListTable.id eq message[MailingMessageTable.mailingListId] }
            .singleOrNull()
            ?.get(MailingListTable.kind) == MailingListKind.DYNAMIC
    }

    private data class PendingRow(
        val deliveryLogId: Uuid,
        val memberId: Uuid,
    )

    /** Message-level send plan -- everything that does NOT depend on the individual recipient. */
    private data class SendPlan(
        val subject: String,
        val sanitized: SanitizedMailingHtml?,
        val legacyBodyText: String,
        val listId: Uuid,
        val linkIndexByUrl: Map<String, Int>,
        /** V1.9.111 -- the list is DYNAMIC (recipients resolved at send time, never tracked). */
        val dynamic: Boolean,
        /** V1.9.111 -- mandatory list: visible notice in subject/banner/footer, no opt-out. */
        val mandatory: Boolean,
        /** V1.9.232 -- the stored attachments in upload order (empty for a plain message). */
        val attachments: List<StoredMailingAttachment> = emptyList(),
        /** V1.9.232 -- the same attachments as transport objects (resolved against the storage root). */
        val mailAttachments: List<MailAttachment> = emptyList(),
    ) {
        val attachmentBytes: Long get() = attachments.sumOf { it.sizeBytes }
    }

    private data class RecipientContext(
        val email: String?,
        val tracking: MailingMailRenderer.RecipientTracking?,
        /** V1.9.111 -- DYNAMIC list only: the member is no longer addressable (left, anonymised, deceased, opted out). */
        val leftTheList: Boolean = false,
    )

    private fun loadSendPlan(messageId: Uuid): SendPlan? =
        transaction {
            val message =
                MailingMessageTable.selectAll().where { MailingMessageTable.id eq messageId }.singleOrNull() ?: return@transaction null
            val sanitized = message[MailingMessageTable.bodyHtml]?.let { MailingHtmlSanitizer.sanitize(it) }
            val stored = MailingAttachmentStore.sendableForMessage(messageId)
            val list =
                MailingListTable
                    .selectAll()
                    .where { MailingListTable.id eq message[MailingMessageTable.mailingListId] }
                    .singleOrNull()
            SendPlan(
                subject = message[MailingMessageTable.subject],
                sanitized = sanitized,
                legacyBodyText = message[MailingMessageTable.bodyText],
                listId = message[MailingMessageTable.mailingListId],
                linkIndexByUrl = if (sanitized != null) MailingTrackingData.linkIndexByUrl(messageId) else emptyMap(),
                dynamic = list?.get(MailingListTable.kind) == MailingListKind.DYNAMIC,
                mandatory = list?.get(MailingListTable.mandatory) == true,
                attachments = stored,
                mailAttachments = stored.mapNotNull { toMailAttachment(it) },
            )
        }

    private fun toMailAttachment(stored: StoredMailingAttachment): MailAttachment? {
        val file = resolveAttachmentFile(stored) ?: return null
        val contentType = stored.textCharset?.let { "${stored.mimeType}; charset=$it" } ?: stored.mimeType
        return MailAttachment(fileName = stored.fileName, contentType = contentType, file = file, sizeBytes = stored.sizeBytes)
    }

    /** The storage key against the root; `null` if there is no root or the key would leave it (a hand-edited row). */
    private fun resolveAttachmentFile(stored: StoredMailingAttachment): File? {
        val root = attachmentStorageRoot?.canonicalFile ?: return null
        if (stored.storageKey.isBlank() || File(stored.storageKey).isAbsolute) return null
        val candidate = root.resolve(stored.storageKey).canonicalFile
        return if (candidate.path.startsWith(root.path + File.separator)) candidate else null
    }

    /**
     * V1.9.232 -- every attachment must still exist with the recorded size and SHA-256 and must not have been removed by retention.
     * Runs off the event loop (hashing up to 20 MiB). A plan without attachments is trivially intact.
     */
    private suspend fun attachmentsIntact(plan: SendPlan): Boolean {
        if (plan.attachments.isEmpty()) return true
        return withContext(Dispatchers.IO) {
            plan.attachments.all { stored ->
                val file = resolveAttachmentFile(stored)
                stored.fileDeletedAt == null &&
                    file != null &&
                    file.isFile &&
                    file.length() == stored.sizeBytes &&
                    sha256Of(file) == stored.sha256
            }
        }
    }

    private fun sha256Of(file: File): String? =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (_: Exception) {
            null
        }

    /** Closes a message whose attachments are missing/tampered: no mail is sent, the open rows and the message become `FAILED`. */
    private fun failForAttachmentIntegrity(messageId: Uuid) {
        val now = DbClock.nowLocalDateTime()
        transaction {
            MailingDeliveryLogTable.update({
                (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                    (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING)
            }) {
                it[deliveryStatus] = DeliveryStatus.FAILED
                it[deliveredAt] = now
            }
            MailingMessageTable.update(
                { (MailingMessageTable.id eq messageId) and (MailingMessageTable.status eq MailingMessageStatus.QUEUED) },
            ) {
                it[status] = MailingMessageStatus.FAILED
                it[sentAt] = now
            }
        }
        logger.error {
            "Mailing message not sent, attachments missing or changed: errorClass=$ATTACHMENT_INTEGRITY_ERROR_CLASS messageId=$messageId"
        }
    }

    /**
     * Reads the recipient's address and CURRENT tracking consent, and -- when at least one consent
     * exists, the message is an HTML message and the address is valid -- mints a token, persists
     * `tracking_token_hash` plus the `open_tracked`/`click_tracked` SNAPSHOT on the delivery row and
     * builds the per-recipient [MailingMailRenderer.RecipientTracking]. Plain-text messages are
     * never tracked (no HTML to carry a pixel or links).
     */
    private fun prepareRecipient(
        plan: SendPlan,
        deliveryLogId: Uuid,
        memberId: Uuid,
    ): RecipientContext =
        transaction {
            val memberRow = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
            val email = memberRow?.get(MemberTable.email)
            if (plan.dynamic) {
                // V1.9.111 -- re-check JUST before sending (a send can last hours): still ACTIVE, not anonymised, not deceased, and
                // not opted out unless the list is mandatory. Never tracked (no token, no pixel, no rewritten links).
                val stillAddressable =
                    memberRow != null &&
                        memberRow[MemberTable.status] == MemberStatus.ACTIVE &&
                        memberRow[MemberTable.anonymizedAt] == null &&
                        memberRow[MemberTable.dateOfDeath] == null &&
                        (
                            plan.mandatory ||
                                MailingListOptOutTable
                                    .selectAll()
                                    .where {
                                        (MailingListOptOutTable.mailingListId eq plan.listId) and
                                            (MailingListOptOutTable.memberId eq memberId)
                                    }.count() == 0L
                        )
                return@transaction RecipientContext(email = email, tracking = null, leftTheList = !stillAddressable)
            }
            if (email == null || !isValidMailboxAddress(email) || plan.sanitized == null) {
                return@transaction RecipientContext(email = email, tracking = null)
            }
            val subscription =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq plan.listId) and
                            (MailingListSubscriptionTable.memberId eq memberId)
                    }.singleOrNull()
            val clickTracked = subscription?.get(MailingListSubscriptionTable.clickTrackingConsentedAt) != null
            val openTracked = subscription?.get(MailingListSubscriptionTable.openTrackingConsentedAt) != null
            if (!clickTracked && !openTracked) return@transaction RecipientContext(email = email, tracking = null)

            val issued = trackingToken.issue()
            MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq deliveryLogId }) {
                it[trackingTokenHash] = issued.hashHex
                it[MailingDeliveryLogTable.openTracked] = openTracked
                it[MailingDeliveryLogTable.clickTracked] = clickTracked
            }
            RecipientContext(
                email = email,
                tracking =
                    MailingMailRenderer.RecipientTracking(
                        clickUrlFor =
                            if (clickTracked) {
                                { index -> "$baseUrl/m/c/${trackingToken.clickToken(nonce = issued.nonce, linkIndex = index)}" }
                            } else {
                                null
                            },
                        linkIndexByUrl = plan.linkIndexByUrl,
                        pixelUrl = if (openTracked) "$baseUrl/m/o/${trackingToken.openToken(issued.nonce)}.gif" else null,
                    ),
            )
        }

    /**
     * Called once at startup, BEFORE the server accepts any request (assumes ONE server instance, like the webhook poller):
     * - a `PENDING` row that carries `claimed_at` (the worker had taken it, the outcome is unknown) becomes `INTERRUPTED` and is
     *   NEVER sent again -- the mail may already have reached the relay;
     * - a `PENDING` row WITHOUT a claim stays `PENDING` and is delivered after the restart exactly once (a restart no longer
     *   loses a mailing list send); `QUEUED` messages stay `QUEUED`;
     * - a `QUEUED` message whose rows are all in a final state is closed (`SENT` if at least one recipient was reached, else `FAILED`).
     * Afterwards the worker is woken once.
     */
    fun recoverInterrupted() {
        transaction {
            val now = DbClock.nowLocalDateTime()
            val interrupted =
                MailingDeliveryLogTable.update({
                    (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING) and MailingDeliveryLogTable.claimedAt.isNotNull()
                }) {
                    it[deliveryStatus] = DeliveryStatus.INTERRUPTED
                    it[deliveredAt] = now
                }
            val queuedMessageIds =
                MailingMessageTable
                    .selectAll()
                    .where { MailingMessageTable.status eq MailingMessageStatus.QUEUED }
                    .map { it[MailingMessageTable.id] }
            var closed = 0
            queuedMessageIds.forEach { messageId ->
                // V1.9.111: an unresolved DYNAMIC message has no rows yet -- it resumes (the worker resolves it), it is not "empty".
                if (awaitsResolution(messageId)) return@forEach
                val stillPending =
                    MailingDeliveryLogTable
                        .selectAll()
                        .where {
                            (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                                (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING)
                        }.count() > 0
                if (!stillPending) {
                    val hasSent =
                        MailingDeliveryLogTable
                            .selectAll()
                            .where {
                                (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                                    (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.SENT)
                            }.count() > 0
                    MailingMessageTable.update({ MailingMessageTable.id eq messageId }) {
                        it[status] = if (hasSent) MailingMessageStatus.SENT else MailingMessageStatus.FAILED
                        it[sentAt] = now
                    }
                    closed++
                }
            }
            if (interrupted > 0 || queuedMessageIds.isNotEmpty()) {
                logger.warn {
                    "MailingDeliveryWorker.recoverInterrupted: $interrupted claimed row(s) marked INTERRUPTED (never re-sent), " +
                        "$closed message(s) closed, ${queuedMessageIds.size - closed} message(s) resume after the restart"
                }
            }
        }
        wake.trySend(Unit)
    }

    /** Same lifecycle contract as [network.lapis.cloud.server.mail.MailDispatcher.shutdown]. */
    fun shutdown() {
        wake.close()
        scope.cancel()
    }

    companion object {
        val DEFAULT_PER_SEND_TIMEOUT: Duration = 60.seconds
        val DEFAULT_SEND_DELAY: Duration = 250.milliseconds
        val DEFAULT_MAX_WAIT: Duration = 60.seconds
        private val MIN_WAIT: Duration = 10.milliseconds
        private const val LOAD_PLAN_ATTEMPTS = 3

        /** Closed vocabulary token of the log line written when a message is refused because of its attachments. */
        const val ATTACHMENT_INTEGRITY_ERROR_CLASS = "ATTACHMENT_INTEGRITY"

        private const val ASSUMED_UPLOAD_BYTES_PER_SECOND = 256L * 1024
        private val MAX_ATTACHMENT_ALLOWANCE: Duration = 10.minutes

        /**
         * V1.9.232 -- extra time per send for the attachment bytes: the Base64 size (x4/3) at an assumed 256 KiB/s, at most 10 minutes.
         * Without it a 28 MB mail would hit the 60 second per-send timeout, which counts as `deliveryUncertain` (never retried, FAILED).
         */
        internal fun attachmentSendAllowance(attachmentBytes: Long): Duration {
            if (attachmentBytes <= 0L) return Duration.ZERO
            val encodedBytes = attachmentBytes / 3 * 4 + 4
            val seconds = encodedBytes / ASSUMED_UPLOAD_BYTES_PER_SECOND + 1
            return minOf(seconds.seconds, MAX_ATTACHMENT_ALLOWANCE)
        }

        /** Retries after a clear 4xx / connection failure, waited before retry N+1 (N = attempts so far). At most [MAX_RETRIES] retries. */
        val RETRY_BACKOFF: List<Duration> = listOf(5.minutes, 15.minutes, 60.minutes)
        const val MAX_RETRIES = 3

        /** Global bulk pause after a provider 4xx reply. */
        val PROVIDER_PAUSE: Duration = 10.minutes
    }
}
