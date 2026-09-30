package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingMessageStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private class CapturingTransport : MailTransport {
    val html = mutableListOf<String>()
    val plain = mutableListOf<String>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        html += htmlBody
        plain += plainTextBody
        return MailSendOutcome.Sent
    }
}

/** Welle V1.9.15 -- the per-recipient tracking snapshot [MailingDeliveryWorker] takes at send time. */
class MailingDeliveryWorkerTrackingTest :
    FunSpec({
        val fx = TrackingFixture()
        val token = testTrackingToken()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        val linkHtml = "<p>Hallo <a href=\"https://example.org/x\">Link</a></p>"

        fun worker(transport: MailTransport) =
            MailingDeliveryWorker(
                transport = transport,
                branding = MailBranding(fromDisplayName = "Testverein", replyTo = "kontakt@example.org"),
                mode = MailingDeliveryMode.SMTP,
                trackingToken = token,
                baseUrl = TEST_TRACKING_BASE_URL,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                sendDelay = 0.milliseconds,
            )

        fun pendingDelivery(
            messageId: Uuid,
            memberId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MailingDeliveryLogTable.insert {
                    it[this.id] = id
                    it[mailingMessageId] = messageId
                    it[this.memberId] = memberId
                    it[deliveredAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[deliveryStatus] = DeliveryStatus.PENDING
                }
            }
            return id
        }

        data class Snapshot(
            val hash: String?,
            val open: Boolean,
            val click: Boolean,
        )

        fun snapshotOf(deliveryId: Uuid): Snapshot =
            transaction {
                val row = MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.id eq deliveryId }.single()
                Snapshot(
                    hash = row[MailingDeliveryLogTable.trackingTokenHash],
                    open = row[MailingDeliveryLogTable.openTracked],
                    click = row[MailingDeliveryLogTable.clickTracked],
                )
            }

        fun run(
            consentOpen: Boolean,
            consentClick: Boolean,
            bodyHtml: String? = linkHtml,
            withLinkRows: Boolean = true,
        ): Triple<Uuid, CapturingTransport, Uuid> {
            val sender = fx.member()
            val list = fx.list(sender)
            val member = fx.member()
            fx.subscribe(listId = list, memberId = member, openConsent = consentOpen, clickConsent = consentClick)
            val message =
                fx.message(
                    listId = list,
                    sentBy = sender,
                    bodyHtml = bodyHtml,
                    status = MailingMessageStatus.QUEUED,
                    sentAt = null,
                )
            if (withLinkRows) fx.link(messageId = message, index = 0, url = "https://example.org/x")
            val delivery = pendingDelivery(message, member)
            val transport = CapturingTransport()
            runBlocking { worker(transport).processMessage(message) }
            return Triple(delivery, transport, message)
        }

        test("no consent: no hash, both flags false, no tracking URLs in the mail") {
            val (delivery, transport, _) = run(consentOpen = false, consentClick = false)
            snapshotOf(delivery) shouldBe Snapshot(null, open = false, click = false)
            transport.html.single() shouldNotContain "/m/c/"
            transport.html.single() shouldNotContain "/m/o/"
            transport.html.single() shouldContain "https://example.org/x"
        }

        test("click consent only: hash + click flag, links rewritten, no pixel") {
            val (delivery, transport, _) = run(consentOpen = false, consentClick = true)
            val snap = snapshotOf(delivery)
            (snap.hash != null) shouldBe true
            snap.open shouldBe false
            snap.click shouldBe true
            transport.html.single() shouldContain "$TEST_TRACKING_BASE_URL/m/c/"
            transport.html.single() shouldNotContain "/m/o/"
            transport.html.single() shouldNotContain "href=\"https://example.org/x\""
            transport.plain.single() shouldNotContain "/m/c/"
        }

        test("open consent only: hash + open flag, pixel, links untouched") {
            val (delivery, transport, _) = run(consentOpen = true, consentClick = false)
            val snap = snapshotOf(delivery)
            snap.open shouldBe true
            snap.click shouldBe false
            transport.html.single() shouldContain "$TEST_TRACKING_BASE_URL/m/o/"
            transport.html.single() shouldContain "href=\"https://example.org/x\""
        }

        test("both consents: the URLs in the mail verify against the stored hash and address the captured link") {
            val (delivery, transport, _) = run(consentOpen = true, consentClick = true)
            val snap = snapshotOf(delivery)
            snap.open shouldBe true
            snap.click shouldBe true
            val clickToken = Regex("/m/c/([^\"]+)\"").find(transport.html.single())!!.groupValues[1]
            val parsed = token.parse(clickToken) as MailingTrackingToken.Parsed.Click
            MailingTrackingToken.hashNonce(parsed.nonce) shouldBe snap.hash
            parsed.linkIndex shouldBe 0
            val pixelToken = Regex("/m/o/([^\"]+)\\.gif").find(transport.html.single())!!.groupValues[1]
            (token.parse(pixelToken) as MailingTrackingToken.Parsed.Open).nonce shouldBe parsed.nonce
        }

        test("a plain-text message is never tracked, even with consent") {
            val (delivery, transport, _) = run(consentOpen = true, consentClick = true, bodyHtml = null)
            snapshotOf(delivery) shouldBe Snapshot(null, open = false, click = false)
            transport.html.single() shouldNotContain "/m/o/"
        }

        test("two recipients get different tokens") {
            val sender = fx.member()
            val list = fx.list(sender)
            val a = fx.member()
            val b = fx.member()
            fx.subscribe(listId = list, memberId = a, clickConsent = true)
            fx.subscribe(listId = list, memberId = b, clickConsent = true)
            val message =
                fx.message(
                    listId = list,
                    sentBy = sender,
                    bodyHtml = linkHtml,
                    status = MailingMessageStatus.QUEUED,
                    sentAt = null,
                )
            fx.link(messageId = message, index = 0, url = "https://example.org/x")
            val da = pendingDelivery(message, a)
            val db = pendingDelivery(message, b)
            val transport = CapturingTransport()
            runBlocking { worker(transport).processMessage(message) }
            snapshotOf(da).hash shouldBe snapshotOf(da).hash
            (snapshotOf(da).hash != snapshotOf(db).hash) shouldBe true
            transport.html.toSet().size shouldBe 2
        }
    })
