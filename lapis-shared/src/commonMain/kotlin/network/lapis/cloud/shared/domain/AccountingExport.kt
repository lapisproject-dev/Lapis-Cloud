package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung". The supported external bookkeeping providers a
 * journal can be pushed to LIVE (as opposed to the DATEV file export, V1.4.5.2, which has no
 * provider concept at all). Literal order load-bearing -- see
 * `network.lapis.cloud.server.db.AccountingExportSchemaDriftTest`, pinned against
 * `43-accounting-export.kuml.kts`. sevDesk hinzugefügt in V1.4.5.4, appended, never inserted --
 * see [network.lapis.cloud.server.accounting.export.AccountingExportProviderAdapter] KDoc for the
 * adapter seam this enum feeds.
 */
@Serializable
enum class AccountingExportProvider { LEXOFFICE, SEVDESK, }

/** Der Markenname des Anbieters, wie er dem Nutzer angezeigt wird -- die EINZIGE Stelle, an der
 * ein Fremdmarken-Name in diesem Codebase steht. Server (Blocker-Detailtexte,
 * `network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer`) und Client (UI-Beschriftungen) lesen
 * beide hier, damit die beiden Surfaces nicht auseinanderlaufen koennen -- Welle V1.4.5.4
 * "sevDesk-Live-Anbindung". */
val AccountingExportProvider.displayName: String
    get() =
        when (this) {
            AccountingExportProvider.LEXOFFICE -> "Lexware Office"
            AccountingExportProvider.SEVDESK -> "sevDesk"
        }

/** Lifecycle of one `startExport` run -- see `AccountingExportPoller` class KDoc "Tick-Phasen" for
 * how a run transitions between these. Literal order load-bearing (schema drift test). */
@Serializable
enum class AccountingExportRunStatus { PLANNED, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, ABORTED }

/** Lifecycle of one journal entry's export attempt within a run. `SKIPPED_ALREADY_EXPORTED` is
 * the idempotency-guard outcome (see file header "exportedKey" in `43-accounting-export.kuml.kts`);
 * `UNKNOWN` is the "sent, but the outcome was never confirmed" state -- a stale claim reaped by the
 * poller, or a network failure AFTER the HTTP request left this server (see
 * `AccountingExportPoller` KDoc "Phase A0"/[network.lapis.cloud.server.accounting.export.VoucherPushOutcome.Indeterminate]).
 * Literal order load-bearing (schema drift test). */
@Serializable
enum class AccountingExportItemStatus { PENDING, SENDING, SUCCEEDED, FAILED, SKIPPED_ALREADY_EXPORTED, UNKNOWN }

/**
 * Whether a journal entry maps to a revenue (`salesinvoice`) or an expense (`purchaseinvoice`)
 * voucher at the provider -- see `AccountingExportPlanner` KDoc for the derivation rule. A
 * deliberately narrower, dedicated two-value type rather than a reuse of the five-valued
 * [LedgerAccountType] -- see `43-accounting-export.kuml.kts` file header "Why direction is its own
 * two-value enum". Literal order load-bearing (schema drift test).
 */
@Serializable
enum class AccountingExportDirection { INCOME, EXPENSE }

/**
 * The reasons [network.lapis.cloud.shared.rpc.IAccountingExportService.previewExport] can refuse
 * (or partially refuse) an export -- collected, never short-circuited, same "show everything at
 * once" posture [DatevExportBlockerKind] already establishes for the DATEV path.
 */
@Serializable
enum class AccountingExportBlockerKind {
    /** No token has ever been stored for this provider, or it was removed. */
    NOT_CONNECTED,

    /** The zero-VAT export disclaimer has not been acknowledged for this connection yet -- see
     * `network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer`. */
    ZERO_VAT_NOT_ACKNOWLEDGED,

    /** A ledger account used by an entry in the requested period has no
     * `accounting_export_category_map` row for this provider yet. */
    UNMAPPED_ACCOUNT,

    /** A journal entry has more than one account on BOTH the debit and the credit side -- cannot
     * be expressed as a single voucher (see `AccountingExportPlanner` KDoc). */
    UNMAPPABLE_MANY_TO_MANY_ENTRY,

    /** Neither a clean INCOME/CREDIT nor a clean EXPENSE/DEBIT pattern -- direction cannot be
     * derived (e.g. a pure Umbuchung between two balance-sheet accounts). */
    UNDETERMINABLE_VOUCHER_TYPE,

    /** No POSTED journal entries fall inside `[from, to]`. */
    EMPTY_PERIOD,

