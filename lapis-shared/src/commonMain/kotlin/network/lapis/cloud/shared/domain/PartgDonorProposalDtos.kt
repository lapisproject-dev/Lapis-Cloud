package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.293 (R5b) -- the proposal for statement B (large donors) computed from the bookings of the FEDERAL unit. See `docs/features/partg-report.adoc`.
 * Enum literal order is part of the wire format; new literals only at the end.
 *
 * The proposal is a SUGGESTION: nothing is written until the board takes rows over, and rows entered by hand are never deleted or changed
 * without an explicit pick. Only the bookings of the federal unit are added up; the case "several smaller amounts to several units" is NOT
 * recognised (that would need personal data below the threshold, see open question Q40).
 */
@Serializable
enum class PartgDonorProposalStatus { NEW, DIFFERENT, UNCHANGED, SIMILAR, ENTERED_ONLY, NOT_TRANSFERABLE }

@Serializable
enum class PartgDonorProposalAction { CREATE, REPLACE_AMOUNT, LINK }

/** Why a row of the proposal cannot be taken over. */
@Serializable
enum class PartgDonorProposalBlock { KIND_UNCLEAR, COUNTRY_MISSING, MASTER_DATA_INVALID, CONTAINS_FEES }

/** Where a row of statement B comes from (computed for the display, never part of the stored report version). */
@Serializable
enum class PartgLargeDonorOrigin { MANUAL, LEDGER, LEDGER_EDITED }

/** Whether the annex can offer a proposal for statement B. */
@Serializable
enum class PartgDonorProposalAvailability { NOT_APPLICABLE, MODE_C, AVAILABLE }

@Serializable
data class PartgDonorProposalRowDto(
    /** The donor key `M:<member uuid>` / `X:<external donor uuid>`; `null` for ENTERED_ONLY. Never logged, never audited. */
    val ref: String?,
    val status: PartgDonorProposalStatus,
    val personKind: PartgPersonKind?,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    /** The yearly sum according to the bookings; `null` when the row is only entered (or its bookings are not above the threshold). */
    val bookedCents: Long?,
    /** The amount of the entered row; `null` when there is none. */
    val enteredCents: Long?,
    /** The entered row that carries this key (id), if any. */
    val linkedRowId: String?,
    /** Entered rows without a key that look like the same person (id); the candidates for LINK. */
    val similarRowIds: List<String> = emptyList(),
    val allowedActions: Set<PartgDonorProposalAction>,
    val preselected: PartgDonorProposalAction?,
    val block: PartgDonorProposalBlock? = null,
    /** ENTERED_ONLY and linked: the bookings of this donor are at present not above the threshold. */
    val linkedBelowThreshold: Boolean = false,
)

@Serializable
data class PartgDonorProposalDto(
    val year: Int,
    val annexRevision: Long,
    /** SHA-256 (hex, 64 lower-case characters) over everything the proposal shows; the take-over is refused when it changed in the meantime. */
    val checksum: String,
    val rows: List<PartgDonorProposalRowDto>,
    /** Donors of the bookings that were anonymised: counted, never named. */
    val anonymisedCount: Int,
    /** Donation bookings without any donor: counted only. */
    val withoutDonorCount: Int,
)

@Serializable
data class PartgDonorProposalPickDto(
    val ref: String,
    val action: PartgDonorProposalAction,
    /** The entered row to link (action LINK only). */
    val targetRowId: String? = null,
)
