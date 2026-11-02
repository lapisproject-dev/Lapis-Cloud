package network.lapis.cloud.server.partg.report

import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnitLevel

/** One reporting unit (or the consolidated party) with the cells of the report year and the year before. Sums only, no persons. */
internal data class UnitPage(
    val label: String,
    val current: Map<String, PartgCell>,
    val prior: Map<String, PartgCell>,
)

/** The sum over all units of one level (federal, all state units, all subordinate summaries). */
internal data class LevelRow(
    val level: PartgUnitLevel,
    val label: String,
    val current: Map<String, PartgCell>,
    val prior: Map<String, PartgCell>,
)

/** Everything the PDF renderer and the XLSX writer need; both render the SAME document. */
internal data class PartgReportDocument(
    val organizationName: String,
    val year: Int,
    val priorYear: Int,
    val scope: PartgReportScope,
    /** In the order of `PartgReportStore.units()`. */
    val units: List<UnitPage>,
    /** Only for the scope ALL: the whole party. */
    val consolidated: UnitPage?,
    /** Only for the scope ALL. */
    val levels: List<LevelRow>,
    /** Restricted to the units in scope. */
    val completeness: PartgCompletenessDto,
) {
    /** The pages in report order: every unit, then (scope ALL) the whole party. */
    val pages: List<UnitPage> get() = units + listOfNotNull(consolidated)

    /** The page the summary is about: the whole party, or the single unit. */
    val summaryPage: UnitPage get() = consolidated ?: units.single()
}

/** A generated export grew beyond its size budget; the work was stopped. Carries no content. */
internal class PartgReportTooLargeException(
    reason: String,
) : RuntimeException(reason, null, false, false)
