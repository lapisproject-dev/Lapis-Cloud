package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG). Literal order is
 * load-bearing (`VolunteerAllowanceSchemaDriftTest` pins it against
 * `46-volunteer-allowance.kuml.kts`) -- append only, never reorder. [INSTRUCTOR] maps to §3
 * Nr. 26 EStG Übungsleiterpauschale, [HONORARY] to §3 Nr. 26a EStG Ehrenamtspauschale -- two
 * INDEPENDENT annual caps, see `network.lapis.cloud.server.rpc.VolunteerAllowanceCalculator`.
 * UNCHANGEABLE once a payment exists (Kare-Ruling, analog [TravelExpenseLineKind]): no dropdown
 * on an existing payment, `updateDraft` does not accept the category.
 */
@Serializable
enum class VolunteerAllowanceCategory { INSTRUCTOR, HONORARY }

/**
 * Welle V1.4.12. Literal order load-bearing, same reason as [VolunteerAllowanceCategory] -- append
 * only. See `network.lapis.cloud.server.rpc.VolunteerAllowanceService` KDoc for the full state
 * machine.
 */
@Serializable
enum class VolunteerAllowancePaymentStatus { DRAFT, REQUESTED, APPROVED, REJECTED, EXECUTED, WITHDRAWN }

/** Welle V1.4.12. Literal order load-bearing, same reason as above. */
@Serializable
enum class VolunteerAllowanceDeclarationSource { IN_APP, ON_PAPER }

/**
 * Welle V1.4.12. **Deliberately lives in the shared module** (unlike `DonationVerdict`, which is
 * `internal` server-side) -- the client needs it for the year-progress bar and the `EXCEEDS_CAP`
 * gate on the approval card. Not a column type (no DB representation), so it is NOT modelled in
 * `46-volunteer-allowance.kuml.kts`.
 */
@Serializable
enum class VolunteerAllowanceVerdict { WITHIN_CAP, CAP_EXHAUSTED, EXCEEDS_CAP }

@Serializable
data class VolunteerAllowancePaymentDto(
    val id: String,
    val subjectMemberId: String,
    val subjectDisplayName: String,
    val category: VolunteerAllowanceCategory,
    val status: VolunteerAllowancePaymentStatus,
    val amount: Decimal,
    val activityDescription: String,
    val paymentDate: LocalDate,
    val createdAt: LocalDateTime,
    val submittedAt: LocalDateTime? = null,
    val requestedBy: String,
    val requestedByDisplayName: String,
    val decidedBy: String? = null,
    val decidedByDisplayName: String? = null,
    val decidedAt: LocalDateTime? = null,
    val decisionNote: String? = null,
    val executedAt: LocalDateTime? = null,
    /** Non-null iff [status] == EXECUTED (`chk_vap_posted_entry_state`). Idempotency anchor. */
    val postedJournalEntryId: String? = null,
    /** Non-null iff [status] == APPROVED (`chk_vap_execution_error_state`). A raw wire code --
     *  render it ONLY via `volunteerAllowancePostingErrorMessage`, never verbatim. */
    val executionError: String? = null,
    /** Frozen at decision time (Duarte-Ruling), never recomputed live afterwards. */
    val priorTotalSnapshot: Decimal? = null,
    val freeAmountSnapshot: Decimal? = null,
    val exceedingAmountSnapshot: Decimal? = null,
    val capDisclaimerVersion: String? = null,
    val capAcknowledgedByDisplayName: String? = null,
    val capAcknowledgedAt: LocalDateTime? = null,
)

@Serializable
data class VolunteerAllowancePaymentInput(
    val category: VolunteerAllowanceCategory,
    val amount: Decimal,
    val activityDescription: String,
    val paymentDate: LocalDate,
)

/**
 * What the approval card needs for its year-progress bar -- LIVE, not the frozen snapshot. See
 * [remainingInThisOrganization] KDoc for the one-organization scope caveat.
 */
@Serializable
data class VolunteerAllowanceYearStatusDto(
    val memberId: String,
    val category: VolunteerAllowanceCategory,
    val calendarYear: Int,
    val annualCap: Decimal,
    /** Sum of this member's/category's/year's EXECUTED payments. */
    val postedTotalInThisOrganization: Decimal,
    /**
     * `annualCap - postedTotalInThisOrganization`, never negative. The §3 Nr. 26/26a EStG cap
     * applies PER PERSON PER CALENDAR YEAR across ALL organizations -- Lapis Cloud only ever
     * knows the sum booked in THIS organization, so this value is a maximum, never a guarantee
     * (Kay-Ruling). **NEVER label this "Restbetrag"** in any UI -- see `VolunteerAllowanceLabels
     * .volunteerAllowanceRemainingLabel` KDoc.
     */
    val remainingInThisOrganization: Decimal,
    val declaration: VolunteerAllowanceSelfDeclarationDto? = null,
)

@Serializable
data class VolunteerAllowanceSelfDeclarationDto(
    val id: String,
    val memberId: String,
    val category: VolunteerAllowanceCategory,
    val calendarYear: Int,
    val source: VolunteerAllowanceDeclarationSource,
    val declaredAt: LocalDateTime,
    val signedOn: LocalDate? = null,
    val recordedByDisplayName: String,
)

