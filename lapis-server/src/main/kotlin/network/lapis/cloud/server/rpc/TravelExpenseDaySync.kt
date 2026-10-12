package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalTime
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.rpc.TravelExpenseLineRules.orBadRequest
import network.lapis.cloud.shared.domain.CalcResult
import network.lapis.cloud.shared.domain.DayInput
import network.lapis.cloud.shared.domain.DayPosition
import network.lapis.cloud.shared.domain.MultiDayRule
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseCalculator
import network.lapis.cloud.shared.domain.TravelExpenseDayInput
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.9.122 -- keeps the `DAY_ALLOWANCE` lines of a DRAFT in step with its travel period and
 * is the ONLY place that writes calculated amounts ([recomputeReport]).
 *
 * Every function runs inside the caller's `transaction {}` AFTER the caller took
 * `SELECT ... FOR UPDATE` on the report row -- that lock is what makes "exactly one day line per
 * (report, day)" hold (there is deliberately no unique index, see V97). All functions are
 * idempotent (Exposed may retry a transaction): [sync] only creates what is missing.
 */
internal object TravelExpenseDaySync {
    /**
     * Creates the missing day lines (all values 0), removes EMPTY day lines outside the period
     * (nothing was entered, nothing to lose, day lines have no receipts), and normalises the times of
     * the remaining ones to what their position allows (a day that stopped being the first day loses
     * its departure time, ...). A day line outside the period that already carries data is a
     * [ConflictException] `day_lines_outside_range` -- the member must clear it consciously.
     */
    fun sync(
        reportId: Uuid,
        travelFrom: LocalDate,
        travelTo: LocalDate,
        now: LocalDateTime,
    ) {
        val days = MultiDayRule.travelDays(from = travelFrom, to = travelTo)
        val dayLines =
            TravelExpenseLineTable
                .selectAll()
                .where {
                    (TravelExpenseLineTable.reportId eq reportId) and
                        (TravelExpenseLineTable.kind eq TravelExpenseLineKind.DAY_ALLOWANCE)
                }.toList()
        val byDate = dayLines.associateBy { it[TravelExpenseLineTable.dayDate] }
        val outside = dayLines.filter { it[TravelExpenseLineTable.dayDate] !in days }
        if (outside.any { !it.isEmptyDayLine() }) throw ConflictException("day_lines_outside_range")
        outside.forEach { row -> TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.id eq row[TravelExpenseLineTable.id] } }

