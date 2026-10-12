package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.shared.domain.EventOnlinePhase
import kotlin.time.Instant

/**
 * Welle V1.9.113 -- the online participation window of an event: opens 15 minutes before the start, closes 60 minutes after the end,
 * both ends inclusive; computed from the class-B wall-clock of the ORGANIZATION zone (never the process zone). Pure -- no database. The
 * Gradle lanes run it twice, once with a Berlin and once with a UTC process zone (`-PtestTimeZone`): the results must not change.
 */
class EventOnlineWindowTest :
    FunSpec({
        val newYork = TimeZone.of("America/New_York")
        val berlin = TimeZone.of("Europe/Berlin")

        // 2030-06-15 18:00 New York (EDT, UTC-4) = 22:00Z; ends 20:00 = 00:00Z next day.
        val starts = LocalDateTime(2030, 6, 15, 18, 0)
        val ends = LocalDateTime(2030, 6, 15, 20, 0)
        val window = EventOnlineWindow.of(startsAt = starts, endsAt = ends, orgZone = newYork)

        test("the window opens 15 minutes before the start and closes 60 minutes after the end, in the organization zone") {
            window.opensAt shouldBe Instant.parse("2030-06-15T21:45:00Z")
            window.closesAt shouldBe Instant.parse("2030-06-16T01:00:00Z")
        }

        test(
            "the boundaries: 15:01 before is not yet, exactly 15:00 before is open, exactly 60:00 after the end is open, 60:01 after is over",
        ) {
            EventOnlineWindow.phase(window = window, now = Instant.parse("2030-06-15T21:44:59Z")) shouldBe EventOnlinePhase.NOT_YET
            EventOnlineWindow.phase(window = window, now = Instant.parse("2030-06-15T21:45:00Z")) shouldBe EventOnlinePhase.OPEN
            EventOnlineWindow.phase(window = window, now = Instant.parse("2030-06-16T01:00:00Z")) shouldBe EventOnlinePhase.OPEN
            EventOnlineWindow.phase(window = window, now = Instant.parse("2030-06-16T01:00:01Z")) shouldBe EventOnlinePhase.ENDED
        }

        test("the same wall clock in another zone is another instant -- the zone is the organization's, not the process zone") {
            val inBerlin = EventOnlineWindow.of(startsAt = starts, endsAt = ends, orgZone = berlin)
            // Berlin is CEST (UTC+2): 18:00 local = 16:00Z
            inBerlin.opensAt shouldBe Instant.parse("2030-06-15T15:45:00Z")
        }

        test("the wall-clock bounds shown to the client are the organization-zone wall clock of the window") {
            EventOnlineWindow.wallOpensAt(startsAt = starts, orgZone = newYork) shouldBe LocalDateTime(2030, 6, 15, 17, 45)
            EventOnlineWindow.wallClosesAt(endsAt = ends, orgZone = newYork) shouldBe LocalDateTime(2030, 6, 15, 21, 0)
        }

        test(
            "daylight saving: an event right after the spring-forward gap and one in the fall-back overlap keep a 15-minute lead and 60-minute trail",
        ) {
            // Berlin 2030-03-31: 02:00 -> 03:00. 03:10 local is CEST (UTC+2) = 01:10Z.
            val spring =
                EventOnlineWindow.of(
                    startsAt = LocalDateTime(2030, 3, 31, 3, 10),
                    endsAt = LocalDateTime(2030, 3, 31, 4, 0),
                    orgZone = berlin,
                )
            spring.opensAt shouldBe Instant.parse("2030-03-31T00:55:00Z")
            spring.closesAt shouldBe Instant.parse("2030-03-31T03:00:00Z")
            // A start inside the gap (02:30 does not exist) resolves forward (to 03:30 CEST = 01:30Z).
            val gap =
                EventOnlineWindow.of(
                    startsAt = LocalDateTime(2030, 3, 31, 2, 30),
                    endsAt = LocalDateTime(2030, 3, 31, 4, 0),
                    orgZone = berlin,
                )
            gap.opensAt shouldBe Instant.parse("2030-03-31T01:15:00Z")
            // Berlin 2030-10-27: 03:00 -> 02:00. The ambiguous 02:30 resolves to the EARLIER offset (CEST, UTC+2) = 00:30Z.
            val overlap =
                EventOnlineWindow.of(
                    startsAt = LocalDateTime(2030, 10, 27, 2, 30),
                    endsAt = LocalDateTime(2030, 10, 27, 4, 0),
                    orgZone = berlin,
                )
            overlap.opensAt shouldBe Instant.parse("2030-10-27T00:15:00Z")
            overlap.closesAt shouldBe Instant.parse("2030-10-27T04:00:00Z")
        }

        test("the UTC column value of an instant round-trips") {
            val instant = Instant.parse("2030-06-15T21:45:00Z")
            EventOnlineWindow.fromUtc(EventOnlineWindow.toUtc(instant)) shouldBe instant
        }
    })
