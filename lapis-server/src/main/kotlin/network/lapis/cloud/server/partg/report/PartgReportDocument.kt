package network.lapis.cloud.server.partg.report

import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgEnterpriseRules
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgMatchState
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgSponsorQualification
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnitLevel

/** One reporting unit (or the consolidated party) with the cells of the report year and the year before. Sums only, no persons. */
@Serializable
internal data class UnitPage(
    /** V1.9.291 (R6): the stable key the comparison of two kept versions matches on (a unit key, or `SUM`); a label changes when a chapter is renamed. */
    val unitKey: String,
    val label: String,
    val current: Map<String, PartgCell>,
    val prior: Map<String, PartgCell>,
)

/** The sum over all units of one level (federal, all state units, all subordinate summaries). */
@Serializable
internal data class LevelRow(
    val level: PartgUnitLevel,
    val label: String,
    val current: Map<String, PartgCell>,
    val prior: Map<String, PartgCell>,
)

/** One unit in statement A and E. [memberCount] is `null` = not entered. */
@Serializable
internal data class AnnexPage(
    val label: String,
    val deductions: Map<PartgDeduction, PartgCell>,
    val memberCount: Int?,
)

/** One row of statement B. PERSONAL DATA OF A THIRD PARTY: never logged; the PDF and the XLSX are the only places it is written to. */
@Serializable
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

/** One single service above the single limit, listed under its sponsor in statement C. */
@Serializable
internal data class SponsorEntryLine(
    val date: LocalDate,
    val amountCents: Long,
    val purpose: String?,
)

/**
 * One row of statement C (V1.9.283). PERSONAL DATA OF A THIRD PARTY. Only sponsors with a [qualification] other than NONE are in the report.
 * [rowNumber] is the position in the sorted list of the unit as the user interface shows it (also counting sponsors that are not in the report).
 */
@Serializable
internal data class SponsorLine(
    val unitLabel: String,
    val rowNumber: Int,
    val personKind: PartgPersonKind,
    val name: String,
    val addressLine: String,
    val country: String,
    val yearlySumCents: Long,
    val entryCount: Int,
    val qualification: PartgSponsorQualification,
    val singleEntries: List<SponsorEntryLine>,
)

/** One row of statement F (V1.9.283). [resultCents] is computed, `null` when income or expenses is not entered. */
@Serializable
internal data class YouthOrgLine(
    val unitLabel: String,
    val rowNumber: Int,
    val name: String,
    val relationship: String?,
    val incomeCents: Long?,
    val expensesCents: Long?,
) {
    val resultCents: Long? get() = if (incomeCents != null && expensesCents != null) incomeCents - expensesCents else null
}

/** One row of statement D (V1.9.287). [resultCents] is computed, `null` when income or expenses is not entered; it may be negative. */
@Serializable
internal data class EnterpriseLine(
    val unitLabel: String,
    val rowNumber: Int,
    val name: String,
    val legalForm: String?,
    val seat: String?,
    val shareBasisPoints: Int?,
    val incomeCents: Long?,
    val expensesCents: Long?,
) {
    val resultCents: Long? get() = PartgEnterpriseRules.resultCents(income = incomeCents, expenses = expensesCents)
}

/** The sum of statement D of ONE unit (never a sum over the whole party); `null` = not computable because a figure is missing. */
@Serializable
internal data class EnterpriseTotal(
    val unitLabel: String,
    val incomeCents: Long?,
    val expensesCents: Long?,
    val resultCents: Long?,
)

/** The separate statements A, B, C, D, F and E over the units in scope. */
@Serializable
internal data class AnnexSection(
    val units: List<AnnexPage>,
    /** Only for the scope ALL: the deductions are partial sums, the member count is the federal value. */
    val wholeParty: AnnexPage?,
    val donors: List<DonorLine>,
    val sponsors: List<SponsorLine> = emptyList(),
    val youthOrgs: List<YouthOrgLine> = emptyList(),
    val enterprises: List<EnterpriseLine> = emptyList(),
    /** One per unit that has at least one enterprise. */
    val enterpriseTotals: List<EnterpriseTotal> = emptyList(),
)

/** The notes that were switched on, plus the holdings. */
@Serializable
internal data class NotesSection(
    val blocks: List<PartgNoteDto>,
    val holdings: List<PartgHoldingDto>,
)

