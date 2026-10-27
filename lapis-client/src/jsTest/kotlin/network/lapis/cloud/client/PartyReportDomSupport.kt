package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgSaveResultDto
import network.lapis.cloud.shared.domain.PartgSheetDto
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IPartyReportService

/** The fixtures and the RPC stub of the party-report DOM tests (V1.9.225). */
internal object PartyReportDomSupport {
    val federal = PartgUnitDto(unitKey = "FEDERAL", level = PartgUnitLevel.FEDERAL)
    val state =
        PartgUnitDto(
            unitKey = "STATE:0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90",
            level = PartgUnitLevel.STATE,
            chapterId = "0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90",
            chapterName = "Nord",
        )

    fun session(
        role: AccountRole = AccountRole.BOARD,
        party: Boolean = true,
    ) = SessionInfoDto(
        memberId = "member-1",
        displayName = "Dana Keller",
        role = role,
        status = MemberStatus.ACTIVE,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        politicalParty = party,
    )

    val positions: List<PartgPositionDto> =
        PartgPositions.ALL.map {
            PartgPositionDto(
                code = it.code,
                section = it.section,
                kind = it.kind,
                parentCode = it.parent,
                sortOrder = it.sortOrder,
                numberLabel = it.numberLabel,
                legalRef = it.legalRef,
                orgHint = if (it.code == "E01") "Nur Pflichtbeiträge" else null,
            )
        }

    val overview: List<PartgOverviewRowDto> =
        listOf(
            PartgOverviewRowDto(unit = federal, filledCount = 0, leafCount = 35),
            PartgOverviewRowDto(
                unit = state,
                filledCount = 12,
                leafCount = 35,
                incomeTotalCents = 150_000,
                expenseTotalCents = 50_000,
                netAssetsCents = -2_500,
            ),
        )

    fun sheet(year: Int) =
        PartgSheetDto(
            year = year,
            unit = federal,
            values = emptyMap(),
            revision = 0,
            priorYear = year - 1,
            priorValues = emptyMap(),
            priorRevision = 0,
        )

    /** The routes of the six methods, found through the compiler-checked service reference. */
    class Routes(
        val overview: String,
        val units: String,
        val positions: String,
        val sheet: String,
        val save: String,
        val hint: String,
    )

    suspend fun routes() =
        Routes(
            overview = routeOf { rpcService<IPartyReportService>().overview(2025) },
            units = routeOf { rpcService<IPartyReportService>().listUnits() },
            positions = routeOf { rpcService<IPartyReportService>().listPositions() },
            sheet = routeOf { rpcService<IPartyReportService>().getSheet(2025, "FEDERAL") },
            save = routeOf { rpcService<IPartyReportService>().saveSheet(2025, "FEDERAL", 0, 0, emptyList()) },
            hint = routeOf { rpcService<IPartyReportService>().updatePositionHint("E01", null) },
        )

    fun answer(
        routes: Routes,
        request: RecordedRequest,
        year: Int,
    ): StubResponse =
        when {
            !request.isRpc -> StubResponse()
            request.rpcRoute == routes.overview -> request.answerWith(jsonOf(ListSerializer(PartgOverviewRowDto.serializer()), overview))
            request.rpcRoute == routes.units ->
                request.answerWith(
                    jsonOf(ListSerializer(PartgUnitDto.serializer()), listOf(federal, state)),
                )
            request.rpcRoute == routes.positions -> request.answerWith(jsonOf(ListSerializer(PartgPositionDto.serializer()), positions))
            request.rpcRoute == routes.sheet -> request.answerWith(jsonOf(PartgSheetDto.serializer(), sheet(year)))
            request.rpcRoute == routes.save ->
                request.answerWith(
                    jsonOf(PartgSaveResultDto.serializer(), PartgSaveResultDto(revision = 1, priorRevision = 0, changed = 2)),
                )
            else -> request.answerWith("null")
        }
}
