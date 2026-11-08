package network.lapis.cloud.server.partg.report

import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgPercent

/**
 * V1.9.256 -- German number formatting, built by hand from a Long: no `String.format`, no Locale. The minus is the ASCII hyphen because the
 * Standard-14 fonts of the PDF (WinAnsi) have no U+2212.
 */
internal object PartgReportNumberFormat {
    /** `-1.234,56` -- thousands separated by a dot, always two decimals. */
    fun euro(cents: Long): String {
        val digits = cents.toString().removePrefix("-").padStart(3, '0')
        val integer = digits.dropLast(2)
        val grouped =
            integer
                .reversed()
                .chunked(3)
                .joinToString(".")
                .reversed()
        return (if (cents < 0) "-" else "") + grouped + "," + digits.takeLast(2)
    }

    /** Hundredths of a percent as `25,00` (without the percent sign; the column says `%`). */
    fun percentBasisPoints(basisPoints: Int): String = euro(basisPoints.toLong())

    /** `12,3` (without the percent sign; the column says `%`) or `n. b.`. */
    fun percent(p: PartgPercent?): String =
        when (p) {
            null -> ""
            is PartgPercent.NotComputable -> PartgReportTexts.NOT_COMPUTABLE
            is PartgPercent.Tenths -> {
                val t = p.tenths
                val abs = if (t < 0) -t else t
                (if (t < 0) "-" else "") + (abs / 10) + "," + (abs % 10)
            }
        }

    fun cell(c: PartgCell): String =
        when (c) {
            is PartgCell.Value -> euro(c.cents)
            is PartgCell.Partial -> euro(c.cents) + " " + PartgReportTexts.INCOMPLETE_SHORT
            is PartgCell.NotEntered -> PartgReportTexts.NOT_ENTERED
        }

    /** The compact form for the narrow matrix columns: a star instead of `(unvollst.)`. */
    fun cellShort(c: PartgCell): String =
        when (c) {
            is PartgCell.Value -> euro(c.cents)
            is PartgCell.Partial -> euro(c.cents) + "*"
            is PartgCell.NotEntered -> PartgReportTexts.NOT_ENTERED
        }

    /** A consolidated cell (V1.9.290, R5): the amount, or `n. b.` -- never 0 for "unknown". */
    fun consolidated(c: PartgConsolidatedCell): String =
        when (c) {
            is PartgConsolidatedCell.Value -> euro(c.cents)
            is PartgConsolidatedCell.NotComputable -> PartgReportTexts.NOT_COMPUTABLE
        }
}
