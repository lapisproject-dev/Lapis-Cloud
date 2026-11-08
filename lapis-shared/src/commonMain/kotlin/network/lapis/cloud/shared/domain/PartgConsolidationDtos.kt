package network.lapis.cloud.shared.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** V1.9.290 (R5): why a unit is NOT part of the consolidated total. It is never counted as 0. */
@Serializable
enum class PartgExclusionReason { NO_DATA, BOOKINGS_NOT_TAKEN_OVER }

@Serializable
data class PartgExcludedUnitDto(
    val unitKey: String,
    val reason: PartgExclusionReason,
)

@Serializable
enum class PartgNotComputableReason { MISSING_IN_UNITS, ELIMINATION_EXCEEDS }

/** One consolidated cell: a value, or "not computable" (n. b.) with the units that cause it. Never 0 for "unknown". */
@Serializable
sealed interface PartgConsolidatedCell {
    @Serializable
    @SerialName("cv")
    data class Value(
        val cents: Long,
    ) : PartgConsolidatedCell

    @Serializable
    @SerialName("cn")
    data class NotComputable(
        val unitKeys: List<String>,
        val reason: PartgNotComputableReason,
    ) : PartgConsolidatedCell
}

/** One of the 42 positions: sum of the units, eliminated part, consolidated value, and the same consolidated value of the year before. */
@Serializable
data class PartgConsolidatedRowDto(
    val code: String,
    val unitSum: PartgConsolidatedCell,
    val eliminatedCents: Long,
    val consolidated: PartgConsolidatedCell,
    val priorConsolidated: PartgConsolidatedCell,
)

@Serializable
data class PartgIntraTransferDto(
    val id: String,
    val kind: PartgTransferKind,
    val payerUnitKey: String,
    val payeeUnitKey: String,
    val payerAmountCents: Long?,
    val payeeAmountCents: Long?,
    val note: String?,
    val match: PartgMatchState,
)

@Serializable
data class PartgIntraTransferInputDto(
    /** `null` = a new pair. */
    val id: String?,
    val kind: PartgTransferKind,
    val payerUnitKey: String,
    val payeeUnitKey: String,
    val payerAmountCents: Long?,
    val payeeAmountCents: Long?,
    val note: String?,
)

/** A difference that stays visible: the entered value of [positionCode] in [unitKey] minus the part that the pairs eliminate. */
@Serializable
data class PartgUnmatchedDto(
    val unitKey: String,
    val positionCode: String,
    val differenceCents: Long,
)

/** One person of the consolidated statement B. [combinationId] is `null` for a row that was not combined. */
@Serializable
data class PartgConsolidatedDonorDto(
    val combinationId: String?,
    val displayDonorId: String,
    val personKind: PartgPersonKind,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    val contributionKind: PartgContributionKind,
    val totalCents: Long,
    val unitKeys: List<String>,
    val donorIds: List<String>,
)

/** A suggestion to combine rows. Carries NO name: the screen resolves the ids through the donor list. */
@Serializable
data class PartgDonorCandidateDto(
    val donorIds: List<String>,
    val unitKeys: List<String>,
)

@Serializable
data class PartgConsolidationDto(
    val year: Int,
    val revision: Long,
    /** Revisions of all annex headers (`A:<year>:<unit>`) and the consolidation header, keyed for [IPartyReportService.createDonorCombination]. */
    val annexRevisions: Map<String, Long>,
    /** Unit keys that are part of the consolidated total. */
    val included: List<String>,
    val excluded: List<PartgExcludedUnitDto>,
    val priorExcluded: List<PartgExcludedUnitDto>,
    /** All 42 positions in catalogue order. */
    val rows: List<PartgConsolidatedRowDto>,
    /** `true` iff the year before has no pairs although it reports transfer positions: its consolidated column is not eliminated. */
    val priorTransfersUnmatched: Boolean,
    val transfers: List<PartgIntraTransferDto>,
    val unmatched: List<PartgUnmatchedDto>,
    val donors: List<PartgConsolidatedDonorDto>,
    val candidates: List<PartgDonorCandidateDto>,
    val candidateTotal: Int,
    val findings: List<PartgFindingDto>,
)