    /** More entries than this wave's DoS backstop allows -- see `AccountingExportPlanner`. */
    TOO_MANY_ENTRIES,

    /** A run for this provider is already PLANNED/RUNNING -- `startExport` refuses a second
     * concurrent run rather than interleaving two exports of the same provider. */
    RUN_ALREADY_IN_PROGRESS,

    /**
     * Security review Fund 2026-09-07 (Runde 4, Befund 2): at least one journal entry inside
     * `[from, to]` has an [AccountingExportItemStatus.UNKNOWN] item from an earlier run (a `SENDING`
     * claim this server aborted or the poller reaped as stale, or a
     * [network.lapis.cloud.server.accounting.export.VoucherPushOutcome.Indeterminate] send outcome)
     * -- its true status at the provider was never confirmed, and lexoffice has no idempotency key
     * on `POST /v1/vouchers`. `retryFailed` already refuses to silently reopen an `UNKNOWN` item
     * (see `AccountingExportStore.retryFailed` KDoc), but `previewExport`/`startExport` had no
     * equivalent guard of their own -- without this blocker, re-running the SAME period through
     * "Prüfen" -> "Übertragen" would plan a brand-new `PENDING` item for the exact journal entry
     * `retryFailed` was just blocked from touching, risking a genuine duplicate voucher. The ONLY
     * way to lift this blocker for an affected entry is
     * `IAccountingExportService.resolveUnknownItem` -- a TREASURER/ADMIN manually checks lexoffice
     * and confirms one way or the other.
     */
    UNRESOLVED_UNKNOWN_ITEMS,
}

@Serializable
data class AccountingExportBlockerDto(
    val kind: AccountingExportBlockerKind,
    val detail: String,
)

/**
 * Security review Fund 2026-09-07 (Runde 4, Befund 2): how a TREASURER/ADMIN resolves an
 * [AccountingExportItemStatus.UNKNOWN] item after manually checking lexoffice for the
 * corresponding voucher -- see `IAccountingExportService.resolveUnknownItem` KDoc for the full
 * flow and [AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS] for why this is needed at all.
 */
@Serializable
enum class AccountingExportUnknownItemResolution {
    /** Confirmed lexoffice does NOT have this voucher -- moves the item to `FAILED`, exactly the
     * shape `retryFailed` already reopens back to `PENDING`, so a normal "Fehlgeschlagene erneut
     * senden" resends it. */
    CONFIRMED_NOT_SENT,

    /** Confirmed lexoffice DOES already have this voucher -- moves the item to `SUCCEEDED`, the
     * SAME idempotency-guard shape a normal successful send produces, so
     * `alreadyExportedJournalEntryIds`/`isAlreadyExported` recognize it and this journal entry is
     * never re-planned by a later `previewExport`/`startExport`. */
    CONFIRMED_SENT,
}

/** One ledger account that appears in the requested period but has no category mapping yet for
 * [AccountingExportBlockerKind.UNMAPPED_ACCOUNT] -- rendered as an inline mapping row (Rams: no
 * separate mapping screen), never the FULL account catalogue. */
@Serializable
data class UnmappedAccountDto(
    val ledgerAccountId: String,
    val accountNumber: String,
    val accountName: String,
    val accountType: LedgerAccountType,
    val entryCount: Int,
)

/** One voucher as it would be (or already was) transmitted -- the preview's line-item detail
 * (Atkinson). Welle V1.4.5.4 "sevDesk-Live-Anbindung" (§3.3 der Umsetzungsplanung): das frühere
 * `voucherType`-Feld (der lexoffice-eigene Literal `"salesinvoice"`/`"purchaseinvoice"`) wurde
 * ENTFERNT -- es war ein echtes DTO-Leck, kein Kosmetikpunkt: für sevDesk-Zeilen wäre es
 * bedeutungslos gewesen (dieser Anbieter kennt keine `voucherType`-Literale), während [direction]
 * bereits dieselbe Information anbieterneutral trägt. Ein Client-Vergleich gegen den
 * lexoffice-spezifischen String hätte für sevDesk-Zeilen immer `false` geliefert -- jede
 * Spendeneinnahme wäre in der Vorschau als "Ausgabe" angezeigt worden. */
@Serializable
data class VoucherPreviewLineDto(
    val journalEntryId: String,
    val entryDate: LocalDate,
    val voucherNumber: String,
    val direction: AccountingExportDirection,
    val categoryName: String?,
    val grossAmount: Decimal,
    val description: String,
    /** `true` if an earlier SUCCEEDED run already exported this entry -- this run would only
     * [AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED] it, never resend. */
    val alreadyExported: Boolean,
)

