package network.lapis.cloud.server.mail.newsletter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** Records every [send] call (including the rendered HTML body); [outcomeFor] lets a test script a per-recipient outcome. */
private class RecordingMailTransport(
    private val outcomeFor: (to: String) -> MailSendOutcome = { MailSendOutcome.Sent },
) : MailTransport {
    val sentTo = mutableListOf<String>()
    val sentHtmlBodies = mutableListOf<String>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        sentTo += to
        sentHtmlBodies += htmlBody
        return outcomeFor(to)
    }
}

/**
 * A transport whose [send] call signals [started] and then suspends on [gate] until the test
 * completes it -- used to hold the worker's single coroutine busy so a test can deterministically
 * saturate its bounded queue.
 */
private class GateMailTransport(
    private val started: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : MailTransport {
    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        started.complete(Unit)
        gate.await()
        return MailSendOutcome.Sent
    }
}

/**
 * Welle V1.9.7 "SuperMailer" -- exercises [MailingDeliveryWorker.processMessage] directly (no
 * [MailingDeliveryWorker.enqueue]/channel indirection needed, per that method's own KDoc) against
 * the real H2-backed [DatabaseConfig], mirroring the house style established by
 * `MemberAnniversaryServiceTest`/`PublicRankingConsentServiceTest` (own fixtures, direct table
 * inserts, no real-wall-clock dependence).
 */
class MailingDeliveryWorkerTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdListIds = mutableListOf<Uuid>()
        val createdMessageIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                createdMessageIds.forEach { messageId ->
                    MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.mailingMessageId eq messageId }
                }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList createdMessageIds }
                MailingListTable.deleteWhere { MailingListTable.id inList createdListIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Worker-Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            createdMemberIds += id
            return id
        }

        fun createList(): Uuid {
            val creator = createMember(email = "creator-${Uuid.random()}@example.org")
            val id = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[MailingListTable.id] = id
                    it[name] = "Worker-Testliste"
                    it[description] = null
                    it[createdBy] = creator
                }
            }
            createdListIds += id
            return id
        }

        fun createMessage(
            listId: Uuid,
            sentBy: Uuid,
            bodyText: String = "Hallo Welt.",
            bodyHtml: String? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MailingMessageTable.insert {
                    it[MailingMessageTable.id] = id
                    it[mailingListId] = listId
                    it[subject] = "Testbetreff"
                    it[MailingMessageTable.bodyText] = bodyText
                    it[MailingMessageTable.bodyHtml] = bodyHtml
                    it[MailingMessageTable.sentBy] = sentBy
                    it[status] = MailingMessageStatus.QUEUED
                }
            }
            createdMessageIds += id
            return id
        }

        fun insertPending(
            messageId: Uuid,
            memberId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MailingDeliveryLogTable.insert {
                    it[MailingDeliveryLogTable.id] = id
                    it[mailingMessageId] = messageId
                    it[MailingDeliveryLogTable.memberId] = memberId
                    it[deliveredAt] = DbClock.nowLocalDateTime()
                    it[deliveryStatus] = DeliveryStatus.PENDING
                }
            }
            return id
        }

        fun deliveryStatusOf(deliveryLogId: Uuid): DeliveryStatus =
            transaction {
                MailingDeliveryLogTable
                    .selectAll()
                    .where {
                        MailingDeliveryLogTable.id eq deliveryLogId
                    }.single()[MailingDeliveryLogTable.deliveryStatus]
            }

        fun messageStatusOf(messageId: Uuid): MailingMessageStatus =
            transaction {
                MailingMessageTable.selectAll().where { MailingMessageTable.id eq messageId }.single()[MailingMessageTable.status]
            }

        fun worker(
            transport: MailTransport,
            mode: MailingDeliveryMode,
        ) = MailingDeliveryWorker(
            transport = transport,
            branding = MailBranding.notConfigured(),
            mode = mode,
            trackingToken = testTrackingToken(),
            baseUrl = TEST_TRACKING_BASE_URL,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sendDelay = 0.milliseconds,
        )

        test("LOG mode: transport.send is never called, delivery rows and the message end SENT") {
            val listId = createList()
            val recipient = createMember(email = "log-mode-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = recipient)
            val deliveryId = insertPending(messageId = messageId, memberId = recipient)
            val transport = RecordingMailTransport()

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.LOG).processMessage(messageId) }

            transport.sentTo shouldBe emptyList()
            deliveryStatusOf(deliveryId) shouldBe DeliveryStatus.SENT
            messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
        }

        test("SMTP mode: exactly one send call per valid recipient, outcome mapped to delivery status") {
            val listId = createList()
            val okRecipient = createMember(email = "smtp-ok-${Uuid.random()}@example.org")
            val failRecipient = createMember(email = "smtp-fail-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = okRecipient)
            val okDeliveryId = insertPending(messageId = messageId, memberId = okRecipient)
            val failDeliveryId = insertPending(messageId = messageId, memberId = failRecipient)

            val transport =
                RecordingMailTransport(outcomeFor = { to ->
                    if (to.startsWith("smtp-fail")) MailSendOutcome.Failed("boom") else MailSendOutcome.Sent
                })

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

            transport.sentTo.size shouldBe 2
            deliveryStatusOf(okDeliveryId) shouldBe DeliveryStatus.SENT
            deliveryStatusOf(failDeliveryId) shouldBe DeliveryStatus.FAILED
            // At least one recipient SENT -> message SENT overall.
            messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
        }

        test("SMTP mode: a message where every recipient fails ends FAILED") {
            val listId = createList()
            val recipient = createMember(email = "smtp-fail-only-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = recipient)
            val deliveryId = insertPending(messageId = messageId, memberId = recipient)
            val transport = RecordingMailTransport(outcomeFor = { MailSendOutcome.Failed("boom") })

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

            deliveryStatusOf(deliveryId) shouldBe DeliveryStatus.FAILED
            messageStatusOf(messageId) shouldBe MailingMessageStatus.FAILED
        }

        test("an invalid mailbox address is skipped, never handed to the transport") {
            val listId = createList()
            val invalidRecipient = createMember(email = "not-a-valid-address")
            val messageId = createMessage(listId = listId, sentBy = invalidRecipient)
            val deliveryId = insertPending(messageId = messageId, memberId = invalidRecipient)
            val transport = RecordingMailTransport()

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

            transport.sentTo shouldBe emptyList()
            deliveryStatusOf(deliveryId) shouldBe DeliveryStatus.SKIPPED_NO_ADDRESS
        }

        test("HTML content is re-sanitized from stored bodyHtml before rendering (defense in depth)") {
            val listId = createList()
            val recipient = createMember(email = "html-${Uuid.random()}@example.org")
            // Review fix (finding #3g, W-SuperMailer round 1): the previous version of this test
            // stored ALREADY-clean HTML and asserted only the message's final status, so it would
            // have passed even if loadSendPlan never re-sanitized anything at all -- it did not
            // prove what its name claims. Storing content that bypasses the normal
            // createDraftMessageHtml pre-save sanitize (a raw `<script>`/`<b>` landing in bodyHtml
            // some other way -- a tampered row, a future code path that forgets to sanitize) and
            // asserting on the actual bytes handed to the transport is the only way to show the
            // worker's OWN re-sanitize pass, not merely the write path's, is what keeps this safe.
            val messageId =
                createMessage(
                    listId = listId,
                    sentBy = recipient,
                    bodyHtml = "<p>Hallo <script>alert('xss')</script><b>Welt</b></p>",
                )
            insertPending(messageId = messageId, memberId = recipient)
            val transport = RecordingMailTransport()

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

            val sentHtml = transport.sentHtmlBodies.single()
            sentHtml shouldNotContain "<script"
            sentHtml shouldNotContain "alert("
            sentHtml shouldNotContain "<b>"
            sentHtml shouldContain "<strong>Welt</strong>" // <b> -> <strong>, only a re-sanitize pass does this
            messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
        }

        // ── Review finding #3 (test coverage), W-SuperMailer round 1 ──────────────────────────────

        test("the real queue/worker loop (enqueue -> init coroutine -> processMessage) delivers a message") {
            val listId = createList()
            val recipient = createMember(email = "queue-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = recipient)
            insertPending(messageId = messageId, memberId = recipient)
            val transport = RecordingMailTransport()

            val realWorker = worker(transport = transport, mode = MailingDeliveryMode.LOG)
            try {
                realWorker.enqueue(messageId)
                runBlocking {
                    withTimeout(5_000) {
                        while (messageStatusOf(messageId) == MailingMessageStatus.QUEUED) {
                            delay(20)
                        }
                    }
                }
                messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
            } finally {
                realWorker.shutdown()
            }
        }

        test(
            "the worker survives a processMessage that throws outside its per-recipient try/catch and keeps " +
                "processing subsequent messages (review finding #1)",
        ) {
            val listId = createList()
            val crashRecipient = createMember(email = "crash-${Uuid.random()}@example.org")
            // Same reproducer as MailingHtmlSanitizerTest's oversized-output case: a raw input just
            // under MAX_HTML_BYTES whose SANITIZED output is over it. Stored directly here
            // (bypassing createDraftMessageHtml's own pre-save check, which now rejects this at
            // creation time) to simulate content that landed in bodyHtml some other way -- e.g. a
            // row written before this wave's sanitize-output-size fix existed. loadSendPlan's
            // re-sanitize throws BadRequestException, which used to escape processMessage entirely
            // and kill the worker's single long-lived coroutine for good.
            val n = 28_429
            val oversizedHtml = "x" + "<i></i>".repeat(n)
            oversizedHtml.toByteArray(Charsets.UTF_8).size shouldBe (MailingHtmlPolicy.MAX_HTML_BYTES - 996)
            val crashingMessageId = createMessage(listId = listId, sentBy = crashRecipient, bodyHtml = oversizedHtml)
            insertPending(messageId = crashingMessageId, memberId = crashRecipient)

            val survivorRecipient = createMember(email = "survivor-${Uuid.random()}@example.org")
            val survivorMessageId = createMessage(listId = listId, sentBy = survivorRecipient)
            insertPending(messageId = survivorMessageId, memberId = survivorRecipient)

            val transport = RecordingMailTransport()
            val realWorker = worker(transport = transport, mode = MailingDeliveryMode.LOG)
            try {
                realWorker.enqueue(crashingMessageId)
                realWorker.enqueue(survivorMessageId)

                runBlocking {
                    withTimeout(5_000) {
                        while (messageStatusOf(survivorMessageId) == MailingMessageStatus.QUEUED) {
                            delay(20)
                        }
                    }
                }

                messageStatusOf(crashingMessageId) shouldBe MailingMessageStatus.FAILED
                messageStatusOf(survivorMessageId) shouldBe MailingMessageStatus.SENT
            } finally {
                realWorker.shutdown()
            }
        }

        test("enqueue throws ConflictException once the bounded queue is saturated") {
            val listId = createList()
            val blockerRecipient = createMember(email = "blocker-${Uuid.random()}@example.org")
            val blockerMessageId = createMessage(listId = listId, sentBy = blockerRecipient)
            insertPending(messageId = blockerMessageId, memberId = blockerRecipient)

            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val realWorker =
                MailingDeliveryWorker(
                    transport = GateMailTransport(started = started, gate = gate),
                    branding = MailBranding.notConfigured(),
                    mode = MailingDeliveryMode.SMTP,
                    trackingToken = testTrackingToken(),
                    baseUrl = TEST_TRACKING_BASE_URL,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    sendDelay = 0.milliseconds,
                )
            try {
                // Occupies the worker's single coroutine indefinitely (until `gate` completes), so
                // it stops draining the channel entirely -- deterministic saturation instead of a
                // timing-dependent race against how fast the worker would otherwise empty it.
                realWorker.enqueue(blockerMessageId)
                runBlocking { withTimeout(5_000) { started.await() } }

                repeat(MailingDeliveryWorker.QUEUE_CAPACITY) { realWorker.enqueue(Uuid.random()) }

                shouldThrow<ConflictException> { realWorker.enqueue(Uuid.random()) }
            } finally {
                gate.complete(Unit)
                runBlocking {
                    withTimeout(5_000) {
                        while (messageStatusOf(blockerMessageId) == MailingMessageStatus.QUEUED) {
                            delay(20)
                        }
                    }
                }
                realWorker.shutdown()
            }
        }

        test("recoverInterrupted: leftover PENDING rows become FAILED, QUEUED messages are closed out") {
            val listId = createList()
            val sentRecipient = createMember(email = "recover-sent-${Uuid.random()}@example.org")
            val pendingOnlyRecipient = createMember(email = "recover-pending-${Uuid.random()}@example.org")

            val messageWithASent = createMessage(listId = listId, sentBy = sentRecipient)
            val sentDeliveryId = Uuid.random()
            transaction {
                MailingDeliveryLogTable.insert {
                    it[id] = sentDeliveryId
                    it[mailingMessageId] = messageWithASent
                    it[memberId] = sentRecipient
                    it[deliveredAt] = DbClock.nowLocalDateTime()
                    it[deliveryStatus] = DeliveryStatus.SENT
                }
            }
            val interruptedDeliveryId = insertPending(messageId = messageWithASent, memberId = pendingOnlyRecipient)

            val messageAllPending = createMessage(listId = listId, sentBy = pendingOnlyRecipient)
            val onlyPendingDeliveryId = insertPending(messageId = messageAllPending, memberId = pendingOnlyRecipient)

            worker(transport = RecordingMailTransport(), mode = MailingDeliveryMode.LOG).recoverInterrupted()

            deliveryStatusOf(interruptedDeliveryId) shouldBe DeliveryStatus.FAILED
            deliveryStatusOf(onlyPendingDeliveryId) shouldBe DeliveryStatus.FAILED
            messageStatusOf(messageWithASent) shouldBe MailingMessageStatus.SENT
            messageStatusOf(messageAllPending) shouldBe MailingMessageStatus.FAILED
        }
    })
