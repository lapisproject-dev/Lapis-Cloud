package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
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
    /** V1.9.293 (R5b): where the row comes from. Display only; NOT part of the stored report version or the exports. */
    val origin: PartgLargeDonorOrigin = PartgLargeDonorOrigin.MANUAL,
    /** V1.9.293 (R5b): when the row was last written from the bookings. */
    val sourceAppliedAt: LocalDateTime? = null,
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

/** V1.9.283 (R3b): why a sponsor is part of statement C. Computed, never stored. */
@Serializable
enum class PartgSponsorQualification { SINGLE, YEARLY, BOTH, NONE }

@Serializable
data class PartgSponsoringEntryDto(
    val id: String,
    val date: LocalDate,
    val amountCents: Long,
    val purpose: String?,
)

@Serializable
data class PartgSponsoringEntryInputDto(
    /** `null` = a new entry. */
    val id: String?,
    val date: LocalDate,
    val amountCents: Long,
    val purpose: String?,
)

@Serializable
data class PartgSponsorDto(
    val id: String,
    /** 1-based position in the list of the unit (yearly sum descending, name, id). Changes when an amount changes. */
    val rowNumber: Int,
    val personKind: PartgPersonKind,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    /** Sorted: date, id. */
    val entries: List<PartgSponsoringEntryDto>,
    val yearlySumCents: Long,
    val qualification: PartgSponsorQualification,
)

@Serializable
data class PartgSponsorInputDto(
    /** `null` = a new sponsor. */
    val id: String?,
    val personKind: PartgPersonKind,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    /** The FULL list of entries of the sponsor (replace). */
    val entries: List<PartgSponsoringEntryInputDto>,
)

@Serializable
data class PartgYouthOrgDto(
    val id: String,
    val rowNumber: Int,
    val name: String,
    val relationship: String?,
    val incomeCents: Long?,
    val expensesCents: Long?,
)

@Serializable
data class PartgYouthOrgInputDto(
    /** `null` = a new row. */
    val id: String?,
    val name: String,
    val relationship: String?,
    val incomeCents: Long?,
    val expensesCents: Long?,
)

/** V1.9.287 (R3c): one enterprise of statement D. */
@Serializable
data class PartgEnterpriseDto(
    val id: String,
    val rowNumber: Int,
    val name: String,
    val legalForm: String?,
    val seat: String?,
    /** Share in hundredths of a percent, 0..10000. */
    val shareBasisPoints: Int?,
    val incomeCents: Long?,
    val expensesCents: Long?,
)

@Serializable
data class PartgEnterpriseInputDto(
    /** `null` = a new row. */
    val id: String?,
    val name: String,
    val legalForm: String?,
    val seat: String?,
    val shareBasisPoints: Int?,
    val incomeCents: Long?,
    val expensesCents: Long?,
)

@Serializable
enum class PartgEnterpriseCheckStatus { MATCHES, DIFFERS, POSITION_NOT_ENTERED, NOT_COMPARABLE }

/** The check of statement D against ONE R1 position. Carries NO amount (neither the D sum nor the R1 value). */
@Serializable
data class PartgEnterpriseCheckDto(
    val positionCode: String,
    val status: PartgEnterpriseCheckStatus,
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
    /** V1.9.283 (R3b): ALL sponsors of the unit (also those under both limits), sorted: yearly sum descending, name, id. Not paged: the unit is capped. */
    val sponsors: List<PartgSponsorDto> = emptyList(),
    /** V1.9.283 (R3b): the youth organisations of the unit, sorted: name, id. */
    val youthOrgs: List<PartgYouthOrgDto> = emptyList(),
    /** V1.9.287 (R3c): statement D of the unit, sorted: created_at, id. */
    val enterprises: List<PartgEnterpriseDto> = emptyList(),
    /** V1.9.287 (R3c): E05 and A02F, in that order; empty when D has no row. */
    val enterpriseChecks: List<PartgEnterpriseCheckDto> = emptyList(),
    /** V1.9.293 (R5b): whether a proposal for statement B from the bookings is available (FEDERAL only). */
    val donorProposal: PartgDonorProposalAvailability = PartgDonorProposalAvailability.NOT_APPLICABLE,
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
enum class PartgFindingCode { P01, P02, P03, P04, P05, P06, P07, P08, P09, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21 }

@Serializable
enum class PartgFindingLevel { CHECK, INFO }

@Serializable
enum class PartgFindingTarget { ANNEX, NOTES, SHEET, MAPPING, OVERVIEW, CONSOLIDATION }

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
    /** V1.9.289 (R4a): a COUNT (e.g. accounts without a position, bookings in mode C). Never an amount. */
    val count: Int? = null,
)