/** Everything the PDF renderer and the XLSX writer need; both render the SAME document. */
@Serializable
internal data class PartgReportDocument(
    val organizationName: String,
    val year: Int,
    val priorYear: Int,
    @Serializable(with = PartgReportScopeKeySerializer::class)
    val scope: PartgReportScope,
    /** In the order of `PartgReportStore.units()`. */
    val units: List<UnitPage>,
    /** Only for the scope ALL: the SUM OF THE UNITS (not consolidated: the transfers between the units are still in it). */
    val unitSum: UnitPage?,
    /** Only for the scope ALL. */
    val levels: List<LevelRow>,
    /** Restricted to the units in scope. */
    val completeness: PartgCompletenessDto,
    val annex: AnnexSection = AnnexSection(units = emptyList(), wholeParty = null, donors = emptyList()),
    val notes: NotesSection = NotesSection(blocks = emptyList(), holdings = emptyList()),
    /** The plausibility hints (codes and row numbers only). */
    val findings: List<PartgFindingDto> = emptyList(),
    /** V1.9.290 (R5): the consolidated whole party; only for the scope ALL. */
    val consolidation: ConsolidationSection? = null,
) {
    /** The pages in report order: every unit, then (scope ALL) the whole party. */
    val pages: List<UnitPage> get() = units + listOfNotNull(unitSum)

    /** The page the summary is about: the whole party, or the single unit. */
    val summaryPage: UnitPage get() = unitSum ?: units.single()
}

/** One of the 42 positions in the consolidation (V1.9.290, R5). The cells are rendered by the PDF and XLSX writers; "not computable" is never 0. */
@Serializable
internal data class ConsolidationLine(
    val code: String,
    val numberText: String,
    val label: String,
    val unitSum: PartgConsolidatedCell,
    val eliminatedCents: Long,
    val consolidated: PartgConsolidatedCell,
    val prior: PartgConsolidatedCell,
    val bold: Boolean,
)

/** A unit that is not part of the consolidated total, with the German reason text. */
@Serializable
internal data class ExcludedLine(
    val unitLabel: String,
    val reason: String,
)

/** One pair of the matching table. The free-text note is NOT exported (it may hold personal data). */
@Serializable
internal data class ReconciliationLine(
    val kind: PartgTransferKind,
    val payerLabel: String,
    val payeeLabel: String,
    val payerAmountCents: Long?,
    val payeeAmountCents: Long?,
    val match: PartgMatchState,
)

/** A difference that stays visible: the entered value of a position minus what the pairs eliminate. */
@Serializable
internal data class UnmatchedLine(
    val unitLabel: String,
    val positionLabel: String,
    val differenceCents: Long,
)

/** One person of the consolidated statement B. PERSONAL DATA OF A THIRD PARTY: never logged. */
@Serializable
internal data class ConsolidatedDonorLine(
    val unitLabels: String,
    val personKind: PartgPersonKind,
    val name: String,
    val addressLine: String,
    val country: String,
    val contributionKind: PartgContributionKind,
    val totalCents: Long,
    val rowCount: Int,
)

/** V1.9.290 (R5): the consolidated whole party; only present for the scope ALL. A draft, never an official statement. */
@Serializable
internal data class ConsolidationSection(
    val included: List<String>,
    val excluded: List<ExcludedLine>,
    val priorTransfersUnmatched: Boolean,
    val lines: List<ConsolidationLine>,
    val reconciliation: List<ReconciliationLine>,
    val unmatched: List<UnmatchedLine>,
    val donors: List<ConsolidatedDonorLine>,
)

/** A generated export grew beyond its size budget; the work was stopped. Carries no content. */
internal class PartgReportTooLargeException(
    reason: String,
) : RuntimeException(reason, null, false, false)

/**
 * V1.9.291 (R6): the scope is stored as its key (`ALL` or a unit key); decoding goes through the strict [PartgReportScope.parse]. The shared class
 * itself stays unchanged.
 */
internal object PartgReportScopeKeySerializer : KSerializer<PartgReportScope> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PartgReportScopeKey", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: PartgReportScope,
    ) = encoder.encodeString(value.key())

    override fun deserialize(decoder: Decoder): PartgReportScope =
        PartgReportScope.parse(decoder.decodeString()) ?: throw IllegalArgumentException("Unknown report scope")
}
