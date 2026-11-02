package network.lapis.cloud.server.partg.report

import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPercent
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportComputation
import network.lapis.cloud.shared.domain.PartgSection

/** One printed line of a position table: both years, the share of the total (null = column stays empty). */
internal data class ReportLine(
    val code: String,
    val numberText: String,
    val label: String,
    val current: PartgCell,
    val currentPercent: PartgPercent?,
    val prior: PartgCell,
    val priorPercent: PartgPercent?,
    val bold: Boolean,
)

/** V1.9.256 -- the order and content of the report tables. The row order of the summary and of the matrix lives here and only here. */
internal object PartgReportLayout {
    /** Income Nr. 1-10 (Nr. 10 = subsidies from other branches, shown on its own line) and the total. */
    val SUMMARY_INCOME_CODES: List<String> = listOf("E01", "E02", "E03", "E04", "E05", "E05A", "E06", "E07", "E08", "E09", "E10", "E11")

    /** Expenses Nr. 1-3 (Nr. 3 = subsidies to other branches), the total and the result. */
    val SUMMARY_EXPENSE_CODES: List<String> = listOf("A01", "A02", "A03", "A04", "RES")

    /** The columns of the income matrix: Nr. 1-9, 5a, 10 and the total (12 columns). */
    val MATRIX_CODES: List<String> = SUMMARY_INCOME_CODES

    /** The columns of the table by level. */
    val LEVEL_CODES: List<String> = listOf("E11", "A04", "RES", "NET")

    fun numberText(code: String): String {
        val position = PartgPositions.find(code) ?: return code
        if (position.numberLabel.isEmpty()) return ""
        return if (position.section == PartgSection.INCOME || position.section == PartgSection.EXPENSE) {
            "Nr. ${position.numberLabel}"
        } else {
            position.numberLabel
        }
    }

    /** Short column title of a matrix column, e.g. `Nr. 5a`. */
    fun matrixTitle(code: String): String = numberText(code)

    fun lines(
        page: UnitPage,
        codes: List<String>,
        totalOf: (String) -> String?,
    ): List<ReportLine> =
        codes.map { code ->
            val position = checkNotNull(PartgPositions.find(code))
            val base = totalOf(code)
            ReportLine(
                code = code,
                numberText = numberText(code),
                label = position.labelDe,
                current = page.current.getValue(code),
                currentPercent =
                    base?.let {
                        PartgReportComputation.percent(
                            numerator = page.current.getValue(code),
                            base = page.current.getValue(it),
                        )
                    },
                prior = page.prior.getValue(code),
                priorPercent =
                    base?.let {
                        PartgReportComputation.percent(
                            numerator = page.prior.getValue(code),
                            base = page.prior.getValue(it),
                        )
                    },
                bold = position.kind == PartgKind.SUM,
            )
        }

    private fun incomeBase(code: String): String? = if (code.startsWith("E")) PartgPositions.INCOME_TOTAL else null

    private fun expenseBase(code: String): String? = if (code.startsWith("A")) PartgPositions.EXPENSE_TOTAL else null

    fun summaryIncome(page: UnitPage): List<ReportLine> = lines(page = page, codes = SUMMARY_INCOME_CODES, totalOf = ::incomeBase)

    fun summaryExpenses(page: UnitPage): List<ReportLine> = lines(page = page, codes = SUMMARY_EXPENSE_CODES, totalOf = ::expenseBase)

    /** All expense positions in catalogue order (with the result). */
    fun expenseLines(page: UnitPage): List<ReportLine> = lines(page = page, codes = codesOf(PartgSection.EXPENSE), totalOf = ::expenseBase)

    /** Assets and liabilities: no percent columns. */
    fun assetLines(page: UnitPage): List<ReportLine> =
        lines(page = page, codes = codesOf(PartgSection.ASSET, PartgSection.LIABILITY)) { null }

    private fun codesOf(vararg sections: PartgSection): List<String> = PartgPositions.ALL.filter { it.section in sections }.map { it.code }

    /** The LEAF codes of the sections a table covers, for the footnote of missing positions. */
    fun leafCodes(vararg sections: PartgSection): Set<String> =
        PartgPositions.ALL
            .filter { it.kind == PartgKind.LEAF && it.section in sections }
            .map { it.code }
            .toSet()

    const val MAX_FOOTNOTE_UNITS = 20

    /**
     * The footnote of a table: for every unit (at most [MAX_FOOTNOTE_UNITS]) the not entered positions among [relevant]. Empty = nothing missing.
     * The text carries unit labels and position numbers only.
     */
    fun missingFootnote(
        document: PartgReportDocument,
        relevant: Set<String>,
    ): String? {
        val entries =
            document.completeness.units.mapNotNull { unit ->
                val codes = unit.missingCurrent.filter { it in relevant }
                if (codes.isEmpty()) {
                    null
                } else {
                    PartgReportAssembler.unitLabel(unit.unit) + ": " + codes.joinToString(", ") { numberText(it) }
                }
            }
        if (entries.isEmpty()) return null
        val shown = entries.take(MAX_FOOTNOTE_UNITS)
        val rest = entries.size - shown.size
        val tail = if (rest > 0) "; und $rest weitere (siehe Vollständigkeit)" else ""
        return PartgReportTexts.INCOMPLETE_SHORT + ": fehlend in " + shown.joinToString("; ") + tail
    }
}
