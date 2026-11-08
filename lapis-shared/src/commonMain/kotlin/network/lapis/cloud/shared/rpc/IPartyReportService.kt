package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.PartgAccountMappingViewDto
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgDonorProposalDto
import network.lapis.cloud.shared.domain.PartgDonorProposalPickDto
import network.lapis.cloud.shared.domain.PartgEnterpriseInputDto
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgIntraTransferInputDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgLedgerProposalDto
import network.lapis.cloud.shared.domain.PartgMappingChangeDto
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesDto
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgProposalPickDto
import network.lapis.cloud.shared.domain.PartgSaveResultDto
import network.lapis.cloud.shared.domain.PartgSheetDto
import network.lapis.cloud.shared.domain.PartgSponsorInputDto
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitStatementDto
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PartgVersionDeletionPreviewDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto
import network.lapis.cloud.shared.domain.PartgYouthOrgInputDto

/**
 * V1.9.225 "Rechenschaftsbericht (PartG), R1" -- see `docs/features/partg-report.adoc`. New methods go to the END (Kilua numbers routes by
 * declaration order). Since V1.9.225 this is the LAST `registerService` in `Application.kt` (after `IDashboardService`).
 *
 * EVERY method first requires the role BOARD or ADMIN ([ForbiddenException]) and only then that the organisation is a political party
 * ([PartyReportNotEnabledException]); the order is fixed so that the gate itself reveals nothing to anyone
 * below the board (the fact is not secret otherwise: `SessionInfoDto.politicalParty` is sent to every logged-in member).
 * R1 stores typed-in or imported values per position, year and unit; R2 (V1.9.256) creates a draft as PDF/XLSX over HTTP routes, not over this interface.
 */
@RpcService
interface IPartyReportService {
    suspend fun listPositions(): List<PartgPositionDto>

    /** Sets or clears (`null` or blank) the organisation's own hint of a position. Audited. */
    suspend fun updatePositionHint(
        code: String,
        hint: String?,
    ): PartgPositionDto

    suspend fun listUnits(): List<PartgUnitDto>

    suspend fun overview(year: Int): List<PartgOverviewRowDto>

    suspend fun getSheet(
        year: Int,
        unitKey: String,
    ): PartgSheetDto

    /**
     * Writes the changes atomically. [expectedRevision] is the revision of the sheet of [year], [expectedPriorRevision] the one of the year
     * before; a mismatch is [PartyReportRevisionConflictException].
     */
    suspend fun saveSheet(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        expectedPriorRevision: Long,
        changes: List<PartgValueChangeDto>,
    ): PartgSaveResultDto

    /** V1.9.256: completeness of all units for [year] and the year before. The same query feeds the overview (server side) and the checklist. */
    suspend fun reportCompleteness(year: Int): PartgCompletenessDto

    /**
     * V1.9.269 (R3): the separate statements of one unit and year -- deductions (A), member count (E) and the large-donor list (B). Read-only;
     * the member count suggestion is only set for the current year and a federal or state unit.
     */
    suspend fun getAnnex(
        year: Int,
        unitKey: String,
    ): PartgAnnexDto

    /** Saves the four deduction fields and the member count (`null` = not entered). A stale [expectedRevision] is [PartyReportRevisionConflictException]. */
    suspend fun saveAnnexFigures(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        deductions: Map<PartgDeduction, Long?>,
        memberCount: Int?,
    ): PartgAnnexDto

