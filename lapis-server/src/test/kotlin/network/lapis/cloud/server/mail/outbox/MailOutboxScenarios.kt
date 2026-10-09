package network.lapis.cloud.server.mail.outbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import network.lapis.cloud.server.mail.MailBudgetConfig
import network.lapis.cloud.server.mail.MailFailureKind
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.budget.MailBudget
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.minusDuration
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private data class SentMail(
    val to: String,
    val subject: String,
    val text: String,
    val html: String,
)

/** Records every send; [outcomeFor] scripts the result per call number (1-based). */
private class RecordingTransport(
    private val outcomeFor: (call: Int) -> MailSendOutcome = { MailSendOutcome.Sent },
) : MailTransport {
    val sent = CopyOnWriteArrayList<SentMail>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        sent += SentMail(to = to, subject = subject, text = plainTextBody, html = htmlBody)
        return outcomeFor(sent.size)
    }
}

private data class RowSnapshot(
    val id: Uuid,
    val purpose: String,
    val priority: Int,
    val status: String,
    val attempt: Int,
    val nextAttemptAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val claimedAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
    val lastErrorClass: String?,
    val payloadCleared: Boolean,
    val lookupHash: String?,
)

/**
 * Welle V1.9.81 -- [MailOutbox] end to end on H2 AND on PostgreSQL, driven deterministically through `persistAll` + `tick(now)` with a
 * settable clock (no background threads): encryption at rest, final rows holding nothing, priorities, TTL, backoff, the reaper and its
 * fence, the lost-restart guarantee, the budget interplay (slot first, then claim; SYSTEM while BULK is exhausted; no head-of-line
 * blocking) and -- Postgres only -- two pollers sharing one queue with `SKIP LOCKED`.
 */
