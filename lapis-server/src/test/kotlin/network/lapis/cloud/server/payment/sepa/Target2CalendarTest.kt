package network.lapis.cloud.server.payment.sepa

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

class Target2CalendarTest :
    FunSpec({
        test("Easter Sunday of 2024 to 2030 (known dates)") {
            mapOf(
                2024 to LocalDate(2024, 3, 31),
                2025 to LocalDate(2025, 4, 20),
                2026 to LocalDate(2026, 4, 5),
                2027 to LocalDate(2027, 3, 28),
                2028 to LocalDate(2028, 4, 16),
                2029 to LocalDate(2029, 4, 1),
                2030 to LocalDate(2030, 4, 21),
            ).forEach { (year, easter) -> Target2Calendar.easterSunday(year) shouldBe easter }
        }

        test("the six closing holidays are closed in every year of the range") {
            (2024..2030).forEach { year ->
                val easter = Target2Calendar.easterSunday(year)
                listOf(
                    LocalDate(year, 1, 1),
                    LocalDate.fromEpochDays(easter.toEpochDays() - 2),
                    LocalDate.fromEpochDays(easter.toEpochDays() + 1),
                    LocalDate(year, 5, 1),
                    LocalDate(year, 12, 25),
                    LocalDate(year, 12, 26),
                ).forEach { Target2Calendar.isOpen(it) shouldBe false }
            }
        }

        test("weekends are closed, ordinary weekdays are open, other German holidays do not close TARGET2") {
            Target2Calendar.isOpen(LocalDate(2026, 10, 17)) shouldBe false // Saturday
            Target2Calendar.isOpen(LocalDate(2026, 10, 18)) shouldBe false // Sunday
            Target2Calendar.isOpen(LocalDate(2026, 10, 13)) shouldBe true
            Target2Calendar.isOpen(LocalDate(2026, 10, 3)) shouldBe false // Saturday anyway
            Target2Calendar.isOpen(LocalDate(2027, 10, 4)) shouldBe true // day after the Day of German Unity (Sunday): Monday
            Target2Calendar.isOpen(LocalDate(2026, 5, 14)) shouldBe true // Ascension Day is a TARGET2 business day
        }

        test("nextOpenDayAfter skips weekends and the Easter closures") {
            Target2Calendar.nextOpenDayAfter(LocalDate(2026, 10, 13)) shouldBe LocalDate(2026, 10, 14)
            Target2Calendar.nextOpenDayAfter(LocalDate(2026, 10, 16)) shouldBe LocalDate(2026, 10, 19)
            // Thursday before Easter 2026 (5 April): Good Friday 3 April and Easter Monday 6 April are closed -> Tuesday 7 April.
            Target2Calendar.nextOpenDayAfter(LocalDate(2026, 4, 2)) shouldBe LocalDate(2026, 4, 7)
            Target2Calendar.nextOpenDayAfter(LocalDate(2026, 12, 24)) shouldBe LocalDate(2026, 12, 28)
            Target2Calendar.nextOpenDayAfter(LocalDate(2026, 12, 31)) shouldBe LocalDate(2027, 1, 4)
            Target2Calendar.nextOpenDayAfter(LocalDate(2026, 4, 30)) shouldBe LocalDate(2026, 5, 4)
        }
    })
