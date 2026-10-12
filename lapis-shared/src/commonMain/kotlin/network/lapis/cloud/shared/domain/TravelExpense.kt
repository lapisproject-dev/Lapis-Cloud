package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger". Literal order is
 * load-bearing (`TravelExpenseSchemaDriftTest` pins it against `45-travel-expense.kuml.kts`'s
 * `travelExpenseReportStatus` enum) -- append only, never reorder. See
 * `network.lapis.cloud.server.rpc.TravelExpenseService` KDoc for the full state machine.
 */
@Serializable
enum class TravelExpenseReportStatus { DRAFT, REQUESTED, APPROVED, REJECTED, EXECUTED, WITHDRAWN }

/**
 * Welle V1.4.11. UNCHANGEABLE once a line exists (Raskin/Tesler-Ruling: no per-line kind
 * dropdown, separate "add line" buttons in the self-service editor instead). Literal order
 * load-bearing, same reason as [TravelExpenseReportStatus] -- append only.
 *
 * V1.9.122 appends [DAY_ALLOWANCE] (one per travel day, maintained ONLY via
 * `ITravelExpenseService.saveDays`/the header sync, never `addLine`), [PUBLIC_TRANSPORT],
 * [ACCOMMODATION], [HOSPITALITY] and [OTHER] (receipted kinds). [PER_DIEM] and [RECEIPTED] stay
 * readable/submittable for reports created before V1.9.122 but the UI no longer creates them.
 */
@Serializable
enum class TravelExpenseLineKind {
    MILEAGE,
    PER_DIEM,
    RECEIPTED,
    DAY_ALLOWANCE,
    PUBLIC_TRANSPORT,
    ACCOMMODATION,
    HOSPITALITY,
    OTHER,
    ;

    /** Kinds that need an attached receipt before submit (mileage's map proof is optional). */
    val requiresReceipt: Boolean
        get() = this == RECEIPTED || this == PUBLIC_TRANSPORT || this == ACCOMMODATION || this == HOSPITALITY || this == OTHER
}

/** V1.9.122 -- how the member wants the reimbursement settled. Literal order load-bearing (append only). */
@Serializable
enum class TravelExpensePayoutChoice { PAYOUT, DONATION_WAIVER }

@Serializable
data class TravelExpenseReceiptDto(
    val id: String,
    val lineId: String,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedAt: LocalDateTime,
)

@Serializable
data class TravelExpenseLineDto(
    val id: String,
    val reportId: String,
    val kind: TravelExpenseLineKind,
    val description: String,
    val kilometers: Decimal? = null,
    val days: Int? = null,
    /** The frozen rate. After [network.lapis.cloud.shared.rpc.ITravelExpenseService.submitReport]
     *  runs, nothing reads `OrganizationSettings` again for this line. */
    val rateSnapshot: Decimal? = null,
    /** Always server-computed, never supplied by the client. For a day line this is the NET amount. */
    val amount: Decimal,
    val receipts: List<TravelExpenseReceiptDto> = emptyList(),
    // ── V1.9.122 (all defaulted: wire-compatible with pre-V1.9.122 clients/payloads) ──
    val destination: String? = null,
    /** DAY_ALLOWANCE: the travel day. ACCOMMODATION: the first night. */
    val dayDate: LocalDate? = null,
    val timeFrom: LocalTime? = null,
    val timeTo: LocalTime? = null,
    val breakfastProvided: Boolean = false,
    val lunchProvided: Boolean = false,
    val dinnerProvided: Boolean = false,
    /** Server-computed: a breakfast supplied by an overnight stay with breakfast (cannot be unchecked). */
    val breakfastForcedByAccommodation: Boolean = false,
    val passengerCount: Int? = null,
    val breakfastIncluded: Boolean? = null,
    val nights: Int? = null,
    val computedMinutes: Int? = null,
    val baseCents: Long? = null,
    val deductionCents: Long = 0,
    /** Net amount in whole cents (== [amount]); the unit the client calculates and displays in. */
    val amountCents: Long = 0,
)

