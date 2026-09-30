package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class MailingTrackingRetentionTest :
    FunSpec({
        val fx = TrackingFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun sentDaysAgo(days: Int): LocalDateTime {
            val now = DbClock.nowLocalDateTime()
            return LocalDateTime(now.date.minus(DatePeriod(days = days)), now.time)
        }

        fun deliveryFor(sentAgoDays: Int): Uuid {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender, sentAt = sentDaysAgo(sentAgoDays))
            fx.link(messageId = msg, index = 0, url = "https://example.org/x")
            val (delivery, _) = fx.delivery(messageId = msg, memberId = fx.member(), openTracked = true, clickTracked = true)
            val now = DbClock.nowLocalDateTime()
            MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 0, now = now)
            MailingTrackingData.recordOpen(deliveryLogId = delivery, now = now)
            return delivery
        }

        fun hashOf(delivery: Uuid): String? =
            transaction {
                MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.id eq delivery }.single()[
                    MailingDeliveryLogTable.trackingTokenHash,
                ]
            }

        fun flagsOf(delivery: Uuid): Triple<Boolean, Boolean, Int> =
            transaction {
                val row = MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.id eq delivery }.single()
                Triple(
                    row[MailingDeliveryLogTable.openTracked],
                    row[MailingDeliveryLogTable.clickTracked],
                    row[MailingDeliveryLogTable.openCount],
                )
            }

        test("messages older than the retention period are cleaned, the token hash survives") {
            val old = deliveryFor(MailingHtmlPolicy.RETENTION_DAYS + 1)
            val hash = hashOf(old)
            MailingTrackingRetention.purgeDue(now = DbClock.nowLocalDateTime(), batchSize = 1000)
            fx.clicksOf(old) shouldBe emptyMap()
            flagsOf(old) shouldBe Triple(false, false, 0)
            hashOf(old) shouldBe hash
        }

        test("messages inside the retention period are untouched") {
            val recent = deliveryFor(MailingHtmlPolicy.RETENTION_DAYS - 1)
            MailingTrackingRetention.purgeDue(now = DbClock.nowLocalDateTime(), batchSize = 1000)
            fx.clicksOf(recent) shouldBe mapOf(0 to 1)
            flagsOf(recent) shouldBe Triple(true, true, 1)
        }

        test("the batch size bounds one purge run") {
            val a = deliveryFor(MailingHtmlPolicy.RETENTION_DAYS + 5)
            val b = deliveryFor(MailingHtmlPolicy.RETENTION_DAYS + 5)
            // Drain whatever other specs left behind first, then prove a batch of 1 cleans exactly 1 of the 2 new rows.
            while (MailingTrackingRetention.purgeDue(now = DbClock.nowLocalDateTime(), batchSize = 1000) == 1000) Unit
            val c = deliveryFor(MailingHtmlPolicy.RETENTION_DAYS + 5)
            val d = deliveryFor(MailingHtmlPolicy.RETENTION_DAYS + 5)
            MailingTrackingRetention.purgeDue(now = DbClock.nowLocalDateTime(), batchSize = 1) shouldBe 1
            val remaining = listOf(c, d).count { flagsOf(it).first }
            remaining shouldBe 1
            listOf(a, b).all { !flagsOf(it).first } shouldBe true
        }

        test("the poller tick never throws and cleans due rows") {
            val old = deliveryFor(MailingHtmlPolicy.RETENTION_DAYS + 9)
            MailingTrackingRetentionPoller().tick()
            flagsOf(old) shouldBe Triple(false, false, 0)
        }

        test("start() and stop() are idempotent") {
            val poller = MailingTrackingRetentionPoller(intervalHours = 24)
            poller.start()
            poller.start()
            poller.stop()
            poller.stop()
        }
    })
