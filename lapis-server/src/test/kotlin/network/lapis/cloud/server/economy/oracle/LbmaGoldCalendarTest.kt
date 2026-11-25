package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.payment.sepa.Target2Calendar
import network.lapis.cloud.shared.domain.ReferenceDateFallback

/** V1.9.324 -- the London gold market calendar behind the reference-date fallback. */
class LbmaGoldCalendarTest :
    FunSpec({
        test("a weekday without a holiday is its own trading day, reason NONE") {
            LbmaGoldCalendar.previousTradingDayOnOrBefore(LocalDate(2026, 11, 20)) shouldBe
                (LocalDate(2026, 11, 20) to ReferenceDateFallback.NONE)
        }

        test("a Saturday and a Sunday move back to the Friday, reason WEEKEND") {
            LbmaGoldCalendar.previousTradingDayOnOrBefore(LocalDate(2026, 11, 21)) shouldBe
                (LocalDate(2026, 11, 20) to ReferenceDateFallback.WEEKEND)
            LbmaGoldCalendar.previousTradingDayOnOrBefore(LocalDate(2026, 11, 22)) shouldBe
                (LocalDate(2026, 11, 20) to ReferenceDateFallback.WEEKEND)
        }

        test("Good Friday 2026 moves to Thursday, Easter Monday 2026 moves across the weekend to the same Thursday, reason HOLIDAY") {
            LbmaGoldCalendar.previousTradingDayOnOrBefore(LocalDate(2026, 4, 3)) shouldBe
                (LocalDate(2026, 4, 2) to ReferenceDateFallback.HOLIDAY)
            LbmaGoldCalendar.previousTradingDayOnOrBefore(LocalDate(2026, 4, 6)) shouldBe
                (LocalDate(2026, 4, 2) to ReferenceDateFallback.HOLIDAY)
        }

        test("New Year 2028 falls on a Saturday: the substitute Monday 3 January is closed, 31 December 2027 is the last trading day") {
            LbmaGoldCalendar.isTradingDay(LocalDate(2028, 1, 3)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2028, 1, 4)) shouldBe true
            LbmaGoldCalendar.previousTradingDayOnOrBefore(LocalDate(2028, 1, 3)) shouldBe
                (LocalDate(2027, 12, 31) to ReferenceDateFallback.HOLIDAY)
        }

        test("Christmas 2027 (Saturday) and Boxing Day (Sunday) are substituted by Monday 27 and Tuesday 28 December") {
            LbmaGoldCalendar.isTradingDay(LocalDate(2027, 12, 24)) shouldBe true
            LbmaGoldCalendar.isTradingDay(LocalDate(2027, 12, 27)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2027, 12, 28)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2027, 12, 29)) shouldBe true
        }

        test("Christmas 2022 (Sunday): Boxing Day Monday 26 and the Christmas substitute Tuesday 27 are closed") {
            LbmaGoldCalendar.isTradingDay(LocalDate(2022, 12, 26)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2022, 12, 27)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2022, 12, 28)) shouldBe true
        }

        test("Christmas 2026 (Friday): Boxing Day Saturday is substituted by Monday 28 December") {
            LbmaGoldCalendar.isTradingDay(LocalDate(2026, 12, 25)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2026, 12, 28)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2026, 12, 29)) shouldBe true
        }

        test("early May, spring and summer bank holidays 2026") {
            LbmaGoldCalendar.isTradingDay(LocalDate(2026, 5, 4)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2026, 5, 25)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2026, 8, 31)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2026, 5, 11)) shouldBe true
        }

        test("one-off changes: 2020 VE Day, 2022 Platinum Jubilee and state funeral, 2023 coronation") {
            LbmaGoldCalendar.isTradingDay(LocalDate(2020, 5, 4)) shouldBe true
            LbmaGoldCalendar.isTradingDay(LocalDate(2020, 5, 8)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2022, 5, 30)) shouldBe true
            LbmaGoldCalendar.isTradingDay(LocalDate(2022, 6, 2)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2022, 6, 3)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2022, 9, 19)) shouldBe false
            LbmaGoldCalendar.isTradingDay(LocalDate(2023, 5, 8)) shouldBe false
            LbmaGoldCalendar.previousTradingDayOnOrBefore(LocalDate(2020, 5, 8)) shouldBe
                (LocalDate(2020, 5, 7) to ReferenceDateFallback.HOLIDAY)
        }

        test("the German holiday of 3 October is a trading day in London") {
            LbmaGoldCalendar.isTradingDay(LocalDate(2025, 10, 3)) shouldBe true
        }

        test("Good Friday and Easter Monday follow the Easter table 2000-2040") {
            val easter =
                mapOf(
                    2000 to LocalDate(2000, 4, 23),
                    2008 to LocalDate(2008, 3, 23),
                    2016 to LocalDate(2016, 3, 27),
                    2024 to LocalDate(2024, 3, 31),
                    2026 to LocalDate(2026, 4, 5),
                    2030 to LocalDate(2030, 4, 21),
                    2038 to LocalDate(2038, 4, 25),
                    2040 to LocalDate(2040, 4, 1),
                )
            easter.forEach { (year, sunday) ->
                Target2Calendar.easterSunday(year) shouldBe sunday
                LbmaGoldCalendar.isTradingDay(LocalDate.fromEpochDays(sunday.toEpochDays() - 2)) shouldBe false
                LbmaGoldCalendar.isTradingDay(LocalDate.fromEpochDays(sunday.toEpochDays() + 1)) shouldBe false
            }
        }

        test("every day from 2000 to 2040 has a trading day within the look-back") {
            var day = LocalDate(2000, 1, 1)
            while (day.year <= 2040) {
                LbmaGoldCalendar.previousTradingDayOnOrBefore(day)
                day = LocalDate.fromEpochDays(day.toEpochDays() + 1)
            }
        }
    })
