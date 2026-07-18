package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.ReserveMovementDto
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.domain.SphereAmountDto
import network.lapis.cloud.shared.domain.UseOfFundsStatementDto
import network.lapis.cloud.shared.domain.UseOfFundsYearDto
import java.math.BigDecimal

/**
 * Pure §55 AO Mittelverwendungsrechnung (use-of-funds / timely-use tracking) + §62 AO
 * Rücklagenbildung (reserve formation) derivation, extracted so it is unit-testable without a
 * database -- same "pure logic extracted to a sibling file" idiom as
 * [FinancialStatementCalculator]/[GeneralLedgerCalculator]/[JournalEntryBalance].
 *
 * **This is a treasurer's Nachweis (evidence) aid, not an automated compliance verdict.** It
 * deliberately does NOT enforce the §62 Abs.1 Nr.3 freie-Rücklage percentage cap (a number a human
 * must configure/verify against current law -- see
 * [network.lapis.cloud.shared.domain.ReserveType.FREIE_RUECKLAGE] KDoc), does NOT auto-apply the
 * §55 Abs.1 Nr.5 Satz 4 small-organization (≤45.000 EUR) exemption, and does NOT certify
 * Gemeinnützigkeit status one way or the other.
 *
 * **The §55 AO timely-use clock is a FIFO vintage pot, org-wide (aggregate), NOT per-sphere** --
 * surpluses legitimately move between spheres, so a per-sphere clock would misstate the obligation;
 * per-sphere received/used is surfaced only as an informational disaggregation
 * ([UseOfFundsYearDto.receivedBySphere]/[UseOfFundsYearDto.usedBySphere]). Each fiscal year:
 *  - a new "vintage" enters the pot equal to that year's [YearFacts.income], dated to that year;
 *  - funds leave the pot, oldest vintage first (FIFO), equal to that year's [YearFacts.expense]
 *    plus any *net positive* reserve allocation (funds moved into a valid §62 reserve leave the
 *    §55 clock -- they are no longer "unused");
 *  - a *net negative* reserve allocation (a net dissolution) returns funds to the pot as a
 *    **new, current-year vintage** -- a deliberate simplification flagged for human review: in
 *    reality a dissolved reserve's original vintage-dating question is genuinely ambiguous, and
 *    re-dating it to the dissolution year is the conservative (more likely to flag work as
 *    time-barred, never less) choice.
 *
 * A vintage dated fiscal year `Y0` is **overdue** at the end of fiscal year `Y` iff
 * `Y - Y0 > TIMELY_USE_YEARS` (i.e. strictly more than [TIMELY_USE_YEARS] fiscal years have
 * elapsed since receipt without the funds being used or reserved).
 *
 * **The clock is anchored at inception**: [statement] always rolls the FIFO pot forward from the
 * *earliest* fiscal year present in [YearFacts] (or `fromFiscalYear` if [facts] is empty), through
 * `toFiscalYear` -- never merely from `fromFiscalYear`. Windowing the roll-forward itself to
 * `fromFiscalYear` would silently corrupt the reported Mittelvortrag/overdue figures whenever the
 * requested window starts after the organization's first fiscal year of activity. `fromFiscalYear`
 * only slices which years are *reported* in [UseOfFundsStatementDto.years].
 *
 * The pot is floored at `0` if cumulative consumption (expense + net reserve allocation) ever
 * exceeds cumulative inflow (income + net reserve dissolution) -- there is no "negative obligation".
 *
 * **BigDecimal pitfall, deliberately guarded against** (same class of bug as
 * [JournalEntryBalance]/[FinancialStatementCalculator]'s KDoc): every comparison is via
 * `BigDecimal.compareTo`, never `equals`/`==`.
 */
internal object UseOfFundsCalculator {
    /**
     * §55 AO -- historically interpreted as "by the end of the second fiscal year following
     * receipt". Surfaced as [UseOfFundsStatementDto.timelyUseYears] rather than a silent constant
     * -- **verify this interpretation against current AO guidance before relying on it**.
     */
    const val TIMELY_USE_YEARS = 2

    private val ZERO = BigDecimal.ZERO

    /**
     * One fiscal year's raw facts, as loaded by the caller (`AccountingService.loadYearFacts`)
     * from `POSTED`-only postings joined to ledger accounts. [reserveAllocationByType] is the net
     * Zuführung (may be negative = net dissolution) per [ReserveType] *in* [fiscalYear];
     * [reserveClosingByType] is the *cumulative* reserve balance per [ReserveType] as of the end of
     * [fiscalYear] (not a per-year delta) -- if a fiscal year between the earliest activity and
     * `toFiscalYear` has no [YearFacts] entry at all (a gap year with zero postings),
     * [statement] carries the previous year's [reserveClosingByType] forward and treats every
     * other field as zero for that gap year.
     */
    data class YearFacts(
        val fiscalYear: Int,
        val income: BigDecimal,
        val expense: BigDecimal,
        val incomeBySphere: Map<GemeinnuetzigkeitSphere, BigDecimal>,
        val expenseBySphere: Map<GemeinnuetzigkeitSphere, BigDecimal>,
        val reserveAllocationByType: Map<ReserveType, BigDecimal>,
        val reserveClosingByType: Map<ReserveType, BigDecimal>,
    )

    /** One FIFO vintage: an amount still "at risk" of the timely-use clock, dated to the fiscal year it entered the pot. */
    private data class Vintage(
        val vintageYear: Int,
        val amount: BigDecimal,
    )

