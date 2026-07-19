package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * SKR42 chart of accounts + double-entry bookkeeping (V0.3.1, chart swapped from SKR49 in
 * V0.3.1.1) -- see `network.lapis.cloud.server.rpc.AccountingService` KDoc for the full lifecycle
 * and `lapis-server/src/main/kuml/10-accounting.kuml.kts`'s file header for the SKR42
 * Kontenklasse background and why the Gemeinnützigkeit sphere is NOT derivable from it.
 *
 * Normal-balance semantic: [ASSET]/[EXPENSE] accounts are debit-normal (a debit increases the
 * balance), [LIABILITY]/[EQUITY]/[INCOME] accounts are credit-normal (a credit increases the
 * balance) -- drives `network.lapis.cloud.server.rpc.GeneralLedgerCalculator`'s running-balance
 * sign, and, in a later wave (V0.3.2), the P&L-vs-balance-sheet split.
 */
@Serializable
enum class LedgerAccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }

/** Soll/Haben. */
@Serializable
enum class PostingSide { DEBIT, CREDIT }

/**
 * [DRAFT] entries may be incomplete/unbalanced and remain freely editable.
 * [POSTED] entries are immutable and were validated balanced (Σdebit = Σcredit) at the moment
 * they transitioned -- see `network.lapis.cloud.server.rpc.JournalEntryBalance` KDoc. Extend with
 * e.g. `REVERSED` in a later Storno wave -- additive, cheap (pre-1.0, one regenerated baseline, no
 * production data to migrate).
 */
@Serializable
enum class JournalEntryStatus { DRAFT, POSTED }

/**
 * The four strictly-separated Gemeinnützigkeit spheres (§§ 51-68 AO): [IDEELLER_BEREICH]
 * (ideeller Bereich), [VERMOEGENSVERWALTUNG] (Vermögensverwaltung), [ZWECKBETRIEB] (Zweckbetrieb),
 * [WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB] (wirtschaftlicher Geschäftsbetrieb). Assigned per posting
 * (DATEV KOST1 cost center), NOT derivable from the SKR42 account/Kontenklasse -- see the
 * `10-accounting.kuml.kts` file header for why. There is deliberately no "Sammelposten"/unassigned
 * literal (DATEV's own KOST1 = 9): every posting must name exactly one real sphere -- this is the
 * highest-legal-risk wave in the backlog (losing Gemeinnützigkeit status), so [PostingInput.sphere]
 * is non-nullable with no default, and this enum offers no escape-hatch literal to default to.
 * [kost1Code] is the DATEV KOST1 number, carried for later DATEV export -- kotlinx.serialization
 * still serializes the enum by its literal name, not this constructor property.
 */
@Serializable
enum class GemeinnuetzigkeitSphere(
    val kost1Code: Int,
) {
    IDEELLER_BEREICH(1),
    VERMOEGENSVERWALTUNG(2),
    ZWECKBETRIEB(3),
    WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB(4),
}

/**
 * The four §62 AO reserve categories a treasurer may designate an `EQUITY` [LedgerAccountDto] as
 * (V0.3.4). Reserves are deliberately modelled as ordinary equity ledger accounts (see
 * `10-accounting.kuml.kts` file header and [network.lapis.cloud.server.rpc.UseOfFundsCalculator]
 * KDoc for the full rationale) -- [ReserveType] is what makes such an account machine-classifiable
 * for the §55/§62 AO Mittelverwendungsrechnung. [PROJEKTRUECKLAGE] (§62 Abs.1 Nr.1,
 * zweckgebundene/Projekt-/Zweckerhaltungsruecklage -- earmarked for a specific future
 * tax-privileged-purpose project, no statutory percentage cap but must be usable/plausible),
 * [FREIE_RUECKLAGE] (§62 Abs.1 Nr.3 -- general reserve, capped at a statutory percentage of surplus
 * from Vermögensverwaltung and of surplus from Zweckbetrieb/wirtschaftlicher
 * Geschäftsbetrieb -- **that cap is NOT enforced here**; it is a number a human must configure and
 * verify against current law, not a constant this codebase assumes), [WIEDERBESCHAFFUNGSRUECKLAGE]
 * (§62 Abs.1 Nr.2 -- replacement reserve for depreciable assets), [BETRIEBSMITTELRUECKLAGE]
 * (legally a Nr.1 sub-case, surfaced as its own literal because it has a distinct
 * operating-funds-for-recurring-obligations purpose, e.g. payroll/rent). §62 Abs.1 Nr.4 (Rücklage
 * zum Erwerb von Gesellschaftsrechten) is deliberately not offered -- out of scope, rare for a
 * Verein/Partei. [paragraphRef] is a KDoc-level documentation hint carried as a constructor
 * property -- **verify against the current AO before relying on it**; kotlinx.serialization still
 * encodes/decodes the enum by its literal name, never by this property.
 */
