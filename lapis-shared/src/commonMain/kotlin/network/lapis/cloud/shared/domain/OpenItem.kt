package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" (`OpenItem`). Literal order is load-bearing
 * (`OpenItemSchemaDriftTest` pins it against `48-open-item.kuml.kts`'s `openItemDirection` enum)
 * -- append only, never reorder. See `network.lapis.cloud.server.rpc.OpenItemService` KDoc for
 * the full state machine and `docs/architecture/open-items.adoc` for the fachlich rationale.
 */
@Serializable
enum class OpenItemDirection { PAYABLE, RECEIVABLE }

/**
 * Literal order load-bearing, same reason as [OpenItemDirection]. `status` is materialized on
 * every write but is NEVER the source of truth for the open amount -- see
 * `network.lapis.cloud.server.rpc.OpenItemMath.openAmount` KDoc.
 */
@Serializable
enum class OpenItemStatus { OPEN, PARTIALLY_SETTLED, SETTLED, CANCELLED }

/** Literal order load-bearing, same reason as [OpenItemDirection]. */
@Serializable
enum class OpenItemSettlementKind { PAYMENT, NETTING }

/** Literal order load-bearing, same reason as [OpenItemDirection]. */
@Serializable
enum class ReceivableDunningNoticeStatus { ISSUED, SKIPPED, CANCELLED }

/**
 * The ONE place a "which [OpenItemStatus] literals may do X" question is answered -- same
 * doctrine [TravelExpenseReportStatusSets] already establishes for its own, structurally
 * different enum.
 */
object OpenItemStatusSets {
    /** May still receive a [OpenItemSettlementKind.PAYMENT] or take part in a netting. */
    val SETTLEABLE: Set<OpenItemStatus> = setOf(OpenItemStatus.OPEN, OpenItemStatus.PARTIALLY_SETTLED)

    /** Terminal -- no further settlement/netting/dunning may ever act on the item again. */
    val CLOSED: Set<OpenItemStatus> = setOf(OpenItemStatus.SETTLED, OpenItemStatus.CANCELLED)
}

/**
 * Pure, platform-neutral counterparty-name normalization -- the SOLE place this codebase decides
 * whether two free-text counterparty names denote "the same" counterparty for netting-candidate
 * matching (`network.lapis.cloud.server.rpc.OpenItemService.listNettingCandidates`). Deliberately
 * `commonMain` (usable from both `lapis-server` and, for a future client-side preview, `lapis-
 * client`) -- same "one normalization function, never duplicated" doctrine every other codegen-
 * adjacent helper in this codebase follows.
 */
object CounterpartyKey {
    const val MAX_LENGTH = 200

    /**
     * Compiled ONCE, not per [of] call (review fund N11): the client's list filter runs [of] twice
     * per loaded row per keystroke (`openItemFilterCounts` + `applyOpenItemFilter`, up to 200 rows),
     * and `Regex("\\s+")` inside the function body meant one fresh `RegExp` compilation per call.
     */
    private val WHITESPACE_RUN = Regex("\\s+")

    /** `lower(trim(collapse_whitespace(name)))`, truncated to [MAX_LENGTH]. */
    fun of(name: String): String =
        name
            .trim()
            .replace(WHITESPACE_RUN, " ")
            .lowercase()
            .take(MAX_LENGTH)
}

@Serializable
data class OpenItemDto(
    val id: String,
    val direction: OpenItemDirection,
    val counterpartyName: String,
    val counterpartyKey: String,
    val crmContactId: String? = null,
    val reference: String? = null,
    val itemDate: LocalDate,
    val dueDate: LocalDate,
    val amount: Decimal,
    /** Never stored -- `amount - Σ(settlements where reversedAt IS NULL)`, always server-computed. */
    val openAmount: Decimal,
    val contraAccountId: String,
    val contraAccountNumber: String,
    val contraAccountName: String,
    val sphere: GemeinnuetzigkeitSphere,
    val status: OpenItemStatus,
    val note: String? = null,
    /** Server-computed against [asOf]; `0` means not overdue. Never re-derived on the client. */
    val daysOverdue: Int,
    /** The reference date [daysOverdue] was computed against (server clock, not the client's). */
    val asOf: LocalDate,
    val creationJournalEntryId: String? = null,
    val creationPostingError: String? = null,
    val createdByMemberId: String,
    val createdAt: LocalDateTime,
    val cancelledAt: LocalDateTime? = null,
    val cancellationReason: String? = null,
    /**
     * Highest dunning level with an **ISSUED** notice (a `SKIPPED` slot was deliberately not dunned, a
     * `CANCELLED` one was withdrawn), `null` while nothing has been issued. RECEIVABLE items only --
     * always `null` on a PAYABLE one, which this domain never duns.
     */
    val highestDunningLevelNumber: Int? = null,
    /**
     * The level `issueReceivableDunningNotice` would really issue next, `null` when there is none (all
     * active levels used, none configured, or the item is no longer settleable). An occupied
     * `uq_rdn_slot` blocks re-issuing regardless of the notice's status, so this can be beyond
     * [highestDunningLevelNumber] + 1.
     */
    val nextDunningLevelNumber: Int? = null,
    /**
     * Server-computed date [nextDunningLevelNumber] becomes due ([dueDate] + that level's grace days),
     * `null` whenever [nextDunningLevelNumber] is. It is **not** a lock: manual dunning is allowed
     * before it (only the automatic run waits for it). Shown next to the issue button in the open-item
     * detail ("Nächste Stufe fällig am …").
     */
    val nextDunningLevelDueOn: LocalDate? = null,
)

@Serializable
data class OpenItemInput(
    val direction: OpenItemDirection,
    val counterpartyName: String,
    val crmContactId: String? = null,
    val reference: String? = null,
    val itemDate: LocalDate,
    val dueDate: LocalDate,
    val amount: Decimal,
    val contraAccountId: String,
    val sphere: GemeinnuetzigkeitSphere,
    val note: String? = null,
)

@Serializable
data class OpenItemSettlementDto(
    val id: String,
    val openItemId: String,
    val kind: OpenItemSettlementKind,
    val amount: Decimal,
    val settledOn: LocalDate,
    val nettingId: String? = null,
    val journalEntryId: String? = null,
    val postingError: String? = null,
    val createdByMemberId: String,
    val createdAt: LocalDateTime,
    val reversedAt: LocalDateTime? = null,
    val reversalReason: String? = null,
)

@Serializable
data class OpenItemDetailDto(
    val item: OpenItemDto,
    val settlements: List<OpenItemSettlementDto> = emptyList(),
    val dunningNotices: List<ReceivableDunningNoticeDto> = emptyList(),
)

/** One aging-bucket cell of the summary row. */
@Serializable
enum class OpenItemAgingBucket { NOT_DUE, DAYS_1_30, DAYS_31_90, OVER_90 }

@Serializable
data class OpenItemAgingBucketDto(
    val bucket: OpenItemAgingBucket,
    val count: Int,
    val totalAmount: Decimal,
)

/**
 * `payableOpenTotal`/`receivableOpenTotal` are NEVER netted against each other -- Jobs-Ruling: in
 * the "Alle" segment the UI shows two separate totals, never one net figure (a payables total and
 * a receivables total answer two different questions: "what do we owe" and "what are we owed").
 */
@Serializable
data class OpenItemSummaryDto(
    val asOf: LocalDate,
    val payableBuckets: List<OpenItemAgingBucketDto> = emptyList(),
    val receivableBuckets: List<OpenItemAgingBucketDto> = emptyList(),
    val payableOpenTotal: Decimal,
    val receivableOpenTotal: Decimal,
    val nettingCandidateCounterpartyCount: Int = 0,
    val accountsConfigured: Boolean = false,
)

@Serializable
data class NettingCandidateDto(
    val counterpartyKey: String,
    val counterpartyDisplayName: String,
    val crmContactId: String? = null,
    /** `true` = matched only via the normalized name, not via a shared CRM contact. */
    val matchedByNameOnly: Boolean,
    val payable: OpenItemDto,
    val receivable: OpenItemDto,
    val maxNettableAmount: Decimal,
)

/** Norman-Ruling: a mandatory, literal preview shown BEFORE [OpenItemDetailDto] execution. */
@Serializable
data class NettingPreviewDto(
    val debitAccountNumber: String,
    val debitAccountName: String,
    val creditAccountNumber: String,
    val creditAccountName: String,
    val amount: Decimal,
    val payableOpenAmountAfter: Decimal,
    val receivableOpenAmountAfter: Decimal,
    val payableStatusAfter: OpenItemStatus,
    val receivableStatusAfter: OpenItemStatus,
)

@Serializable
data class OpenItemNettingDto(
    val id: String,
    val counterpartyKey: String,
    val amount: Decimal,
    val payableItemId: String,
    val receivableItemId: String,
    val journalEntryId: String? = null,
    val postingError: String? = null,
    val createdByMemberId: String,
    val createdAt: LocalDateTime,
    val reversedAt: LocalDateTime? = null,
    val reversalReason: String? = null,
)

/** What the counterparty-defaults lookup returns -- empty list means no prior item for this name. */
@Serializable
data class CounterpartyDefaultsDto(
    val contraAccountId: String,
    val contraAccountNumber: String,
    val contraAccountName: String,
    val sphere: GemeinnuetzigkeitSphere,
)

/**
 * Structured payload for an [AuditEntityType.OPEN_ITEM] audit entry. **Never carries**
 * [OpenItemDto.counterpartyName]/[OpenItemDto.reference]/[OpenItemDto.note] (free text, PII-
 * minimization same as [TravelExpenseSnapshot]) -- carries [creationPostingError] so two
 * consecutive failed `retryOpenItemPosting` entries are not byte-identical.
 */
@Serializable
data class OpenItemSnapshot(
    val openItemId: String,
    val direction: OpenItemDirection,
    val counterpartyKey: String,
    val status: OpenItemStatus,
    val amount: Decimal,
    val creationJournalEntryId: String? = null,
    val creationPostingError: String? = null,
    val reference: String? = null,
    val note: String? = null,
)

/** Structured payload for an [AuditEntityType.OPEN_ITEM_NETTING] audit entry. */
@Serializable
data class OpenItemNettingSnapshot(
    val nettingId: String,
    val payableItemId: String,
    val receivableItemId: String,
    val amount: Decimal,
    val journalEntryId: String? = null,
)
