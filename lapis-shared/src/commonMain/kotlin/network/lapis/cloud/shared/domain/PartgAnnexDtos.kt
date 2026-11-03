package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * V1.9.269 "Rechenschaftsbericht (PartG), R3" -- separate statements A (deductions) and B (large donors), the member count E, the notes
 * and the plausibility hints. See `docs/features/partg-report.adoc`. Enum literal order is part of the schema drift tests.
 */
@Serializable
enum class PartgPersonKind { NATURAL, LEGAL }

@Serializable
enum class PartgContributionKind { DONATION, FEE, BOTH }

@Serializable
enum class PartgNoteBlock { GENERAL_ACCOUNTING, RELIEF_28, HOLDINGS, MEDIA_PRODUCTS, REAL_ESTATE_VALUATION }

@Serializable
enum class PartgTriState { YES, NO, UNSPECIFIED }

/** The four deduction fields of statement A; the order is the order of the output. */
@Serializable
enum class PartgDeduction { UNASSIGNABLE, CASH_OVER_1000, OVER_3300, WRONGLY_REPORTED }

@Serializable
data class PartgLargeDonorDto(
    val id: String,
    /** 1-based position in the list of the unit (amount descending, name, id). Changes when an amount changes. */
    val rowNumber: Int,
    val personKind: PartgPersonKind,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    val contributionKind: PartgContributionKind,
    val amountCents: Long,
)

@Serializable
data class PartgLargeDonorInputDto(
    /** `null` = a new row. */
    val id: String?,
    val personKind: PartgPersonKind,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    val contributionKind: PartgContributionKind,
    val amountCents: Long,
)

@Serializable
data class PartgMemberCountSuggestionDto(
    val count: Long,
    val asOf: LocalDate,
)

@Serializable
data class PartgAnnexDto(
    val year: Int,
    val unit: PartgUnitDto,
    val revision: Long,
    /** All four keys are always present; `null` = not entered. */
    val deductions: Map<PartgDeduction, Long?>,
    val memberCount: Int?,
    /** Only for the current year and a federal or state unit, otherwise `null`. */
    val memberCountSuggestion: PartgMemberCountSuggestionDto?,
    /** Sorted: amount descending, name, id. */
    val donors: List<PartgLargeDonorDto>,
)

@Serializable
data class PartgHoldingDto(
    val name: String,
    val seat: String?,
    /** Share in hundredths of a percent, 0..10000. */
    val shareBasisPoints: Int?,
    val capitalCents: Long?,
    val resultCents: Long?,
)

@Serializable
data class PartgNoteDto(
    val block: PartgNoteBlock,
    val included: Boolean,
    val body: String?,
    val reliefAbs1: PartgTriState?,
    val reliefAbs3: PartgTriState?,
)

@Serializable
data class PartgNotesDto(
    val year: Int,
    val revision: Long,
    /** Always five, in the order of [PartgNoteBlock]. */
    val notes: List<PartgNoteDto>,
    val holdings: List<PartgHoldingDto>,
)

@Serializable
enum class PartgFindingCode { P01, P02, P03, P04, P05, P06 }

@Serializable
enum class PartgFindingLevel { CHECK, INFO }

@Serializable
enum class PartgFindingTarget { ANNEX, NOTES, SHEET }

/** A plausibility hint. Carries NEVER a name, an address or an amount: only row numbers, position codes and gap codes. */
@Serializable
data class PartgFindingDto(
    val code: PartgFindingCode,
    val level: PartgFindingLevel,
    /** `null` = the organisation. */
    val unitKey: String?,
    val rowNumbers: List<Int> = emptyList(),
    val positionCode: String? = null,
    /** `MEMBER_COUNT`, `DEDUCTIONS` or `GENERAL_ACCOUNTING`. */
    val gap: String? = null,
    val target: PartgFindingTarget,
)
