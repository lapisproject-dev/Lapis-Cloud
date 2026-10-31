package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import java.time.temporal.ChronoUnit

class PartnerBackoffTest :
    FunSpec({
        test("the back-off grows 1, 2, 4, 8, 16, then stays at 24 hours") {
            (1..8).map { PartnerServerStore.backoffHours(it) } shouldBe listOf(1L, 2L, 4L, 8L, 16L, 24L, 24L, 24L)
        }

        test("a huge failure count does not overflow") {
            PartnerServerStore.backoffHours(1000) shouldBe 24L
            PartnerServerStore.backoffHours(Int.MAX_VALUE) shouldBe 24L
            PartnerServerStore.backoffHours(0) shouldBe 1L
        }

        val now = LocalDateTime(2026, 11, 5, 10, 17, 42)

        test("the next slot is deterministic, after the next full hour and within one hour of it") {
            val first = PartnerServerStore.nextSlot(host = "partner.example.de", now = now, salt = "salt-a")
            PartnerServerStore.nextSlot(host = "partner.example.de", now = now, salt = "salt-a") shouldBe first
            val nextHour = LocalDateTime(2026, 11, 5, 11, 0, 0).toJavaLocalDateTime()
            val offset = ChronoUnit.SECONDS.between(nextHour, first.toJavaLocalDateTime())
            offset shouldBeInRange 0L..3599L
        }

        test("a different salt or host gives a different slot") {
            val a = PartnerServerStore.nextSlot(host = "partner.example.de", now = now, salt = "salt-a")
            PartnerServerStore.nextSlot(host = "partner.example.de", now = now, salt = "salt-b") shouldNotBe a
            PartnerServerStore.nextSlot(host = "other.example.de", now = now, salt = "salt-a") shouldNotBe a
        }
    })
