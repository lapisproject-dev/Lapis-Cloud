package network.lapis.cloud.server.mail.newsletter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.mail.maskEmailForLogging
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
 * relay), and a queue of MESSAGE ids (one row = one whole mailing-list send), not individual mails.
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
 * silently losing which recipients were already handled. There is deliberately NO automatic
 * re-send after a restart ([recoverInterrupted] only marks leftover `PENDING` rows `FAILED` and
 * closes out any still-`QUEUED` message) -- re-trying an interrupted bulk send risks mailing real
 * members twice, which this wave treats as strictly worse than a manual re-check by the board.
 *
 * **D3 (plan) -- recipient eligibility is decided ONCE, at queuing time**, by
 * `MailingService.sendMailingMessage` (active subscription, `MemberStatus.ACTIVE`, not anonymized,
 * not deceased) -- this worker only re-validates the mailbox ADDRESS shape per recipient (an
 * address can theoretically change between queuing and send, however small that window), never the
 * membership eligibility itself.
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
) {
    private val queue = Channel<Uuid>(capacity = QUEUE_CAPACITY)

    init {
        // Exactly ONE long-lived worker -- see class KDoc for why more would not help.
        //
        // **Review fix (finding #1, W-SuperMailer round 1)**: processMessage's own per-recipient
        // try/catch only guards the send loop BODY -- anything thrown by loadSendPlan (e.g. a
        // re-sanitize of oversized stored HTML, or a transient DB error), or by the final status
        // UPDATE, escapes processMessage entirely. Without this try/catch around the `for` loop
        // itself, that exception would propagate out of the coroutine launched here and kill this
        // single long-lived worker for good -- SupervisorJob only isolates SIBLING coroutines, it
        // does not restart a failed child, and this scope launches exactly one worker. Every
        // enqueue() after that would keep succeeding (trySend into the still-open channel) while
        // NOTHING is ever processed again, until the next server restart. Catching Throwable (not
        // just Exception) and rethrowing CancellationException mirrors the exact discipline
        // processMessage's own per-recipient catch already documents.
        scope.launch {
            for (messageId in queue) {
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
     * Hands [messageId] to the queue. Never suspends (`trySend`, same idiom as
     * [network.lapis.cloud.server.mail.MailDispatcher.enqueue]). Unlike that dispatcher (which
     * silently drops a saturated single transactional mail), a saturated bulk-send queue throws
     * [network.lapis.cloud.shared.rpc.ConflictException] back to the caller -- `MailingService
     * .sendMailingMessage` has ALREADY committed the `QUEUED` transition and the `PENDING` rows by
     * the time this is called, so the caller must know the send did NOT actually get picked up (the
     * message is left correctly `QUEUED` for a later manual retry, never silently lost the way a
     * dropped transactional mail would be).
     */
    fun enqueue(messageId: Uuid) {
        val result = queue.trySend(messageId)
        if (result.isFailure) {
            logger.error { "Mailing send queue saturated, message NOT picked up: messageId=$messageId" }
            throw ConflictException(
                "Versand-Warteschlange ist ausgelastet -- die Nachricht bleibt in der Warteschlange, bitte in Kürze erneut versuchen.",
            )
        }
    }

    /**
     * Processes exactly one message end to end. `internal`, not `private`, so
     * `MailingDeliveryWorkerTest` can call it directly without going through the [queue]/[scope]
     * indirection.
     */
    internal suspend fun processMessage(messageId: Uuid) {
        val plan = loadSendPlan(messageId)
        if (plan == null) {
            logger.error { "processMessage: mailing message $messageId no longer exists, skipping" }
            return
        }

        var anySent = false
        for ((deliveryLogId, memberId) in plan.pendingRows) {
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
                    )
                val outcomeStatus =
                    if (email == null || !isValidMailboxAddress(email)) {
                        DeliveryStatus.SKIPPED_NO_ADDRESS
                    } else if (mode == MailingDeliveryMode.LOG) {
                        // LOG mode: the full pipeline (sanitize/render) already ran above -- only
                        // the actual transport call is skipped, honestly reflected to the board via
                        // IMailingService.getMailingDeliveryMode()/the compose screen's caption.
                        DeliveryStatus.SENT
                    } else {
                        when (
                            val outcome =
                                withTimeout(perSendTimeout) {
                                    transport.send(
                                        to = email,
                                        subject = renderedMail.subject,
                                        plainTextBody = renderedMail.plainText,
                                        htmlBody = renderedMail.html,
                                    )
                                }
                        ) {
                            is MailSendOutcome.Sent -> DeliveryStatus.SENT
                            is MailSendOutcome.Skipped -> DeliveryStatus.FAILED
                            is MailSendOutcome.Failed -> DeliveryStatus.FAILED
                        }
                    }
                if (outcomeStatus == DeliveryStatus.SENT) anySent = true
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
            } catch (t: Throwable) {
                // Catches Throwable, not just Exception -- this is a long-lived worker loop (see
                // MailDispatcher.sendOne KDoc for the identical reasoning); one recipient's failure
                // must never take the whole worker coroutine down.
                logger.error { "Mailing delivery FAILED (unexpected ${t::class.simpleName}): messageId=$messageId" }
                transaction {
                    MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq deliveryLogId }) {
                        it[deliveryStatus] = DeliveryStatus.FAILED
                        it[deliveredAt] = DbClock.nowLocalDateTime()
                    }
                }
            }
            delay(sendDelay)
        }

        transaction {
            MailingMessageTable.update({ MailingMessageTable.id eq messageId }) {
                it[status] = if (anySent) MailingMessageStatus.SENT else MailingMessageStatus.FAILED
                it[sentAt] = DbClock.nowLocalDateTime()
            }
        }
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
        val pendingRows: List<PendingRow>,
    )

    private data class RecipientContext(
        val email: String?,
        val tracking: MailingMailRenderer.RecipientTracking?,
    )

    private fun loadSendPlan(messageId: Uuid): SendPlan? =
        transaction {
            val message =
                MailingMessageTable.selectAll().where { MailingMessageTable.id eq messageId }.singleOrNull() ?: return@transaction null
            val sanitized = message[MailingMessageTable.bodyHtml]?.let { MailingHtmlSanitizer.sanitize(it) }
            val pending =
                MailingDeliveryLogTable
                    .selectAll()
                    .where {
                        (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                            (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING)
                    }.map { row ->
                        PendingRow(deliveryLogId = row[MailingDeliveryLogTable.id], memberId = row[MailingDeliveryLogTable.memberId])
                    }
            SendPlan(
                subject = message[MailingMessageTable.subject],
                sanitized = sanitized,
                legacyBodyText = message[MailingMessageTable.bodyText],
                listId = message[MailingMessageTable.mailingListId],
                linkIndexByUrl = if (sanitized != null) MailingTrackingData.linkIndexByUrl(messageId) else emptyMap(),
                pendingRows = pending,
            )
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
            val email =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq memberId }
                    .singleOrNull()
                    ?.get(MemberTable.email)
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
     * D2 (plan) -- called once at startup, BEFORE the server accepts any request. Every leftover
     * `PENDING` delivery-log row (an interrupted send) becomes `FAILED`; every still-`QUEUED`
     * message is closed out as `SENT` (at least one recipient row is `SENT`) or `FAILED`
     * (otherwise) -- see class KDoc for why there is no automatic re-send.
     */
    fun recoverInterrupted() {
        transaction {
            MailingDeliveryLogTable.update({ MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING }) {
                it[deliveryStatus] = DeliveryStatus.FAILED
                it[deliveredAt] = DbClock.nowLocalDateTime()
            }
            val interruptedMessageIds =
                MailingMessageTable
                    .selectAll()
                    .where { MailingMessageTable.status eq MailingMessageStatus.QUEUED }
                    .map { it[MailingMessageTable.id] }
            interruptedMessageIds.forEach { messageId ->
                val hasSent =
                    MailingDeliveryLogTable
                        .selectAll()
                        .where {
                            (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                                (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.SENT)
                        }.count() > 0
                MailingMessageTable.update({ MailingMessageTable.id eq messageId }) {
                    it[status] = if (hasSent) MailingMessageStatus.SENT else MailingMessageStatus.FAILED
                    it[sentAt] = DbClock.nowLocalDateTime()
                }
            }
            if (interruptedMessageIds.isNotEmpty()) {
                logger.warn {
                    "MailingDeliveryWorker.recoverInterrupted: closed out ${interruptedMessageIds.size} interrupted message(s) after restart"
                }
            }
        }
    }

    /** Same lifecycle contract as [network.lapis.cloud.server.mail.MailDispatcher.shutdown]. */
    fun shutdown() {
        queue.close()
        scope.cancel()
    }

    companion object {
        const val QUEUE_CAPACITY = 16
        val DEFAULT_PER_SEND_TIMEOUT: Duration = 60.seconds
        val DEFAULT_SEND_DELAY: Duration = 250.milliseconds
    }
}
