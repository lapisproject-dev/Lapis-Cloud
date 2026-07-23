package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AnnualFinancialStatementDto
import network.lapis.cloud.shared.domain.BalanceSheetDto
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.CostCenterInput
import network.lapis.cloud.shared.domain.CostCenterReportDto
import network.lapis.cloud.shared.domain.DonationDutyReportDto
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.ExternalDonorInput
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.GeneralLedgerDto
import network.lapis.cloud.shared.domain.IncomeStatementDto
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.KassenbuchDto
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.UseOfFundsStatementDto

/**
 * SKR42 chart of accounts + double-entry bookkeeping (V0.3.1, chart swapped from SKR49 in
 * V0.3.1.1) -- see `network.lapis.cloud.server.rpc.AccountingService` for the implementation and
 * `lapis-server/src/main/kuml/10-accounting.kuml.kts` for the schema-shape rationale.
 *
 * Bookkeeping is treasury-only, not member-public (unlike e.g. [IContributionService]'s own-data
 * carve-out) -- every method requires TREASURER/ADMIN. A [JournalEntryDto] may be saved as a
 * [JournalEntryStatus.DRAFT] (incomplete/unbalanced postings allowed, freely editable) or posted
 * directly/transitioned to [JournalEntryStatus.POSTED] (validated balanced -- Σdebit = Σcredit --
 * at that moment, then immutable).
 */
@RpcService
interface IAccountingService {
    /** Role: TREASURER/ADMIN. Rejects a duplicate [LedgerAccountInput.accountNumber]. */
    suspend fun createLedgerAccount(input: LedgerAccountInput): LedgerAccountDto

    /** Role: TREASURER/ADMIN. Deactivates (never deletes) a [LedgerAccountDto]. */
    suspend fun deactivateLedgerAccount(id: String): LedgerAccountDto

    /** Role: TREASURER/ADMIN. */
    suspend fun listLedgerAccounts(activeOnly: Boolean = true): List<LedgerAccountDto>

    /**
     * Role: TREASURER/ADMIN. Saves [input] as [JournalEntryStatus.DRAFT] -- [input].postings may
     * be empty or unbalanced (that is the point of a draft). Use [postDraftEntry] to transition it
     * to [JournalEntryStatus.POSTED] once it balances. The donor-identity/§25 PartG validation
     * ([input].donorMemberId/externalDonorId/donorCategory mutual exclusion, see [JournalEntryInput]
     * KDoc) still applies at save time -- only the §25 PartG PROHIBITED-verdict *enforcement* itself
     * is deferred to [postJournalEntry]/[postDraftEntry] (same "draft is provisional" rule as every
     * other POST-time-only guard, e.g. `requireActiveCostCenters`).
     */
    suspend fun saveDraftEntry(input: JournalEntryInput): JournalEntryDto

    /**
     * Role: TREASURER/ADMIN. Validates [input].postings balanced (at least two lines, at least one
     * debit and one credit, Σdebit = Σcredit) and writes the entry directly as
     * [JournalEntryStatus.POSTED] in one atomic transaction -- an unbalanced attempt is rejected
     * and nothing is persisted. When [network.lapis.cloud.shared.domain.OrganizationSettingsDto
     * .isPoliticalParty] is `true` and this entry carries a donor (member, external, or explicit
     * anonymous), the §25 PartG compliance check also runs: a `PROHIBITED` verdict rejects the
     * whole entry with [ConflictException] (nothing persisted); an `ALLOWED` verdict with
     * additional duties still succeeds -- duties are never a posting blocker, see
     * `network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator` KDoc and
     * [getDonationDutyReport]. A complete no-op for a plain gemeinnuetziger Verein
     * (`isPoliticalParty == false`).
     */
    suspend fun postJournalEntry(input: JournalEntryInput): JournalEntryDto

    /**
     * Role: TREASURER/ADMIN. Transitions an existing [JournalEntryStatus.DRAFT] entry to
     * [JournalEntryStatus.POSTED] once its current postings balance; rejected (and the entry left
     * unchanged) if they do not. Same §25 PartG compliance gate as [postJournalEntry] applies here
     * too, at this POST transition -- see that method's KDoc.
     */
    suspend fun postDraftEntry(id: String): JournalEntryDto

    /** Role: TREASURER/ADMIN. */
    suspend fun getJournalEntry(id: String): JournalEntryDto

