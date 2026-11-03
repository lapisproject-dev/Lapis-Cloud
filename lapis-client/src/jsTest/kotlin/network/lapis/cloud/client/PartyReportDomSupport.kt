package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgMemberCountSuggestionDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesDto
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgSaveResultDto
import network.lapis.cloud.shared.domain.PartgSheetDto
import network.lapis.cloud.shared.domain.PartgUnitCompletenessDto
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

    /** The federal unit misses E01 and E02 and has no prior year; the state unit is complete with a half-entered prior year. */
    fun completeness(year: Int) =
        PartgCompletenessDto(
            year = year,
            priorYear = year - 1,
            leafCount = 35,
            units =
                listOf(
                    PartgUnitCompletenessDto(
                        unit = federal,
                        missingCurrent = listOf("E01", "E02"),
                        enteredPrior = 0,
                    ),
                    PartgUnitCompletenessDto(unit = state, missingCurrent = emptyList(), enteredPrior = 30),
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

    // ---- V1.9.269 (R3): the state the stub answers with; every R3 test sets what it needs first (resetR3)
    var annex: PartgAnnexDto = annexDto()
    var findings: List<PartgFindingDto> = emptyList()
    var notes: PartgNotesDto = notesDto()

    fun resetR3() {
        annex = annexDto()
        findings = emptyList()
        notes = notesDto()
    }

    fun donor(
        id: String,
        row: Int,
        name: String,
        city: String? = "Braunschweig",
        cents: Long = 1_200_000L,
        kind: PartgPersonKind = PartgPersonKind.NATURAL,
    ) = PartgLargeDonorDto(
        id = id,
        rowNumber = row,
        personKind = kind,
        name = name,
        street = if (city == null) null else "Hauptstraße 1",
        postalCode = if (city == null) null else "38100",
        city = city,
        country = "DE",
        contributionKind = PartgContributionKind.DONATION,
        amountCents = cents,
    )

    fun annexDto(
        year: Int = 2025,
        suggestion: Boolean = true,
        donors: List<PartgLargeDonorDto> =
            listOf(donor("d1", 1, "Fixture Person Eins"), donor("d2", 2, "Fixture Firma Zwei", null, 1_100_000L, PartgPersonKind.LEGAL)),
    ) = PartgAnnexDto(
        year = year,
        unit = federal,
        revision = 2,
        deductions = PartgDeduction.entries.associateWith { null },
        memberCount = null,
        memberCountSuggestion = if (suggestion) PartgMemberCountSuggestionDto(1234, kotlinx.datetime.LocalDate(2025, 6, 15)) else null,
        donors = donors,
    )

    fun notesDto() =
        PartgNotesDto(
            year = 2025,
            revision = 1,
            notes = PartgNoteBlock.entries.map { PartgNoteDto(it, false, null, null, null) },
            holdings = emptyList(),
        )

    /** The routes of the seven methods, found through the compiler-checked service reference. */
    class Routes(
        val overview: String,
        val units: String,
        val positions: String,
        val sheet: String,
        val save: String,
        val hint: String,
        val completeness: String,
        val getAnnex: String,
        val saveFigures: String,
        val saveDonor: String,
        val deleteDonor: String,
        val getNotes: String,
        val saveNotes: String,
        val findings: String,
    )

    suspend fun routes() =
        Routes(
            overview = routeOf { rpcService<IPartyReportService>().overview(2025) },
            units = routeOf { rpcService<IPartyReportService>().listUnits() },
            positions = routeOf { rpcService<IPartyReportService>().listPositions() },
            sheet = routeOf { rpcService<IPartyReportService>().getSheet(2025, "FEDERAL") },
            save = routeOf { rpcService<IPartyReportService>().saveSheet(2025, "FEDERAL", 0, 0, emptyList()) },
            hint = routeOf { rpcService<IPartyReportService>().updatePositionHint("E01", null) },
            completeness = routeOf { rpcService<IPartyReportService>().reportCompleteness(2025) },
            getAnnex = routeOf { rpcService<IPartyReportService>().getAnnex(2025, "FEDERAL") },
            saveFigures = routeOf { rpcService<IPartyReportService>().saveAnnexFigures(2025, "FEDERAL", 0, emptyMap(), null) },
            saveDonor =
                routeOf {
                    rpcService<IPartyReportService>().saveLargeDonor(
                        2025,
                        "FEDERAL",
                        0,
                        PartgLargeDonorInputDto(
                            null,
                            PartgPersonKind.NATURAL,
                            "x",
                            null,
                            null,
                            null,
                            "DE",
                            PartgContributionKind.DONATION,
                            1_000_001L,
                        ),
                    )
                },
            deleteDonor = routeOf { rpcService<IPartyReportService>().deleteLargeDonor(2025, "FEDERAL", 0, "x") },
            getNotes = routeOf { rpcService<IPartyReportService>().getNotes(2025) },
            saveNotes = routeOf { rpcService<IPartyReportService>().saveNotes(2025, 0, emptyList(), emptyList()) },
            findings = routeOf { rpcService<IPartyReportService>().reportFindings(2025) },
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
            request.rpcRoute == routes.completeness ->
                request.answerWith(jsonOf(PartgCompletenessDto.serializer(), completeness(year)))
            request.rpcRoute == routes.positions -> request.answerWith(jsonOf(ListSerializer(PartgPositionDto.serializer()), positions))
            request.rpcRoute == routes.sheet -> request.answerWith(jsonOf(PartgSheetDto.serializer(), sheet(year)))
            request.rpcRoute == routes.save ->
                request.answerWith(
                    jsonOf(PartgSaveResultDto.serializer(), PartgSaveResultDto(revision = 1, priorRevision = 0, changed = 2)),
                )
            request.rpcRoute == routes.getAnnex -> request.answerWith(jsonOf(PartgAnnexDto.serializer(), annex))
            request.rpcRoute == routes.saveFigures || request.rpcRoute == routes.saveDonor || request.rpcRoute == routes.deleteDonor ->
                request.answerWith(jsonOf(PartgAnnexDto.serializer(), annex))
            request.rpcRoute == routes.getNotes || request.rpcRoute == routes.saveNotes ->
                request.answerWith(jsonOf(PartgNotesDto.serializer(), notes))
            request.rpcRoute == routes.findings -> request.answerWith(jsonOf(ListSerializer(PartgFindingDto.serializer()), findings))
            else -> request.answerWith("null")
        }
}
