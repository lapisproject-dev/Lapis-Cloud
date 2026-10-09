package network.lapis.cloud.server.mail.newsletter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailBudgetConfig
import network.lapis.cloud.server.mail.MailFailureKind
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

        val createdWorkers = mutableListOf<MailingDeliveryWorker>()

        // Every worker a test builds is shut down afterwards: since V1.9.81 a woken worker scans the whole queue table, so a leaked
        // one could pick up messages another test left QUEUED.
        afterTest { createdWorkers.forEach { it.shutdown() } }

        fun worker(
            transport: MailTransport,
            mode: MailingDeliveryMode,
            budget: MailBudgetStore = MailBudgetStore(null),
        ) = MailingDeliveryWorker(
            transport = transport,
            branding = MailBranding.notConfigured(),
            mode = mode,
            trackingToken = testTrackingToken(),
            baseUrl = TEST_TRACKING_BASE_URL,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sendDelay = 0.milliseconds,
            budget = budget,
            maxWait = 20.milliseconds,
        ).also { createdWorkers += it }

        fun resetBudgetState() {
            transaction {
                MailSendSlotTable.deleteWhere { MailSendSlotTable.id neq Uuid.random() }
                MailBudgetLockTable.update({ MailBudgetLockTable.id eq 1.toShort() }) { it[bulkPausedUntil] = null }
            }
        }

        data class RowState(
            val status: DeliveryStatus,
            val claimedAt: LocalDateTime?,
            val attemptCount: Int,
            val nextAttemptAt: LocalDateTime?,
        )

        fun rowState(deliveryLogId: Uuid): RowState =
            transaction {
                MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.id eq deliveryLogId }.single().let {
                    RowState(
                        status = it[MailingDeliveryLogTable.deliveryStatus],
                        claimedAt = it[MailingDeliveryLogTable.claimedAt],
                        attemptCount = it[MailingDeliveryLogTable.attemptCount],
                        nextAttemptAt = it[MailingDeliveryLogTable.nextAttemptAt],
                    )
                }
            }

        fun waitUntil(
            timeoutMs: Long = 10_000,
            condition: () -> Boolean,
        ) {
            runBlocking {
                withTimeout(timeoutMs) {
                    while (!condition()) delay(10)
                }
            }
        }

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
                    if (to.startsWith("smtp-fail")) MailSendOutcome.Failed(sanitizedErrorMessage = "boom") else MailSendOutcome.Sent
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
            val transport = RecordingMailTransport(outcomeFor = { MailSendOutcome.Failed(sanitizedErrorMessage = "boom") })

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
            // The queue is FIFO by queued_at (V1.9.81): the crashing message is the older one, so the survivor can only finish AFTER it.
            val queuedBase = DbClock.nowLocalDateTime()
            transaction {
                MailingMessageTable.update({ MailingMessageTable.id eq crashingMessageId }) { it[queuedAt] = queuedBase }
                MailingMessageTable.update({ MailingMessageTable.id eq survivorMessageId }) {
                    it[queuedAt] =
                        queuedBase.plusDuration(1.seconds)
                }
            }

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

        test("enqueue throws ConflictException once the worker has been shut down, and never while it is running") {
            val live = worker(transport = RecordingMailTransport(), mode = MailingDeliveryMode.LOG)
            repeat(100) { live.enqueue(Uuid.random()) } // the queue is the database: nothing can saturate
            live.shutdown()
            shouldThrow<ConflictException> { live.enqueue(Uuid.random()) }
        }

        test("20 queued messages are all delivered, FIFO by queued_at -- the send queue is never saturated") {
            val listId = createList()
            val recipients = (1..20).map { createMember(email = "fifo-$it-${Uuid.random()}@example.org") }
            val base = DbClock.nowLocalDateTime()
            // Created in REVERSE order of their queued_at, so only a queued_at-ordered worker sends them in 1..20 order.
            val messageIds = mutableMapOf<Int, Uuid>()
            for (i in 20 downTo 1) {
                val messageId = createMessage(listId = listId, sentBy = recipients[0])
                transaction {
                    MailingMessageTable.update({ MailingMessageTable.id eq messageId }) { it[queuedAt] = base.plusDuration(i.seconds) }
                }
                insertPending(messageId = messageId, memberId = recipients[i - 1])
                messageIds[i] = messageId
            }
            val transport = RecordingMailTransport()
            val realWorker = worker(transport = transport, mode = MailingDeliveryMode.SMTP)
            repeat(20) { realWorker.enqueue(Uuid.random()) }
            waitUntil { messageIds.values.all { messageStatusOf(it) != MailingMessageStatus.QUEUED } }

            transport.sentTo.size shouldBe 20
            transport.sentTo.map { it.substringAfter("fifo-").substringBefore("-").toInt() } shouldContainExactly (1..20).toList()
            messageIds.values.forEach { messageStatusOf(it) shouldBe MailingMessageStatus.SENT }
        }

        test("the claim happens BEFORE the send: at send time the row carries claimed_at and attempt_count 1") {
            val listId = createList()
            val recipient = createMember(email = "claim-order-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = recipient)
            val deliveryId = insertPending(messageId = messageId, memberId = recipient)
            var seenAtSend: RowState? = null
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        seenAtSend = rowState(deliveryId)
                        return MailSendOutcome.Sent
                    }
                }

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

            val atSend = requireNotNull(seenAtSend) { "transport.send was never called" }
            atSend.status shouldBe DeliveryStatus.PENDING
            (atSend.claimedAt != null) shouldBe true
            atSend.attemptCount shouldBe 1
            deliveryStatusOf(deliveryId) shouldBe DeliveryStatus.SENT
        }

        test("recoverInterrupted: a CLAIMED pending row becomes INTERRUPTED and is never sent; an unclaimed one is sent exactly once") {
            val listId = createList()
            val claimedRecipient = createMember(email = "recover-claimed-${Uuid.random()}@example.org")
            val freshRecipient = createMember(email = "recover-fresh-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = claimedRecipient)
            val claimedRow = insertPending(messageId = messageId, memberId = claimedRecipient)
            val freshRow = insertPending(messageId = messageId, memberId = freshRecipient)
            transaction {
                MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq claimedRow }) {
                    it[claimedAt] = DbClock.nowLocalDateTime()
                    it[attemptCount] = 1
                }
            }
            val transport = RecordingMailTransport()
            val restarted = worker(transport = transport, mode = MailingDeliveryMode.SMTP)

            restarted.recoverInterrupted()
            waitUntil { messageStatusOf(messageId) != MailingMessageStatus.QUEUED }

            deliveryStatusOf(claimedRow) shouldBe DeliveryStatus.INTERRUPTED
            deliveryStatusOf(freshRow) shouldBe DeliveryStatus.SENT
            transport.sentTo.size shouldBe 1
            transport.sentTo.single().startsWith("recover-fresh") shouldBe true
            // INTERRUPTED does not count as reached, but the fresh recipient was.
            messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
        }

        test("recoverInterrupted: a QUEUED message whose rows are all final is closed; INTERRUPTED alone does not make it SENT") {
            val listId = createList()
            val recipient = createMember(email = "recover-only-claimed-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = recipient)
            val row = insertPending(messageId = messageId, memberId = recipient)
            transaction {
                MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq row }) { it[claimedAt] = DbClock.nowLocalDateTime() }
            }
            val transport = RecordingMailTransport()

            worker(transport = transport, mode = MailingDeliveryMode.SMTP).recoverInterrupted()

            deliveryStatusOf(row) shouldBe DeliveryStatus.INTERRUPTED
            messageStatusOf(messageId) shouldBe MailingMessageStatus.FAILED
            transport.sentTo shouldBe emptyList()
        }

        test("an erased (Art. 17) delivery row is skipped without error; a row erased mid-send does not break the run") {
            val listId = createList()
            val a = createMember(email = "erase-a-${Uuid.random()}@example.org")
            val b = createMember(email = "erase-b-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = a)
            val rowA = insertPending(messageId = messageId, memberId = a)
            val rowB = insertPending(messageId = messageId, memberId = b)
            val transport =
                object : MailTransport {
                    val sent = mutableListOf<String>()

                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        sent += to
                        // The first recipient's own row AND the other one vanish while we are sending (erasure runs in parallel).
                        transaction { MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.mailingMessageId eq messageId } }
                        return MailSendOutcome.Sent
                    }
                }

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

            transport.sent.size shouldBe 1
            // nothing left to deliver: the message is closed (nobody was recorded as reached)
            messageStatusOf(messageId) shouldBe MailingMessageStatus.FAILED
            listOf(rowA, rowB).forEach { id ->
                transaction { MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.id eq id }.count() } shouldBe 0
            }
        }

        // ── V1.9.81: hourly budget, retries, global pause ─────────────────────────────────────────

        fun smtpWorkerWithBudget(
            transport: MailTransport,
            max: Int = 10,
            reserve: Int = 2,
        ) = worker(
            transport = transport,
            mode = MailingDeliveryMode.SMTP,
            budget = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = max, reservePerHour = reserve)),
        )

        test("BULK budget exhausted: the worker waits, sends nothing, and resumes once the window has moved on") {
            resetBudgetState()
            val clock = MutableTestClock("2031-03-01T10:00:00Z")
            TimeTestSupport.withMutableServerClock(clock = clock) {
                val listId = createList()
                val recipient = createMember(email = "budget-wait-${Uuid.random()}@example.org")
                val messageId = createMessage(listId = listId, sentBy = recipient)
                val row = insertPending(messageId = messageId, memberId = recipient)
                // 8 BULK slots already used just now (max 10, reserve 2 -> bulk limit 8)
                val now = DbClock.nowLocalDateTime()
                transaction {
                    repeat(8) {
                        MailSendSlotTable.insert {
                            it[id] = Uuid.random()
                            it[reservedAt] = now
                            it[lane] = "BULK"
                        }
                    }
                }
                val transport = RecordingMailTransport()
                val w = smtpWorkerWithBudget(transport)
                val job = CoroutineScope(Dispatchers.IO).async { w.processMessage(messageId) }

                runBlocking { delay(400) }
                transport.sentTo shouldBe emptyList()
                job.isCompleted shouldBe false
                rowState(row).claimedAt shouldBe null // waiting happens BEFORE the claim

                clock.advance(61.minutes)
                runBlocking { withTimeout(10_000) { job.await() } }

                transport.sentTo.size shouldBe 1
                deliveryStatusOf(row) shouldBe DeliveryStatus.SENT
                messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
            }
            resetBudgetState()
        }

        test("LOG mode never reserves a budget slot") {
            resetBudgetState()
            val listId = createList()
            val recipient = createMember(email = "log-no-slot-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = recipient)
            insertPending(messageId = messageId, memberId = recipient)
            val w =
                worker(
                    transport = RecordingMailTransport(),
                    mode = MailingDeliveryMode.LOG,
                    budget = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = 10, reservePerHour = 2)),
                )

            runBlocking { w.processMessage(messageId) }

            transaction { MailSendSlotTable.selectAll().count() } shouldBe 0
            messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
        }

        test("SMTP 4xx: the claim is released, retries wait 5/15/60 minutes, a global 10 minute pause is set, the 4th failure is final") {
            resetBudgetState()
            val clock = MutableTestClock("2031-03-02T10:00:00Z")
            TimeTestSupport.withMutableServerClock(clock = clock) {
                val listId = createList()
                val recipient = createMember(email = "retry-${Uuid.random()}@example.org")
                val messageId = createMessage(listId = listId, sentBy = recipient)
                val row = insertPending(messageId = messageId, memberId = recipient)
                val transport =
                    RecordingMailTransport(outcomeFor = {
                        MailSendOutcome.Failed(
                            sanitizedErrorMessage = "x",
                            kind = MailFailureKind.TRANSIENT,
                            smtpReplyCode = 451,
                            errorClass = "SMTP_451",
                        )
                    })
                val w = smtpWorkerWithBudget(transport, max = 100, reserve = 10)
                val job = CoroutineScope(Dispatchers.IO).async { w.processMessage(messageId) }

                val expectedBackoff = listOf(5.minutes, 15.minutes, 60.minutes)
                expectedBackoff.forEachIndexed { index, backoff ->
                    val attempt = index + 1
                    waitUntil { rowState(row).let { it.attemptCount == attempt && it.claimedAt == null && it.nextAttemptAt != null } }
                    val state = rowState(row)
                    state.status shouldBe DeliveryStatus.PENDING
                    state.nextAttemptAt shouldBe DbClock.nowLocalDateTime().plusDuration(backoff)
                    // the provider's 4xx also pauses the whole bulk lane for 10 minutes
                    MailBudgetStore(null).bulkPausedUntil() shouldBe DbClock.nowLocalDateTime().plusDuration(10.minutes)
                    // the 10 minute global bulk pause (set by the provider's 4xx) outlasts the first two backoffs
                    clock.advance(maxOf(backoff, 10.minutes) + 1.seconds)
                }
                runBlocking { withTimeout(10_000) { job.await() } }

                transport.sentTo.size shouldBe 4
                rowState(row).status shouldBe DeliveryStatus.FAILED
                rowState(row).attemptCount shouldBe 4
                messageStatusOf(messageId) shouldBe MailingMessageStatus.FAILED
            }
            resetBudgetState()
        }

        test("a 5xx, an authentication failure and a timeout after the data transfer are final -- never retried") {
            resetBudgetState()
            val listId = createList()
            val failures =
                mapOf(
                    "p5xx" to
                        MailSendOutcome.Failed(
                            sanitizedErrorMessage = "x",
                            kind = MailFailureKind.PERMANENT,
                            smtpReplyCode = 550,
                            errorClass = "SMTP_550",
                        ),
                    "auth" to MailSendOutcome.Failed(sanitizedErrorMessage = "x", kind = MailFailureKind.PERMANENT, errorClass = "AUTH"),
                    "uncertain" to
                        MailSendOutcome.Failed(
                            sanitizedErrorMessage = "x",
                            kind = MailFailureKind.TRANSIENT,
                            deliveryUncertain = true,
                            errorClass = "TIMEOUT",
                        ),
                )
            failures.forEach { (key, outcome) ->
                val recipient = createMember(email = "$key-${Uuid.random()}@example.org")
                val messageId = createMessage(listId = listId, sentBy = recipient)
                val row = insertPending(messageId = messageId, memberId = recipient)
                val transport = RecordingMailTransport(outcomeFor = { outcome })

                runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

                withClue(key) {
                    transport.sentTo.size shouldBe 1
                    deliveryStatusOf(row) shouldBe DeliveryStatus.FAILED
                    messageStatusOf(messageId) shouldBe MailingMessageStatus.FAILED
                }
            }
            // none of them is a 4xx reply, so none of them pauses the bulk lane
            MailBudgetStore(null).bulkPausedUntil() shouldBe null
        }

        test("a connection failure before anything was sent is retried; the bulk pause is only set for a 4xx reply") {
            resetBudgetState()
            val clock = MutableTestClock("2031-03-03T10:00:00Z")
            TimeTestSupport.withMutableServerClock(clock = clock) {
                val listId = createList()
                val recipient = createMember(email = "connect-${Uuid.random()}@example.org")
                val messageId = createMessage(listId = listId, sentBy = recipient)
                val row = insertPending(messageId = messageId, memberId = recipient)
                var calls = 0
                val transport =
                    RecordingMailTransport(outcomeFor = {
                        calls++
                        if (calls == 1) {
                            MailSendOutcome.Failed(sanitizedErrorMessage = "x", kind = MailFailureKind.TRANSIENT, errorClass = "CONNECT")
                        } else {
                            MailSendOutcome.Sent
                        }
                    })
                val w = smtpWorkerWithBudget(transport, max = 100, reserve = 10)
                val job = CoroutineScope(Dispatchers.IO).async { w.processMessage(messageId) }

                waitUntil { rowState(row).let { it.attemptCount == 1 && it.claimedAt == null && it.nextAttemptAt != null } }
                MailBudgetStore(null).bulkPausedUntil() shouldBe null
                clock.advance(6.minutes)
                runBlocking { withTimeout(10_000) { job.await() } }

                transport.sentTo.size shouldBe 2
                deliveryStatusOf(row) shouldBe DeliveryStatus.SENT
            }
            resetBudgetState()
        }

        test("a transient database error in the middle of a send does not fail the message; the send finishes") {
            val listId = createList()
            val first = createMember(email = "flaky-a-${Uuid.random()}@example.org")
            val second = createMember(email = "flaky-b-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = first)
            val rowA = insertPending(messageId = messageId, memberId = first)
            val rowB = insertPending(messageId = messageId, memberId = second)
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val flakyBudget =
                object : MailBudgetStore(null) {
                    override fun bulkPausedUntil(): LocalDateTime? {
                        if (calls.incrementAndGet() == 1) error("simulated connection pool timeout")
                        return super.bulkPausedUntil()
                    }
                }
            val transport = RecordingMailTransport()

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP, budget = flakyBudget).processMessage(messageId) }

            transport.sentTo.size shouldBe 2
            deliveryStatusOf(rowA) shouldBe DeliveryStatus.SENT
            deliveryStatusOf(rowB) shouldBe DeliveryStatus.SENT
            messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
        }

        test("a claimed PENDING row without an owner becomes INTERRUPTED and the message still closes") {
            val listId = createList()
            val ok = createMember(email = "orphan-ok-${Uuid.random()}@example.org")
            val stuck = createMember(email = "orphan-stuck-${Uuid.random()}@example.org")
            val messageId = createMessage(listId = listId, sentBy = ok)
            val okRow = insertPending(messageId = messageId, memberId = ok)
            val stuckRow = insertPending(messageId = messageId, memberId = stuck)
            transaction {
                MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq stuckRow }) {
                    it[claimedAt] = DbClock.nowLocalDateTime()
                    it[attemptCount] = 1
                }
            }
            val transport = RecordingMailTransport()

            runBlocking { worker(transport = transport, mode = MailingDeliveryMode.SMTP).processMessage(messageId) }

            transport.sentTo.size shouldBe 1
            deliveryStatusOf(okRow) shouldBe DeliveryStatus.SENT
            deliveryStatusOf(stuckRow) shouldBe DeliveryStatus.INTERRUPTED
            messageStatusOf(messageId) shouldBe MailingMessageStatus.SENT
        }
    })
