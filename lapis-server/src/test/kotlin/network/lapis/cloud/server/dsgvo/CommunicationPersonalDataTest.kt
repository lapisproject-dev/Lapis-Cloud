package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.mail.newsletter.MailingTrackingData
import network.lapis.cloud.server.mail.newsletter.TrackingFixture
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Welle V1.9.15 -- Art. 15 export and Art. 17 erasure of the mailing tracking data. The erasure case
 * is the load-bearing one: `mailing_link_click` has a FK onto the delivery log (no CASCADE by repo
 * convention), so without the explicit click-first delete the whole erasure transaction would abort
 * as soon as a single click row exists.
 */
class CommunicationPersonalDataTest :
    FunSpec({
        val fx = TrackingFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("export lists consent timestamps, deliveries, open counts and clicks per link -- and never the token hash") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            fx.link(messageId = msg, index = 0, url = "https://example.org/a")
            val subject = fx.member()
            fx.subscribe(listId = list, memberId = subject, openConsent = true, clickConsent = true)
            val (delivery, _) = fx.delivery(messageId = msg, memberId = subject, openTracked = true, clickTracked = true)
            val now = DbClock.nowLocalDateTime()
            repeat(2) { MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 0, now = now) }
            MailingTrackingData.recordOpen(deliveryLogId = delivery, now = now)

            val export = transaction { CommunicationPersonalData.exportMember(subject) }
            val subscription = export["subscriptions"]!!.jsonArray.single().jsonObject
            (subscription["openTrackingConsentedAt"].toString() != "null") shouldBe true
            (subscription["clickTrackingConsentedAt"].toString() != "null") shouldBe true

            val deliveries = export["mailingDeliveries"]!!.jsonArray
            deliveries.size shouldBe 1
            val entry = deliveries.single().jsonObject
            entry["openTracked"]!!.let { (it as JsonPrimitive).boolean } shouldBe true
            (entry["openCount"] as JsonPrimitive).int shouldBe 1
            val click = entry["clicks"]!!.jsonArray.single().jsonObject
            (click["clickCount"] as JsonPrimitive).int shouldBe 2
            click["targetUrl"].toString() shouldBe "\"https://example.org/a\""
            entry.keys.none { it.contains("hash", ignoreCase = true) || it.contains("token", ignoreCase = true) } shouldBe true
        }

        test("export contains nothing of other members") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val a = fx.member()
            val b = fx.member()
            fx.delivery(messageId = msg, memberId = a, clickTracked = true)
            fx.delivery(messageId = msg, memberId = b, clickTracked = true)
            transaction { CommunicationPersonalData.exportMember(a) }["mailingDeliveries"]!!.jsonArray.size shouldBe 1
        }

        test("erasure with existing click rows runs without an FK violation and removes clicks, deliveries and the outcome is reported") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            fx.link(messageId = msg, index = 0, url = "https://example.org/a")
            fx.link(messageId = msg, index = 1, url = "https://example.org/b")
            val subject = fx.member()
            val bystander = fx.member()
            fx.subscribe(listId = list, memberId = subject, clickConsent = true)
            val (subjectDelivery, _) = fx.delivery(messageId = msg, memberId = subject, clickTracked = true)
            val (bystanderDelivery, _) = fx.delivery(messageId = msg, memberId = bystander, clickTracked = true)
            val now = DbClock.nowLocalDateTime()
            MailingTrackingData.recordClick(deliveryLogId = subjectDelivery, linkIndex = 0, now = now)
            MailingTrackingData.recordClick(deliveryLogId = subjectDelivery, linkIndex = 1, now = now)
            MailingTrackingData.recordClick(deliveryLogId = bystanderDelivery, linkIndex = 0, now = now)

            val outcomes =
                transaction {
                    CommunicationPersonalData.eraseMember(
                        memberId = subject,
                        mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
                    )
                }

            outcomes.single { it.table == "mailing_link_click" }.rowsDeleted shouldBe 2
            outcomes.single { it.table == "mailing_delivery_log" }.rowsDeleted shouldBe 1
            fx.clicksOf(subjectDelivery) shouldBe emptyMap()
            fx.clicksOf(bystanderDelivery) shouldBe mapOf(0 to 1)
            transaction {
                MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.memberId eq subject }.count()
            } shouldBe 0
            transaction {
                MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.memberId eq bystander }.count()
            } shouldBe 1
        }
    })