@Serializable
data class VolunteerAllowanceSelfDeclarationInput(
    val memberId: String,
    val category: VolunteerAllowanceCategory,
    val calendarYear: Int,
    val source: VolunteerAllowanceDeclarationSource,
    /** Required for ON_PAPER, must be `null` for IN_APP (`chk_vasd_source_shape`). */
    val signedOn: LocalDate? = null,
)

@Serializable
data class VolunteerAllowanceCapDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

@Serializable
data class VolunteerAllowanceCapAcknowledgmentInput(
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)

/**
 * What the self-service form needs without calling `getOrganizationSettings()`
 * (TREASURER/BOARD/ADMIN-gated). Deliberately does NOT expose the ledger-account id, only whether
 * it is configured -- same tier `TravelExpenseRatesDto` already establishes.
 */
@Serializable
data class VolunteerAllowanceConfigDto(
    val instructorCap: Decimal,
    val honoraryCap: Decimal,
    val expenseAccountConfigured: Boolean = false,
    val bankAccountConfigured: Boolean = false,
)

/** Pure, platform-neutral rules -- the ONE declarative reference for validation, same role as `TravelExpenseAmountRules`. */
object VolunteerAllowanceRules {
    const val MAX_ACTIVITY_DESCRIPTION_LENGTH = 200
    const val MIN_ACTIVITY_DESCRIPTION_LENGTH = 3
    const val MAX_DECISION_NOTE_LENGTH = 1000
    const val MAX_PAYMENT_AMOUNT = 10_000

    /** A plausibility cap against typos, not a legal requirement. */
    const val MAX_BACKDATE_DAYS = 400
    const val MAX_FUTURE_DAYS = 31
    const val MAX_OPEN_PER_MEMBER = 10
}

/** The ONE place a "which [VolunteerAllowancePaymentStatus] literals may do X" question is answered. */
object VolunteerAllowancePaymentStatusSets {
    val TERMINAL: Set<VolunteerAllowancePaymentStatus> =
        setOf(VolunteerAllowancePaymentStatus.REJECTED, VolunteerAllowancePaymentStatus.EXECUTED, VolunteerAllowancePaymentStatus.WITHDRAWN)

    /** May only be mutated here. */
    val EDITABLE: Set<VolunteerAllowancePaymentStatus> = setOf(VolunteerAllowancePaymentStatus.DRAFT)

    val WITHDRAWABLE: Set<VolunteerAllowancePaymentStatus> =
        setOf(VolunteerAllowancePaymentStatus.DRAFT, VolunteerAllowancePaymentStatus.REQUESTED)

    /** `approve=false` is also valid from APPROVED -- "no dead end", same as the Relief/Travel precedent. */
    val REJECTABLE: Set<VolunteerAllowancePaymentStatus> =
        setOf(VolunteerAllowancePaymentStatus.REQUESTED, VolunteerAllowancePaymentStatus.APPROVED)

    /** Counts against [VolunteerAllowanceRules.MAX_OPEN_PER_MEMBER]; blocks nothing else. */
    val OPEN: Set<VolunteerAllowancePaymentStatus> =
        setOf(VolunteerAllowancePaymentStatus.DRAFT, VolunteerAllowancePaymentStatus.REQUESTED, VolunteerAllowancePaymentStatus.APPROVED)

    /**
     * Counts toward the annual §3 Nr. 26/26a EStG cap -- ONLY EXECUTED, mirroring
     * `priorPostedDonationTotalThisYear`'s own `POSTED`-only filter. A REQUESTED/APPROVED payment
     * has not yet actually been paid out, so it must not shrink another payment's headroom.
     */
    val COUNTS_TOWARD_CAP: Set<VolunteerAllowancePaymentStatus> = setOf(VolunteerAllowancePaymentStatus.EXECUTED)
}

/**
 * Structured payload for a [AuditEntityType.VOLUNTEER_ALLOWANCE_PAYMENT] audit entry. **Never
 * carries** [VolunteerAllowancePaymentDto.activityDescription]/[VolunteerAllowancePaymentDto.decisionNote]
 * (free text, PII-minimization same as [TravelExpenseSnapshot]); [executionError] IS carried --
 * otherwise two consecutive failed `retryPosting` entries would be byte-identical.
 */
@Serializable
data class VolunteerAllowanceSnapshot(
    val paymentId: String,
    val subjectMemberId: String,
    val category: VolunteerAllowanceCategory,
    val status: VolunteerAllowancePaymentStatus,
    val paymentDate: LocalDate,
    val amount: Decimal,
    val priorTotalSnapshot: Decimal? = null,
    val freeAmountSnapshot: Decimal? = null,
    val exceedingAmountSnapshot: Decimal? = null,
    val capDisclaimerVersion: String? = null,
    val postedJournalEntryId: String? = null,
    val executionError: String? = null,
)

/** Structured payload for a [AuditEntityType.VOLUNTEER_DECLARATION] audit entry. Carries no free text at all. */
@Serializable
data class VolunteerAllowanceDeclarationSnapshot(
    val declarationId: String,
    val memberId: String,
    val category: VolunteerAllowanceCategory,
    val calendarYear: Int,
    val source: VolunteerAllowanceDeclarationSource,
    val signedOn: LocalDate? = null,
    val recordedBy: String,
)
