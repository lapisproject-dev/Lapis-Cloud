package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.VatFilingPeriodicity
import network.lapis.cloud.shared.domain.VatNotApplicableReason
import network.lapis.cloud.shared.domain.VatRate
import network.lapis.cloud.shared.domain.VatRateLineDto
import network.lapis.cloud.shared.domain.VatReturnPreviewDto
import java.math.BigDecimal

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)" -- pure, DB-free preview derivation, same
 * "pure logic extracted to a sibling file, unit-testable without a database" idiom as
 * [FinancialStatementCalculator]/[UseOfFundsCalculator].
 *
 * [preview] is THE authority on what a [VatReturnPreviewDto] contains -- `AccountingService
 * .getVatReturnPreview` loads [VatPostingLine]s from the already-POSTED, already-frozen
 * `posting.vat_amount` SNAPSHOT column and calls this object once. This object NEVER re-derives an
 * amount from a gross value -- every `vatAmount`/`gross` pair it consumes is already the persisted
 * truth (see [VatCalculator] KDoc for where that snapshot is computed, at post time).
 */
internal object VatReturnCalculator {
    /** §18 Abs.2 UStG: Kalendermonat als Voranmeldungszeitraum, wenn die Steuer des Vorjahres
     *  9.000 EUR ueberstieg (Reform zum 01.01.2025); sonst Kalendervierteljahr. */
    val MONTHLY_THRESHOLD_EUR: BigDecimal = BigDecimal("9000.00")

    /**
     * §18 Abs.2 S.3 UStG: Befreiung von der Voranmeldungspflicht MOEGLICH (auf Antrag, im Ermessen
     * des Finanzamts), wenn die Steuer des Vorjahres diesen Betrag nicht ueberstieg.
     *
     * 2.000 EUR gilt fuer Voranmeldungszeitraeume ab 2025 -- dieselbe Reform (JStG 2024), die
     * [MONTHLY_THRESHOLD_EUR] von 7.500 auf 9.000 EUR angehoben hat, hat §18 Abs.2 S.3 zeitgleich
     * von vormals 1.000 EUR auf 2.000 EUR angehoben. 1.000 EUR ist der Altstand (Voranmeldungs-
     * zeitraeume bis 2024) und hier nicht mehr relevant, weil diese Nachweishilfe ausschliesslich
     * laufende Zeitraeume betrachtet.
     */
    val EXEMPTION_THRESHOLD_EUR: BigDecimal = BigDecimal("2000.00")

    private val ZERO = BigDecimal.ZERO.setScale(2)

    /**
     * One POSTED [network.lapis.cloud.shared.domain.PostingDto] line already joined to its
     * [network.lapis.cloud.shared.domain.LedgerAccountDto.type] -- [gross]/[vatAmount] are the
     * GESPEICHERTEN `posting.amount`/`posting.vat_amount` values, never re-derived. Always
     * NON-NEGATIVE, exactly as stored ([JournalEntryBalance] and the
     * `chk_posting_vat_amount_non_negative` constraint both forbid a negative `posting.amount`/
     * `vat_amount`) -- [side] is what turns "always positive" into "correctly signed for this
     * account's normal-balance side" (see [signedGross]/[signedVatAmount]).
     */
    data class VatPostingLine(
        val accountType: LedgerAccountType,
        val side: PostingSide,
        val rate: VatRate,
        val gross: BigDecimal,
        val vatAmount: BigDecimal,
    ) {
        /**
         * [gross]/[vatAmount] signed by [side] against [accountType]'s normal-balance side (see
         * [GeneralLedgerCalculator] KDoc) -- exactly the `if (side == normalSide) amount else
         * amount.negate()` idiom every other aggregation over INCOME/EXPENSE postings in this
         * codebase already uses (see [AccountingService.loadAccountBalances]). Without this, a
         * manual storno (the only correction path for an immutable POSTED entry, see
         * `BankStatementMatcher` KDoc) on the non-normal side would INCREASE the reported VAT
         * instead of reducing it -- the bug this pair of functions exists to prevent.
         */
        private val normalSide get() = GeneralLedgerCalculator.normalBalanceSideOf(accountType)

        val signedGross: BigDecimal get() = if (side == normalSide) gross else gross.negate()

        val signedVatAmount: BigDecimal get() = if (side == normalSide) vatAmount else vatAmount.negate()
    }