/** Dry-run summary for `[from, to]` against [provider] -- returned by `previewExport`, computed by
 * the EXACT same `AccountingExportPlanner.plan` call `startExport` uses to decide what to actually
 * send (see that object's own KDoc for why the two can never structurally disagree). */
@Serializable
data class AccountingExportPreviewDto(
    val provider: AccountingExportProvider,
    val from: LocalDate,
    val to: LocalDate,
    val entryCount: Int,
    val alreadyExportedCount: Int,
    val toSendCount: Int,
    val totalGross: Decimal,
    val unmappedAccounts: List<UnmappedAccountDto>,
    val blockers: List<AccountingExportBlockerDto>,
    /** First 20 (`AccountingExportPlanner.MAX_LISTED_LINES_IN_PREVIEW`) entries, ordered like the
     * eventual export -- [totalLineCount] carries the true count for an "alle anzeigen" UI. */
    val sampleLines: List<VoucherPreviewLineDto>,
    val totalLineCount: Int,
    /** `blockers.isEmpty()` -- computed server-side, same posture [DatevExportPreviewDto.exportable]. */
    val exportable: Boolean,
)

/** The current connection state for [provider] -- NEVER carries the token itself, sealed or plain,
 * only [tokenLast4] for a `••••1234` display (see `AccountingExportConnectionDto` call sites --
 * this DTO crosses the wire to every TREASURER/ADMIN client). */
@Serializable
data class AccountingExportConnectionDto(
    val provider: AccountingExportProvider,
    val connected: Boolean,
    val tokenLast4: String?,
    val connectedCompanyName: String?,
    val lastTestedAt: LocalDateTime?,
    val zeroVatAcknowledged: Boolean,
    val zeroVatAcknowledgedAt: LocalDateTime?,
)

/** One category the provider knows -- returned by `listCategories`, the catalogue
 * `mapAccount`/the inline mapping UI picks from. Mirrors the provider's own
 * `posting-categories` shape (`id`/`name`/`type`/`groupName`) closely enough that no provider-
 * specific client code is needed, but stays provider-neutral in field naming for the sevDesk
 * (V1.4.5.4) adapter to reuse without a second DTO. */
@Serializable
data class ExternalCategoryDto(
    val id: String,
    val name: String,
    val groupName: String?,
    val direction: AccountingExportDirection,
)

/** One export run -- returned by `startExport`/`getRun`/`getLatestRun`, polled by the client while
 * [status] is non-terminal (`AccountingExportPoller` writes progress every tick). */
@Serializable
data class AccountingExportRunDto(
    val id: String,
    val provider: AccountingExportProvider,
    val from: LocalDate,
    val to: LocalDate,
    val status: AccountingExportRunStatus,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val unknown: Int,
)

/** Version/text/hash triad for `network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer` -- same
 * shape [DunningComplianceDisclaimerDto]/[network.lapis.cloud.shared.domain.AuctionComplianceDisclaimerDto]
 * already establish for a "shown once, quittance echoes the hash back" legal-risk disclaimer. */
@Serializable
data class AccountingExportZeroVatDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/** One item's outcome within a run -- returned by `listRunItems`, the detail table behind the
 * run's one-line summary (Ive/Jobs: "987 uebertragen, 13 nicht -- ansehen"). */
@Serializable
data class AccountingExportItemDto(
    /** Security review Fund 2026-09-07 (Runde 4, Befund 2): the `accounting_export_item` row's own
     * id -- previously absent, only [journalEntryId] was exposed. Needed to target
     * `IAccountingExportService.resolveUnknownItem`, which acts on ONE specific item, not a whole
     * journal entry (a journal entry can have accumulated several items across several runs). */
    val id: String,
    /** Security review Fund 2026-09-07 (Runde 5, MAJOR): the OWNING run's id -- previously absent.
     * Needed by `IAccountingExportService.listUnknownItems`, which spans every run of a provider,
     * not just the one currently open on screen -- see that method's own KDoc for why an item's run
     * cannot otherwise be found once a later run has started for the same provider. */
    val runId: String,
    val journalEntryId: String,
    val entryDate: LocalDate,
    val voucherNumber: String,
    val grossAmount: Decimal,
    val status: AccountingExportItemStatus,
    val externalVoucherId: String?,
    val errorCode: String?,
    val errorMessage: String?,
)