@Serializable
data class TravelExpenseReportDto(
    val id: String,
    val subjectMemberId: String,
    val subjectDisplayName: String,
    val status: TravelExpenseReportStatus,
    val purpose: String,
    val travelFrom: LocalDate,
    val travelTo: LocalDate,
    /** Sum of every line, computed server-side. The client may DISPLAY it, never supply it. */
    val totalAmount: Decimal,
    val lines: List<TravelExpenseLineDto> = emptyList(),
    val createdAt: LocalDateTime,
    val submittedAt: LocalDateTime? = null,
    val requestedBy: String,
    val requestedByDisplayName: String,
    val decidedBy: String? = null,
    val decidedByDisplayName: String? = null,
    val decidedAt: LocalDateTime? = null,
    val decisionNote: String? = null,
    val executedAt: LocalDateTime? = null,
    /** Non-null iff [status] == EXECUTED (`chk_ter_posted_entry_state`). Idempotency anchor. */
    val postedJournalEntryId: String? = null,
    /** Non-null iff [status] == APPROVED (`chk_ter_execution_error_state`). A raw error code --
     *  render it ONLY via `travelExpensePostingErrorMessage`, never verbatim. */
    val executionError: String? = null,
    // ── V1.9.122 ──
    val advanceCents: Long = 0,
    val payoutChoice: TravelExpensePayoutChoice? = null,
    /** Snapshot taken at submit; before that the live member number. */
    val membershipNumber: String? = null,
    val grossCents: Long = 0,
    val deductionCents: Long = 0,
    val payoutCents: Long = 0,
    /** Frozen calculation parameters; `null` while still a DRAFT. */
    val ratesSnapshot: TravelExpenseRateSet? = null,
    /** 31 December of the travel year -- an informational hint, never blocking. */
    val submissionDeadline: LocalDate? = null,
)

/**
 * @property advanceCents `null` == unchanged (create: 0). Whole cents, 0..[TravelExpenseAmountRules.MAX_ADVANCE_CENTS].
 * @property payoutChoice `null` == unchanged (create: unset). The choice is mandatory at submit.
 */
@Serializable
data class TravelExpenseReportInput(
    val purpose: String,
    val travelFrom: LocalDate,
    val travelTo: LocalDate,
    val advanceCents: Long? = null,
    val payoutChoice: TravelExpensePayoutChoice? = null,
)

/** One travel day as entered in the day grid; the server recomputes every amount. */
@Serializable
data class TravelExpenseDayInput(
    val dayDate: LocalDate,
    val timeFrom: LocalTime? = null,
    val timeTo: LocalTime? = null,
    val breakfastProvided: Boolean = false,
    val lunchProvided: Boolean = false,
    val dinnerProvided: Boolean = false,
    val destination: String? = null,
)

/**
 * Exactly the fields for [kind] set, every other field `null` -- same discriminated-flat-row
 * idiom [ContributionReliefRequestInput] establishes, server-validated (`requireLineShape`) and
 * mirrored DB-side by `chk_tel_kind_shape`. [amount] is ONLY for RECEIPTED -- for MILEAGE/PER_DIEM
 * it must be `null` (the server computes it).
 */
@Serializable
data class TravelExpenseLineInput(
    val kind: TravelExpenseLineKind,
    val description: String,
    val kilometers: Decimal? = null,
    val days: Int? = null,
    val amount: Decimal? = null,
    // ── V1.9.122 ──
    val destination: String? = null,
    val passengerCount: Int? = null,
    /** ACCOMMODATION: the first night. */
    val dayDate: LocalDate? = null,
    val breakfastIncluded: Boolean? = null,
    val nights: Int? = null,
    /** Receipted kinds: amount in whole cents. If [amount] is ALSO set, both must agree. */
    val amountCents: Long? = null,
)

/**
 * Contact person for travel-expense questions, entered by the instance operator. Personal data of
 * a contact person (no FK to `member`) -- see `docs/architecture/travel-expenses.adoc`.
 */
@Serializable
data class TravelExpenseContactDto(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val hint: String? = null,
)

/**
 * What the self-service form needs without calling `getOrganizationSettings()` (TREASURER/BOARD/
 * ADMIN-gated). Deliberately does NOT expose the ledger-account id, only whether it is configured.
 * [advanceAccountId]/[donationWaiverAccountId] are filled for ADMIN only.
 */
@Serializable
data class TravelExpenseRatesDto(
    val mileageRatePerKm: Decimal? = null,
    val perDiemRate: Decimal? = null,
    val expenseAccountConfigured: Boolean = false,
    val bankAccountConfigured: Boolean = false,
    // ── V1.9.122 ──
    val rateSet: TravelExpenseRateSet? = null,
    val contact: TravelExpenseContactDto = TravelExpenseContactDto(),
    val canEditSettings: Boolean = false,
    val advanceAccountId: String? = null,
    val donationWaiverAccountId: String? = null,
)

/**
 * Everything the ADMIN settings screen writes (V1.9.122). Whole cents everywhere; the server
 * validates every range BEFORE the database and trims/normalises the contact block (blank ->
 * `null`). [mileageCentsPerKm] `null` == mileage not offered.
 */
@Serializable
data class TravelExpenseSettingsInput(
    val tierHours: List<Int>,
    val tierCents: List<Long>,
    val breakfastPercent: Int,
    val lunchPercent: Int,
    val dinnerPercent: Int,
    val mileageCentsPerKm: Long? = null,
    val passengerCentsPerKm: Long,
    val perDiemLegacyRate: Decimal? = null,
    val contact: TravelExpenseContactDto = TravelExpenseContactDto(),
    val advanceAccountId: String? = null,
    val donationWaiverAccountId: String? = null,
)

