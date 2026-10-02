package network.lapis.cloud.server.time

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DbClock
import kotlin.time.Instant

/**
 * V1.9.38: [ServerClock] is the one clock of the server. Its storage zone is a constant (UTC), so the result never depends on the
 * zone of the process or the container -- the tests below flip the JVM default zone to prove it.
 */
class ServerClockTest :
    FunSpec({
        val berlin = TimeZone.of("Europe/Berlin")

        test("the storage zone is UTC") {
            ServerClock.zone shouldBe TimeZone.UTC
        }

        test("now() is the UTC wall-clock of the instant, whatever the zone of the JVM is") {
            val original = java.util.TimeZone.getDefault()
            try {
                listOf("UTC", "Europe/Berlin", "America/Los_Angeles", "Asia/Tbilisi").forEach { processZone ->
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(processZone))
                    TimeTestSupport.withServerClock(instant = "2026-07-01T10:00:00Z") {
                        ServerClock.now() shouldBe LocalDateTime(2026, 7, 1, 10, 0, 0)
                        ServerClock.nowIn(berlin) shouldBe LocalDateTime(2026, 7, 1, 12, 0, 0)
                        ServerClock.todayIn(berlin) shouldBe LocalDate(2026, 7, 1)
                    }
                }
            } finally {
                java.util.TimeZone.setDefault(original)
            }
        }

        test("DbClock.nowLocalDateTime() defaults to UTC, not to the zone of the process") {
            val original = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
                val before =
                    kotlin.time.Clock.System
                        .now()
                val stamp = DbClock.nowLocalDateTime()
                val after =
                    kotlin.time.Clock.System
                        .now()
                val asInstant = ServerClock.systemToInstant(stamp)
                // truncated to microseconds, so it may be a hair earlier than `before`
                (asInstant >= before - kotlin.time.Duration.parse("1s")) shouldBe true
                (asInstant <= after) shouldBe true
            } finally {
                java.util.TimeZone.setDefault(original)
            }
        }

        test("todayIn rolls over at the organization's midnight, not at UTC midnight") {
            TimeTestSupport.withServerClock(instant = "2026-12-31T23:30:00Z") {
                ServerClock.todayIn(TimeZone.UTC) shouldBe LocalDate(2026, 12, 31)
                ServerClock.todayIn(berlin) shouldBe LocalDate(2027, 1, 1)
            }
        }

        test("wallToInstant: summer and winter offsets") {
            ServerClock.wallToInstant(wall = LocalDateTime(2026, 7, 1, 20, 0), orgZone = berlin) shouldBe
                Instant.parse("2026-07-01T18:00:00Z")
            ServerClock.wallToInstant(wall = LocalDateTime(2026, 12, 1, 20, 0), orgZone = berlin) shouldBe
                Instant.parse("2026-12-01T19:00:00Z")
        }

        // The library's DST behaviour is pinned here so a kotlinx-datetime update cannot change it silently (plan S10).
        test("wallToInstant: a wall-clock in the spring gap resolves forward (02:30 does not exist on 2026-03-29)") {
            ServerClock.wallToInstant(wall = LocalDateTime(2026, 3, 29, 2, 30), orgZone = berlin) shouldBe
                Instant.parse("2026-03-29T01:30:00Z")
        }

        test("wallToInstant: an ambiguous wall-clock in the autumn overlap takes the earlier offset (02:30 on 2026-10-25)") {
            ServerClock.wallToInstant(wall = LocalDateTime(2026, 10, 25, 2, 30), orgZone = berlin) shouldBe
                Instant.parse("2026-10-25T00:30:00Z")
        }

        test("systemToWall re-expresses one instant, so injected test clocks stay consistent") {
            ServerClock.systemToWall(utc = LocalDateTime(2026, 7, 1, 18, 0), orgZone = berlin) shouldBe LocalDateTime(2026, 7, 1, 20, 0)
            ServerClock.systemToWall(utc = LocalDateTime(2026, 10, 25, 0, 30), orgZone = berlin) shouldBe LocalDateTime(2026, 10, 25, 2, 30)
            ServerClock.systemToWall(utc = LocalDateTime(2026, 10, 25, 1, 30), orgZone = berlin) shouldBe LocalDateTime(2026, 10, 25, 2, 30)
        }
    })
