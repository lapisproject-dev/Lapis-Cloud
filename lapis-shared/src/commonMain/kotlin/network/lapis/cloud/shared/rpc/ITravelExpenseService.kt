package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.RefundAccountPrefillDto
import network.lapis.cloud.shared.domain.TravelExpenseDayInput
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseSepaBatchDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaContextDto
import network.lapis.cloud.shared.domain.TravelExpenseSettingsInput

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger". See
 * `network.lapis.cloud.server.rpc.TravelExpenseService` KDoc for the full state machine and
 * `docs/architecture/travel-expenses.adoc` for the fachlich rationale.
 */
@RpcService
interface ITravelExpenseService {
    /** Every authenticated member, read-only. The reason the two rate columns are NOT on
     *  `OrganizationSettingsDto` -- see that type's own KDoc. */
    suspend fun getTravelExpenseRates(): TravelExpenseRatesDto

    /** Role: ADMIN. Writes an ORGANIZATION_SETTINGS audit entry (same entity-type assignment as
     *  `OrganizationSettingsService.updateOrganizationSettings`). */
    suspend fun updateTravelExpenseRates(
        mileageRatePerKm: Decimal?,
        perDiemRate: Decimal?,
    ): TravelExpenseRatesDto

    /**
     * Self-service. MEMBER only for themselves (IDOR gate); BOARD/ADMIN also on behalf of another
     * member -- whoever submits this way may still NEVER approve the resulting report themselves
     * (four-eyes across BOTH paths, see [decideReport]). Cap:
     * `TravelExpenseAmountRules.MAX_OPEN_DRAFTS_PER_MEMBER` open drafts per subject ->
     * [ConflictException].
     */
    suspend fun createDraft(
        subjectMemberId: String,
        input: TravelExpenseReportInput,
    ): TravelExpenseReportDto

    /** DRAFT only, subject or requester. No BOARD/ADMIN bypass. */
    suspend fun updateDraft(
        reportId: String,
        input: TravelExpenseReportInput,
    ): TravelExpenseReportDto

    /**
     * DRAFT only. The amount is computed SERVER-SIDE (`TravelExpenseAmountRules`); a caller-
     * supplied `amount` on a MILEAGE/PER_DIEM line is a `BadRequestException`, never silently
     * discarded. Sets `rate_snapshot` provisionally to the CURRENT rate (so the live total is
     * displayable); [submitReport] freezes it authoritatively. Returns the WHOLE report so the
     * client never has to sum the total itself.
     */
    suspend fun addLine(
        reportId: String,
        input: TravelExpenseLineInput,
    ): TravelExpenseReportDto

    /** DRAFT only. Deletes the line, its `travel_expense_receipt` rows AND their files. */
    suspend fun removeLine(lineId: String): TravelExpenseReportDto

    /**
     * DRAFT -> REQUESTED. Validates: at least 1 line; every RECEIPTED line has at least 1
     * receipt; a rate is configured for every line kind actually used; the travel period is
     * plausible (`travelTo` <= today, `travelFrom` not more than
     * `TravelExpenseAmountRules.MAX_TRAVEL_BACKDATE_DAYS` days in the past); every PER_DIEM
     * line's `days` <= the travel span in days; every line's amount > 0. Freezes `rate_snapshot`
     * for every MILEAGE/PER_DIEM line to the THEN-current rate, recomputes every amount and
     * `total_amount`. No path reads `OrganizationSettings` again after this.
     */
    suspend fun submitReport(reportId: String): TravelExpenseReportDto

    /**
     * Every authenticated caller, exclusively their own reports (subject OR requester), including
     * DRAFT. Capped at the same page size `listReports` uses (200), newest-created first --
     * self-service, no cursor param (unlike `listReports`'s board queue): a long-tenured member's
     * older EXECUTED/REJECTED/WITHDRAWN history is simply not shown past the cap rather than
     * growing this response unboundedly (review MINOR fix).
     */
    suspend fun listMyReports(): List<TravelExpenseReportDto>

    /**
     * Subject OR requester, NO BOARD/ADMIN bypass (same reasoning as
     * `withdrawReliefRequest`: a board-triggered withdrawal would disguise a board decision as "the
     * member changed their mind"). DRAFT/REQUESTED -> WITHDRAWN only.
     */
    suspend fun withdrawReport(reportId: String): TravelExpenseReportDto

    /**
     * Role: BOARD/ADMIN -- NOT TREASURER (a discretionary judgment call about the legitimacy of a
     * trip/purpose/amount, not a mechanical accounting fact; mirrors
     * `IContributionReliefService.listReliefRequests`).
     *
     * **DRAFT is NEVER visible here** -- the query hard-filters `submitted_at IS NOT NULL`, and
     * `status = DRAFT` as an argument throws [BadRequestException] instead of silently returning
     * an empty page. A draft is private, and this filter also makes `submitted_at` non-null for
     * every deliverable row, keeping the keyset cursor total.
     *
     * Page size `MAX_LIST_RESULTS = 200`, `submitted_at` ASC + `id` as tiebreaker (oldest first --
     * a decision queue, never an activity feed). Real keyset pagination from the start
     * ([afterSubmittedAt]/[afterId], both together; only one set is treated as "no cursor", never
     * an error).
     */
    suspend fun listReports(
        status: TravelExpenseReportStatus? = null,
        afterSubmittedAt: LocalDateTime? = null,
        afterId: String? = null,
    ): List<TravelExpenseReportDto>

