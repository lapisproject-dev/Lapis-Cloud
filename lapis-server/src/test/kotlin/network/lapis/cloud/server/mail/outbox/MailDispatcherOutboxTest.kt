package network.lapis.cloud.server.mail.outbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/**
 * Welle V1.9.81 -- [MailDispatcher] with a durable outbox behind it: `enqueue` is a pure hand-off (no database work on the caller's
 * thread, same timing as the in-memory dispatcher), `enqueueAll` writes a whole batch atomically, and without an outbox everything is
 * exactly the old behaviour.
 */
class MailDispatcherOutboxTest :
    FunSpec({
        val key = ByteArray(SecretBox.KEY_SIZE_BYTES) { (it + 11).toByte() }

        beforeSpec { DatabaseConfig.connect() }
        beforeTest { transaction { MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() } } }
        afterSpec { transaction { MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() } } }

        fun outbox(handoffCapacity: Int = 256) =
            MailOutbox(
                secretBox = SecretBox(key),
                lookupHasher = MailRecipientHasher(key),
                budget = MailBudgetStore(null),
                transport = NoOpMailTransport(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                handoffCapacity = handoffCapacity,
            )

        fun rowCount(): Int = transaction { MailOutboxTable.selectAll().count().toInt() }

        fun dispatcher(outbox: MailOutbox) =
            MailDispatcher(
                transport = NoOpMailTransport(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                outbox = outbox,
            )

        test(
            "enqueue returns at once and does NO database work on the caller's thread: with the persister not running nothing is written",
        ) {
            val o = outbox()
            val d = dispatcher(o)
            // The outbox was never started, so its persister is "blocked"; enqueue must still return immediately.
            repeat(5) {
                d.enqueue(
                    to = "a$it@example.org",
                    subject = "s",
                    plainTextBody = "p",
                    htmlBody = "<p>h</p>",
                    purpose = "password-reset",
                ) shouldBe
                    true
            }
            rowCount() shouldBe 0

            o.start()
            runBlocking { withTimeout(10_000) { while (rowCount() < 5) delay(20) } }
            rowCount() shouldBe 5
            d.shutdown()
        }

        test("a saturated hand-off drops the newest mail and says so (false), like the in-memory queue always did") {
            val o = outbox(handoffCapacity = 2)
            val d = dispatcher(o)
            d.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "password-reset") shouldBe true
            d.enqueue(to = "b@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "password-reset") shouldBe true
            d.enqueue(to = "c@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "password-reset") shouldBe false
            d.shutdown()
        }

        test("after shutdown nothing is accepted any more") {
            val o = outbox()
            val d = dispatcher(o)
            d.shutdown()
            d.enqueue(to = "a@example.org", subject = "s", plainTextBody = "p", htmlBody = "h", purpose = "password-reset") shouldBe false
        }

        test("a payload over the size limit is refused up front") {
            val o =
                MailOutbox(
                    secretBox = SecretBox(key),
                    lookupHasher = MailRecipientHasher(key),
                    budget = MailBudgetStore(null),
                    transport = NoOpMailTransport(),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    maxPayloadBytes = 1000,
                )
            o.offer(
                OutboundMail(
                    to = "a@example.org",
                    subject = "s",
                    plainTextBody = "x".repeat(2000),
                    htmlBody = "h",
                    purpose = "password-reset",
                ),
            ) shouldBe
                false
            o.offer(
                OutboundMail(
                    to = "a@example.org",
                    subject = "s",
                    plainTextBody = "x".repeat(100),
                    htmlBody = "h",
                    purpose = "password-reset",
                ),
            ) shouldBe
                true
        }

        test("enqueueAll writes a whole batch (more than the hand-off channel holds) in one go") {
            val o = outbox(handoffCapacity = 4)
            val d = dispatcher(o)
            val mails =
                (1..300).map {
                    OutboundMail(
                        to = "batch$it@example.org",
                        subject = "s",
                        plainTextBody = "p",
                        htmlBody = "<p>h</p>",
                        purpose = "event-cancelled",
                    )
                }

            runBlocking { d.enqueueAll(mails) }

            rowCount() shouldBe 300
            d.shutdown()
        }

        test("without an outbox, enqueueAll falls back to the in-memory queue, one mail after the other") {
            val sent = CopyOnWriteArrayList<String>()
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        sent += to
                        return MailSendOutcome.Sent
                    }
                }
            val d = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

            runBlocking {
                d.enqueueAll(
                    (1..10).map {
                        OutboundMail(
                            to = "fallback$it@example.org",
                            subject = "s",
                            plainTextBody = "p",
                            htmlBody = "h",
                            purpose = "event-cancelled",
                        )
                    },
                )
                withTimeout(10_000) { while (sent.size < 10) delay(20) }
            }

            sent.toSet().size shouldBe 10
            rowCount() shouldBe 0
            d.shutdown()
        }

        test("OutboundMail.toString never prints the address or a body") {
            OutboundMail(
                to = "secret@example.org",
                subject = "geheim",
                plainTextBody = "token",
                htmlBody = "<p>token</p>",
                purpose = "password-reset",
            ).toString() shouldBe
                "OutboundMail(purpose=password-reset)"
        }
    })