        for (day in days) {
            val existing = byDate[day]
            if (existing == null) {
                insertEmptyDayLine(reportId = reportId, day = day, now = now)
            } else {
                normaliseTimes(row = existing, position = MultiDayRule.positionOf(day = day, from = travelFrom, to = travelTo))
            }
        }
    }

    /**
     * Applies the member's day entries. A day that carries ANY entry must satisfy the time rules of
     * its position (single day: both times; first day: departure only; last day: arrival only;
     * middle day: none; arrival not before departure) -- an entirely empty day is accepted as "not
     * filled in yet" and just resets it. Days that are not in [inputs] stay untouched.
     */
    fun saveDays(
        reportId: Uuid,
        travelFrom: LocalDate,
        travelTo: LocalDate,
        inputs: List<TravelExpenseDayInput>,
        now: LocalDateTime,
    ) {
        if (inputs.size > TravelExpenseAmountRules.MAX_DAY_LINES) {
            throw BadRequestException("at most ${TravelExpenseAmountRules.MAX_DAY_LINES} days per report")
        }
        if (inputs.map { it.dayDate }.toSet().size != inputs.size) throw BadRequestException("duplicate_day")
        for (input in inputs) {
            val date = input.dayDate
            if (date < travelFrom || date > travelTo) throw BadRequestException("Day $date: day_outside_range")
            val destination = TravelExpenseLineRules.requireDestination(input.destination)
            val from = input.timeFrom?.toMinutePrecision()
            val to = input.timeTo?.toMinutePrecision()
            val empty =
                from == null &&
                    to == null &&
                    destination == null &&
                    !input.breakfastProvided &&
                    !input.lunchProvided &&
                    !input.dinnerProvided
            if (!empty) {
                // Validate with any rate set: only the error codes are of interest here.
                TravelExpenseCalculator
                    .day(
                        input =
                            DayInput(
                                date = date,
                                timeFrom = from,
                                timeTo = to,
                                breakfastProvided = input.breakfastProvided,
                                lunchProvided = input.lunchProvided,
                                dinnerProvided = input.dinnerProvided,
                            ),
                        travelFrom = travelFrom,
                        travelTo = travelTo,
                        forcedBreakfastDates = emptySet(),
                        rates = TravelExpenseDefaults.pdvTemplate(),
                    ).orBadRequest("Day $date")
            }
            val existing =
                TravelExpenseLineTable
                    .selectAll()
                    .where {
                        (TravelExpenseLineTable.reportId eq reportId) and
                            (TravelExpenseLineTable.kind eq TravelExpenseLineKind.DAY_ALLOWANCE) and
                            (TravelExpenseLineTable.dayDate eq date)
                    }.singleOrNull()
            if (existing == null) insertEmptyDayLine(reportId = reportId, day = date, now = now)
            TravelExpenseLineTable.update({
                (TravelExpenseLineTable.reportId eq reportId) and
                    (TravelExpenseLineTable.kind eq TravelExpenseLineKind.DAY_ALLOWANCE) and
                    (TravelExpenseLineTable.dayDate eq date)
            }) {
                it[timeFrom] = from?.toJavaLocalTime()
                it[timeTo] = to?.toJavaLocalTime()
                it[breakfastProvided] = input.breakfastProvided
                it[lunchProvided] = input.lunchProvided
                it[dinnerProvided] = input.dinnerProvided
                it[TravelExpenseLineTable.destination] = destination
                it[description] = destination.orEmpty()
            }
        }
    }

    /**
     * THE one path that writes amounts. Recomputes every day line (shared calculator, forced
     * breakfast from overnight stays with breakfast) and every MILEAGE line, then the report total.
     *
     * @param strict `false` (draft editing): a day that is not filled in completely counts as 0 and
     *   a mileage line without a configured rate keeps its amount. `true` (submit): any such case is a
     *   [BadRequestException] -- nothing is frozen that the calculator could not calculate.
     */
    fun recomputeReport(
        reportId: Uuid,
        rates: TravelExpenseRateSet,
        strict: Boolean,
    ): BigDecimal {
        val report = TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }.single()
        val travelFrom = report[TravelExpenseReportTable.travelFrom]
        val travelTo = report[TravelExpenseReportTable.travelTo]
        val lines = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq reportId }.toList()
        val forced = forcedBreakfastDatesOf(lines)

        for (row in lines) {
            val lineId = row[TravelExpenseLineTable.id]
            when (row[TravelExpenseLineTable.kind]) {
                TravelExpenseLineKind.DAY_ALLOWANCE -> {
                    val date = requireNotNull(row[TravelExpenseLineTable.dayDate]) { "DAY_ALLOWANCE line $lineId has no day_date" }
                    val result =
                        TravelExpenseCalculator.day(
                            input =
                                DayInput(
                                    date = date,
                                    timeFrom = row[TravelExpenseLineTable.timeFrom]?.toKotlinLocalTime(),
                                    timeTo = row[TravelExpenseLineTable.timeTo]?.toKotlinLocalTime(),
                                    breakfastProvided = row[TravelExpenseLineTable.breakfastProvided],
                                    lunchProvided = row[TravelExpenseLineTable.lunchProvided],
                                    dinnerProvided = row[TravelExpenseLineTable.dinnerProvided],
                                ),
                            travelFrom = travelFrom,
                            travelTo = travelTo,
                            forcedBreakfastDates = forced,
                            rates = rates,
                        )
                    val day =
                        when (result) {
                            is CalcResult.Ok -> result.value
                            is CalcResult.Err -> {
                                if (strict) throw BadRequestException("Day $date: ${result.error.code} (${result.error.key})")
                                null
                            }
                        }
                    TravelExpenseLineTable.update({ TravelExpenseLineTable.id eq lineId }) {
                        it[computedMinutes] = day?.minutes ?: 0
                        it[baseAmount] = (day?.baseCents ?: 0L).centsToAmount()
                        it[deductionAmount] = (day?.deductionCents ?: 0L).centsToAmount()
                        it[amount] = (day?.netCents ?: 0L).centsToAmount()
                    }
                }
                TravelExpenseLineKind.MILEAGE -> {
                    val kmDecimal = requireNotNull(row[TravelExpenseLineTable.kilometers]) { "MILEAGE line $lineId has no kilometers" }
                    val whole = kmDecimal.stripTrailingZeros().scale() <= 0
                    if (!whole) {
                        if (strict) throw BadRequestException("mileage_fractional_km_reenter")
                    } else {
                        val result =
                            TravelExpenseCalculator.mileage(
                                km = kmDecimal.toLong(),
                                passengers = row[TravelExpenseLineTable.passengerCount] ?: 0,
                                rates = rates,
                            )
                        when (result) {
                            is CalcResult.Ok ->
                                TravelExpenseLineTable.update({ TravelExpenseLineTable.id eq lineId }) {
                                    it[amount] = result.value.centsToAmount()
                                    it[rateSnapshot] = requireNotNull(rates.mileageCentsPerKm).centsToRate()
                                }
                            is CalcResult.Err ->
                                if (strict) throw BadRequestException("${result.error.code} (${result.error.key})")
                        }
                    }
                }
                else -> Unit
            }
        }
        return recomputeTotal(reportId)
    }

    fun recomputeTotal(reportId: Uuid): BigDecimal {
        val total =
            TravelExpenseLineTable
                .selectAll()
                .where { TravelExpenseLineTable.reportId eq reportId }
                .toList()
                .fold(BigDecimal.ZERO.setScale(2)) { acc, row -> acc + row[TravelExpenseLineTable.amount] }
        TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq reportId }) {
            it[totalAmount] = total
        }
        return total
    }

    /** `(first night, nights)` pairs of the ACCOMMODATION lines that include breakfast -> the mornings already provided. */
    fun forcedBreakfastDatesOf(lines: List<ResultRow>): Set<LocalDate> =
        TravelExpenseCalculator.forcedBreakfastDates(
            lines
                .filter {
                    it[TravelExpenseLineTable.kind] == TravelExpenseLineKind.ACCOMMODATION &&
                        it[TravelExpenseLineTable.breakfastIncluded] == true &&
                        it[TravelExpenseLineTable.dayDate] != null &&
                        it[TravelExpenseLineTable.days] != null
                }.map { requireNotNull(it[TravelExpenseLineTable.dayDate]) to requireNotNull(it[TravelExpenseLineTable.days]) },
        )

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private fun insertEmptyDayLine(
        reportId: Uuid,
        day: LocalDate,
        now: LocalDateTime,
    ) {
        TravelExpenseLineTable.insert {
            it[id] = Uuid.random()
            it[TravelExpenseLineTable.reportId] = reportId
            it[kind] = TravelExpenseLineKind.DAY_ALLOWANCE
            it[description] = ""
            it[dayDate] = day
            it[computedMinutes] = 0
            it[baseAmount] = BigDecimal.ZERO.setScale(2)
            it[deductionAmount] = BigDecimal.ZERO.setScale(2)
            it[amount] = BigDecimal.ZERO.setScale(2)
            it[breakfastProvided] = false
            it[lunchProvided] = false
            it[dinnerProvided] = false
            it[createdAt] = now
        }
    }

    private fun normaliseTimes(
        row: ResultRow,
        position: DayPosition,
    ) {
        val from = row[TravelExpenseLineTable.timeFrom]
        val to = row[TravelExpenseLineTable.timeTo]
        val wantFrom = if (position == DayPosition.SINGLE || position == DayPosition.FIRST) from else null
        val wantTo = if (position == DayPosition.SINGLE || position == DayPosition.LAST) to else null
        if (wantFrom != from || wantTo != to) {
            TravelExpenseLineTable.update({ TravelExpenseLineTable.id eq row[TravelExpenseLineTable.id] }) {
                it[timeFrom] = wantFrom
                it[timeTo] = wantTo
            }
        }
    }

    private fun ResultRow.isEmptyDayLine(): Boolean =
        this[TravelExpenseLineTable.timeFrom] == null &&
            this[TravelExpenseLineTable.timeTo] == null &&
            this[TravelExpenseLineTable.destination] == null &&
            !this[TravelExpenseLineTable.breakfastProvided] &&
            !this[TravelExpenseLineTable.lunchProvided] &&
            !this[TravelExpenseLineTable.dinnerProvided]

    private fun LocalTime.toMinutePrecision(): LocalTime = LocalTime(hour, minute)
}
