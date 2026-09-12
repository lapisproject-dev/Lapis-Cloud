package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
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
 * dropdown, three separate "add line" buttons in the self-service editor instead). Literal order
 * load-bearing, same reason as [TravelExpenseReportStatus] -- append only.
 */
@Serializable
enum class TravelExpenseLineKind { MILEAGE, PER_DIEM, RECEIPTED }

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
    /** Always server-computed, never supplied by the client. */
    val amount: Decimal,
    val receipts: List<TravelExpenseReceiptDto> = emptyList(),
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
)

@Serializable
data class TravelExpenseReportInput(
    val purpose: String,
    val travelFrom: LocalDate,
    val travelTo: LocalDate,
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
)

/**
 * What the self-service form needs without calling `getOrganizationSettings()` (TREASURER/BOARD/
 * ADMIN-gated). Deliberately does NOT expose the ledger-account id, only whether it is configured.
 */
@Serializable
data class TravelExpenseRatesDto(
    val mileageRatePerKm: Decimal? = null,
    val perDiemRate: Decimal? = null,
    val expenseAccountConfigured: Boolean = false,
    val bankAccountConfigured: Boolean = false,
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
    const val MAX_LINES_PER_REPORT = 30
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
)
