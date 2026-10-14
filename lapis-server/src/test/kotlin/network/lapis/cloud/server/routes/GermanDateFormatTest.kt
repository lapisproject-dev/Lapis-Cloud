package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime

/** V1.9.128 -- [formatEventRange]/[germanWeekday], the human-readable date of the public event and ticket pages. */
class GermanDateFormatTest :
    FunSpec({
        test("same day: weekday, date, 'start bis end Uhr'") {
            formatEventRange(start = LocalDateTime(2026, 10, 20, 20, 15), end = LocalDateTime(2026, 10, 20, 22, 15)) shouldBe
                "Dienstag, 20. Oktober 2026, 20:15 bis 22:15 Uhr"
        }

        test("several days: both ends carry weekday and date") {
            formatEventRange(start = LocalDateTime(2026, 10, 23, 18, 0), end = LocalDateTime(2026, 10, 25, 14, 0)) shouldBe
                "Freitag, 23. Oktober 2026, 18:00 Uhr bis Sonntag, 25. Oktober 2026, 14:00 Uhr"
        }

        test("end == start renders only the start") {
            val at = LocalDateTime(2026, 10, 20, 20, 15)
            formatEventRange(start = at, end = at) shouldBe "Dienstag, 20. Oktober 2026, 20:15 Uhr"
        }

        test("end before start (defensive) renders only the start") {
            formatEventRange(start = LocalDateTime(2026, 10, 20, 20, 15), end = LocalDateTime(2026, 10, 20, 19, 0)) shouldBe
                "Dienstag, 20. Oktober 2026, 20:15 Uhr"
        }

        test("all seven weekdays, Monday first") {
            val names = (19..25).map { day -> germanWeekday(LocalDateTime(2026, 10, day, 12, 0)) }
            names shouldBe listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
        }

        test("midnight and single-digit days are zero-padded in the time and not in the day") {
            formatEventRange(start = LocalDateTime(2030, 1, 1, 0, 5), end = LocalDateTime(2030, 1, 1, 9, 0)) shouldBe
                "Dienstag, 1. Januar 2030, 00:05 bis 09:00 Uhr"
        }
    })
