package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgEnterpriseInputDto
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
import network.lapis.cloud.shared.domain.PartgSponsorInputDto
import network.lapis.cloud.shared.domain.PartgSponsoringEntryInputDto
import network.lapis.cloud.shared.domain.PartgUnitCompletenessDto
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgYouthOrgInputDto
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
        chapterTreasurerScopes: List<network.lapis.cloud.shared.domain.RegionalChapterRefDto> = emptyList(),
    ) = SessionInfoDto(
        memberId = "member-1",
        displayName = "Dana Keller",
        role = role,
        status = MemberStatus.ACTIVE,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        politicalParty = party,
        chapterTreasurerScopes = chapterTreasurerScopes,
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

    /** V1.9.289 (R4a): when set, the overview answers with this list instead of [overview]. */
    var overviewOverride: List<PartgOverviewRowDto>? = null

    /** V1.9.289 (R4a): the answers of the mapping, mode and proposal calls; every R4 test sets what it needs first. */
    var mappingView: network.lapis.cloud.shared.domain.PartgAccountMappingViewDto? = null
    var proposal: network.lapis.cloud.shared.domain.PartgLedgerProposalDto? = null
    val modeCalls = mutableListOf<Triple<String, network.lapis.cloud.shared.domain.PartgBookkeepingMode, Int>>()

    /** V1.9.290 (R5): the answer of `getConsolidation` and of the four consolidation writes; `null` = the stub answers "null" (the call fails). */
    var consolidation: network.lapis.cloud.shared.domain.PartgConsolidationDto? = null

    /** V1.9.290 (R5): the requests of the four consolidation writes, in order (route name to the whole request). */
    val consolidationWrites = mutableListOf<Pair<String, RecordedRequest>>()

    /** V1.9.291 (R6): the answers of the version calls; `null` = the stub answers "null" (the call fails). */
    var versionOverview: network.lapis.cloud.shared.domain.PartgVersionOverviewDto? = null
    var versionDiff: network.lapis.cloud.shared.domain.PartgVersionDiffDto? = null
    var deletionPreview: network.lapis.cloud.shared.domain.PartgVersionDeletionPreviewDto? = null
    var versionAnswer: network.lapis.cloud.shared.domain.PartgVersionDto? = null
    var deadlineAnswer: network.lapis.cloud.shared.domain.PartgDeadlineDto? = null

    /** V1.9.291 (R6): the requests of the version writes (route name to the whole request), in order. */
    val versionWrites = mutableListOf<Pair<String, RecordedRequest>>()

    /** V1.9.292 (R4b): the answer of `unitStatementPreview`; `null` = the stub answers "null" (the call fails). */
    var unitStatement: network.lapis.cloud.shared.domain.PartgUnitStatementDto? = null

    /** V1.9.292 (R4b): when set, `listUnits` answers with this list instead of the federal and the state unit. */
    var unitsOverride: List<PartgUnitDto>? = null

    fun resetR6() {
        versionOverview = null
        versionDiff = null
        deletionPreview = null
        versionAnswer = null
        deadlineAnswer = null
        versionWrites.clear()
    }

    fun resetR4() {
        resetR6()
        unitStatement = null
        unitsOverride = null
        overviewOverride = null
        mappingView = null
        proposal = null
        modeCalls.clear()
        consolidation = null
        consolidationWrites.clear()
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

    fun sponsor(
        id: String,
        row: Int,
        name: String,
        amounts: List<Long>,
        city: String? = "Hamburg",
    ) = network.lapis.cloud.shared.domain.PartgSponsorDto(
        id = id,
        rowNumber = row,
        personKind = PartgPersonKind.LEGAL,
        name = name,
        street = null,
        postalCode = null,
        city = city,
        country = "DE",
        entries =
            amounts.mapIndexed { i, cents ->
                network.lapis.cloud.shared.domain
                    .PartgSponsoringEntryDto("$id-e$i", kotlinx.datetime.LocalDate(2025, 3, 1 + i), cents, "Zweck $i")
            },
        yearlySumCents = amounts.sum(),
        qualification =
            network.lapis.cloud.shared.domain.PartgStatementRules
                .qualification(amounts),
    )

    fun youth(
        id: String,
        row: Int,
        name: String,
        income: Long? = null,
        expenses: Long? = null,
    ) = network.lapis.cloud.shared.domain
        .PartgYouthOrgDto(id, row, name, "Jugendverband", income, expenses)

    fun enterprise(
        id: String,
        row: Int,
        name: String,
        income: Long? = null,
        expenses: Long? = null,
        share: Int? = null,
    ) = network.lapis.cloud.shared.domain
        .PartgEnterpriseDto(id, row, name, "GmbH", "Berlin", share, income, expenses)

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
        val saveSponsor: String,
        val deleteSponsor: String,
        val saveYouthOrg: String,
        val deleteYouthOrg: String,
        val saveEnterprise: String,
        val deleteEnterprise: String,
        val getMappings: String,
        val saveMappings: String,
        val setMode: String,
        val propose: String,
        val applyProposal: String,
        val getConsolidation: String,
        val saveTransfer: String,
        val deleteTransfer: String,
        val createCombination: String,
        val dissolveCombination: String,
        val versionOverview: String,
        val freeze: String,
        val compare: String,
        val declare: String,
        val finalize: String,
        val confirmDeadline: String,
        val previewDeletion: String,
        val deleteOld: String,
        val unitStatement: String,
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
            saveSponsor =
                routeOf {
                    rpcService<IPartyReportService>().saveSponsor(
                        2025,
                        "FEDERAL",
                        0,
                        PartgSponsorInputDto(
                            null,
                            PartgPersonKind.LEGAL,
                            "x",
                            null,
                            null,
                            null,
                            "DE",
                            listOf(PartgSponsoringEntryInputDto(null, kotlinx.datetime.LocalDate(2025, 1, 1), 1L, null)),
                        ),
                    )
                },
            deleteSponsor = routeOf { rpcService<IPartyReportService>().deleteSponsor(2025, "FEDERAL", 0, "x") },
            saveYouthOrg =
                routeOf {
                    rpcService<IPartyReportService>().saveYouthOrg(2025, "FEDERAL", 0, PartgYouthOrgInputDto(null, "x", null, null, null))
                },
            deleteYouthOrg = routeOf { rpcService<IPartyReportService>().deleteYouthOrg(2025, "FEDERAL", 0, "x") },
            saveEnterprise =
                routeOf {
                    rpcService<IPartyReportService>()
                        .saveEnterprise(2025, "FEDERAL", 0, PartgEnterpriseInputDto(null, "x", null, null, null, null, null))
                },
            deleteEnterprise = routeOf { rpcService<IPartyReportService>().deleteEnterprise(2025, "FEDERAL", 0, "x") },
            getMappings = routeOf { rpcService<IPartyReportService>().getAccountMappings(2025, "FEDERAL") },
            saveMappings = routeOf { rpcService<IPartyReportService>().saveAccountMappings(2025, "FEDERAL", emptyList()) },
            setMode =
                routeOf {
                    rpcService<IPartyReportService>()
                        .setUnitMode(2025, "FEDERAL", network.lapis.cloud.shared.domain.PartgBookkeepingMode.A, 0)
                },
            propose = routeOf { rpcService<IPartyReportService>().proposeFromLedger(2025, "FEDERAL") },
            applyProposal = routeOf { rpcService<IPartyReportService>().applyLedgerProposal(2025, "FEDERAL", 0, 0, emptyList()) },
            getConsolidation = routeOf { rpcService<IPartyReportService>().getConsolidation(2025) },
            saveTransfer =
                routeOf {
                    rpcService<IPartyReportService>().saveIntraTransfer(
                        2025,
                        0,
                        network.lapis.cloud.shared.domain.PartgIntraTransferInputDto(
                            null,
                            network.lapis.cloud.shared.domain.PartgTransferKind.SUBSIDY,
                            "FEDERAL",
                            "x",
                            1,
                            null,
                            null,
                        ),
                    )
                },
            deleteTransfer = routeOf { rpcService<IPartyReportService>().deleteIntraTransfer(2025, 0, "x") },
            createCombination = routeOf { rpcService<IPartyReportService>().createDonorCombination(2025, 0, emptyMap(), emptyList()) },
            dissolveCombination = routeOf { rpcService<IPartyReportService>().dissolveDonorCombination(2025, 0, "x") },
            versionOverview = routeOf { rpcService<IPartyReportService>().versionOverview(2025) },
            freeze = routeOf { rpcService<IPartyReportService>().freezeVersion(2025, "ALL", null) },
            compare = routeOf { rpcService<IPartyReportService>().compareVersions("a", "b") },
            declare = routeOf { rpcService<IPartyReportService>().declareVersion("a", "h", true) },
            finalize = routeOf { rpcService<IPartyReportService>().finalizeVersion("a", 1, "h") },
            confirmDeadline = routeOf { rpcService<IPartyReportService>().confirmDeadline(2025, "2026-09-30") },
            previewDeletion = routeOf { rpcService<IPartyReportService>().previewVersionDeletion(10) },
            deleteOld = routeOf { rpcService<IPartyReportService>().deleteOldVersions(10, emptyList()) },
            unitStatement = routeOf { rpcService<IPartyReportService>().unitStatementPreview(2025, "FEDERAL") },
        )

    fun answer(
        routes: Routes,
        request: RecordedRequest,
        year: Int,
    ): StubResponse =
        when {
            !request.isRpc -> StubResponse()
            request.rpcRoute == routes.overview ->
                request.answerWith(jsonOf(ListSerializer(PartgOverviewRowDto.serializer()), overviewOverride ?: overview))
            request.rpcRoute == routes.getMappings || request.rpcRoute == routes.saveMappings ->
                request.answerWith(
                    jsonOf(
                        network.lapis.cloud.shared.domain.PartgAccountMappingViewDto
                            .serializer(),
                        checkNotNull(mappingView) { "the test did not set a mapping view" },
                    ),
                )
            request.rpcRoute == routes.propose ->
                request.answerWith(
                    jsonOf(
                        network.lapis.cloud.shared.domain.PartgLedgerProposalDto
                            .serializer(),
                        checkNotNull(proposal) { "the test did not set a proposal" },
                    ),
                )
            request.rpcRoute == routes.applyProposal ->
                request.answerWith(
                    jsonOf(PartgSaveResultDto.serializer(), PartgSaveResultDto(revision = 1, priorRevision = 0, changed = 1)),
                )
            request.rpcRoute == routes.setMode -> request.answerWith(jsonOf(PartgOverviewRowDto.serializer(), overview.first()))
            request.rpcRoute in
                listOf(
                    routes.getConsolidation,
                    routes.saveTransfer,
                    routes.deleteTransfer,
                    routes.createCombination,
                    routes.dissolveCombination,
                ) -> {
                if (request.rpcRoute != routes.getConsolidation) consolidationWrites += request.rpcRoute to request
                val dto = consolidation
                if (dto == null) {
                    request.answerWith("null")
                } else {
                    request.answerWith(
                        jsonOf(
                            network.lapis.cloud.shared.domain.PartgConsolidationDto
                                .serializer(),
                            dto,
                        ),
                    )
                }
            }
            request.rpcRoute == routes.versionOverview ->
                request.answerWith(
                    versionOverview?.let {
                        jsonOf(
                            network.lapis.cloud.shared.domain.PartgVersionOverviewDto
                                .serializer(),
                            it,
                        )
                    } ?: "null",
                )
            request.rpcRoute == routes.compare ->
                request.answerWith(
                    versionDiff?.let {
                        jsonOf(
                            network.lapis.cloud.shared.domain.PartgVersionDiffDto
                                .serializer(),
                            it,
                        )
                    } ?: "null",
                )
            request.rpcRoute == routes.previewDeletion ->
                request.answerWith(
                    deletionPreview?.let {
                        jsonOf(
                            network.lapis.cloud.shared.domain.PartgVersionDeletionPreviewDto
                                .serializer(),
                            it,
                        )
                    } ?: "null",
                )
            request.rpcRoute in listOf(routes.freeze, routes.declare, routes.finalize) -> {
                versionWrites += request.rpcRoute to request
                request.answerWith(
                    versionAnswer?.let {
                        jsonOf(
                            network.lapis.cloud.shared.domain.PartgVersionDto
                                .serializer(),
                            it,
                        )
                    } ?: "null",
                )
            }
            request.rpcRoute == routes.confirmDeadline -> {
                versionWrites += request.rpcRoute to request
                request.answerWith(
                    deadlineAnswer?.let {
                        jsonOf(
                            network.lapis.cloud.shared.domain.PartgDeadlineDto
                                .serializer(),
                            it,
                        )
                    } ?: "null",
                )
            }
            request.rpcRoute == routes.unitStatement ->
                request.answerWith(
                    unitStatement?.let {
                        jsonOf(
                            network.lapis.cloud.shared.domain.PartgUnitStatementDto
                                .serializer(),
                            it,
                        )
                    } ?: "null",
                )
            request.rpcRoute == routes.deleteOld -> {
                versionWrites += request.rpcRoute to request
                request.answerWith("1")
            }
            request.rpcRoute == routes.units ->
                request.answerWith(
                    jsonOf(ListSerializer(PartgUnitDto.serializer()), unitsOverride ?: listOf(federal, state)),
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
            request.rpcRoute == routes.saveFigures ||
                request.rpcRoute == routes.saveDonor ||
                request.rpcRoute == routes.deleteDonor ||
                request.rpcRoute == routes.saveSponsor ||
                request.rpcRoute == routes.deleteSponsor ||
                request.rpcRoute == routes.saveYouthOrg ||
                request.rpcRoute == routes.deleteYouthOrg ||
                request.rpcRoute == routes.saveEnterprise ||
                request.rpcRoute == routes.deleteEnterprise ->
                request.answerWith(jsonOf(PartgAnnexDto.serializer(), annex))
            request.rpcRoute == routes.getNotes || request.rpcRoute == routes.saveNotes ->
                request.answerWith(jsonOf(PartgNotesDto.serializer(), notes))
            request.rpcRoute == routes.findings -> request.answerWith(jsonOf(ListSerializer(PartgFindingDto.serializer()), findings))
            else -> request.answerWith("null")
        }
}