    /**
     * Role: TREASURER/ADMIN. Grundbuch (journal), chronologically ordered by [entryDate].
     * [donorMemberId] (V0.4.1, default `null`) narrows the result to entries attributed to that
     * donor -- see [network.lapis.cloud.shared.domain.JournalEntryDto.donorMemberId] KDoc. A
     * natural, low-cost extension of the existing filter set for treasury auditing; not required
     * by the Spendenbescheinigung PDF route itself, which loads one entry directly by id.
     */
    suspend fun listJournal(
        from: LocalDate? = null,
        to: LocalDate? = null,
        status: JournalEntryStatus? = null,
        donorMemberId: String? = null,
    ): List<JournalEntryDto>

    /**
     * Role: TREASURER/ADMIN. Hauptbuch (general ledger) for one [LedgerAccountDto], with a running
     * balance whose sign follows that account's [network.lapis.cloud.shared.domain
     * .LedgerAccountType] normal-balance side. Only [JournalEntryStatus.POSTED] postings
     * contribute -- a [JournalEntryStatus.DRAFT] entry's postings are provisional and must not
     * move the ledger.
     */
    suspend fun getGeneralLedgerAccount(
        ledgerAccountId: String,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): GeneralLedgerDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Gewinn- und Verlustrechnung (GuV / income statement): the flow
     * of `INCOME`/`EXPENSE` postings over `[from, to]` (both inclusive; `from == null` means "since
     * inception"). Only [JournalEntryStatus.POSTED] postings contribute -- same
     * "DRAFT is provisional" rule as [getGeneralLedgerAccount].
     */
    suspend fun getIncomeStatement(
        from: LocalDate? = null,
        to: LocalDate,
    ): IncomeStatementDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Bilanz (balance sheet) as of [asOf], computed as the
     * *cumulative* stock of [JournalEntryStatus.POSTED] postings since inception through [asOf] --
     * deliberately not windowed to a fiscal year, see [BalanceSheetDto] KDoc for why.
     */
    suspend fun getBalanceSheet(asOf: LocalDate): BalanceSheetDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Jahresabschluss (annual financial statement) for [fiscalYear],
     * assuming Geschäftsjahr = Kalenderjahr (calendar year) -- bundles [getIncomeStatement] over
     * that year with [getBalanceSheet] as of the year's end. See [AnnualFinancialStatementDto] KDoc
     * for why [AnnualFinancialStatementDto.periodResult] and
     * [AnnualFinancialStatementDto.accumulatedResult] are reported as two distinct figures.
     */
    suspend fun getAnnualFinancialStatement(fiscalYear: Int): AnnualFinancialStatementDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Vier-Sphären-Ergebnisrechnung (V0.3.3): the same
     * `INCOME`/`EXPENSE` flow over `[from, to]` as [getIncomeStatement] (`from == null` means
     * "since inception"), re-aggregated by [network.lapis.cloud.shared.domain
     * .GemeinnuetzigkeitSphere] instead of collapsed across all four -- see
     * [FourSphereIncomeStatementDto] KDoc. Always returns all four spheres (zero-filled if a
     * sphere had no activity). Only [JournalEntryStatus.POSTED] postings contribute -- same
     * "DRAFT is provisional" rule as [getIncomeStatement].
     */
    suspend fun getFourSphereIncomeStatement(
        from: LocalDate? = null,
        to: LocalDate,
    ): FourSphereIncomeStatementDto

    /**
     * Role: TREASURER/BOARD/ADMIN. §55 AO Mittelverwendungsrechnung (use-of-funds/timely-use
     * tracking) + §62 AO Rücklagen (reserve formation) over `[fromFiscalYear, toFiscalYear]`
     * (calendar years, both inclusive). Only [JournalEntryStatus.POSTED] postings contribute --
     * same "DRAFT is provisional" rule as every other statement method. The §55 AO timely-use
     * clock is anchored at inception (rolled forward from the earliest fiscal year with activity),
     * never windowed to [fromFiscalYear] -- see [UseOfFundsStatementDto] KDoc for why. This is a
     * treasurer's Nachweis aid, not an automated compliance verdict -- see
     * `network.lapis.cloud.shared.domain.UseOfFunds` file KDoc for what is deliberately NOT
     * enforced (freie-Rücklage percentage cap, small-org exemption, §64 AO Freigrenze).
     */
    suspend fun getUseOfFundsStatement(
        fromFiscalYear: Int,
        toFiscalYear: Int,
    ): UseOfFundsStatementDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Kassenbuch (cash book, V0.3.5, GoBD-Vorstufe) for one
     * [network.lapis.cloud.shared.domain.LedgerAccountDto.isCashRegister] account over
     * `[from, to]` (both inclusive; `from == null` means "since inception") -- see [KassenbuchDto]
     * KDoc. Only [JournalEntryStatus.POSTED] postings contribute -- same "DRAFT is provisional"
     * rule as [getGeneralLedgerAccount]. Rejects with [ConflictException] if [ledgerAccountId]
     * names an existing account that is not [network.lapis.cloud.shared.domain.LedgerAccountDto
     * .isCashRegister] (wrong kind of resource for this endpoint -- requires a DB lookup of the
     * account's persisted state to evaluate, same tier as [getGeneralLedgerAccount]'s other
     * existing-entity-wrong-state checks), and [NotFoundException] if it does not exist at all --
     * mirrors [getGeneralLedgerAccount]'s existing not-found behavior.
     */
    suspend fun getKassenbuch(
        ledgerAccountId: String,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): KassenbuchDto

    /**
     * Role: TREASURER/ADMIN. Kostenstellen-/Projektbuchhaltung (V0.3.6): creates a new, open-ended
     * [CostCenterDto] -- see that DTO's KDoc. Rejects a duplicate [CostCenterInput.code] and a
     * blank [CostCenterInput.code] with [BadRequestException].
     */
    suspend fun createCostCenter(input: CostCenterInput): CostCenterDto

    /** Role: TREASURER/ADMIN. Deactivates (never deletes) a [CostCenterDto]. */
    suspend fun deactivateCostCenter(id: String): CostCenterDto

    /** Role: TREASURER/BOARD/ADMIN. */
    suspend fun listCostCenters(activeOnly: Boolean = true): List<CostCenterDto>

    /**
     * Role: TREASURER/BOARD/ADMIN. Kostenstellen-/Projektbuchhaltung report (V0.3.6): the same
     * `INCOME`/`EXPENSE` flow over `[from, to]` as [getIncomeStatement] (`from == null` means
     * "since inception"), re-aggregated by [CostCenterDto] instead of collapsed across all
     * accounts -- see [CostCenterReportDto] KDoc. Only [JournalEntryStatus.POSTED] postings
     * contribute -- same "DRAFT is provisional" rule as every other statement method.
     */
    suspend fun getCostCenterReport(
        from: LocalDate? = null,
        to: LocalDate,
    ): CostCenterReportDto

    /**
     * Role: TREASURER/ADMIN. V0.5.1 §25 PartG donor identity for a non-member donor -- see
     * [ExternalDonorDto] KDoc. Rejects a blank [ExternalDonorInput.displayName] with
     * [BadRequestException]. NOT gated on [network.lapis.cloud.shared.domain.OrganizationSettingsDto
     * .isPoliticalParty] -- useful to a plain gemeinnuetziger Verein too (attributing a non-member
     * donation for a §10b EStG receipt); only the compliance *check* itself is party-gated.
     */
    suspend fun createExternalDonor(input: ExternalDonorInput): ExternalDonorDto

    /** Role: TREASURER/ADMIN. Deactivates (never deletes) an [ExternalDonorDto]. */
    suspend fun deactivateExternalDonor(id: String): ExternalDonorDto

    /** Role: TREASURER/BOARD/ADMIN. */
    suspend fun listExternalDonors(activeOnly: Boolean = true): List<ExternalDonorDto>

    /** Role: TREASURER/BOARD/ADMIN. */
    suspend fun getExternalDonor(id: String): ExternalDonorDto

    /**
     * Role: TREASURER/BOARD/ADMIN. §25 PartG follow-up-duty report (V0.5.1) for [calendarYear] --
     * see [network.lapis.cloud.shared.domain.DonationDutyReportDto] KDoc. Returns
     * `partyRulesApply = false` with empty lists when
     * [network.lapis.cloud.shared.domain.OrganizationSettingsDto.isPoliticalParty] is `false` -- a
     * complete no-op for a plain gemeinnuetziger Verein. Only [JournalEntryStatus.POSTED] donations
     * contribute (same "DRAFT is provisional" rule as every other statement method); a `PROHIBITED`
     * donation can never appear here -- it was hard-blocked at post time, see [postJournalEntry]
     * KDoc.
     */
    suspend fun getDonationDutyReport(calendarYear: Int): DonationDutyReportDto
}
