package network.lapis.cloud.server.partg.report

import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgPersonKind
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

/** One unit in statement A and E. [memberCount] is `null` = not entered. */
internal data class AnnexPage(
    val label: String,
    val deductions: Map<PartgDeduction, PartgCell>,
    val memberCount: Int?,
)

/** One row of statement B. PERSONAL DATA OF A THIRD PARTY: never logged; the PDF and the XLSX are the only places it is written to. */
internal data class DonorLine(
    val unitLabel: String,
    val rowNumber: Int,
    val personKind: PartgPersonKind,
    val name: String,
    /** Street, postal code and city already joined, empty when none is known. */
    val addressLine: String,
    val country: String,
    val contributionKind: PartgContributionKind,
    val amountCents: Long,
)

/** The separate statements A, B and E over the units in scope. */
internal data class AnnexSection(
    val units: List<AnnexPage>,
    /** Only for the scope ALL: the deductions are partial sums, the member count is the federal value. */
    val wholeParty: AnnexPage?,
    val donors: List<DonorLine>,
)

/** The notes that were switched on, plus the holdings. */
internal data class NotesSection(
    val blocks: List<PartgNoteDto>,
    val holdings: List<PartgHoldingDto>,
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
    val annex: AnnexSection = AnnexSection(units = emptyList(), wholeParty = null, donors = emptyList()),
    val notes: NotesSection = NotesSection(blocks = emptyList(), holdings = emptyList()),
    /** The plausibility hints (codes and row numbers only). */
    val findings: List<PartgFindingDto> = emptyList(),
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