    /**
     * Role: BOARD/ADMIN. [note] is REQUIRED both for `approve=true` and `approve=false`
     * (mandatory justification -- a rejection with no reason plus a copyable draft is the
     * alternative to line-by-line partial approval). `approve=true` only from REQUESTED;
     * `approve=false` from [network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
     * .REJECTABLE] (also APPROVED -- the only way out of a permanently unbookable report, "no
     * dead end"). Decision AND booking in the SAME transaction, `SELECT ... FOR UPDATE` on the
     * report row. If the booking fails, the report stays APPROVED with `executionError`.
     *
     * **Four-eyes, without exception**: `decidedBy` in `{subjectMemberId, requestedBy}` ->
     * [ForbiddenException], in BOTH cases. The second branch is new compared to
     * `decideReliefRequest` (which checks only `subjectMemberId`) and closes exactly the "on
     * behalf of" gap: whoever submitted for someone else may not approve it themselves either.
     */
    suspend fun decideReport(
        reportId: String,
        approve: Boolean,
        note: String? = null,
    ): TravelExpenseReportDto

    /**
     * Role: BOARD/ADMIN. Only from APPROVED with `executionError` set. Identical booking path to
     * [decideReport]. **Idempotent**: under the report lock, `posted_journal_entry_id IS NULL` is
     * re-checked; an already-posted report is EXECUTED and already fails the status check --
     * `uq_ter_posted_journal_entry` additionally guarantees DB-side that two bookings can never
     * hang off one report.
     */
    suspend fun retryPosting(reportId: String): TravelExpenseReportDto

    // ── V1.9.122 "Reisekostenabrechnung nach PdV-Formular" -- appended, existing signatures untouched ──

    /**
     * Role: ADMIN. Writes every V1.9.122 setting (tiers, meal percentages, mileage/passenger rate,
     * contact block, optional account mappings) in one call; the older [updateTravelExpenseRates]
     * stays and writes only the two legacy rates. Writes an ORGANIZATION_SETTINGS audit entry that
     * carries numbers and booleans only -- never the contact's name/e-mail/phone.
     */
    suspend fun updateTravelExpenseSettings(input: TravelExpenseSettingsInput): TravelExpenseRatesDto

    /**
     * V1.9.199. Role: ADMIN (same gate as [updateTravelExpenseSettings]). Renders a Markdown contact hint to sanitized HTML for the live
     * preview (at most `TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH` characters, rate-limited per member). Writes nothing.
     */
    suspend fun previewTravelContactHint(markdown: String): String

    /**
     * DRAFT only, subject or requester. Replaces the per-day entries (times, meals, destination)
     * of the travel period. Every amount is recomputed server-side by the shared
     * `TravelExpenseCalculator`; the client never supplies a cent value for a day.
     */
    suspend fun saveDays(
        reportId: String,
        days: List<TravelExpenseDayInput>,
    ): TravelExpenseReportDto

    /** DRAFT only. Edits one NON-day line (kilometres, passengers, amount, nights, ...). */
    suspend fun updateLine(
        lineId: String,
        input: TravelExpenseLineInput,
    ): TravelExpenseReportDto

    // ── V1.9.123 "SEPA-Ueberweisungsdatei" -- appended, existing signatures untouched. The export itself is NOT an RPC: it is an
    //    HTTP POST (`TravelExpenseSepaRoutes`) so the file can carry `Cache-Control: no-store` and `Content-Disposition`. ──

    /**
     * Sets the account a refund is transferred to. The IBAN is validated (country, length, mod 97) and stored SecretBox-encrypted, bound
     * to this report via AAD. **The subject only** -- never the requester or the board. After submission the change is flagged for the
     * approver; after a SEPA export it is still allowed (bank return AC01/AC04/MD07 ...), flagged and audited again, and only a reasoned
     * re-export uses it. `iban == null` with a holder is a holder-only update; `iban == null` without a holder clears (DRAFT only).
     */
    suspend fun setRefundAccount(
        reportId: String,
        iban: String?,
        accountHolder: String?,
    ): TravelExpenseReportDto

    /** Subject only: the IBAN and holder of the subject's most recent OTHER report that has one, in plain text. Never for a requester. */
    suspend fun getRefundAccountPrefill(reportId: String): RefundAccountPrefillDto

    /** Role: BOARD/ADMIN. EXECUTED reports with `PAYOUT`, oldest first, capped at 500, each with its `sepaBlocker`. */
    suspend fun listSepaExportCandidates(): List<TravelExpenseReportDto>

    /** Role: BOARD/ADMIN. Metadata of generated files, newest first, `limit` clamped to 1..200. There is no download. */
    suspend fun listSepaBatches(limit: Int = 50): List<TravelExpenseSepaBatchDto>

    /** Role: BOARD/ADMIN. Debtor account choices, the default execution date and what is still missing in the setup. */
    suspend fun getSepaExportContext(): TravelExpenseSepaContextDto
}