    /** Adds ([PartgLargeDonorInputDto.id] `null`) or changes a large-donor row. At most 500 rows per unit and year; only amounts above 10,000.00 EUR. */
    suspend fun saveLargeDonor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        donor: PartgLargeDonorInputDto,
    ): PartgAnnexDto

    suspend fun deleteLargeDonor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        donorId: String,
    ): PartgAnnexDto

    /** The notes of the organisation for [year]: always five blocks, plus the holdings. */
    suspend fun getNotes(year: Int): PartgNotesDto

    suspend fun saveNotes(
        year: Int,
        expectedRevision: Long,
        notes: List<PartgNoteDto>,
        holdings: List<PartgHoldingDto>,
    ): PartgNotesDto

    /** The plausibility hints P01..P06 over all units. Never carries a name, an address or an amount. */
    suspend fun reportFindings(year: Int): List<PartgFindingDto>

    /**
     * V1.9.283 (R3b): adds ([PartgSponsorInputDto.id] `null`) or replaces a sponsor of statement C together with the FULL list of its entries.
     * ALL sponsors are stored (the yearly sum needs them); whether a sponsor is part of the report is computed. At most 500 sponsors, 100 entries per
     * sponsor and 2000 entries per unit and year.
     */
    suspend fun saveSponsor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        sponsor: PartgSponsorInputDto,
    ): PartgAnnexDto

    suspend fun deleteSponsor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        sponsorId: String,
    ): PartgAnnexDto

    /** V1.9.283 (R3b): adds ([PartgYouthOrgInputDto.id] `null`) or changes a youth organisation of statement F. At most 20 per unit and year. */
    suspend fun saveYouthOrg(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        youthOrg: PartgYouthOrgInputDto,
    ): PartgAnnexDto

    suspend fun deleteYouthOrg(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        youthOrgId: String,
    ): PartgAnnexDto

    /** V1.9.287 (R3c): adds ([PartgEnterpriseInputDto.id] `null`) or changes an enterprise of statement D. At most 50 per unit and year. */
    suspend fun saveEnterprise(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        enterprise: PartgEnterpriseInputDto,
    ): PartgAnnexDto

    suspend fun deleteEnterprise(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        enterpriseId: String,
    ): PartgAnnexDto

    // ---------------------------------------------------------------- R4a (V1.9.289)

    /**
     * V1.9.289 (R4a): the ledger accounts with their mapping to the report positions, the template suggestions (only for unmapped accounts) and the
     * balances of [year]. Federal roles and a chapter treasurer (read only, for the units of the chapters granted).
     */
    suspend fun getAccountMappings(
        year: Int,
        unitKey: String,
    ): PartgAccountMappingViewDto

    /**
     * V1.9.289 (R4a): saves the mapping changes atomically (federal roles only, at most 2000 per call) and returns the view of [year] and [unitKey]
     * afterwards. Every change names the expected old state; a mismatch is [PartyReportRevisionConflictException]. A target must be a LEAF position
     * of the matching section; an equity account can only be "not in the report".
     */
    suspend fun saveAccountMappings(
        year: Int,
        unitKey: String,
        changes: List<PartgMappingChangeDto>,
    ): PartgAccountMappingViewDto

    /**
     * V1.9.289 (R4a): sets the bookkeeping mode of a federal or state unit for [year] (federal roles only; a SUB unit is always C). When the unit
     * already has POSTED bookings in the year, [confirmedBookingCount] must equal their current number, else
     * [PartyReportModeConfirmationRequiredException]. The bookings are never changed.
     */
    suspend fun setUnitMode(
        year: Int,
        unitKey: String,
        mode: PartgBookkeepingMode,
        confirmedBookingCount: Int,
    ): PartgOverviewRowDto

    /** V1.9.289 (R4a): the positions where the bookings differ from the entered values. [PartyReportProposalNotAvailableException] in mode C. */
    suspend fun proposeFromLedger(
        year: Int,
        unitKey: String,
    ): PartgLedgerProposalDto

    /**
     * V1.9.289 (R4a): takes over the chosen positions with source `LEDGER`. The server recomputes every amount UNDER THE LOCK; a pick whose amount
     * differs, is negative or does not exist is [PartyReportProposalStaleException].
     */
    suspend fun applyLedgerProposal(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        expectedPriorRevision: Long,
        picks: List<PartgProposalPickDto>,
    ): PartgSaveResultDto

    // ---------------------------------------------------------------- R5 (V1.9.290)

    /**
     * V1.9.290 (R5): the whole party consolidated over its units (federal roles only): completeness list, 42 positions with the eliminated
     * transfers, the matching table, the computed statement B across all units and the hints P17..P21. A DRAFT, no official report.
     */
    suspend fun getConsolidation(year: Int): PartgConsolidationDto

    /** V1.9.290 (R5): adds ([PartgIntraTransferInputDto.id] `null`) or changes one pair; one pair per (year, kind, payer, payee). At most 1000 per year. */
    suspend fun saveIntraTransfer(
        year: Int,
        expectedRevision: Long,
        transfer: PartgIntraTransferInputDto,
    ): PartgConsolidationDto

    suspend fun deleteIntraTransfer(
        year: Int,
        expectedRevision: Long,
        transferId: String,
    ): PartgConsolidationDto

    /**
     * V1.9.290 (R5): combines 2..20 large-donor rows of DIFFERENT units of [year] to one person. [expectedAnnexRevisions] (as in
     * [PartgConsolidationDto.annexRevisions]) must still be current for the units involved, else [PartyReportRevisionConflictException].
     */
    suspend fun createDonorCombination(
        year: Int,
        expectedRevision: Long,
        expectedAnnexRevisions: Map<String, Long>,
        largeDonorIds: List<String>,
    ): PartgConsolidationDto

    suspend fun dissolveDonorCombination(
        year: Int,
        expectedRevision: Long,
        combinationId: String,
    ): PartgConsolidationDto

    // ---------------------------------------------------------------- R6 (V1.9.291)
    // All of these start with the federal gate (BOARD/ADMIN), never with the scoped gate: a chapter treasurer reaches none of them.

    /** V1.9.291 (R6): the kept versions of [year], the year's status, the deadline and whether the live state deviates from the newest kept version. */
    suspend fun versionOverview(year: Int): PartgVersionOverviewDto

    /**
     * V1.9.291 (R6): keeps the current computed report of [year] and [scopeKey] (`ALL` or a unit key) as a new, numbered, unchangeable version.
     * Refused with [PartyReportVersionUnchangedException] when it equals the newest version, with [PartyReportVersionTooLargeException] above 4 MiB.
     */
    suspend fun freezeVersion(
        year: Int,
        scopeKey: String,
        comment: String?,
    ): PartgVersionDto

    /** V1.9.291 (R6): compares two kept versions cell by cell (read only). */
    suspend fun compareVersions(
        versionIdA: String,
        versionIdB: String,
    ): PartgVersionDiffDto

    /**
     * V1.9.291 (R6): records the Finanzvorstand's assurance on a whole-party version. [reviewed] must be `true`; [expectedContentHash] must equal the
     * stored checksum. Once only per version.
     */
    suspend fun declareVersion(
        versionId: String,
        expectedContentHash: String,
        reviewed: Boolean,
    ): PartgVersionDto

    /** V1.9.291 (R6): marks a declared whole-party version as internally final. [typedVersionNumber] must be the version's number. Irreversible. */
    suspend fun finalizeVersion(
        versionId: String,
        typedVersionNumber: Int,
        expectedContentHash: String,
    ): PartgVersionDto

    /** V1.9.291 (R6): confirms or changes the deadline of [year]; [deadline] is a strict ISO date. */
    suspend fun confirmDeadline(
        year: Int,
        deadline: String,
    ): PartgDeadlineDto

    /** V1.9.291 (R6, ADMIN only): the versions the deletion routine would remove with this retention in years (10..30). */
    suspend fun previewVersionDeletion(olderThanYears: Int): PartgVersionDeletionPreviewDto

    /** V1.9.291 (R6, ADMIN only): removes exactly [expectedVersionIds] if the recomputed set is identical; returns how many. */
    suspend fun deleteOldVersions(
        olderThanYears: Int,
        expectedVersionIds: List<String>,
    ): Int

    // ---------------------------------------------------------------- R4b (V1.9.292)

    /**
     * V1.9.292 (R4b): read-only unit-part preview (one unit, same source as the PDF/XLSX export of a chapter treasurer). Scoped gate: a chapter
     * treasurer gets a 404 for every unit that is not theirs (and for `FEDERAL`, `ALL` or an unparsable key); a federal caller may ask for any unit.
     */
    suspend fun unitStatementPreview(
        year: Int,
        unitKey: String,
    ): PartgUnitStatementDto

    // ---------------------------------------------------------------- R5b (V1.9.293)

    /**
     * V1.9.293 (R5b, federal gate only -- a chapter treasurer gets 403): the proposal for statement B of the FEDERAL unit, computed from the
     * bookings of the federal level (yearly sum per donor strictly above 10,000.00 EUR). [PartyReportProposalNotAvailableException] in mode C.
     * Read only; rows entered by hand stay as they are.
     */
    suspend fun largeDonorProposal(year: Int): PartgDonorProposalDto

    /**
     * V1.9.293 (R5b): takes over the chosen rows of the proposal into statement B of the federal unit. The server recomputes the whole proposal
     * UNDER THE LOCK of the annex; when [expectedChecksum] no longer matches, nothing is written ([PartyReportProposalStaleException]). One
     * transaction, one revision bump, one audit entry with counters only. Returns the annex as saved.
     */
    suspend fun applyLargeDonorProposal(
        year: Int,
        expectedChecksum: String,
        picks: List<PartgDonorProposalPickDto>,
    ): PartgAnnexDto
}
