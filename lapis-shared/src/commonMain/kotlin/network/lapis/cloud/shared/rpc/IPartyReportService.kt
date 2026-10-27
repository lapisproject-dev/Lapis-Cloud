package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
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
 * R1 stores typed-in or imported values per position, year and unit; it creates no report and no PDF.
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
}
