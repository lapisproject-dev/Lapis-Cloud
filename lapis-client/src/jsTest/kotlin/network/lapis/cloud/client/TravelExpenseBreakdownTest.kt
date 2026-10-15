package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import network.lapis.cloud.shared.domain.TravelExpenseCalculator
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.132 -- the breakdown model. The invariant that matters: the rows add up to what the shared calculator says, and the figures come from the
 * rates handed in (a submitted report's FROZEN rates), never from anything current. Hand-calculated 3-day trip, see [entries].
 */
class TravelExpenseBreakdownTest {
    private val d1 = LocalDate(2026, 1, 10)
    private val d2 = LocalDate(2026, 1, 11)
    private val d3 = LocalDate(2026, 1, 12)
    private val rates = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30)

    // Day 1: 08:00 to 24:00 = 16 h -> 12,00. Day 2: 24 h -> 28,00 with lunch (-11,20) -> 16,80.
    // Day 3: 00:00 to 12:00 = 12 h -> 6,00 with lunch + dinner (-22,40, capped at 6,00) -> 0,00.
    private val entries =
        listOf(
            TravelExpenseDayEntry(d1, LocalTime(8, 0), null, false, false, false, null),
            TravelExpenseDayEntry(d2, null, null, false, true, false, null),
            TravelExpenseDayEntry(d3, null, LocalTime(12, 0), false, true, true, null),
        )

    private fun line(
        kind: TravelExpenseLineKind,
        amountCents: Long,
        kilometers: Double? = null,
        rateSnapshot: Double? = null,
        passengers: Int? = null,
        days: Int? = null,
    ) = TravelExpenseLineDto(
        id = "l-$kind-$amountCents",
        reportId = "r",
        kind = kind,
        description = "x",
        kilometers = kilometers?.toDecimal(),
        days = days,
        rateSnapshot = rateSnapshot?.toDecimal(),
        amount = (amountCents / 100.0).toDecimal(),
        passengerCount = passengers,
        amountCents = amountCents,
    )

    private fun report(
        lines: List<TravelExpenseLineDto> = emptyList(),
        status: TravelExpenseReportStatus = TravelExpenseReportStatus.DRAFT,
        payout: TravelExpensePayoutChoice? = null,
        snapshot: TravelExpenseRateSet? = null,
    ) = TravelExpenseReportDto(
        id = "r",
        subjectMemberId = "m",
        subjectDisplayName = "M",
        status = status,
        purpose = "p",
        travelFrom = d1,
        travelTo = d3,
        totalAmount = 0.0.toDecimal(),
        lines = lines,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        requestedBy = "m",
        requestedByDisplayName = "M",
        payoutChoice = payout,
        ratesSnapshot = snapshot,
    )

    private fun ok(model: BreakdownModel): BreakdownModel.Ok = assertIs<BreakdownModel.Ok>(model)

    private val mileage = line(TravelExpenseLineKind.MILEAGE, 3200, kilometers = 100.0, rateSnapshot = 0.3, passengers = 1)
    private val hotel = line(TravelExpenseLineKind.ACCOMMODATION, 8000)

    @Test
    fun theRows_areGrossDeductionsLinesSubtotalAdvancePayout_inThatOrder_withHandCalculatedFigures() {
        val model = ok(buildBreakdown(report(listOf(mileage, hotel)), rates, entries, advanceCents = 5000))
        assertEquals(
            listOf(
                BreakdownRowKind.GROSS,
                BreakdownRowKind.DEDUCTION,
                BreakdownRowKind.LINE,
                BreakdownRowKind.LINE,
                BreakdownRowKind.LINE,
                BreakdownRowKind.SUBTOTAL,
                BreakdownRowKind.ADVANCE,
                BreakdownRowKind.PAYOUT,
            ),
            model.rows.map { it.kind },
        )
        assertEquals(listOf(4600L, -1720L, 3000L, 200L, 8000L, 14080L, -5000L, 9080L), model.rows.map { it.cents })
    }

    @Test
    fun theRowsAboveTheSubtotal_addUpToTheCalculatorsSubtotal() {
        val model = ok(buildBreakdown(report(listOf(mileage, hotel)), rates, entries, advanceCents = 0))
        val subtotalRow = model.rows.single { it.kind == BreakdownRowKind.SUBTOTAL }
        assertEquals(subtotalRow.cents, model.rows.takeWhile { it.kind != BreakdownRowKind.SUBTOTAL }.sumOf { it.cents })
        assertEquals(model.totals.subtotalCents, subtotalRow.cents)
        assertEquals(model.totals.subtotalCents, model.days.sumOf { it.netCents } + 3200 + 8000)
        assertEquals(
            model.totals,
            TravelExpenseCalculator.totals(
                model.days.map {
                    it.netCents
                } + listOf(3200L, 8000L),
                model.days.map { it.deductionCents },
                0,
            ),
        )
    }

    @Test
    fun theDays_carryTheMealsAndTheCap() {
        val model = ok(buildBreakdown(report(), rates, entries, advanceCents = 0))
        assertEquals(listOf(1200L, 2800L, 600L), model.days.map { it.baseCents })
        assertEquals(listOf(1200L, 1680L, 0L), model.days.map { it.netCents })
        assertEquals(emptyList(), model.days[0].meals)
        assertEquals(listOf(Meal.LUNCH), model.days[1].meals.map { it.meal })
        assertEquals(
            1120L,
            model.days[1]
                .meals
                .single()
                .cents,
        )
        assertEquals(
            40,
            model.days[1]
                .meals
                .single()
                .percent,
        )
        assertNull(model.days[1].cappedAtCents, "11,20 is below the 28,00 allowance: nothing capped")
        assertEquals(600L, model.days[2].cappedAtCents, "22,40 of meals, capped at the 6,00 allowance")
        assertEquals(600L, model.days[2].deductionCents)
        assertEquals(2800L, model.days[2].fullTierCents)
    }

    @Test
    fun aMileageLine_withoutPassengers_isOneRow() {
        val own = line(TravelExpenseLineKind.MILEAGE, 3000, kilometers = 100.0, rateSnapshot = 0.3, passengers = 0)
        val rows = ok(buildBreakdown(report(listOf(own)), rates, emptyList(), 0)).rows.filter { it.kind == BreakdownRowKind.LINE }
        assertEquals(1, rows.size)
        assertEquals(3000L, rows.single().cents)
        assertTrue(rows.single().label.startsWith("Fahrtkosten: 100 km"), rows.single().label)
    }

    @Test
    fun aMileageLine_usesTheRateSetsRate_whenTheLineHasNone() {
        val noRate = line(TravelExpenseLineKind.MILEAGE, 3200, kilometers = 100.0, passengers = 1)
        val rows = ok(buildBreakdown(report(listOf(noRate)), rates, emptyList(), 0)).rows.filter { it.kind == BreakdownRowKind.LINE }
        assertEquals(listOf(3000L, 200L), rows.map { it.cents })
    }

    @Test
    fun aMileageLine_thatDoesNotAddUp_showsOnlyTheStoredAmount_withoutAFormula() {
        val odd = line(TravelExpenseLineKind.MILEAGE, 3100, kilometers = 100.0, rateSnapshot = 0.3, passengers = 1)
        val rows = ok(buildBreakdown(report(listOf(odd)), rates, emptyList(), 0)).rows.filter { it.kind == BreakdownRowKind.LINE }
        assertEquals(1, rows.size)
        assertEquals(3100L, rows.single().cents)
        assertEquals("Fahrtkosten", rows.single().label)
    }

    @Test
    fun aLegacyPerDiemLine_isItsOwnRow_soTheSumStaysReproducible() {
        val legacy = line(TravelExpenseLineKind.PER_DIEM, 5600, rateSnapshot = 28.0, days = 2)
        val model = ok(buildBreakdown(report(listOf(legacy)), rates, emptyList(), 0))
        val row = model.rows.single { it.kind == BreakdownRowKind.LINE }
        assertEquals(5600L, row.cents)
        assertTrue(row.label.startsWith("Tagegeld (alte Erfassung)"), row.label)
        assertEquals(5600L, model.totals.subtotalCents)
    }

    @Test
    fun otherLinesOfOneKind_areSummedIntoOneRow() {
        val a = line(TravelExpenseLineKind.PUBLIC_TRANSPORT, 1000)
        val b = line(TravelExpenseLineKind.PUBLIC_TRANSPORT, 550)
        val rows = ok(buildBreakdown(report(listOf(a, b)), rates, emptyList(), 0)).rows.filter { it.kind == BreakdownRowKind.LINE }
        assertEquals(listOf(1550L), rows.map { it.cents })
    }

    @Test
    fun withoutRates_thereIsNoBreakdown_andNoNumber() {
        assertEquals(BreakdownModel.NoSnapshot, buildBreakdown(report(status = TravelExpenseReportStatus.REQUESTED), null, null, 0))
    }

    @Test
    fun theFigures_comeFromTheRatesHandedIn_notFromAnyOtherSet() {
        val frozen = TravelExpenseRateSet(tierCents = listOf(500L, 1000L, 2000L))
        val submitted = report(status = TravelExpenseReportStatus.REQUESTED, snapshot = frozen, lines = storedDays())
        val withFrozen = ok(buildBreakdown(submitted, submitted.ratesSnapshot, null, 0))
        val withOther = ok(buildBreakdown(submitted, rates, null, 0))
        assertEquals(listOf(1000L, 2000L, 500L), withFrozen.days.map { it.baseCents }, "16 h / 24 h / 12 h on the frozen 5/10/20 EUR tiers")
        assertEquals(listOf(1200L, 2800L, 600L), withOther.days.map { it.baseCents })
        assertEquals(2000L, withFrozen.days[0].fullTierCents)
    }

    @Test
    fun storedDayLines_areReadWhenNoGridEntriesAreGiven() {
        val model = ok(buildBreakdown(report(lines = storedDays()), rates, null, 0))
        assertEquals(3, model.days.size)
        assertEquals(listOf(d1, d2, d3), model.days.map { it.date })
    }

    @Test
    fun aDonationWaiver_isNamedAsSuchInTheLastRow() {
        val normal = ok(buildBreakdown(report(listOf(hotel), payout = TravelExpensePayoutChoice.PAYOUT), rates, emptyList(), 0))
        val waiver = ok(buildBreakdown(report(listOf(hotel), payout = TravelExpensePayoutChoice.DONATION_WAIVER), rates, emptyList(), 0))
        assertEquals("Auszahlungsbetrag", normal.rows.last().label)
        assertEquals("Verzicht zugunsten einer Spende", waiver.rows.last().label)
        assertEquals(normal.rows.last().cents, waiver.rows.last().cents)
    }

    @Test
    fun anIncompleteDay_countsZero_andIsMarkedIncomplete() {
        val partial = listOf(TravelExpenseDayEntry(d1, null, null, false, false, false, null))
        val model = ok(buildBreakdown(report(), rates, partial, 0))
        assertFalse(model.days.single().complete)
        assertEquals(0L, model.days.single().netCents)
    }

    private fun storedDays(): List<TravelExpenseLineDto> =
        entries.map { e ->
            TravelExpenseLineDto(
                id = "day-${e.date}",
                reportId = "r",
                kind = TravelExpenseLineKind.DAY_ALLOWANCE,
                description = "",
                amount = 0.0.toDecimal(),
                dayDate = e.date,
                timeFrom = e.timeFrom,
                timeTo = e.timeTo,
                lunchProvided = e.lunchProvided,
                dinnerProvided = e.dinnerProvided,
            )
        }
}
