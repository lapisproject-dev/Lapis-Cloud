package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.time.TimeTestSupport
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.Month
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition

/** V1.9.38: golden tests for the generated RFC 5545 VTIMEZONE blocks, so a wrong DST rule cannot ship green. */
class IcsVTimezoneTest :
    FunSpec({
        test("Europe/Berlin yields DAYLIGHT and STANDARD blocks with the EU last-Sunday rules") {
            TimeTestSupport.withServerClock(instant = "2026-07-01T10:00:00Z") {
                IcsVTimezone.render("Europe/Berlin") shouldBe
                    listOf(
                        "BEGIN:VTIMEZONE",
                        "TZID:Europe/Berlin",
                        "BEGIN:DAYLIGHT",
                        "DTSTART:19700329T020000",
                        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31",
                        "TZOFFSETFROM:+0100",
                        "TZOFFSETTO:+0200",
                        "END:DAYLIGHT",
                        "BEGIN:STANDARD",
                        "DTSTART:19701025T030000",
                        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31",
                        "TZOFFSETFROM:+0200",
                        "TZOFFSETTO:+0100",
                        "END:STANDARD",
                        "END:VTIMEZONE",
                    ).joinToString(separator = "\r\n", postfix = "\r\n")
            }
        }

        test("a fixed-offset zone yields a single STANDARD block") {
            TimeTestSupport.withServerClock(instant = "2026-07-01T10:00:00Z") {
                IcsVTimezone.render("Asia/Tbilisi") shouldBe
                    listOf(
                        "BEGIN:VTIMEZONE",
                        "TZID:Asia/Tbilisi",
                        "BEGIN:STANDARD",
                        "DTSTART:19700101T000000",
                        "TZOFFSETFROM:+0400",
                        "TZOFFSETTO:+0400",
                        "END:STANDARD",
                        "END:VTIMEZONE",
                    ).joinToString(separator = "\r\n", postfix = "\r\n")
            }
        }

        test("rules RRULE cannot express are not representable") {
            fun rule(
                indicator: Int,
                midnightEnd: Boolean = false,
            ) = ZoneOffsetTransitionRule.of(
                Month.MARCH,
                indicator,
                DayOfWeek.SUNDAY,
                if (midnightEnd) LocalTime.MIDNIGHT else LocalTime.of(2, 0),
                midnightEnd,
                TimeDefinition.WALL,
                ZoneOffset.ofHours(1),
                ZoneOffset.ofHours(1),
                ZoneOffset.ofHours(2),
            )
            IcsVTimezone.isRepresentable(rule(-1)) shouldBe true
            IcsVTimezone.isRepresentable(rule(25)) shouldBe true
            IcsVTimezone.isRepresentable(rule(26)) shouldBe false
            IcsVTimezone.isRepresentable(rule(-2)) shouldBe false
            IcsVTimezone.isRepresentable(rule(1, midnightEnd = true)) shouldBe false
        }
    })
