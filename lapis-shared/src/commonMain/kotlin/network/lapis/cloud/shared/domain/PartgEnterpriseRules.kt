package network.lapis.cloud.shared.domain

import kotlin.math.abs

/**
 * V1.9.287 (R3c) -- pure rules of statement D (enterprise activity), shared by client, server hints and DTO (one rule, pinned by
 * `PartgEnterpriseRulesTest`). Integer arithmetic only: 50 * [PartgAnnexRules.MAX_AMOUNT_CENTS] fits a Long.
 */
object PartgEnterpriseRules {
    const val MAX_ENTERPRISES_PER_UNIT = 50
    const val LEGAL_FORM_MAX = 60
    const val SHARE_MAX_BASIS_POINTS = 10_000

    /** A difference counts only when STRICTLY greater than 1.00 EUR. */
    const val TOLERANCE_CENTS = 100L
    const val INCOME_POSITION = "E05"
    const val EXPENSE_POSITION = "A02F"

    fun validate(input: PartgEnterpriseInputDto): PartgAnnexViolation? {
        val name = PartgAnnexRules.normalize(input.name) ?: return PartgAnnexViolation.NAME_EMPTY
        PartgAnnexRules.badText(value = name, max = PartgAnnexRules.NAME_MAX)?.let { return it }
        PartgAnnexRules.badText(value = PartgAnnexRules.normalize(input.legalForm), max = LEGAL_FORM_MAX)?.let { return it }
        PartgAnnexRules.badText(value = PartgAnnexRules.normalize(input.seat), max = PartgAnnexRules.SEAT_MAX)?.let { return it }
        val share = input.shareBasisPoints
        if (share != null && (share < 0 || share > SHARE_MAX_BASIS_POINTS)) return PartgAnnexViolation.OUT_OF_RANGE
        val income = input.incomeCents
        if (income != null && (income < 0L || income > PartgAnnexRules.MAX_AMOUNT_CENTS)) return PartgAnnexViolation.OUT_OF_RANGE
        val expenses = input.expensesCents
        if (expenses != null && (expenses < 0L || expenses > PartgAnnexRules.MAX_AMOUNT_CENTS)) return PartgAnnexViolation.OUT_OF_RANGE
        return null
    }

    /** Income minus expenses, `null` when one of them is not entered. May be negative. */
    fun resultCents(
        income: Long?,
        expenses: Long?,
    ): Long? = if (income != null && expenses != null) income - expenses else null

    /** Sum of one figure over all rows, or `null` when there is no row or one row lacks the figure. */
    fun completeSum(figures: List<Long?>): Long? {
        if (figures.isEmpty() || figures.any { it == null }) return null
        return figures.sumOf { it ?: 0L }
    }

    /**
     * [figures] = the figure of every D row; [positionCents] = the R1 value, `null` = not entered. `null` when D has no row.
     */
    fun check(
        figures: List<Long?>,
        positionCents: Long?,
    ): PartgEnterpriseCheckStatus? {
        if (figures.isEmpty()) return null
        val sum = completeSum(figures) ?: return PartgEnterpriseCheckStatus.NOT_COMPARABLE
        if (positionCents == null) return PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED
        return if (abs(sum - positionCents) > TOLERANCE_CENTS) PartgEnterpriseCheckStatus.DIFFERS else PartgEnterpriseCheckStatus.MATCHES
    }
}