abstract class MailOutboxScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val key = ByteArray(SecretBox.KEY_SIZE_BYTES) { (it * 7 + 1).toByte() }
        val secretBox = SecretBox(key)
        val hasher = MailRecipientHasher(key)
        val base = LocalDateTime(2031, 7, 1, 8, 0, 0)
        var now = base
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        beforeSpec { db.activate() }
        installLaneGuards(db = db)

        fun clean() {
            transaction {
                MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() }
                MailSendSlotTable.deleteWhere { MailSendSlotTable.id neq Uuid.random() }
                MailBudgetLockTable.update({ MailBudgetLockTable.id eq 1.toShort() }) { it[bulkPausedUntil] = null }
            }
        }
        beforeTest {
            clean()
            now = base
        }
        afterSpec {
            clean()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            db.deactivate()
        }

        fun outbox(
            transport: MailTransport,
            budget: MailBudgetStore = MailBudgetStore(null),
            maxQueued: Int = MailOutbox.DEFAULT_MAX_QUEUED,
            staleClaim: Duration = 10.minutes,
        ) = MailOutbox(
            secretBox = secretBox,
            lookupHasher = hasher,
            budget = budget,
            transport = transport,
            scope = scope,
            maxQueued = maxQueued,
            staleClaim = staleClaim,
            clock = { now },
        )

        fun mail(
            to: String = "secret.person@example.org",
            purpose: String = "event-registration",
            subject: String = "Geheimer Betreff 4711",
            text: String = "Klartext-Inhalt mit Token ABC123",
            html: String = "<p>HTML-Inhalt mit Token ABC123</p>",
        ) = OutboundMail(to = to, subject = subject, plainTextBody = text, htmlBody = html, purpose = purpose)

        fun persist(
            o: MailOutbox,
            vararg mails: OutboundMail,
        ) = runBlocking { o.persistAll(mails.toList()) }

        fun rows(): List<RowSnapshot> =
            transaction {
                MailOutboxTable.selectAll().map {
                    RowSnapshot(
                        id = it[MailOutboxTable.id],
                        purpose = it[MailOutboxTable.purpose],
                        priority = it[MailOutboxTable.priority].toInt(),
                        status = it[MailOutboxTable.status],
                        attempt = it[MailOutboxTable.attemptCount],
                        nextAttemptAt = it[MailOutboxTable.nextAttemptAt],
                        createdAt = it[MailOutboxTable.createdAt],
                        expiresAt = it[MailOutboxTable.expiresAt],
                        claimedAt = it[MailOutboxTable.claimedAt],
                        finishedAt = it[MailOutboxTable.finishedAt],
                        lastErrorClass = it[MailOutboxTable.lastErrorClass],
                        payloadCleared =
                            it[MailOutboxTable.recipientEnc] == null &&
                                it[MailOutboxTable.subjectEnc] == null &&
                                it[MailOutboxTable.textEnc] == null &&
                                it[MailOutboxTable.htmlEnc] == null,
                        lookupHash = it[MailOutboxTable.recipientLookupHash],
                    )
                }
            }

        fun tick(o: MailOutbox) = runBlocking { o.tick(now) }

        fun fillSlots(
            count: Int,
            lane: String,
            at: LocalDateTime = now,
        ) = transaction {
            repeat(count) {
                MailSendSlotTable.insert {
                    it[id] = Uuid.random()
                    it[reservedAt] = at
                    it[MailSendSlotTable.lane] = lane
                }
            }
        }

        test("the raw columns never hold the address, the subject or the body in clear; the lookup hash is an HMAC, not the address") {
            val o = outbox(RecordingTransport())
            persist(o, mail())
            val raw = transaction { MailOutboxTable.selectAll().single() }
            val cells =
                listOf(
                    raw[MailOutboxTable.recipientEnc],
                    raw[MailOutboxTable.subjectEnc],
                    raw[MailOutboxTable.textEnc],
                    raw[MailOutboxTable.htmlEnc],
                    raw[MailOutboxTable.recipientLookupHash],
                    raw[MailOutboxTable.purpose],
                    raw[MailOutboxTable.lastErrorClass],
                )
            cells.filterNotNull().forEach { cell ->
                cell shouldNotContain "secret.person"
                cell shouldNotContain "example.org"
                cell shouldNotContain "Geheimer"
                cell shouldNotContain "4711"
                cell shouldNotContain "ABC123"
                cell shouldNotContain "Inhalt"
            }
            listOf(
                raw[MailOutboxTable.recipientEnc],
                raw[MailOutboxTable.subjectEnc],
                raw[MailOutboxTable.textEnc],
                raw[MailOutboxTable.htmlEnc],
            ).forEach { it!!.startsWith("v1:") shouldBe true }
            raw[MailOutboxTable.recipientLookupHash] shouldBe hasher.hash("secret.person@example.org")
            raw[MailOutboxTable.recipientLookupHash]!!.length shouldBe 64
            hasher.hash("  Secret.Person@Example.ORG ") shouldBe hasher.hash("secret.person@example.org")
        }

        test("SENT clears the payload and the lookup hash in the same step; the transport got the decrypted mail") {
            val transport = RecordingTransport()
            val o = outbox(transport)
            persist(o, mail())

            val result = tick(o)

            result.processed shouldBe 1
            transport.sent.single() shouldBe
                SentMail(
                    to = "secret.person@example.org",
                    subject = "Geheimer Betreff 4711",
                    text = "Klartext-Inhalt mit Token ABC123",
                    html = "<p>HTML-Inhalt mit Token ABC123</p>",
                )
            rows().single().let {
                it.status shouldBe "SENT"
                it.payloadCleared shouldBe true
                it.lookupHash shouldBe null
                it.finishedAt shouldBe now
                it.attempt shouldBe 1
                it.lastErrorClass shouldBe null
            }
        }

        test("the database refuses a final state that still carries a payload (CHECK), on the row as it stands") {
            val o = outbox(RecordingTransport())
            persist(o, mail())
            val id = rows().single().id
            shouldThrow<ExposedSQLException> {
                transaction { MailOutboxTable.update({ MailOutboxTable.id eq id }) { it[status] = "SENT" } }
            }
            // an open row cannot lose its payload either
            shouldThrow<ExposedSQLException> {
                transaction { MailOutboxTable.update({ MailOutboxTable.id eq id }) { it[recipientEnc] = null } }
            }
            rows().single().status shouldBe "QUEUED"
        }

        test("priority 0 (password reset) is delivered before an OLDER priority-1 mail") {
            val transport = RecordingTransport()
            val o = outbox(transport)
            persist(o, mail(to = "old@example.org", purpose = "event-registration"))
            now = now.plusDuration(5.seconds)
            persist(o, mail(to = "reset@example.org", purpose = "password-reset"))

            tick(o)

            transport.sent.map { it.to } shouldContainExactly listOf("reset@example.org", "old@example.org")
            rows().associate { it.purpose to it.priority } shouldBe mapOf("event-registration" to 1, "password-reset" to 0)
        }

        test("a priority-0 mail expires after 30 minutes without being sent; a priority-1 mail has no expiry") {
            val transport = RecordingTransport()
            val o = outbox(transport)
            persist(
                o,
                mail(to = "reset@example.org", purpose = "password-reset"),
                mail(to = "other@example.org", purpose = "event-registration"),
            )
            rows().first { it.purpose == "password-reset" }.expiresAt shouldBe now.plusDuration(30.minutes)
            rows().first { it.purpose == "event-registration" }.expiresAt shouldBe null

            now = now.plusDuration(31.minutes)
            tick(o)

            transport.sent.map { it.to } shouldContainExactly listOf("other@example.org")
            rows().first { it.purpose == "password-reset" }.let {
                it.status shouldBe "EXPIRED"
                it.payloadCleared shouldBe true
                it.lookupHash shouldBe null
                it.finishedAt shouldBe now
            }
        }

        test("a transient SMTP 4xx backs off 1/5/15/60 minutes, five attempts in all, then FAILED with the closed error class") {
            val transport =
                RecordingTransport {
                    MailSendOutcome.Failed(
                        sanitizedErrorMessage = "x",
                        kind = MailFailureKind.TRANSIENT,
                        smtpReplyCode = 451,
                        errorClass = "SMTP_451",
                    )
                }
            val o = outbox(transport)
            persist(o, mail())
            val backoff = listOf(1.minutes, 5.minutes, 15.minutes, 60.minutes)

            backoff.forEachIndexed { index, wait ->
                val attempt = index + 1
                tick(o)
                transport.sent.size shouldBe attempt
                rows().single().let {
                    it.status shouldBe "QUEUED"
                    it.attempt shouldBe attempt
                    it.nextAttemptAt shouldBe now.plusDuration(wait)
                    it.lastErrorClass shouldBe "SMTP_451"
                    it.claimedAt shouldBe null
                    it.payloadCleared shouldBe false
                }
                // one second early: nothing happens
                now = now.plusDuration(wait).minusDuration(1.seconds)
                tick(o)
                transport.sent.size shouldBe attempt
                now = now.plusDuration(1.seconds)
            }
            tick(o)

            transport.sent.size shouldBe 5
            rows().single().let {
                it.status shouldBe "FAILED"
                it.attempt shouldBe 5
                it.lastErrorClass shouldBe "SMTP_451"
                it.payloadCleared shouldBe true
                it.lookupHash shouldBe null
            }
        }

        test("a permanent failure (5xx) is final at once; the payload is gone") {
            val transport =
                RecordingTransport {
                    MailSendOutcome.Failed(
                        sanitizedErrorMessage = "x",
                        kind = MailFailureKind.PERMANENT,
                        smtpReplyCode = 550,
                        errorClass = "SMTP_550",
                    )
                }
            val o = outbox(transport)
            persist(o, mail())

            tick(o)
            tick(o)

            transport.sent.size shouldBe 1
            rows().single().let {
                it.status shouldBe "FAILED"
                it.lastErrorClass shouldBe "SMTP_550"
                it.attempt shouldBe 1
                it.payloadCleared shouldBe true
            }
        }

        test(
            "a system mail whose delivery is uncertain (timeout after the data transfer) IS retried -- a duplicate is the documented price",
        ) {
            val transport =
                RecordingTransport { call ->
                    if (call == 1) {
                        MailSendOutcome.Failed(
                            sanitizedErrorMessage = "x",
                            kind = MailFailureKind.TRANSIENT,
                            deliveryUncertain = true,
                            errorClass = "TIMEOUT",
                        )
                    } else {
                        MailSendOutcome.Sent
                    }
                }
            val o = outbox(transport)
            persist(o, mail())

            tick(o)
            rows().single().status shouldBe "QUEUED"
            now = now.plusDuration(61.seconds)
            tick(o)

            transport.sent.size shouldBe 2
            rows().single().status shouldBe "SENT"
        }

        test("the reaper turns a stale SENDING row into FAILED/INTERRUPTED without sending it again; a fresh claim is left alone") {
            val transport = RecordingTransport()
            val o = outbox(transport)
            persist(o, mail(to = "stale@example.org"), mail(to = "fresh@example.org"))
            val ids = rows().sortedBy { it.id.toString() }.map { it.id }
            transaction {
                MailOutboxTable.update({ MailOutboxTable.id eq ids[0] }) {
                    it[status] = "SENDING"
                    it[claimedAt] = now.minusDuration(11.minutes)
                    it[attemptCount] = 1
                }
                MailOutboxTable.update({ MailOutboxTable.id eq ids[1] }) {
                    it[status] = "SENDING"
                    it[claimedAt] = now.minusDuration(1.minutes)
                    it[attemptCount] = 1
                }
            }

            tick(o)

            transport.sent.size shouldBe 0
            val byId = rows().associateBy { it.id }
            byId.getValue(ids[0]).let {
                it.status shouldBe "FAILED"
                it.lastErrorClass shouldBe "INTERRUPTED"
                it.payloadCleared shouldBe true
                it.lookupHash shouldBe null
            }
            byId.getValue(ids[1]).status shouldBe "SENDING"
        }

        test("a late completion after the reaper loses against the fence: the row stays FAILED/INTERRUPTED") {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport =
                object : MailTransport {
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
            val o = outbox(transport)
            persist(o, mail())

            val firstTick = CoroutineScope(Dispatchers.IO).async { o.tick(now) }
            runBlocking { withTimeout(10_000) { started.await() } }
            rows().single().status shouldBe "SENDING"
            now = now.plusDuration(11.minutes)
            tick(o) // the reaper of a second tick closes the stale claim
            rows().single().let {
                it.status shouldBe "FAILED"
                it.lastErrorClass shouldBe "INTERRUPTED"
            }
            gate.complete(Unit)
            runBlocking { withTimeout(10_000) { firstTick.await() } }

            rows().single().let {
                it.status shouldBe "FAILED"
                it.lastErrorClass shouldBe "INTERRUPTED"
                it.payloadCleared shouldBe true
            }
        }

        test("the queue cap drops what does not fit; what fits is stored") {
            val o = outbox(RecordingTransport(), maxQueued = 3)
            persist(o, *(1..5).map { mail(to = "cap$it@example.org") }.toTypedArray())
            rows().size shouldBe 3
            rows().all { it.status == "QUEUED" } shouldBe true
        }

        test(
            "a ciphertext that no longer opens ends FAILED/DECRYPT, is never sent; moving a ciphertext to another row does not open either",
        ) {
            val transport = RecordingTransport()
            val o = outbox(transport)
            persist(o, mail(to = "a@example.org"), mail(to = "b@example.org"), mail(to = "c@example.org"))
            val ids = rows().map { it.id }
            transaction {
                // c: garbage
                MailOutboxTable.update({ MailOutboxTable.id eq ids[2] }) { it[recipientEnc] = "v1:AAAA:BBBB" }
                // a <-> b: swap the sealed recipient (AAD binds the row id)
                val a = MailOutboxTable.selectAll().where { MailOutboxTable.id eq ids[0] }.single()[MailOutboxTable.recipientEnc]
                val b = MailOutboxTable.selectAll().where { MailOutboxTable.id eq ids[1] }.single()[MailOutboxTable.recipientEnc]
                MailOutboxTable.update({ MailOutboxTable.id eq ids[0] }) { it[recipientEnc] = b }
                MailOutboxTable.update({ MailOutboxTable.id eq ids[1] }) { it[recipientEnc] = a }
            }

            tick(o)

            transport.sent.size shouldBe 0
            rows().forEach {
                it.status shouldBe "FAILED"
                it.lastErrorClass shouldBe "DECRYPT"
                it.payloadCleared shouldBe true
            }
        }

        test("a mail persisted by one instance is delivered by the next one after a 'restart'") {
            val first = outbox(RecordingTransport())
            persist(first, mail(to = "survivor@example.org"))
            val transport = RecordingTransport()

            tick(outbox(transport))

            transport.sent.single().to shouldBe "survivor@example.org"
            rows().single().status shouldBe "SENT"
        }

        test("closeOrphanedRows closes open rows as FAILED/OUTBOX_DISABLED and erases their payload") {
            val o = outbox(RecordingTransport())
            persist(o, mail(to = "a@example.org"), mail(to = "b@example.org"))
            val sendingId = rows().first().id
            transaction {
                MailOutboxTable.update({ MailOutboxTable.id eq sendingId }) {
                    it[status] = "SENDING"
                    it[claimedAt] = now
                    it[attemptCount] = 1
                }
            }

            MailOutbox.closeOrphanedRows(now) shouldBe 2

            rows().forEach {
                it.status shouldBe "FAILED"
                it.lastErrorClass shouldBe "OUTBOX_DISABLED"
                it.payloadCleared shouldBe true
                it.lookupHash shouldBe null
            }
        }

        // ── budget interplay ───────────────────────────────────────────────────────────────────

        fun budget(
            max: Int = 10,
            reserve: Int = 2,
        ) = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = max, reservePerHour = reserve))

        test("budget exhausted: the system mail WAITS (not claimed, no attempt burnt) and goes out after the window moved on") {
            val transport = RecordingTransport()
            val o = outbox(transport, budget = budget())
            fillSlots(count = 10, lane = "SYSTEM")
            persist(o, mail(purpose = "event-registration"))

            val blocked = tick(o)

            transport.sent.size shouldBe 0
            blocked.waitUntil shouldBe now.plusDuration(MailBudget.WINDOW)
            rows().single().let {
                it.status shouldBe "QUEUED"
                it.attempt shouldBe 0
                it.claimedAt shouldBe null
            }

            now = now.plusDuration(MailBudget.WINDOW).plusDuration(1.seconds)
            tick(o)

            transport.sent.size shouldBe 1
            rows().single().status shouldBe "SENT"
        }

        test("a system mail goes out while the BULK lane is exhausted; the event-cancelled mail (BULK) waits") {
            val transport = RecordingTransport()
            val o = outbox(transport, budget = budget())
            fillSlots(count = 8, lane = "BULK") // 8 = max 10 - reserve 2
            persist(
                o,
                mail(to = "cancel@example.org", purpose = "event-cancelled"),
                mail(to = "reset@example.org", purpose = "password-reset"),
            )

            val result = tick(o)

            transport.sent.map { it.to } shouldContainExactly listOf("reset@example.org")
            result.waitUntil shouldBe now.plusDuration(MailBudget.WINDOW)
            rows().first { it.purpose == "event-cancelled" }.let {
                it.status shouldBe "QUEUED"
                it.attempt shouldBe 0
            }
        }

        test("a blocked BULK mail at the head of the queue does not hold back system mails behind it") {
            val transport = RecordingTransport()
            val o = outbox(transport, budget = budget())
            fillSlots(count = 8, lane = "BULK")
            persist(o, mail(to = "cancel@example.org", purpose = "event-cancelled")) // older, priority 1, BULK
            now = now.plusDuration(2.seconds)
            persist(o, mail(to = "peer@example.org", purpose = "peer-request-target")) // newer, priority 1, SYSTEM

            tick(o)

            transport.sent.map { it.to } shouldContainExactly listOf("peer@example.org")
        }

        test("the global bulk pause holds an event-cancelled mail back until it ends") {
            val transport = RecordingTransport()
            val store = budget()
            val o = outbox(transport, budget = store)
            store.pauseBulk(now.plusDuration(10.minutes))
            persist(o, mail(purpose = "event-cancelled"))

            tick(o).waitUntil shouldBe now.plusDuration(10.minutes)
            transport.sent.size shouldBe 0
            now = now.plusDuration(11.minutes)
            tick(o)

            transport.sent.size shouldBe 1
        }

        test("a 4xx reply pauses the bulk lane for 10 minutes") {
            val transport =
                RecordingTransport {
                    MailSendOutcome.Failed(
                        sanitizedErrorMessage = "x",
                        kind = MailFailureKind.TRANSIENT,
                        smtpReplyCode = 421,
                        errorClass = "SMTP_421",
                    )
                }
            val store = budget()
            val o = outbox(transport, budget = store)
            persist(o, mail())

            tick(o)

            store.bulkPausedUntil() shouldBe now.plusDuration(10.minutes)
        }

        test("a large batch persists in one call and every row is a normal queued row (enqueueAll path)") {
            val o = outbox(RecordingTransport())
            persist(o, *(1..300).map { mail(to = "batch$it@example.org", purpose = "event-cancelled") }.toTypedArray())
            rows().size shouldBe 300
            rows().all { it.status == "QUEUED" && it.priority == 1 && it.expiresAt == null } shouldBe true
        }

        test("two pollers sharing one queue deliver every mail exactly once and never block each other (Postgres: SKIP LOCKED)") {
            if (!db.isPostgres) return@test
            val transport = RecordingTransport()
            val a = outbox(transport)
            val b = outbox(transport)
            persist(a, *(1..200).map { mail(to = "u$it@example.org", purpose = "peer-request-target") }.toTypedArray())
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf(a, b).map { o ->
                        pool.submit {
                            val deadline = System.nanoTime() + 120_000L * 1_000_000
                            while (System.nanoTime() < deadline) {
                                val open =
                                    transaction {
                                        MailOutboxTable.selectAll().where { MailOutboxTable.status neq "SENT" }.count()
                                    }
                                if (open == 0L) break
                                runBlocking { o.tick(now) }
                            }
                        }
                    }
                futures.forEach { it.get(150, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            transport.sent.size shouldBe 200
            transport.sent
                .map { it.to }
                .toSet()
                .size shouldBe 200
            rows().all { it.status == "SENT" && it.payloadCleared } shouldBe true
        }

        test("retention: finished rows go after 7 days (SENT/EXPIRED) and 30 days (FAILED); slots after 2 hours") {
            val o = outbox(RecordingTransport())

            fun finalRow(
                status: String,
                age: Duration,
            ): Uuid {
                val id = Uuid.random()
                transaction {
                    MailOutboxTable.insert {
                        it[MailOutboxTable.id] = id
                        it[purpose] = "event-registration"
                        it[priority] = 1.toShort()
                        it[MailOutboxTable.status] = status
                        it[attemptCount] = 1
                        it[nextAttemptAt] = now.minusDuration(age)
                        it[createdAt] = now.minusDuration(age)
                        it[finishedAt] = now.minusDuration(age)
                    }
                }
                return id
            }
            val sentOld = finalRow("SENT", 8.days)
            val sentYoung = finalRow("SENT", 6.days)
            val expiredOld = finalRow("EXPIRED", 8.days)
            val failedYoung = finalRow("FAILED", 29.days)
            val failedOld = finalRow("FAILED", 31.days)
            fillSlots(count = 2, lane = "SYSTEM", at = now.minusDuration(3.hours))
            fillSlots(count = 1, lane = "SYSTEM", at = now.minusDuration(30.minutes))

            tick(o)

            rows().map { it.id }.toSet() shouldBe setOf(sentYoung, failedYoung)
            withClue(
                "old rows $sentOld $expiredOld $failedOld are gone",
            ) { transaction { MailSendSlotTable.selectAll().count() } shouldBe 1 }
        }
    })

class MailOutboxTest : MailOutboxScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MailOutboxPostgresTest : MailOutboxScenarios(TestDatabase.Postgres())
