package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class MailingTrackingDataTest :
    FunSpec({
        val fx = TrackingFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("recordClick: creates the row, increments, and saturates at the ceiling") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val (delivery, _) = fx.delivery(messageId = msg, memberId = fx.member(), clickTracked = true)
            MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 0, now = DbClock.nowLocalDateTime())
            MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 0, now = DbClock.nowLocalDateTime())
            MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 3, now = DbClock.nowLocalDateTime())
            fx.clicksOf(delivery) shouldBe mapOf(0 to 2, 3 to 1)

            repeat(MailingHtmlPolicy.MAX_EVENT_COUNT + 5) {
                MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 0, now = DbClock.nowLocalDateTime())
            }
            fx.clicksOf(delivery)[0] shouldBe MailingHtmlPolicy.MAX_EVENT_COUNT
        }

        test("recordClick: concurrent first clicks produce exactly one row and lose no increment") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val (delivery, _) = fx.delivery(messageId = msg, memberId = fx.member(), clickTracked = true)
            runBlocking {
                (1..8)
                    .map {
                        async(
                            Dispatchers.IO,
                        ) { MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 1, now = DbClock.nowLocalDateTime()) }
                    }.awaitAll()
            }
            val clicks = fx.clicksOf(delivery)
            clicks.keys shouldBe setOf(1)
            (clicks[1]!! in 1..8) shouldBe true
        }

        test("recordOpen: counts, keeps first_opened_at stable and saturates") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val (delivery, _) = fx.delivery(messageId = msg, memberId = fx.member(), openTracked = true)
            MailingTrackingData.recordOpen(deliveryLogId = delivery, now = DbClock.nowLocalDateTime())
            val first =
                transaction {
                    MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.id eq delivery }.single()[
                        MailingDeliveryLogTable.firstOpenedAt,
                    ]
                }
            (first != null) shouldBe true
            MailingTrackingData.recordOpen(deliveryLogId = delivery, now = DbClock.nowLocalDateTime())
            val second =
                transaction {
                    MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.id eq delivery }.single()[
                        MailingDeliveryLogTable.firstOpenedAt,
                    ]
                }
            second shouldBe first
            fx.openCountOf(delivery) shouldBe 2
            transaction {
                MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id eq delivery }) {
                    it[openCount] = MailingHtmlPolicy.MAX_EVENT_COUNT
                }
            }
            MailingTrackingData.recordOpen(deliveryLogId = delivery, now = DbClock.nowLocalDateTime())
            fx.openCountOf(delivery) shouldBe MailingHtmlPolicy.MAX_EVENT_COUNT
        }

        test("captureLinks: distinct, first-occurrence order, idempotent") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val sanitized =
                MailingHtmlSanitizer.sanitize(
                    "<p><a href=\"https://a.example/\">1</a><a href=\"https://b.example/\">2</a><a href=\"https://a.example/\">3</a>" +
                        "<a href=\"mailto:m@example.org\">m</a></p>",
                )
            transaction { MailingTrackingData.captureLinks(messageId = msg, sanitized = sanitized) }
            transaction { MailingTrackingData.captureLinks(messageId = msg, sanitized = sanitized) }
            val map = transaction { MailingTrackingData.linkIndexByUrl(msg) }
            map shouldBe mapOf("https://a.example/" to 0, "https://b.example/" to 1)
            transaction { MailingMessageLinkTable.selectAll().where { MailingMessageLinkTable.mailingMessageId eq msg }.count() } shouldBe 2
        }

        test("captureLinks: links the click route could not redirect are not captured, indexes stay contiguous") {
            val sender = fx.member()
            val msg = fx.message(listId = fx.list(sender), sentBy = sender)
            val sanitized =
                MailingHtmlSanitizer.sanitize(
                    "<p><a href=\"https://example.org/my page\">1</a><a href=\"https://ok.example/\">2</a>" +
                        "<a href=\"https://example.org/?q=a|b\">3</a><a href=\"https://example.org/%zz\">4</a>" +
                        "<a href=\"https://ex.org/{{x}}\">5</a></p>",
                )
            transaction { MailingTrackingData.captureLinks(messageId = msg, sanitized = sanitized) }
            transaction { MailingTrackingData.linkIndexByUrl(msg) } shouldBe mapOf("https://ok.example/" to 0)
        }

        test("recordClick: writes nothing once the delivery is no longer click_tracked (consent withdrawn mid-request)") {
            val sender = fx.member()
            val msg = fx.message(listId = fx.list(sender), sentBy = sender)
            val (delivery, _) = fx.delivery(messageId = msg, memberId = fx.member(), clickTracked = false)
            MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 0, now = DbClock.nowLocalDateTime())
            fx.clicksOf(delivery) shouldBe emptyMap()
        }

        test("mayCountClick / mayCountOpen need snapshot AND current consent AND active subscription AND non-anonymized member") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)

            val ok = fx.member()
            fx.subscribe(listId = list, memberId = ok, openConsent = true, clickConsent = true)
            fx.delivery(messageId = msg, memberId = ok, openTracked = true, clickTracked = true)

            val noConsent = fx.member()
            fx.subscribe(listId = list, memberId = noConsent)
            fx.delivery(messageId = msg, memberId = noConsent, openTracked = true, clickTracked = true)

            val noSnapshot = fx.member()
            fx.subscribe(listId = list, memberId = noSnapshot, openConsent = true, clickConsent = true)
            fx.delivery(messageId = msg, memberId = noSnapshot)

            val unsub = fx.member()
            fx.subscribe(listId = list, memberId = unsub, openConsent = true, clickConsent = true, unsubscribed = true)
            fx.delivery(messageId = msg, memberId = unsub, openTracked = true, clickTracked = true)

            val anon = fx.member(anonymized = true)
            fx.subscribe(listId = list, memberId = anon, openConsent = true, clickConsent = true)
            fx.delivery(messageId = msg, memberId = anon, openTracked = true, clickTracked = true)

            transaction {
                val refs =
                    listOf(ok, noConsent, noSnapshot, unsub, anon).map { member ->
                        val row =
                            MailingDeliveryLogTable
                                .selectAll()
                                .where {
                                    (MailingDeliveryLogTable.memberId eq member) and (MailingDeliveryLogTable.mailingMessageId eq msg)
                                }.single()
                        MailingTrackingData.findDeliveryByHash(row[MailingDeliveryLogTable.trackingTokenHash]!!)!!
                    }
                refs.map { MailingTrackingData.mayCountClick(it) } shouldBe listOf(true, false, false, false, false)
                refs.map { MailingTrackingData.mayCountOpen(it) } shouldBe listOf(true, false, false, false, false)
            }
        }

        test("eraseForSubscription wipes clicks/opens of exactly the withdrawn kind for that member and list only") {
            val sender = fx.member()
            val list = fx.list(sender)
            val otherList = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val otherMsg = fx.message(listId = otherList, sentBy = sender)
            val member = fx.member()
            val bystander = fx.member()
            val (d1, _) = fx.delivery(messageId = msg, memberId = member, openTracked = true, clickTracked = true)
            val (d2, _) = fx.delivery(messageId = otherMsg, memberId = member, openTracked = true, clickTracked = true)
            val (d3, _) = fx.delivery(messageId = msg, memberId = bystander, openTracked = true, clickTracked = true)
            listOf(d1, d2, d3).forEach {
                MailingTrackingData.recordClick(deliveryLogId = it, linkIndex = 0, now = DbClock.nowLocalDateTime())
                MailingTrackingData.recordOpen(deliveryLogId = it, now = DbClock.nowLocalDateTime())
            }

            transaction { MailingTrackingData.eraseForSubscription(listId = list, memberId = member, eraseOpen = false, eraseClick = true) }
            fx.clicksOf(d1) shouldBe emptyMap()
            fx.openCountOf(d1) shouldBe 1 // open untouched
            fx.clicksOf(d2) shouldBe mapOf(0 to 1) // other list untouched
            fx.clicksOf(d3) shouldBe mapOf(0 to 1) // other member untouched

            transaction { MailingTrackingData.eraseForSubscription(listId = list, memberId = member, eraseOpen = true, eraseClick = false) }
            fx.openCountOf(d1) shouldBe 0
            fx.openCountOf(d2) shouldBe 1
            fx.openCountOf(d3) shouldBe 1
        }
    })
