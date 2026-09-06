package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- one financial-history row's kind. [DONATION] sits alongside
 * the [ContributionStatus]-derived kinds because a donation is not a [ContributionStatus]
 * transition at all -- it comes from `journal_entry`/`posting`, an entirely different table family
 * (see [MemberFinancialHistoryDto] KDoc "Vier getrennte Größen"). [CONTRIBUTION_DEBIT_IN_FLIGHT]
 * (Review MEDIUM) covers `ContributionStatusSets.DEBIT_IN_FLIGHT`
 * (`DEBIT_SCHEDULED`/`DEBIT_SUBMITTED`) -- a running SEPA collection is neither paid, waived, nor
 * (per that same status-set object) genuinely outstanding, so it belongs to none of the DTO's four
 * summed amounts, but it must still appear as a ROW: without one, a member whose only contribution
 * is mid-collection sees an empty history/an "no payments yet" empty state for money that is, in
 * fact, already in flight.
 */
@Serializable
enum class FinancialHistoryEntryKind {
    CONTRIBUTION_PAID,
    CONTRIBUTION_WAIVED,
    CONTRIBUTION_OUTSTANDING,
    CONTRIBUTION_DEBIT_IN_FLIGHT,
    DONATION,
}

/**
 * One line of a member's financial history -- either a [ContributionDto] settlement/obligation or
 * a donation-bearing [network.lapis.cloud.shared.domain.DonorCategory]-tagged journal entry. See
 * [MemberFinancialHistoryDto] KDoc "Zeitachse" for how [date] is chosen per [kind].
 */
@Serializable
data class FinancialHistoryEntryDto(
    val kind: FinancialHistoryEntryKind,
    val date: LocalDate,
    val amount: Decimal,
    /** Datenabgeleitet, NICHT übersetzbar: Zeitraum bei Beiträgen, `journal_entry.description` bei Spenden. */
    val label: String,
    /** Nur bei den drei CONTRIBUTION_*-Arten gesetzt, bei [FinancialHistoryEntryKind.DONATION] immer null. */
    val contributionStatus: ContributionStatus?,
    /** `contribution.id` bzw. `journal_entry.id` -- auch der deterministische Sortier-Tiebreaker. */
    val sourceId: String,
)

/**
 * One calendar year's slice of a member's financial history, see [MemberFinancialHistoryDto.years].
 *
 * A year does not automatically appear for every year that has EVER had a booking: it is included
 * only if [entries] is non-empty OR [donationsTotal] is non-zero
 * (`entries.isNotEmpty() || donationsTotal.signum() != 0`, see
 * `MemberFinancialHistoryService.loadHistory`). Deliberately `!= 0`, not `> 0` -- a year whose
 * donation net is NEGATIVE because a later year's Storno was booked against it stays visible with
 * [entries] empty, so that negative total is never silently dropped from the timeline. A year with
 * no entries AND an exactly-zero netted total (a fully self-offsetting `journal_entry`) carries no
 * information and is omitted entirely.
 */
@Serializable
data class FinancialHistoryYearDto(
    val year: Int,
    val contributionsPaid: Decimal,
    val donationsTotal: Decimal,
    val entries: List<FinancialHistoryEntryDto>,
)

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- a read-only, per-member financial history (Beitragshistorie
 * je Mitglied). Returned by
 * [network.lapis.cloud.shared.rpc.IMemberFinancialHistoryService.getMemberFinancialHistory].
 *
 * **Vier getrennte Größen, bewusst keine Gesamtsumme**: [contributionsPaid]/[contributionsWaived]/
 * [contributionsOutstanding]/[donationsTotal] are four independent amounts. There is deliberately
 * NO `total` field and there must never be one -- a member's paid contributions, waived
 * contributions, still-owed contributions, and donations are four qualitatively different things;
 * whoever needs a sum forms it explicitly and therefore knows exactly what they added together.
 *
 * **Veranstaltungsgebühren bewusst ausgeschlossen**: `EventFeePostingBridge` writes
 * `donorMemberId = null` for every event-fee journal entry -- the ledger carries no payer identity
 * for event fees at all, so an event fee can never appear here. Estimating one would put an
 * unattributable, non-bookkept figure into what is otherwise a strict read of the general ledger.
 *
 * **`ExternalDonor` bewusst ausgeschlossen**: `external_donor` has no relationship to `member` at
 * all. `donorMemberId`/`externalDonorId` are kept mutually exclusive on `journal_entry` purely
 * ANWENDUNGSSEITIG, by `AccountingService.requireDonorMutualExclusionAndCategory` (enforced on
 * every `saveDraftEntry`/`postJournalEntry` call) and `DonationPostingBridge` -- `journal_entry`
 * itself carries no CHECK constraint for this (Review MINOR: an earlier revision of this KDoc
 * wrongly cited `chk_payment_checkout_session_donor_identity`, which was never on `journal_entry`
 * at all -- it sat on `payment_checkout_session`, one hop upstream, and was dropped in
 * `V18__events.sql` besides). A donation a member makes always carries `donorMemberId`, never
 * `externalDonorId` as long as that application-side rule holds -- so `donorMemberId eq
 * requestedId` excludes every external-donor donation from [donationsTotal] and [years], but this
 * is an invariant the application code upholds, not one the schema would catch if violated.
 *
 * **Zeitachse** -- which date places an entry into a [FinancialHistoryYearDto]:
 * - [FinancialHistoryEntryKind.CONTRIBUTION_PAID] -> `paidAt.date` (fallback `periodStart` for the
 *   pre-V1.2.1-bridge legacy case `paidAt == null`, see `MemberFinancialHistoryService.loadHistory`
 *   KDoc)
 * - [FinancialHistoryEntryKind.CONTRIBUTION_WAIVED] -> `periodStart`
 * - [FinancialHistoryEntryKind.CONTRIBUTION_OUTSTANDING] -> `dueDate`
 * - [FinancialHistoryEntryKind.CONTRIBUTION_DEBIT_IN_FLIGHT] -> `dueDate`
 * - [FinancialHistoryEntryKind.DONATION] -> `journal_entry.entry_date`
 *
 * **Anonymisierte Mitglieder**: if the member's `anonymizedAt` is set, [anonymized] is `true`,
 * [memberDisplayName] is a fixed placeholder, but every amount stays exactly as booked -- GoBD
 * aufbewahrungspflichtige Buchungen dürfen durch eine DSGVO-Anonymisierung nicht verändert werden.
 *
 * **Namensverbot**: neither "Lebenszeitwert" nor "lifetime value"/"LifetimeValue" may appear
 * anywhere in this domain (identifier, string, KDoc, doc) -- enforced by
 * `MemberFinancialHistoryNamingScanTest`. This DTO is a read of what the general ledger and the
 * contribution table already recorded, not a predictive or marketing metric.
 */
@Serializable
data class MemberFinancialHistoryDto(
    val memberId: String,
    val memberDisplayName: String,
    val anonymized: Boolean,
    val joinedAt: LocalDate,
    val friendSince: LocalDate?,
    val contributionsPaid: Decimal,
    val contributionsWaived: Decimal,
    val contributionsOutstanding: Decimal,
    val donationsTotal: Decimal,
    val years: List<FinancialHistoryYearDto>,
)
