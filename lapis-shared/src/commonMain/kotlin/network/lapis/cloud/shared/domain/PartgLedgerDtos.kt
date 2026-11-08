package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.9.289 (R4a) -- one ledger account in the "Kontenzuordnung" tab. [positionCode] / [excluded] are the STORED mapping (both empty = not
 * mapped). [templateSuggestion] / [templateSuggestsExcluded] come from the party chart template and are only shown for accounts that are not
 * mapped yet. [balanceCents] is the balance of the report year (flow for INCOME/EXPENSE, closing balance for ASSET/LIABILITY), `null` when it
 * does not fit; [postingCount] counts postings in the year.
 */
@Serializable
data class PartgAccountMappingRowDto(
    val accountId: String,
    val number: String,
    val name: String,
    val type: LedgerAccountType,
    val active: Boolean,
    val positionCode: String? = null,
    val excluded: Boolean = false,
    val templateSuggestion: String? = null,
    val templateSuggestsExcluded: Boolean = false,
    val balanceCents: Long? = null,
    val postingCount: Int = 0,
    /**
     * V1.9.292 (R4b): a GUESS from the account name ([PartyAccountNameHeuristic]), only for federal callers and only for an unmapped account
     * without a hit in the standard chart. Never applied by itself; [nameSuggestionKeyword] is the word of the name that triggered it.
     */
    val nameSuggestion: String? = null,
    val nameSuggestionKeyword: String? = null,
)

@Serializable
data class PartgAccountMappingViewDto(
    val rows: List<PartgAccountMappingRowDto>,
    val unmappedCount: Int,
    /** Unmapped accounts that have postings in [year]. */
    val unmappedWithActivityCount: Int,
    /** `true` iff the caller may save (federal roles). A chapter treasurer reads only. */
    val editable: Boolean,
    val year: Int,
    val unitKey: String,
)

/**
 * One requested change of the mapping. The expected old state (`expected*`) is compared under the lock; a mismatch is a conflict.
 * [clear] removes the mapping (back to "not mapped"); otherwise either [newPositionCode] is set or [newExcluded] is `true`.
 */
@Serializable
data class PartgMappingChangeDto(
    val accountId: String,
    val expectedMapped: Boolean,
    val expectedPositionCode: String? = null,
    val expectedExcluded: Boolean? = null,
    val newPositionCode: String? = null,
    val newExcluded: Boolean = false,
    val clear: Boolean = false,
)

/** One position where the bookings differ from what is entered. */
@Serializable
data class PartgLedgerProposalRowDto(
    val positionCode: String,
    val enteredCents: Long? = null,
    val enteredSource: PartgValueSource? = null,
    val proposedCents: Long,
    val preselected: Boolean,
    val transferable: Boolean,
)

@Serializable
data class PartgLedgerProposalDto(
    val year: Int,
    val unitKey: String,
    /** Only the positions with a difference. */
    val rows: List<PartgLedgerProposalRowDto>,
    val revision: Long,
    val priorRevision: Long,
    val mode: PartgBookkeepingMode,
    /** Unmapped accounts with postings in the year (their amounts are NOT part of the proposal). */
    val unmappedWithActivityCount: Int = 0,
)

/** One value the person chose to take over. The server recomputes the amount under the lock and refuses a value that differs. */
@Serializable
data class PartgProposalPickDto(
    val positionCode: String,
    val proposedCents: Long,
)

/** One active chapter-treasurer grant (V1.9.289). */
@Serializable
data class RegionalChapterTreasurerDto(
    val grantId: String,
    val memberId: String,
    val displayName: String,
    val chapterId: String,
    val grantedAt: LocalDateTime,
)

/** Audit snapshot of a changed mapping: account NUMBER and position codes only, never a balance, never a name. */
@Serializable
data class PartgMappingAuditSnapshot(
    val accountNumber: String,
    val oldCode: String? = null,
    val oldExcluded: Boolean? = null,
    val newCode: String? = null,
    val newExcluded: Boolean? = null,
)

/** Audit snapshot of a changed bookkeeping mode: the booking COUNT, never an amount. */
@Serializable
data class PartgModeAuditSnapshot(
    val year: Int,
    val unitKey: String,
    val old: PartgBookkeepingMode,
    val new: PartgBookkeepingMode,
    val bookingCount: Int,
)

/** Collective audit snapshot of an applied ledger proposal: counts and codes only. */
@Serializable
data class PartgLedgerProposalAppliedSnapshot(
    val year: Int,
    val unitKey: String,
    val count: Int,
    val codes: List<String>,
)

/** Audit snapshot of a chapter-treasurer grant: ids only. */
@Serializable
data class RegionalChapterTreasurerSnapshot(
    val memberId: String,
    val regionalChapterId: String,
    val revoked: Boolean = false,
)