    fun preview(
        from: LocalDate,
        to: LocalDate,
        lines: List<VatPostingLine>,
        priorYear: List<VatPostingLine>,
        earliestPostedEntryDate: LocalDate?,
        disclaimerVersion: String,
    ): VatReturnPreviewDto {
        val outputVatLines = rateLinesOf(lines = lines, accountType = LedgerAccountType.INCOME)
        val inputVatLines = rateLinesOf(lines = lines, accountType = LedgerAccountType.EXPENSE)
        val totalOutputVat = outputVatLines.sumVat()
        val totalInputVat = inputVatLines.sumVat()

        val unclassified = lines.filter { it.rate == VatRate.UNCLASSIFIED }
        val unclassifiedGrossTotal = unclassified.fold(ZERO) { acc, line -> acc + line.signedGross }

        val taxableGrossTotal =
            lines
                .filter { it.accountType == LedgerAccountType.INCOME && it.rate.isTaxable }
                .fold(ZERO) { acc, line -> acc + line.signedGross }

        val priorYearCovered = earliestPostedEntryDate != null && earliestPostedEntryDate <= LocalDate(from.year - 1, 1, 1)
        val priorYearVatBalance =
            if (!priorYearCovered) {
                null
            } else {
                val priorOutput =
                    priorYear.filter { it.accountType == LedgerAccountType.INCOME }.fold(ZERO) { acc, l -> acc + l.signedVatAmount }
                val priorInput =
                    priorYear.filter { it.accountType == LedgerAccountType.EXPENSE }.fold(ZERO) { acc, l -> acc + l.signedVatAmount }
                priorOutput - priorInput
            }

        val filingPeriodicity =
            when {
                priorYearVatBalance == null -> VatFilingPeriodicity.UNKNOWN
                priorYearVatBalance > MONTHLY_THRESHOLD_EUR -> VatFilingPeriodicity.MONTHLY
                else -> VatFilingPeriodicity.QUARTERLY
            }
        val exemptionOnRequestPossible =
            filingPeriodicity == VatFilingPeriodicity.QUARTERLY &&
                priorYearVatBalance != null &&
                priorYearVatBalance <= EXEMPTION_THRESHOLD_EUR

        return VatReturnPreviewDto(
            from = from,
            to = to,
            applicable = true,
            notApplicableReason = null,
            outputVatLines = outputVatLines,
            inputVatLines = inputVatLines,
            totalOutputVat = totalOutputVat,
            totalInputVat = totalInputVat,
            balance = totalOutputVat - totalInputVat,
            unclassifiedPostingCount = unclassified.size,
            unclassifiedGrossTotal = unclassifiedGrossTotal,
            taxableGrossTotal = taxableGrossTotal,
            filingPeriodicity = filingPeriodicity,
            exemptionOnRequestPossible = exemptionOnRequestPossible,
            priorYearVatBalance = priorYearVatBalance,
            disclaimerVersion = disclaimerVersion,
        )
    }

    /** The `applicable = false` shortcut -- all lists empty, all sums `0.00`. Same
     *  `DonationDutyReportDto.partyRulesApply = false` posture, see
     *  `network.lapis.cloud.shared.rpc.IAccountingService.getVatReturnPreview` KDoc. */
    fun notApplicable(
        from: LocalDate,
        to: LocalDate,
        reason: VatNotApplicableReason,
        disclaimerVersion: String,
    ): VatReturnPreviewDto =
        VatReturnPreviewDto(
            from = from,
            to = to,
            applicable = false,
            notApplicableReason = reason,
            outputVatLines = emptyList(),
            inputVatLines = emptyList(),
            totalOutputVat = ZERO,
            totalInputVat = ZERO,
            balance = ZERO,
            unclassifiedPostingCount = 0,
            unclassifiedGrossTotal = ZERO,
            taxableGrossTotal = ZERO,
            filingPeriodicity = VatFilingPeriodicity.UNKNOWN,
            exemptionOnRequestPossible = false,
            priorYearVatBalance = null,
            disclaimerVersion = disclaimerVersion,
        )

    /**
     * Groups [lines] of [accountType] by [VatRate], restricted to [VatRate.isTaxable] (excludes
     * [VatRate.UNCLASSIFIED]/[VatRate.NOT_SUBJECT] -- neither ever appears in a real UStVA line),
     * ordered by [VatRate] declaration order. Zeilen ohne Buchungen werden WEGGELASSEN (nicht
     * zero-filled) -- anders als [FinancialStatementCalculator.fourSphereIncomeStatement]'s
     * festverdrahtete Vier-Zeilen-Zero-Fill, weil es hier keine feste Zeilenzahl gibt.
     * `netTotal = grossTotal - vatTotal` gilt per Konstruktion, nicht durch eine zweite Berechnung.
     */
    private fun rateLinesOf(
        lines: List<VatPostingLine>,
        accountType: LedgerAccountType,
    ): List<VatRateLineDto> =
        VatRate.entries
            .filter { it.isTaxable }
            .mapNotNull { rate ->
                val matching = lines.filter { it.accountType == accountType && it.rate == rate }
                if (matching.isEmpty()) return@mapNotNull null
                val grossTotal = matching.fold(ZERO) { acc, line -> acc + line.signedGross }
                val vatTotal = matching.fold(ZERO) { acc, line -> acc + line.signedVatAmount }
                VatRateLineDto(
                    rate = rate,
                    grossTotal = grossTotal,
                    netTotal = grossTotal - vatTotal,
                    vatTotal = vatTotal,
                    postingCount = matching.size,
                )
            }

    private fun List<VatRateLineDto>.sumVat(): BigDecimal = fold(ZERO) { acc, line -> acc + line.vatTotal }
}
