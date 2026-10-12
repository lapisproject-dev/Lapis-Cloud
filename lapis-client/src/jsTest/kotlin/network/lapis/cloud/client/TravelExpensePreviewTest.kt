package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.122 -- the client preview feeds the SHARED calculator and adds nothing of its own: these tests pin that the preview of a trip is
 * exactly what the shared rules say (the same hand-calculated 3-day trip the server test uses).
 */
class TravelExpensePreviewTest {
    private val d1 = LocalDate(2026, 1, 10)
    private val d2 = LocalDate(2026, 1, 11)
    private val d3 = LocalDate(2026, 1, 12)
    private val rates = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30)

    private fun report(lines: List<TravelExpenseLineDto> = emptyList()) =
        TravelExpenseReportDto(
            id = "r",
            subjectMemberId = "m",
            subjectDisplayName = "M",
            status = TravelExpenseReportStatus.DRAFT,
            purpose = "p",
            travelFrom = d1,
            travelTo = d3,
            totalAmount = 0.0.toDecimal(),
            lines = lines,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
            requestedBy = "m",
            requestedByDisplayName = "M",
        )

    private fun entry(
        date: LocalDate,
        from: LocalTime? = null,
        to: LocalTime? = null,
        lunch: Boolean = false,
        dinner: Boolean = false,
    ) = TravelExpenseDayEntry(date, from, to, breakfastProvided = false, lunchProvided = lunch, dinnerProvided = dinner, destination = null)

    @Test
    fun threeDayTrip_matchesTheHandCalculation() {
        val entries =
            listOf(
                entry(d1, from = LocalTime(10, 0)),
                entry(d2, lunch = true, dinner = true),
                entry(d3, to = LocalTime(7, 30)),
            )
        val preview = travelExpensePreview(report(), rates, entries, advanceCents = 0)
        assertEquals(listOf(1200L, 560L, 0L), preview.days.map { it.result?.netCents })
        assertEquals(1760L, preview.totals.subtotalCents)
        assertEquals(2240L, preview.totals.deductionCents)
        assertEquals(1760L, preview.totals.payoutCents)
    }

    @Test
    fun anIncompleteDayCountsAsZero_notAsAnError() {
        // a first day without a departure time
        val preview = travelExpensePreview(report(), rates, listOf(entry(d1)), advanceCents = 0)
        assertNull(preview.days.single().result)
        assertEquals(0L, preview.totals.subtotalCents)
    }

    @Test
    fun otherLinesKeepTheServerAmount_andTheAdvanceReducesThePayout() {
        val mileage =
            TravelExpenseLineDto(
                id = "l",
                reportId = "r",
                kind = TravelExpenseLineKind.MILEAGE,
                description = "Fahrt",
                amount = 36.0.toDecimal(),
                amountCents = 3600,
            )
        val preview = travelExpensePreview(report(listOf(mileage)), rates, emptyList(), advanceCents = 1000)
        assertEquals(3600L, preview.totals.subtotalCents)
        assertEquals(2600L, preview.totals.payoutCents)
        val over = travelExpensePreview(report(listOf(mileage)), rates, emptyList(), advanceCents = 5000)
        assertTrue(over.totals.advanceExceedsTotal)
        assertEquals(0L, over.totals.payoutCents)
    }

    @Test
    fun anOvernightStayWithBreakfast_lockstheBreakfastDeductionInThePreview() {
        val hotel =
            TravelExpenseLineDto(
                id = "h",
                reportId = "r",
                kind = TravelExpenseLineKind.ACCOMMODATION,
                description = "Hotel",
                amount = 100.0.toDecimal(),
                amountCents = 10000,
                dayDate = d1,
                nights = 2,
                breakfastIncluded = true,
            )
        assertEquals(setOf(d2, d3), travelExpenseForcedBreakfastDates(listOf(hotel)))
        val preview = travelExpensePreview(report(listOf(hotel)), rates, listOf(entry(d2)), advanceCents = 0)
        assertEquals(
            2240L,
            preview.days
                .single()
                .result
                ?.netCents,
            "28.00 - 20 % breakfast",
        )
        assertTrue(
            preview.days
                .single()
                .result
                ?.breakfastForcedByAccommodation == true,
        )
    }

    @Test
    fun parsers_areExactAndStrict() {
        assertEquals(LocalTime(8, 5), travelExpenseParseTime("08:05"))
        assertNull(travelExpenseParseTime(""))
        assertNull(travelExpenseParseTime("25:00"))
        assertEquals(1250L, travelExpenseParseCents("12,50"))
        assertEquals(1250L, travelExpenseParseCents("12.5"))
        assertEquals(0L, travelExpenseParseCents(""))
        assertNull(travelExpenseParseCents("12,505"))
        assertNull(travelExpenseParseCents("-1"))
        assertEquals("12,50", travelExpenseCentsText(1250))
        assertEquals("16:00", travelExpenseDuration(960))
        assertEquals("0:05", travelExpenseDuration(5))
    }

    @Test
    fun deadline_isTheEndOfTheTravelYear_andNeverBlocks() {
        val r = report()
        assertFalse(travelExpenseDeadlineOverdue(r, LocalDate(2026, 12, 31)))
        assertTrue(travelExpenseDeadlineOverdue(r, LocalDate(2027, 1, 1)))
    }

    @Test
    fun dayLinesOutsideTheNewRange_areOnlyReportedWhenTheyCarryData() {
        val empty =
            TravelExpenseLineDto(
                id = "a",
                reportId = "r",
                kind = TravelExpenseLineKind.DAY_ALLOWANCE,
                description = "",
                amount = 0.0.toDecimal(),
                dayDate = d3,
            )
        val filled = empty.copy(id = "b", timeTo = LocalTime(9, 0))
        assertFalse(travelExpenseDayLinesOutsideRange(report(listOf(empty)), d1, d2))
        assertTrue(travelExpenseDayLinesOutsideRange(report(listOf(filled)), d1, d2))
        assertFalse(travelExpenseDayLinesOutsideRange(report(listOf(filled)), d1, d3))
    }
}