@Serializable
enum class ReserveType(
    val paragraphRef: String,
) {
    PROJEKTRUECKLAGE("§62 Abs.1 Nr.1 AO"),
    FREIE_RUECKLAGE("§62 Abs.1 Nr.3 AO"),
    WIEDERBESCHAFFUNGSRUECKLAGE("§62 Abs.1 Nr.2 AO"),
    BETRIEBSMITTELRUECKLAGE("§62 Abs.1 Nr.1 AO"),
}

/**
 * V0.3.6 Kostenstellen-/Projektbuchhaltung: an open-ended, user-created cost center/project
 * classification (DATEV KOST2 sense, see `10-accounting.kuml.kts` file header and
 * `network.lapis.cloud.server.rpc.AccountingService`'s cost-center methods for the full
 * lifecycle) -- NOT a fixed enum like [GemeinnuetzigkeitSphere]/[ReserveType]: a treasurer creates
 * a new [CostCenterDto] per project/campaign/event as needed (e.g. "SOMMERFEST-2027"). [code] is a
 * free-text, unique, stable business key (cross-referencing target for a later V0.6 crowdfunding/
 * auction campaign); [name] is the human-readable display label -- mirrors [LedgerAccountDto]'s
 * own accountNumber+name split. Lifecycle is create/deactivate/list only, exactly
 * [LedgerAccountDto]'s own CRUD shape -- deactivate, never delete, so historical postings that
 * reference this cost center are never invalidated.
 */
@Serializable
data class CostCenterDto(
    val id: String,
    val code: String,
    val name: String,
    val description: String?,
    val active: Boolean,
)

/** [active] defaults to `true` for the common "create it active" call shape. */
@Serializable
data class CostCenterInput(
    val code: String,
    val name: String,
    val description: String? = null,
    val active: Boolean = true,
)

/**
 * One SKR42 Konto. [accountNumber] is the five-digit (or shorter, some system accounts are
 * shorter) SKR42 number whose leading digit is the Kontenklasse (0-9) -- see the `.kuml.kts` file
 * header for why the Gemeinnützigkeit sphere is NOT derivable from that class under SKR42.
 * [accountClass] is that leading digit, carried as its own field for reporting purposes rather
 * than re-parsed from [accountNumber] on every read. [reserveType] (V0.3.4) is non-null only for a
 * treasurer-designated §62 AO reserve account -- see [ReserveType] KDoc; it is only ever set when
 * [type] is [LedgerAccountType.EQUITY], enforced at the service layer
 * (`network.lapis.cloud.server.rpc.AccountingService`). [isCashRegister] (V0.3.5) marks this
 * account as a physical cash register (Kasse) for the Kassenbuch view -- see the `.kuml.kts` file
 * header for why `accountClass` alone cannot distinguish a Kasse from a Bank/Forderungen account
 * within the same SKR42 Kontenklasse 1; it is only ever `true` when [type] is
 * [LedgerAccountType.ASSET], enforced the same way as [reserveType]'s cross-column rule.
 */
