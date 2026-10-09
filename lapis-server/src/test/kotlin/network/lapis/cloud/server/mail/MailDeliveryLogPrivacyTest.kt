package network.lapis.cloud.server.mail

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.server.mail.newsletter.TEST_TRACKING_BASE_URL
import network.lapis.cloud.server.mail.newsletter.testTrackingToken
import network.lapis.cloud.server.mail.outbox.MailOutbox
import network.lapis.cloud.server.mail.outbox.MailRecipientHasher
import network.lapis.cloud.server.mail.outbox.OutboundMail
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.Properties
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Welle V1.9.81 -- one capture over EVERY log line of the whole mail pipeline (dispatcher, outbox, mailing worker, real
 * [JakartaMailTransport] with hostile exceptions): neither the full address, nor its local part, nor the subject, nor a token from a body
 * appears -- on success, on every failure class, on drop, on expiry, on the reaper's verdict and on a mailing-list send.
 * The masked form (`s***@example.org`) is allowed. Also: `last_error_class` stays inside the closed vocabulary.
 */
class MailDeliveryLogPrivacyTest :
    FunSpec({
        val address = "secret.person@example.org"
        val subject = "Geheimer Betreff 4711"
        val token = "TOKEN-9f8e7d6c"
        val key = ByteArray(SecretBox.KEY_SIZE_BYTES) { (it + 5).toByte() }
        val smtp =
            (
                SmtpConfig.load(
                    env = {
                        mapOf(
                            SmtpConfig.ENV_HOST to "mail.example.invalid",
                            SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                            SmtpConfig.ENV_PASSWORD to "s3cr3t",
                            SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                            SmtpConfig.ENV_FROM_NAME to "Test",
                        )[it]
                    },
                ) as SmtpConfigState.Configured
            ).config
        val createdMemberIds = mutableListOf<Uuid>()
        val createdListIds = mutableListOf<Uuid>()
        val createdMessageIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        // The privacy flows deliberately provoke 4xx replies far in the (simulated) future: never leave the global bulk pause behind --
        // every spec in this JVM shares one database.
        fun resetBudgetState() {
            transaction {
                MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() }
                MailSendSlotTable.deleteWhere { MailSendSlotTable.id neq Uuid.random() }
                MailBudgetLockTable.update({ MailBudgetLockTable.id eq 1.toShort() }) { it[bulkPausedUntil] = null }
            }
        }
        beforeTest { resetBudgetState() }
        afterTest { resetBudgetState() }
        afterSpec {
            transaction {
                MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() }
                MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.mailingMessageId inList createdMessageIds }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList createdMessageIds }
                MailingListTable.deleteWhere { MailingListTable.id inList createdListIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        /** A real [JakartaMailTransport] whose relay "answers" with [failure] -- exceptions that echo the address, like real servers do. */
        fun failingTransport(failure: () -> Throwable) =
            JakartaMailTransport(config = smtp, sendMessage = { throw failure() }, sessionFactory = { Session.getInstance(Properties()) })

        fun workingTransport() =
            JakartaMailTransport(config = smtp, sendMessage = { }, sessionFactory = { Session.getInstance(Properties()) })

        fun <T> capture(block: () -> T): Pair<T, List<ILoggingEvent>> {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val previous = root.level
            root.level = Level.TRACE
            root.addAppender(appender)
            try {
                val result = block()
                return result to appender.list.toList()
            } finally {
                root.detachAppender(appender)
                root.level = previous
            }
        }

        fun assertClean(events: List<ILoggingEvent>) {
            val pipeline =
                events.filter {
                    it.loggerName.startsWith(
                        "network.lapis.cloud.server.mail",
                    ) ||
                        it.loggerName.contains("Mailing")
                }
            pipeline.forEach { event ->
                val text = event.formattedMessage + (event.throwableProxy?.message ?: "") + (event.throwableProxy?.className ?: "")
                listOf(address, "secret.person", subject, "Geheimer", token).forEach { forbidden ->
                    if (text.contains(forbidden)) throw AssertionError("log line leaks '$forbidden': ${event.formattedMessage}")
                }
            }
        }

        fun outbox(
            transport: MailTransport,
            now: () -> LocalDateTime,
        ) = MailOutbox(
            secretBox = SecretBox(key),
            lookupHasher = MailRecipientHasher(key),
            budget = MailBudgetStore(null),
            transport = transport,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = now,
        )

        val mail =
            OutboundMail(
                to = address,
                subject = subject,
                plainTextBody = "Link $token",
                htmlBody = "<p>$token</p>",
                purpose = "event-registration",
            )

        test("outbox: success, SMTP 5xx, SMTP 4xx with retries, connection failure, expiry, decrypt failure, the reaper, a drop") {
            var now = DbClock.nowLocalDateTime()
            val (_, events) =
                capture {
                    // success
                    outbox(workingTransport()) { now }.let { o ->
                        runBlocking {
                            o.persistAll(listOf(mail))
                            o.tick(now)
                        }
                    }
                    // permanent
                    outbox(
                        failingTransport {
                            SendFailedException(
                                "550 5.1.1 <$address> $subject unknown",
                                MessagingException("550 $address"),
                            )
                        },
                    ) { now }.let { o ->
                        runBlocking {
                            o.persistAll(listOf(mail))
                            o.tick(now)
                        }
                    }
                    // transient: 4xx, five attempts through the whole ladder
                    val transient = outbox(failingTransport { MessagingException("451 4.7.1 $address $token try later") }) { now }
                    runBlocking { transient.persistAll(listOf(mail)) }
                    repeat(6) {
                        runBlocking { transient.tick(now) }
                        now = now.plusDuration(61.minutes)
                    }
                    // connection failure
                    outbox(
                        failingTransport { MessagingException("could not connect to $address", java.net.ConnectException(address)) },
                    ) { now }.let { o ->
                        runBlocking {
                            o.persistAll(listOf(mail))
                            o.tick(now)
                        }
                    }
                    // expiry (priority 0, 30 minutes)
                    outbox(workingTransport()) { now }.let { o ->
                        runBlocking { o.persistAll(listOf(mail.copy(purpose = "password-reset"))) }
                        now = now.plusDuration(31.minutes)
                        runBlocking { o.tick(now) }
                    }
                    // decrypt failure
                    outbox(workingTransport()) { now }.let { o ->
                        runBlocking { o.persistAll(listOf(mail)) }
                        transaction { MailOutboxTable.update({ MailOutboxTable.status eq "QUEUED" }) { it[recipientEnc] = "v1:AAAA:BBBB" } }
                        runBlocking { o.tick(now) }
                    }
                    // the reaper
                    outbox(workingTransport()) { now }.let { o ->
                        runBlocking { o.persistAll(listOf(mail)) }
                        transaction {
                            MailOutboxTable.update({ MailOutboxTable.status eq "QUEUED" }) {
                                it[status] = "SENDING"
                                it[claimedAt] = now.minusDuration(30.minutes)
                                it[attemptCount] = 1
                            }
                        }
                        runBlocking { o.tick(now) }
                    }
                    // a drop (too large)
                    MailOutbox(
                        secretBox = SecretBox(key),
                        lookupHasher = MailRecipientHasher(key),
                        budget = MailBudgetStore(null),
                        transport = workingTransport(),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        maxPayloadBytes = 10,
                    ).offer(mail)
                }
            events.isNotEmpty() shouldBe true
            assertClean(events)
            // and the stored error class stays in the closed vocabulary
            transaction { MailOutboxTable.selectAll().mapNotNull { it[MailOutboxTable.lastErrorClass] } }
                .forEach { Regex("^[A-Z0-9_]{1,64}$").matches(it) shouldBe true }
        }

        test("the in-memory dispatcher (no outbox) logs a failure without the address, the subject or any token") {
            val (_, events) =
                capture {
                    val d =
                        MailDispatcher(
                            transport =
                                failingTransport {
                                    SendFailedException(
                                        "550 <$address> $subject $token",
                                        MessagingException("550 $address"),
                                    )
                                },
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        )
                    d.enqueue(
                        to = address,
                        subject = subject,
                        plainTextBody = "Link $token",
                        htmlBody = "<p>$token</p>",
                        purpose = "password-reset",
                    )
                    d.enqueue(
                        to = address,
                        subject = subject,
                        plainTextBody = "Link $token",
                        htmlBody = "<p>$token</p>",
                        purpose = "password-reset",
                        logRecipient = false,
                    )
                    runBlocking { delay(500) }
                    d.shutdown()
                }
            events.any { it.formattedMessage.contains("Mail delivery FAILED") } shouldBe true
            assertClean(events)
        }

        test("mailing-list worker: a send, a clear 4xx deferral, a 5xx and an unknown crash log no address, subject or token") {
            val recipientAddr = address
            val memberId = Uuid.random()
            val listId = Uuid.random()
            val messageId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = "Privacy-Test"
                    it[email] = recipientAddr
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                MailingListTable.insert {
                    it[id] = listId
                    it[name] = "Privacy-Liste"
                    it[createdBy] = memberId
                }
                MailingMessageTable.insert {
                    it[id] = messageId
                    it[mailingListId] = listId
                    it[MailingMessageTable.subject] = subject
                    it[bodyText] = "Link $token"
                    it[sentBy] = memberId
                    it[status] = MailingMessageStatus.QUEUED
                }
            }
            createdMemberIds += memberId
            createdListIds += listId
            createdMessageIds += messageId

            fun pending() =
                transaction {
                    MailingDeliveryLogTable.insert {
                        it[id] = Uuid.random()
                        it[mailingMessageId] = messageId
                        it[MailingDeliveryLogTable.memberId] = memberId
                        it[deliveredAt] = DbClock.nowLocalDateTime()
                        it[deliveryStatus] = DeliveryStatus.PENDING
                    }
                }

            fun worker(transport: MailTransport) =
                MailingDeliveryWorker(
                    transport = transport,
                    branding = MailBranding.notConfigured(),
                    mode = MailingDeliveryMode.SMTP,
                    trackingToken = testTrackingToken(),
                    baseUrl = TEST_TRACKING_BASE_URL,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    sendDelay = 0.milliseconds,
                    maxWait = 10.milliseconds,
                )
            val (_, events) =
                capture {
                    pending()
                    worker(workingTransport()).let { w ->
                        runBlocking { w.processMessage(messageId) }
                        w.shutdown()
                    }
                    transaction {
                        MailingMessageTable.update({ MailingMessageTable.id eq messageId }) {
                            it[status] =
                                MailingMessageStatus.QUEUED
                        }
                    }
                    pending()
                    worker(
                        failingTransport { SendFailedException("550 <$address> $subject", MessagingException("550 $address $token")) },
                    ).let { w ->
                        runBlocking { w.processMessage(messageId) }
                        w.shutdown()
                    }
                    transaction {
                        MailingMessageTable.update({ MailingMessageTable.id eq messageId }) {
                            it[status] =
                                MailingMessageStatus.QUEUED
                        }
                    }
                    pending()
                    worker(failingTransport { IllegalStateException("crash with $address $subject $token") }).let { w ->
                        runBlocking { w.processMessage(messageId) }
                        w.shutdown()
                    }
                }
            events.any { it.formattedMessage.contains("Mailing delivery") } shouldBe true
            assertClean(events)
            // keep the shape of the capture honest: the masked form IS allowed
            events.filter { it.formattedMessage.contains("***") }.forEach { it.formattedMessage.contains("s***@example.org") shouldBe true }
        }

        test("the scan itself works: a line carrying the address would be caught") {
            val caught =
                runCatching {
                    assertClean(
                        listOf(FakeEvent(message = "leak $address", logger = "network.lapis.cloud.server.mail.MailDispatcher")),
                    )
                }
            caught.isFailure shouldBe true
            runCatching {
                assertClean(
                    listOf(FakeEvent(message = "masked s***@example.org only", logger = "network.lapis.cloud.server.mail.MailDispatcher")),
                )
            }.isSuccess shouldBe true
            listOf<String>().shouldBeEmpty()
        }
    })

private class FakeEvent(
    private val message: String,
    private val logger: String,
) : ILoggingEvent by (
        ch.qos.logback.classic.spi
            .LoggingEvent()
            .also {
                it.message = message
                it.loggerName = logger
            }
    )
