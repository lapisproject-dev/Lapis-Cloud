package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesDto
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgSaveResultDto
import network.lapis.cloud.shared.domain.PartgSheetDto
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgValueChangeDto

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
}