@Serializable
data class LedgerAccountDto(
    val id: String,
    val accountNumber: String,
    val name: String,
    val accountClass: Int,
    val type: LedgerAccountType,
    val active: Boolean,
    val reserveType: ReserveType? = null,
    val isCashRegister: Boolean = false,
)

/**
 * [reserveType] defaults to `null` so existing call sites stay source-compatible -- see
 * [ReserveType]/[LedgerAccountDto] KDoc. [isCashRegister] (V0.3.5) defaults to `false` for the same
 * reason -- see [LedgerAccountDto] KDoc.
 */
@Serializable
data class LedgerAccountInput(
    val accountNumber: String,
    val name: String,
    val accountClass: Int,
    val type: LedgerAccountType,
    val active: Boolean = true,
    val reserveType: ReserveType? = null,
    val isCashRegister: Boolean = false,
)

/**
 * One Soll/Haben line of a [JournalEntryDto] -- always references an active [LedgerAccountDto].
 * [sphere] is the mandatory (never null) Gemeinnützigkeit sphere this line is booked to -- see
 * [GemeinnuetzigkeitSphere] KDoc. [costCenterId]/[costCenterCode]/[costCenterName] (V0.3.6) are
 * all `null` together unless this line was tagged with a [CostCenterDto] -- unlike [sphere], this
 * is OPTIONAL: most day-to-day postings (membership dues, routine bills) carry no project/campaign
 * association, see [CostCenterDto] KDoc.
 */
@Serializable
data class PostingDto(
    val id: String,
    val ledgerAccountId: String,
    val ledgerAccountNumber: String,
    val ledgerAccountName: String,
    val side: PostingSide,
    val amount: Decimal,
    val sphere: GemeinnuetzigkeitSphere,
    val costCenterId: String? = null,
    val costCenterCode: String? = null,
    val costCenterName: String? = null,
)

/**
 * [sphere] deliberately has NO default value -- see [GemeinnuetzigkeitSphere] KDoc for why every
 * posting must be assigned to a sphere explicitly, with no silent fallback. [costCenterId] (V0.3.6)
 * defaults to `null` -- deliberately the OPPOSITE default-value policy from [sphere]: most postings
 * have no project/campaign association, so requiring every call site to pass one would misrepresent
 * how rarely a cost center actually applies -- see [CostCenterDto] KDoc.
 */
@Serializable
data class PostingInput(
    val ledgerAccountId: String,
    val side: PostingSide,
    val amount: Decimal,
    val sphere: GemeinnuetzigkeitSphere,
    val costCenterId: String? = null,
)

/**
 * [donorMemberId]/[donorMemberDisplayName] (V0.4.1 Spendenbescheinigung) are non-null only when
 * this entry has been attributed to a donating member -- see `10-accounting.kuml.kts` file header
 * (`donor_member_id`) and `network.lapis.cloud.server.rpc.AccountingService`'s
 * `requireDonationIncomePosting` for the validation (must reference at least one posting against
 * an `INCOME` ledger account). `createdBy` remains the treasurer who booked the entry, never the
 * donor -- these are deliberately two distinct fields.
 */
@Serializable
data class JournalEntryDto(
    val id: String,
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String?,
    val createdBy: String,
    val createdByDisplayName: String,
    val status: JournalEntryStatus,
    val postedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val postings: List<PostingDto>,
    val donorMemberId: String? = null,
    val donorMemberDisplayName: String? = null,
)

/**
 * [postings] must contain at least two lines with at least one [PostingSide.DEBIT] and one
 * [PostingSide.CREDIT] line, and Σdebit must equal Σcredit for `postJournalEntry`/
 * `postDraftEntry` to succeed -- see `network.lapis.cloud.server.rpc.JournalEntryBalance` KDoc.
 * `saveDraftEntry` accepts an incomplete/unbalanced set of [postings] (that is the point of a
 * draft). [donorMemberId] (V0.4.1) defaults to `null` -- see [JournalEntryDto] KDoc; when set, the
 * entry must reference an existing member (`NotFoundException` otherwise) and contain at least
 * one posting against an `INCOME` ledger account (`BadRequestException` otherwise).
 */