/**
 * The ONE place a "which [TravelExpenseReportStatus] literals may do X" question is answered --
 * same doctrine [ContributionReliefStatusSets] already establishes for its own, structurally
 * different enum.
 */
object TravelExpenseReportStatusSets {
    val TERMINAL: Set<TravelExpenseReportStatus> =
        setOf(TravelExpenseReportStatus.REJECTED, TravelExpenseReportStatus.EXECUTED, TravelExpenseReportStatus.WITHDRAWN)

    /** Lines/receipts may only be mutated here. */
    val EDITABLE: Set<TravelExpenseReportStatus> = setOf(TravelExpenseReportStatus.DRAFT)

    val WITHDRAWABLE: Set<TravelExpenseReportStatus> =
        setOf(TravelExpenseReportStatus.DRAFT, TravelExpenseReportStatus.REQUESTED)

    /** `approve=false` is also valid from APPROVED -- "no dead end", same as the Relief precedent. */
    val REJECTABLE: Set<TravelExpenseReportStatus> =
        setOf(TravelExpenseReportStatus.REQUESTED, TravelExpenseReportStatus.APPROVED)

    /** Counts against [TravelExpenseAmountRules.MAX_OPEN_DRAFTS_PER_MEMBER]; blocks nothing else. */
    val OPEN: Set<TravelExpenseReportStatus> =
        setOf(TravelExpenseReportStatus.DRAFT, TravelExpenseReportStatus.REQUESTED, TravelExpenseReportStatus.APPROVED)
}

/**
 * Pure, platform-neutral amount rules -- the ONE declarative reference `TravelExpenseAmountsTest`
 * checks the server computation against (same role as [ContributionExemptionRules]). Rounding is
 * HALF_UP to 2 decimals, because `JournalEntryBalance.MAX_AMOUNT_SCALE == 2`.
 */
object TravelExpenseAmountRules {
    const val MAX_KILOMETERS = 10_000
    const val MAX_DAYS = 60
    const val MAX_LINE_AMOUNT = 100_000

    /** V1.9.122: counts the NON-day lines only; day lines have their own cap [MAX_DAY_LINES]. */
    const val MAX_LINES_PER_REPORT = 30
    const val MAX_OTHER_LINES = 30
    const val MAX_DAY_LINES = 31
    const val MAX_TRAVEL_SPAN_DAYS = 31
    const val MAX_KM_PER_LINE = 5000
    const val MAX_PASSENGERS = 8
    const val MAX_RECEIPTED_CENTS = 1_000_000L
    const val MAX_ADVANCE_CENTS = 100_000_000L
    const val MAX_DESTINATION_LENGTH = 200
    const val MAX_NIGHTS = 30
    const val MAX_CONTACT_NAME_LENGTH = 120
    const val MAX_CONTACT_EMAIL_LENGTH = 254
    const val MAX_CONTACT_PHONE_LENGTH = 40
    const val MAX_CONTACT_HINT_LENGTH = 1000
    const val MAX_RECEIPTS_PER_LINE = 5
    const val MAX_RECEIPTS_PER_REPORT = 50
    const val MAX_PURPOSE_LENGTH = 200
    const val MAX_DESCRIPTION_LENGTH = 200
    const val MAX_DECISION_NOTE_LENGTH = 1000
    const val MAX_OPEN_DRAFTS_PER_MEMBER = 10

    /** A plausibility cap against typos, not a legal requirement. */
    const val MAX_TRAVEL_BACKDATE_DAYS = 730
}

/**
 * Structured payload for an [AuditEntityType.TRAVEL_EXPENSE_REPORT] audit entry. **Never carries**
 * [TravelExpenseReportDto.purpose]/[TravelExpenseLineDto.description]/
 * [TravelExpenseReportDto.decisionNote] (free text, PII-minimization same as
 * [ContributionReliefSnapshot]); [executionError] IS carried -- otherwise two consecutive failed
 * `retryPosting` entries would be byte-identical.
 */
@Serializable
data class TravelExpenseSnapshot(
    val reportId: String,
    val subjectMemberId: String,
    val status: TravelExpenseReportStatus,
    val travelFrom: LocalDate,
    val travelTo: LocalDate,
    val lineCount: Int,
    val lineKinds: List<TravelExpenseLineKind>,
    val totalAmount: Decimal,
    val postedJournalEntryId: String? = null,
    val executionError: String? = null,
    // V1.9.122 -- numbers/enums only; free text (destination) is NEVER carried.
    val payoutChoice: TravelExpensePayoutChoice? = null,
    val advanceCents: Long = 0,
    val dayLineCount: Int = 0,
)
