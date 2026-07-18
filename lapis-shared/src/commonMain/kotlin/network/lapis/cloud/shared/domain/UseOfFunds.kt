package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.serialization.Serializable

/*
 * V0.3.4 reporting DTOs -- §55 AO Mittelverwendungsrechnung (use-of-funds / timely-use tracking)
 * and §62 AO Rücklagenbildung (reserve formation), derived purely from the existing `POSTED`
 * postings plus the [ReserveType] classification on `EQUITY` [LedgerAccountDto]s via
 * `network.lapis.cloud.server.rpc.UseOfFundsCalculator`. None of these are persisted -- same
 * "derived reporting DTO" status as [GeneralLedgerDto]/[FinancialStatements]' DTOs.
 *
 * **This is a treasurer's Nachweis (evidence) aid, not a Gemeinnützigkeit compliance verdict.** It
 * deliberately does NOT: enforce the §62 Abs.1 Nr.3 freie-Rücklage percentage cap (that cap is a
 * number a human must configure/verify against current law -- see [ReserveType.FREIE_RUECKLAGE]
 * KDoc); auto-apply the §55 Abs.1 Nr.5 Satz 4 small-organization (≤45.000 EUR) exemption; or
 * certify that the organization has/has not lost its tax-exempt status. See
 * `network.lapis.cloud.server.rpc.UseOfFundsCalculator` KDoc for the full derivation and every
 * simplifying assumption, each explicitly flagged for human review.
 */

/** One [GemeinnuetzigkeitSphere]'s slice of a [UseOfFundsYearDto] -- always four entries, zero-filled, informational only. */
@Serializable
data class SphereAmountDto(
    val sphere: GemeinnuetzigkeitSphere,
    val amount: Decimal,
)

/**
 * One [ReserveType]'s movement within a [UseOfFundsYearDto]. [allocated] is the net Zuführung
 * (contribution) to reserves of this type in the year -- **may be negative**, meaning a net
 * Auflösung (dissolution) exceeded that year's contributions (see
 * `network.lapis.cloud.server.rpc.UseOfFundsCalculator` KDoc for the simplified re-clocking this
 * implies). [closingBalance] is the cumulative balance of all [ReserveType]-tagged accounts of this
 * type at the end of the year. Always four entries per [UseOfFundsYearDto], zero-filled if a
 * reserve type had no accounts/activity.
 */
@Serializable
data class ReserveMovementDto(
    val reserveType: ReserveType,
    val allocated: Decimal,
    val closingBalance: Decimal,
)

/**
 * One fiscal year's row of a [UseOfFundsStatementDto]. [fundsReceived] (Mittelzufluss) is the
 * year's `INCOME` flow; [fundsUsed] (Mittelverwendung) is the year's `EXPENSE` flow;
 * [fundsAllocatedToReserves] is the year's net Zuführung across all reserve types (== the sum of
 * [reserveMovements]' [ReserveMovementDto.allocated]). [receivedBySphere]/[usedBySphere] are purely
 * informational per-sphere disaggregations of [fundsReceived]/[fundsUsed] -- always four entries,
 * zero-filled -- see `UseOfFundsCalculator` KDoc for why the timely-use clock itself is
 * deliberately NOT per-sphere. [timelyUseObligationRemaining] (Mittelvortrag) is the §55 AO FIFO
 * carry-forward pot at this year's end, floored at `0`; [overdueAmount] is the portion of that pot
 * whose statutory deadline (`UseOfFundsStatementDto.timelyUseYears` fiscal years after receipt) has
 * already lapsed at this year's end.
 */
@Serializable
data class UseOfFundsYearDto(
    val fiscalYear: Int,
    val fundsReceived: Decimal,
    val fundsUsed: Decimal,
    val fundsAllocatedToReserves: Decimal,
    val reserveMovements: List<ReserveMovementDto>,
    val receivedBySphere: List<SphereAmountDto>,
    val usedBySphere: List<SphereAmountDto>,
    val timelyUseObligationRemaining: Decimal,
    val overdueAmount: Decimal,
)

/**
 * §55 AO Mittelverwendungsrechnung + §62 AO Rücklagen over `[fromFiscalYear, toFiscalYear]`
 * (calendar years, both inclusive). [timelyUseYears] is the historically-interpreted "must be used
 * by the end of the Nth fiscal year following receipt" window (currently `2`) -- surfaced as data
 * rather than a silent constant so callers/UI can display the assumption; **verify this against
 * current AO interpretation before relying on it**.
 *
 * [years] contains one [UseOfFundsYearDto] per fiscal year in `[fromFiscalYear, toFiscalYear]`
 * only -- but the §55 AO timely-use clock behind [UseOfFundsYearDto.timelyUseObligationRemaining]/
 * [UseOfFundsYearDto.overdueAmount] is **anchored at inception** (rolled forward from the earliest
 * fiscal year with any activity, not from [fromFiscalYear]) -- see
 * `network.lapis.cloud.server.rpc.UseOfFundsCalculator` KDoc for why windowing the roll-forward
 * itself would silently corrupt the reported Mittelvortrag/overdue figures. [closingTimelyUseObligation]/
 * [closingOverdue] are the pot/overdue amount at the end of [toFiscalYear].
 */
@Serializable
data class UseOfFundsStatementDto(
    val fromFiscalYear: Int,
    val toFiscalYear: Int,
    val timelyUseYears: Int,
    val years: List<UseOfFundsYearDto>,
    val totalFundsReceived: Decimal,
    val totalFundsUsed: Decimal,
    val totalFundsAllocatedToReserves: Decimal,
    val closingTimelyUseObligation: Decimal,
    val closingOverdue: Decimal,
)
