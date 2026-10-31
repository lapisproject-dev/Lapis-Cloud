package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import java.time.Duration

class PartnerRecursionScheduleTest :
    FunSpec({
        val last = LocalDateTime(2026, 11, 5, 10, 0, 0)

        fun offsetSeconds(salt: String): Long {
            // The smallest delay after the hour for which a run is due, found by scanning the jitter window.
            val base = last.toJavaLocalDateTime().plusHours(1)
            return (0L..PartnerRecursionLimits.RUN_JITTER_SECONDS).first { s ->
                PartnerRecursionFrontier.isRunDue(lastRunAt = last, now = base.plusSeconds(s).toKotlinLocalDateTime(), salt = salt)
            }
        }

        test("a run is due when none is stored") {
            PartnerRecursionFrontier.isRunDue(lastRunAt = null, now = last, salt = "s") shouldBe true
        }

        test("not before one hour has passed, whatever the salt") {
            listOf("a", "b", "c").forEach { salt ->
                PartnerRecursionFrontier.isRunDue(
                    lastRunAt = last,
                    now = LocalDateTime(2026, 11, 5, 10, 59, 59),
                    salt = salt,
                ) shouldBe false
            }
        }

        test("the offset lies in [0, 300) seconds and is deterministic per salt") {
            val offsets = listOf("a", "b", "c", "d", "e", "f").map { offsetSeconds(it) }
            offsets.forEach { (it in 0 until PartnerRecursionLimits.RUN_JITTER_SECONDS) shouldBe true }
            offsetSeconds("a") shouldBe offsetSeconds("a")
            (offsets.distinct().size > 1) shouldBe true
        }

        test("always due after 65 minutes") {
            val now = last.toJavaLocalDateTime().plus(Duration.ofMinutes(65))
            PartnerRecursionFrontier.isRunDue(
                lastRunAt = last,
                now = LocalDateTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, 0),
                salt = "anything",
            ) shouldBe true
        }
    })