    /**
     * Derives a [UseOfFundsStatementDto] for `[fromFiscalYear, toFiscalYear]` from [facts]. See
     * class KDoc for the full FIFO/inception-anchoring/reserve-dissolution rules. [facts] need not
     * contain an entry for every fiscal year in range -- missing years are treated as zero-activity
     * gap years (see [YearFacts] KDoc).
     */
    fun statement(
        facts: List<YearFacts>,
        fromFiscalYear: Int,
        toFiscalYear: Int,
    ): UseOfFundsStatementDto {
        val factsByYear = facts.associateBy { it.fiscalYear }
        val inceptionYear = facts.minOfOrNull { it.fiscalYear } ?: fromFiscalYear
        val rollFrom = minOf(inceptionYear, fromFiscalYear)

        var pot: List<Vintage> = emptyList()
        var lastReserveClosing: Map<ReserveType, BigDecimal> = emptyMap()
        val rows = mutableListOf<UseOfFundsYearDto>()

        for (year in rollFrom..toFiscalYear) {
            val yearFacts = factsByYear[year]
            val income = yearFacts?.income ?: ZERO
            val expense = yearFacts?.expense ?: ZERO
            val incomeBySphere = yearFacts?.incomeBySphere ?: emptyMap()
            val expenseBySphere = yearFacts?.expenseBySphere ?: emptyMap()
            val reserveAllocation = yearFacts?.reserveAllocationByType ?: emptyMap()
            val reserveClosing = yearFacts?.reserveClosingByType ?: lastReserveClosing
            lastReserveClosing = reserveClosing

            val netReserveAllocation = reserveAllocation.values.fold(ZERO) { acc, v -> acc + v }
            val dissolutionInflow = if (netReserveAllocation.compareTo(ZERO) < 0) netReserveAllocation.negate() else ZERO
            val allocationOutflow = if (netReserveAllocation.compareTo(ZERO) > 0) netReserveAllocation else ZERO

            val inflow = income + dissolutionInflow
            val outflow = expense + allocationOutflow

            val potWithInflow =
                if (inflow.compareTo(ZERO) > 0) pot + Vintage(year, inflow) else pot
            pot = consumeFifo(potWithInflow, outflow)

            if (year in fromFiscalYear..toFiscalYear) {
                val obligationRemaining = pot.fold(ZERO) { acc, v -> acc + v.amount }
                val overdue =
                    pot
                        .filter { (year - it.vintageYear) > TIMELY_USE_YEARS }
                        .fold(ZERO) { acc, v -> acc + v.amount }

                rows +=
                    UseOfFundsYearDto(
                        fiscalYear = year,
                        fundsReceived = income,
                        fundsUsed = expense,
                        fundsAllocatedToReserves = netReserveAllocation,
                        reserveMovements =
                            ReserveType.entries.map { type ->
                                ReserveMovementDto(
                                    reserveType = type,
                                    allocated = reserveAllocation[type] ?: ZERO,
                                    closingBalance = reserveClosing[type] ?: ZERO,
                                )
                            },
                        receivedBySphere =
                            GemeinnuetzigkeitSphere.entries.map { sphere ->
                                SphereAmountDto(sphere, incomeBySphere[sphere] ?: ZERO)
                            },
                        usedBySphere =
                            GemeinnuetzigkeitSphere.entries.map { sphere ->
                                SphereAmountDto(sphere, expenseBySphere[sphere] ?: ZERO)
                            },
                        timelyUseObligationRemaining = obligationRemaining,
                        overdueAmount = overdue,
                    )
            }
        }

        val totalFundsReceived = rows.fold(ZERO) { acc, row -> acc + row.fundsReceived }
        val totalFundsUsed = rows.fold(ZERO) { acc, row -> acc + row.fundsUsed }
        val totalFundsAllocatedToReserves = rows.fold(ZERO) { acc, row -> acc + row.fundsAllocatedToReserves }

        return UseOfFundsStatementDto(
            fromFiscalYear = fromFiscalYear,
            toFiscalYear = toFiscalYear,
            timelyUseYears = TIMELY_USE_YEARS,
            years = rows,
            totalFundsReceived = totalFundsReceived,
            totalFundsUsed = totalFundsUsed,
            totalFundsAllocatedToReserves = totalFundsAllocatedToReserves,
            closingTimelyUseObligation = rows.lastOrNull()?.timelyUseObligationRemaining ?: ZERO,
            closingOverdue = rows.lastOrNull()?.overdueAmount ?: ZERO,
        )
    }

    /**
     * Removes [amount] from [pot], oldest [Vintage] first, dropping a vintage entirely once
     * exhausted and partially reducing the first one that is not. If [amount] exceeds the pot's
     * total, the pot is drained to empty (floored at `0`) rather than going negative.
     */
    private fun consumeFifo(
        pot: List<Vintage>,
        amount: BigDecimal,
    ): List<Vintage> {
        var remaining = amount
        val result = mutableListOf<Vintage>()
        for (vintage in pot) {
            if (remaining.compareTo(ZERO) <= 0) {
                result += vintage
                continue
            }
            if (vintage.amount.compareTo(remaining) <= 0) {
                remaining -= vintage.amount
            } else {
                result += Vintage(vintage.vintageYear, vintage.amount - remaining)
                remaining = ZERO
            }
        }
        return result
    }
}