@Serializable
data class JournalEntryInput(
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String? = null,
    val postings: List<PostingInput> = emptyList(),
    val donorMemberId: String? = null,
)

/** One row of the Hauptbuch (general ledger) for a single [GeneralLedgerDto.ledgerAccountId]. */
@Serializable
data class GeneralLedgerLineDto(
    val journalEntryId: String,
    val entryDate: LocalDate,
    val description: String,
    val side: PostingSide,
    val amount: Decimal,
    val runningBalance: Decimal,
)

/**
 * The Hauptbuch (general ledger) for one [LedgerAccountDto], chronologically ordered.
 * [openingBalance] is always `0` in this wave (no opening-balance/carryforward support yet --
 * see the `.kuml.kts` file header "Explicit non-goals"); [closingBalance] equals the last
 * [GeneralLedgerLineDto.runningBalance], or [openingBalance] if [lines] is empty.
 */
@Serializable
data class GeneralLedgerDto(
    val ledgerAccountId: String,
    val accountNumber: String,
    val name: String,
    val type: LedgerAccountType,
    val openingBalance: Decimal,
    val closingBalance: Decimal,
    val lines: List<GeneralLedgerLineDto>,
)

/**
 * One Kassenbuch (cash book, V0.3.5) line -- a specialized Einnahme/Ausgabe (not Soll/Haben) view
 * over one [PostingDto] against a [LedgerAccountDto.isCashRegister] account. Exactly one of
 * [amountIn] (Einnahme)/[amountOut] (Ausgabe) is non-zero, the other is always `0` -- see
 * `network.lapis.cloud.server.rpc.KassenbuchCalculator` KDoc. [kassenbuchNumber] is a 1-based,
 * gapless sequential line number derived purely from chronological ordering of the account's
 * existing immutable `POSTED` postings -- no new persisted sequence, safe because a `POSTED` entry
 * can never be reordered or renumbered. [voucherReference] is carried through unchanged (including
 * `null` for a pre-V0.3.5 posting that predates the "kein Buchen ohne Beleg" guard). The mandatory
 * [PostingDto.sphere] is deliberately NOT surfaced here -- the Kassenbuch is a cash-audit view, not
 * a Gemeinnützigkeit-sphere report.
 */
@Serializable
data class KassenbuchLineDto(
    val kassenbuchNumber: Int,
    val journalEntryId: String,
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String?,
    val amountIn: Decimal,
    val amountOut: Decimal,
    val runningBalance: Decimal,
)

/**
 * The Kassenbuch (cash book, V0.3.5) for one [LedgerAccountDto.isCashRegister] account,
 * chronologically ordered -- see [KassenbuchLineDto] KDoc. [openingBalance] is the cumulative
 * running balance of the last full-history posting strictly before `from` (or `0` if `from` is
 * null/inception, or no such posting exists); [closingBalance] equals the last returned
 * [KassenbuchLineDto.runningBalance] within `[from, to]`, or [openingBalance] if [lines] is empty.
 * `kassenbuchNumber`/running balances are always computed over the account's FULL posted history
 * first, then narrowed to `[from, to]` -- never numbered/balanced over a filtered subset -- so a
 * posting's `kassenbuchNumber` never changes depending on the query window. This is a
 * GoBD-informed view (foundational for V0.5's full GoBD bundle) -- cryptographic tamper-evidence,
 * retention enforcement, and TSE integration are explicitly out of scope this wave, see
 * `10-accounting.kuml.kts` file header.
 */
@Serializable
data class KassenbuchDto(
    val ledgerAccountId: String,
    val accountNumber: String,
    val name: String,
    val openingBalance: Decimal,
    val closingBalance: Decimal,
    val lines: List<KassenbuchLineDto>,
)
