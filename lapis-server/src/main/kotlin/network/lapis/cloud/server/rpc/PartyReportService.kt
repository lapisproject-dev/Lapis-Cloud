package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgAnnexWriter
import network.lapis.cloud.server.partg.PartgConsolidationView
import network.lapis.cloud.server.partg.PartgConsolidationWriter
import network.lapis.cloud.server.partg.PartgDonorMasterData
import network.lapis.cloud.server.partg.PartgDonorProposals
import network.lapis.cloud.server.partg.PartgLedgerLinkStore
import network.lapis.cloud.server.partg.PartgLedgerProposals
import network.lapis.cloud.server.partg.PartgLedgerReader
import network.lapis.cloud.server.partg.PartgMemberCountSuggestion
import network.lapis.cloud.server.partg.PartgReportSnapshotReader
import network.lapis.cloud.server.partg.PartgReportStore
import network.lapis.cloud.server.partg.PartgSheetWriter
import network.lapis.cloud.server.partg.PartgStatementStore
import network.lapis.cloud.server.partg.PartgVersionOperations
import network.lapis.cloud.server.partg.PartgYearRules
import network.lapis.cloud.server.partg.PartyReportActor
import network.lapis.cloud.server.partg.report.PartgConsolidation
import network.lapis.cloud.server.partg.report.PartgDonorConsolidation
import network.lapis.cloud.server.partg.report.PartgPlausibility
import network.lapis.cloud.server.partg.report.PartgUnitPart
import network.lapis.cloud.server.partg.requirePartyReportAccess
import network.lapis.cloud.server.partg.requirePartyReportScopedAccess
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgAccountMappingRowDto
import network.lapis.cloud.shared.domain.PartgAccountMappingViewDto
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgDonorProposalAvailability
import network.lapis.cloud.shared.domain.PartgDonorProposalDto
import network.lapis.cloud.shared.domain.PartgDonorProposalPickDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRules
import network.lapis.cloud.shared.domain.PartgEnterpriseCheckDto
import network.lapis.cloud.shared.domain.PartgEnterpriseDto
import network.lapis.cloud.shared.domain.PartgEnterpriseInputDto
import network.lapis.cloud.shared.domain.PartgEnterpriseRules
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgIntraTransferInputDto
import network.lapis.cloud.shared.domain.PartgLargeDonorDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgLedgerProposalDto
import network.lapis.cloud.shared.domain.PartgLedgerProposalRowDto
import network.lapis.cloud.shared.domain.PartgLedgerRules
import network.lapis.cloud.shared.domain.PartgMappingAuditSnapshot
import network.lapis.cloud.shared.domain.PartgMappingChangeDto
import network.lapis.cloud.shared.domain.PartgModeAuditSnapshot
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesDto
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgPositionHintSnapshot
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgProposalPickDto
import network.lapis.cloud.shared.domain.PartgSaveResultDto
import network.lapis.cloud.shared.domain.PartgSheetDto
import network.lapis.cloud.shared.domain.PartgSponsorDto
import network.lapis.cloud.shared.domain.PartgSponsorInputDto
import network.lapis.cloud.shared.domain.PartgSponsoringEntryDto
import network.lapis.cloud.shared.domain.PartgStatementRules
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgUnitStatementDto
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.domain.PartgVersionDeletionPreviewDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto
import network.lapis.cloud.shared.domain.PartgYouthOrgDto
import network.lapis.cloud.shared.domain.PartgYouthOrgInputDto
import network.lapis.cloud.shared.domain.PartyAccountNameHeuristic
import network.lapis.cloud.shared.domain.StandardPartyChart
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IPartyReportService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportModeConfirmationRequiredException
import network.lapis.cloud.shared.rpc.PartyReportProposalNotAvailableException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.225 -- see [IPartyReportService] and `docs/features/partg-report.adoc`. EVERY method starts with `requirePartyReportAccess(call)`
 * (pinned by `PartyReportAccessTripwireTest`). Writes: 60 per minute and member. Nothing is logged except counts; no amount, no hint text.
 */
class PartyReportService internal constructor(
    private val call: ApplicationCall,
    private val writeRateLimiter: FederationInboxRateLimiter,
    private val findingsRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30),
    // V1.9.291 (R6): the shared instances come from `Application.module`; the defaults are for tests that build the service on its own
    freezeRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10),
    versionReadRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60),
    exportConcurrency: Semaphore = Semaphore(permits = 2),
) : IPartyReportService {
    private val versions =
        PartgVersionOperations(
            writeRateLimiter = writeRateLimiter,
            freezeRateLimiter = freezeRateLimiter,
            readRateLimiter = versionReadRateLimiter,
            concurrency = exportConcurrency,
        )

    override suspend fun listPositions(): List<PartgPositionDto> {
        requirePartyReportAccess(call)
        return transaction { PartgReportStore.positions() }
    }

    override suspend fun updatePositionHint(
        code: String,
        hint: String?,
    ): PartgPositionDto {
        val current = requirePartyReportAccess(call)
        val normalized = normalizeHint(hint)
        if (PartgPositions.find(code) == null) throw PartyReportInvalidInputException("Unknown position")
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        val now = ServerClock.now()
        return transaction {
            val row = PartgReportStore.lockPosition(code) ?: throw NotFoundException("Position not found")
            val old = row.orgHint
            if (old != normalized) {
                PartgReportStore.setHint(code = code, hint = normalized, actor = current.memberId, now = now)
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.PARTG_REPORT_POSITION,
                    entityId = row.id,
                    action = AuditAction.UPDATE,
                    before =
                        Json.encodeToString(
                            PartgPositionHintSnapshot.serializer(),
                            PartgPositionHintSnapshot(code = code, hint = old),
                        ),
                    after =
                        Json.encodeToString(
                            PartgPositionHintSnapshot.serializer(),
                            PartgPositionHintSnapshot(code = code, hint = normalized),
                        ),
                    occurredAt = now,
                )
            }
            PartgReportStore.positions().single { it.code == code }
        }
    }

    override suspend fun listUnits(): List<PartgUnitDto> {
        val actor = requirePartyReportScopedAccess(call)
        return transaction { PartgReportStore.units().filter { actor.allowsKey(it.unitKey) } }
    }

    override suspend fun overview(year: Int): List<PartgOverviewRowDto> {
        val actor = requirePartyReportScopedAccess(call)
        PartgYearRules.requireValid(year)
        return transaction { enrichOverview(year = year, rows = PartgReportStore.overview(year), actor = actor) }
    }

    override suspend fun getSheet(
        year: Int,
        unitKey: String,
    ): PartgSheetDto {
        val actor = requirePartyReportScopedAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        // a chapter treasurer does not learn that another unit exists: 404
        if (!actor.allows(unit)) throw NotFoundException("Unit not found")
        return transaction {
            val dto = PartgReportStore.unitDto(unit)
            val sheet = PartgReportStore.findSheet(year = year, unit = unit)
            val prior = PartgReportStore.findSheet(year = year - 1, unit = unit)
            PartgSheetDto(
                year = year,
                unit = dto,
                values = sheet?.let { PartgReportStore.values(it.id) }.orEmpty(),
                revision = sheet?.revision ?: 0L,
                priorYear = year - 1,
                priorValues = prior?.let { PartgReportStore.values(it.id) }.orEmpty(),
                priorRevision = prior?.revision ?: 0L,
                valueSources = sheet?.let { PartgReportStore.valueSources(it.id) }.orEmpty(),
            )
        }
    }

    override suspend fun saveSheet(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        expectedPriorRevision: Long,
        changes: List<PartgValueChangeDto>,
    ): PartgSaveResultDto {
        val actor = requirePartyReportScopedAccess(call)
        val current = actor.current
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!actor.allows(unit)) throw ForbiddenException()
        if (changes.size > MAX_CHANGES) throw PartyReportInvalidInputException("Too many changes")
        val currentChanges = LinkedHashMap<String, Long?>()
        val priorChanges = LinkedHashMap<String, Long?>()
        changes.forEach { change ->
            if (change.positionCode !in PartgPositions.LEAF_CODES) throw PartyReportInvalidInputException("Not a typed-in position")
            val target =
                when (change.year) {
                    year -> currentChanges
                    year - 1 -> priorChanges
                    else -> throw PartyReportInvalidInputException("Year of a change must be the report year or the year before")
                }
            val cents = change.amountCents
            if (cents != null && (cents < 0L || cents > PARTG_MAX_CENTS)) throw PartyReportInvalidInputException("Amount out of range")
            if (target.containsKey(change.positionCode)) throw PartyReportInvalidInputException("Duplicate change")
            target[change.positionCode] = cents
        }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgSheetWriter.write(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            expectedPriorRevision = expectedPriorRevision,
            currentChanges = currentChanges,
            priorChanges = priorChanges,
            source = PartgValueSource.MANUAL,
            now = ServerClock.now(),
        )
    }

    override suspend fun reportCompleteness(year: Int): PartgCompletenessDto {
        requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        return transaction { PartgReportStore.completeness(year) }
    }

    // ---------------------------------------------------------------- R3 (V1.9.269)

    override suspend fun getAnnex(
        year: Int,
        unitKey: String,
    ): PartgAnnexDto {
        requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        return transaction { annexDto(year = year, unit = unit) }
    }

    override suspend fun saveAnnexFigures(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        deductions: Map<PartgDeduction, Long?>,
        memberCount: Int?,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        PartgAnnexRules.validateFigures(deductions = deductions, memberCount = memberCount)?.let {
            throw PartyReportInvalidInputException(it.name)
        }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.saveFigures(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            deductions = deductions,
            memberCount = memberCount,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun saveLargeDonor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        donor: PartgLargeDonorInputDto,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        PartgAnnexRules.validateDonor(donor)?.let { throw PartyReportInvalidInputException(it.name) }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.saveDonor(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            donor = donor,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun deleteLargeDonor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        donorId: String,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.deleteDonor(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            donorId = donorId,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun getNotes(year: Int): PartgNotesDto {
        requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        return transaction { notesDto(year) }
    }

    override suspend fun saveNotes(
        year: Int,
        expectedRevision: Long,
        notes: List<PartgNoteDto>,
        holdings: List<PartgHoldingDto>,
    ): PartgNotesDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        PartgAnnexRules.validateNotes(notes = notes, holdings = holdings)?.let { throw PartyReportInvalidInputException(it.name) }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.saveNotes(
            current = current,
            year = year,
            expectedRevision = expectedRevision,
            notes = notes,
            holdings = holdings,
            now = ServerClock.now(),
        ) { notesDto(year) }
    }

    override suspend fun reportFindings(year: Int): List<PartgFindingDto> {
        val actor = requirePartyReportScopedAccess(call)
        val current = actor.current
        PartgYearRules.requireValid(year)
        if (!findingsRateLimiter.checkAndRecord("partg:findings:${current.memberId}")) throw RateLimitedException()
        val snapshot = PartgReportSnapshotReader.read(listOf(year, year - 1))
        val ledger = transaction { PartgLedgerProposals.ledgerFacts(year = year, units = snapshot.units) }
        // V1.9.290 (R5): the hints P17..P21 are for the federal view only. A chapter treasurer gets none (not even for their own unit): the
        // consolidation reads the data of all units, which they may not see.
        val consolidationInput =
            if (actor is PartyReportActor.Federal) {
                PartgConsolidation.findings(
                    year = year,
                    units = snapshot.units,
                    entered = snapshot.entered.values,
                    consolidation = snapshot.consolidation,
                    donors =
                        PartgDonorConsolidation.compute(
                            units = snapshot.units,
                            annex = snapshot.annex[year].orEmpty(),
                            combinations = snapshot.consolidation[year]?.combinations.orEmpty(),
                        ),
                )
            } else {
                null
            }
        val findings =
            PartgPlausibility.check(
                year = year,
                units = snapshot.units,
                entered = snapshot.entered.values,
                annex = snapshot.annex,
                notes = snapshot.notes,
                ledger = ledger,
                consolidation = consolidationInput,
            )
        // a chapter treasurer sees only the hints of their own units; the hints of the whole organisation (unitKey null) are not theirs
        return if (actor is PartyReportActor.Federal) {
            findings
        } else {
            findings.filter { f ->
                f.unitKey != null && actor.allowsKey(f.unitKey!!)
            }
        }
    }

    // ---------------------------------------------------------------- R3b (V1.9.283)

    override suspend fun saveSponsor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        sponsor: PartgSponsorInputDto,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        PartgStatementRules.validateSponsor(input = sponsor, year = year)?.let { throw PartyReportInvalidInputException(it.name) }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.saveSponsor(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            sponsor = sponsor,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun deleteSponsor(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        sponsorId: String,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.deleteSponsor(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            sponsorId = sponsorId,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun saveYouthOrg(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        youthOrg: PartgYouthOrgInputDto,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        PartgStatementRules.validateYouthOrg(youthOrg)?.let { throw PartyReportInvalidInputException(it.name) }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.saveYouthOrg(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            youthOrg = youthOrg,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun deleteYouthOrg(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        youthOrgId: String,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.deleteYouthOrg(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            youthOrgId = youthOrgId,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun saveEnterprise(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        enterprise: PartgEnterpriseInputDto,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        PartgEnterpriseRules.validate(enterprise)?.let { throw PartyReportInvalidInputException(it.name) }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.saveEnterprise(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            enterprise = enterprise,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    override suspend fun deleteEnterprise(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        enterpriseId: String,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.deleteEnterprise(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            enterpriseId = enterpriseId,
            now = ServerClock.now(),
        ) { annexDto(year = year, unit = unit) }
    }

    // ---------------------------------------------------------------- R4a (V1.9.289)

    override suspend fun getAccountMappings(
        year: Int,
        unitKey: String,
    ): PartgAccountMappingViewDto {
        val actor = requirePartyReportScopedAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!actor.allows(unit)) throw NotFoundException("Unit not found")
        return transaction { mappingView(year = year, unit = unit, editable = actor is PartyReportActor.Federal) }
    }

    override suspend fun saveAccountMappings(
        year: Int,
        unitKey: String,
        changes: List<PartgMappingChangeDto>,
    ): PartgAccountMappingViewDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (changes.size > MAX_MAPPING_CHANGES) throw PartyReportInvalidInputException("Too many changes")
        if (changes.map { it.accountId }.toSet().size != changes.size) throw PartyReportInvalidInputException("Duplicate account")
        val parsed =
            changes.map { change ->
                change to
                    (runCatching { Uuid.parse(change.accountId) }.getOrNull() ?: throw NotFoundException("Account not found"))
            }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        val now = ServerClock.now()
        return transaction {
            // serialises all mapping writers (same settings-row lock every settings writer takes BEFORE the audit chain row)
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .forUpdate()
                .single()
            val accounts = PartgLedgerReader.accounts().associateBy { it.id }
            val stored = PartgLedgerLinkStore.mappings()
            // validate EVERYTHING before the first write: all or nothing
            val plan =
                parsed.map { (change, accountId) ->
                    val account = accounts[accountId] ?: throw NotFoundException("Account not found")
                    val old = stored[accountId]
                    if (change.expectedMapped != (old != null)) throw PartyReportRevisionConflictException()
                    if (old != null && (change.expectedPositionCode != old.positionCode || change.expectedExcluded != old.excluded)) {
                        throw PartyReportRevisionConflictException()
                    }
                    when {
                        change.clear -> Unit
                        change.newExcluded ->
                            if (change.newPositionCode !=
                                null
                            ) {
                                throw PartyReportInvalidInputException("Excluded has no position")
                            }
                        else -> {
                            val code = change.newPositionCode ?: throw PartyReportInvalidInputException("Position missing")
                            if (!PartgLedgerRules.allowedTarget(
                                    accountType = account.type,
                                    positionCode = code,
                                )
                            ) {
                                throw PartyReportInvalidInputException("Not a valid target")
                            }
                        }
                    }
                    Triple(change, account, old)
                }
            plan.forEach { (change, account, old) ->
                val accountId = account.id
                when {
                    change.clear && old == null -> Unit
                    change.clear -> {
                        PartgLedgerLinkStore.delete(accountId)
                        recordMappingAudit(
                            current = current,
                            rowId = old!!.id,
                            action = AuditAction.VOID,
                            before =
                                PartgMappingAuditSnapshot(
                                    accountNumber = account.number,
                                    oldCode = old.positionCode,
                                    oldExcluded = old.excluded,
                                    newCode = null,
                                    newExcluded = null,
                                ),
                            after = null,
                            now = now,
                        )
                    }
                    old != null && old.excluded == change.newExcluded && old.positionCode == change.newPositionCode -> Unit
                    else -> {
                        val rowId =
                            PartgLedgerLinkStore.upsert(
                                accountId = accountId,
                                positionCode = if (change.newExcluded) null else change.newPositionCode,
                                excluded = change.newExcluded,
                                actor = current.memberId,
                                now = now,
                            )
                        recordMappingAudit(
                            current = current,
                            rowId = rowId,
                            action = if (old == null) AuditAction.CREATE else AuditAction.UPDATE,
                            before =
                                old?.let {
                                    PartgMappingAuditSnapshot(
                                        accountNumber = account.number,
                                        oldCode = it.positionCode,
                                        oldExcluded = it.excluded,
                                        newCode = null,
                                        newExcluded = null,
                                    )
                                },
                            after =
                                PartgMappingAuditSnapshot(
                                    accountNumber = account.number,
                                    newCode = if (change.newExcluded) null else change.newPositionCode,
                                    newExcluded = change.newExcluded,
                                ),
                            now = now,
                        )
                    }
                }
            }
            mappingView(year = year, unit = unit, editable = true)
        }
    }

    override suspend fun setUnitMode(
        year: Int,
        unitKey: String,
        mode: PartgBookkeepingMode,
        confirmedBookingCount: Int,
    ): PartgOverviewRowDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!PartgLedgerRules.modeAllowed(
                level = unit.level,
                mode = mode,
            )
        ) {
            throw PartyReportInvalidInputException("A summary of sub-units is always mode C")
        }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        val now = ServerClock.now()
        return transaction {
            if (unit.chapterId != null) {
                PartgReportStore.lockChapter(unit)
            } else {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .forUpdate()
                    .single()
            }
            val old = PartgLedgerLinkStore.modeOf(year = year, unit = unit)
            if (unit.level != PartgUnitLevel.SUBORDINATE && old != mode) {
                val count = PartgLedgerReader.postedCount(year = year, chapterId = PartgLedgerProposals.chapterOf(unit))
                if (count > 0 && confirmedBookingCount != count) throw PartyReportModeConfirmationRequiredException()
                val rowId = PartgLedgerLinkStore.setMode(year = year, unit = unit, mode = mode, actor = current.memberId, now = now)
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.PARTG_UNIT_MODE,
                    entityId = rowId,
                    action = AuditAction.UPDATE,
                    before = null,
                    after =
                        Json.encodeToString(
                            PartgModeAuditSnapshot.serializer(),
                            PartgModeAuditSnapshot(year = year, unitKey = unit.key(), old = old, new = mode, bookingCount = count),
                        ),
                    occurredAt = now,
                )
            }
            enrichOverview(year = year, rows = PartgReportStore.overview(year), actor = PartyReportActor.Federal(current))
                .single { it.unit.unitKey == unit.key() }
        }
    }

    override suspend fun proposeFromLedger(
        year: Int,
        unitKey: String,
    ): PartgLedgerProposalDto {
        val actor = requirePartyReportScopedAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!actor.allows(unit)) throw NotFoundException("Unit not found")
        return transaction {
            val mode = PartgLedgerLinkStore.modeOf(year = year, unit = unit)
            if (!PartgLedgerProposals.available(unit = unit, mode = mode)) throw PartyReportProposalNotAvailableException()
            val sheet = PartgReportStore.findSheet(year = year, unit = unit)
            val prior = PartgReportStore.findSheet(year = year - 1, unit = unit)
            val entered = sheet?.let { PartgReportStore.values(it.id) }.orEmpty()
            val sources = sheet?.let { PartgReportStore.valueSources(it.id) }.orEmpty()
            val proposal = PartgLedgerProposals.proposalFor(year = year, unit = unit)
            val rows =
                proposal.entries
                    .mapNotNull { (code, proposed) ->
                        val have = entered[code]
                        if (have == proposed || (have == null && proposed == 0L)) {
                            null
                        } else {
                            PartgLedgerProposalRowDto(
                                positionCode = code,
                                enteredCents = have,
                                enteredSource = if (have == null) null else sources[code],
                                proposedCents = proposed,
                                preselected = PartgLedgerRules.preselect(entered = have, source = sources[code], proposed = proposed),
                                transferable = PartgLedgerRules.transferable(proposed),
                            )
                        }
                    }.sortedBy { PartgPositions.find(it.positionCode)?.sortOrder ?: Int.MAX_VALUE }
            PartgLedgerProposalDto(
                year = year,
                unitKey = unit.key(),
                rows = rows,
                revision = sheet?.revision ?: 0L,
                priorRevision = prior?.revision ?: 0L,
                mode = mode,
                unmappedWithActivityCount = PartgLedgerProposals.unmappedWithActivity(year = year, unit = unit),
            )
        }
    }

    override suspend fun applyLedgerProposal(
        year: Int,
        unitKey: String,
        expectedRevision: Long,
        expectedPriorRevision: Long,
        picks: List<PartgProposalPickDto>,
    ): PartgSaveResultDto {
        val actor = requirePartyReportScopedAccess(call)
        val current = actor.current
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (!actor.allows(unit)) throw ForbiddenException()
        if (picks.size > PartgPositions.LEAF_COUNT) throw PartyReportInvalidInputException("Too many picks")
        val byCode = LinkedHashMap<String, Long>()
        picks.forEach { pick ->
            if (pick.positionCode !in PartgPositions.LEAF_CODES) throw PartyReportInvalidInputException("Not a typed-in position")
            if (byCode.put(pick.positionCode, pick.proposedCents) != null) throw PartyReportInvalidInputException("Duplicate pick")
        }
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgSheetWriter.writeLedgerProposal(
            current = current,
            year = year,
            unit = unit,
            expectedRevision = expectedRevision,
            expectedPriorRevision = expectedPriorRevision,
            picks = byCode,
            now = ServerClock.now(),
        )
    }

    // ---------------------------------------------------------------- R5 (V1.9.290)

    override suspend fun getConsolidation(year: Int): PartgConsolidationDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!findingsRateLimiter.checkAndRecord("partg:consolidation:${current.memberId}")) throw RateLimitedException()
        val snapshot = PartgReportSnapshotReader.read(listOf(year, year - 1))
        val pending = transaction { takeOverPending(year = year, units = snapshot.units) }
        return PartgConsolidationView.build(year = year, snapshot = snapshot, takeOverPending = pending)
    }

    override suspend fun saveIntraTransfer(
        year: Int,
        expectedRevision: Long,
        transfer: PartgIntraTransferInputDto,
    ): PartgConsolidationDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgConsolidationWriter.saveTransfer(
            current = current,
            year = year,
            expectedRevision = expectedRevision,
            transfer = transfer,
            now = ServerClock.now(),
        ) { consolidationWithin(year) }
    }

    override suspend fun deleteIntraTransfer(
        year: Int,
        expectedRevision: Long,
        transferId: String,
    ): PartgConsolidationDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgConsolidationWriter.deleteTransfer(
            current = current,
            year = year,
            expectedRevision = expectedRevision,
            transferId = transferId,
            now = ServerClock.now(),
        ) { consolidationWithin(year) }
    }

    override suspend fun createDonorCombination(
        year: Int,
        expectedRevision: Long,
        expectedAnnexRevisions: Map<String, Long>,
        largeDonorIds: List<String>,
    ): PartgConsolidationDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgConsolidationWriter.createCombination(
            current = current,
            year = year,
            expectedRevision = expectedRevision,
            expectedAnnexRevisions = expectedAnnexRevisions,
            largeDonorIds = largeDonorIds,
            now = ServerClock.now(),
        ) { consolidationWithin(year) }
    }

    override suspend fun dissolveDonorCombination(
        year: Int,
        expectedRevision: Long,
        combinationId: String,
    ): PartgConsolidationDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgConsolidationWriter.dissolveCombination(
            current = current,
            year = year,
            expectedRevision = expectedRevision,
            combinationId = combinationId,
            now = ServerClock.now(),
        ) { consolidationWithin(year) }
    }

    // ---------------------------------------------------------------- R6 (V1.9.291)
    // The federal gate, never the scoped one: a chapter treasurer reaches none of these. The work is in PartgVersionOperations.

    override suspend fun versionOverview(year: Int): PartgVersionOverviewDto {
        val current = requirePartyReportAccess(call)
        return versions.overview(current = current, year = year)
    }

    override suspend fun freezeVersion(
        year: Int,
        scopeKey: String,
        comment: String?,
    ): PartgVersionDto {
        val current = requirePartyReportAccess(call)
        return versions.freeze(current = current, year = year, scopeKey = scopeKey, comment = comment)
    }

    override suspend fun compareVersions(
        versionIdA: String,
        versionIdB: String,
    ): PartgVersionDiffDto {
        val current = requirePartyReportAccess(call)
        return versions.compare(current = current, versionIdA = versionIdA, versionIdB = versionIdB)
    }

    override suspend fun declareVersion(
        versionId: String,
        expectedContentHash: String,
        reviewed: Boolean,
    ): PartgVersionDto {
        val current = requirePartyReportAccess(call)
        return versions.declare(current = current, versionId = versionId, expectedHash = expectedContentHash, reviewed = reviewed)
    }

    override suspend fun finalizeVersion(
        versionId: String,
        typedVersionNumber: Int,
        expectedContentHash: String,
    ): PartgVersionDto {
        val current = requirePartyReportAccess(call)
        return versions.finalize(
            current = current,
            versionId = versionId,
            typedVersionNumber = typedVersionNumber,
            expectedHash = expectedContentHash,
        )
    }

    override suspend fun confirmDeadline(
        year: Int,
        deadline: String,
    ): PartgDeadlineDto {
        val current = requirePartyReportAccess(call)
        return versions.confirmDeadline(current = current, year = year, deadline = deadline)
    }

    override suspend fun previewVersionDeletion(olderThanYears: Int): PartgVersionDeletionPreviewDto {
        val current = requirePartyReportAccess(call)
        return versions.previewDeletion(current = current, olderThanYears = olderThanYears)
    }

    override suspend fun deleteOldVersions(
        olderThanYears: Int,
        expectedVersionIds: List<String>,
    ): Int {
        val current = requirePartyReportAccess(call)
        return versions.deleteOld(current = current, olderThanYears = olderThanYears, expectedVersionIds = expectedVersionIds)
    }

    // ---------------------------------------------------------------- R4b (V1.9.292)

    override suspend fun unitStatementPreview(
        year: Int,
        unitKey: String,
    ): PartgUnitStatementDto {
        val actor = requirePartyReportScopedAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey)
        // a chapter treasurer does not learn what exists: a key that is unparsable, FEDERAL, foreign or unknown is one and the same 404
        if (actor is PartyReportActor.ChapterTreasurer && (unit == null || !actor.allows(unit))) throw NotFoundException("Unit not found")
        if (unit == null) throw PartyReportInvalidInputException("Invalid unit")
        if (!findingsRateLimiter.checkAndRecord("partg:findings:${actor.current.memberId}")) throw RateLimitedException()
        val snapshot = PartgReportSnapshotReader.read(listOf(year, year - 1))
        val dto = snapshot.units.firstOrNull { it.unitKey == unit.key() } ?: throw NotFoundException("Unit not found")
        val built = PartgUnitPart.build(snapshot = snapshot, year = year, unit = unit)
        val page = built.document.units.single()
        return PartgUnitStatementDto(
            year = year,
            unit = dto,
            mode = built.mode,
            current = page.current,
            prior = page.prior,
            missingCurrent =
                built.document.completeness.units
                    .single()
                    .missingCurrent,
            findings = built.document.findings,
            postedEntryCount = built.postedEntryCount,
        )
    }

    // ---------------------------------------------------------------- R5b (V1.9.293)

    override suspend fun largeDonorProposal(year: Int): PartgDonorProposalDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!findingsRateLimiter.checkAndRecord("partg:donorproposal:${current.memberId}")) throw RateLimitedException()
        return transaction {
            val unit = PartgUnit.Federal
            if (!PartgLedgerProposals.available(unit = unit, mode = PartgLedgerLinkStore.modeOf(year = year, unit = unit))) {
                throw PartyReportProposalNotAvailableException()
            }
            donorProposalWithin(year)
        }
    }

    override suspend fun applyLargeDonorProposal(
        year: Int,
        expectedChecksum: String,
        picks: List<PartgDonorProposalPickDto>,
    ): PartgAnnexDto {
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!PartgDonorProposalRules.validChecksum(expectedChecksum)) throw PartyReportInvalidInputException("INVALID_CHECKSUM")
        if (picks.size > PartgDonorProposalRules.MAX_PICKS) throw PartyReportInvalidInputException("TOO_MANY_PICKS")
        if (picks.any { !PartgDonorProposalRules.validRef(it.ref) }) throw PartyReportInvalidInputException("INVALID_REF")
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
        return PartgAnnexWriter.applyDonorProposal(
            current = current,
            year = year,
            expectedChecksum = expectedChecksum,
            picks = picks,
            now = ServerClock.now(),
            recompute = { donorProposalWithin(year) },
        ) { annexDto(year = year, unit = PartgUnit.Federal) }
    }

    /** Must run inside a transaction. Reads the bookings, the master data and the entered rows of the FEDERAL unit; writes nothing. */
    private fun donorProposalWithin(year: Int): PartgDonorProposalDto {
        val annex = PartgAnnexStore.findAnnex(year = year, unit = PartgUnit.Federal)
        val donors = annex?.let { PartgAnnexStore.donors(it.id) }.orEmpty()
        val donations = PartgLedgerReader.federalDonationTotals(year = year)
        val candidates = PartgDonorProposals.candidateKeys(donations)
        val master =
            PartgDonorMasterData.load(
                memberIds = candidates.mapNotNull { it.donorMemberId },
                externalIds = candidates.mapNotNull { it.externalDonorId },
            )
        return PartgDonorProposals.compute(year = year, annex = annex, donors = donors, donations = donations, master = master)
    }

    /** Must run inside a transaction (the writer's): the caller returns exactly the state it wrote. */
    private fun consolidationWithin(year: Int): PartgConsolidationDto {
        val snapshot = PartgReportSnapshotReader.readWithin(listOf(year, year - 1))
        return PartgConsolidationView.build(
            year = year,
            snapshot = snapshot,
            takeOverPending = takeOverPending(year = year, units = snapshot.units),
        )
    }

    /** Must run inside a transaction. Read outside the revision fence on purpose: informative only, and posted bookings are immutable. */
    private fun takeOverPending(
        year: Int,
        units: List<PartgUnitDto>,
    ): Map<Int, Set<String>> {
        val keys = units.map { it.unitKey }
        return listOf(year, year - 1).associateWith { PartgLedgerProposals.takeOverPending(year = it, unitKeys = keys) }
    }

    private fun PartyReportActor.allowsKey(unitKey: String): Boolean = PartgUnit.parse(unitKey)?.let { allows(it) } == true

    private fun recordMappingAudit(
        current: network.lapis.cloud.server.security.CurrentMember,
        rowId: Uuid,
        action: AuditAction,
        before: PartgMappingAuditSnapshot?,
        after: PartgMappingAuditSnapshot?,
        now: kotlinx.datetime.LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.PARTG_ACCOUNT_MAPPING,
            entityId = rowId,
            action = action,
            before = before?.let { Json.encodeToString(PartgMappingAuditSnapshot.serializer(), it) },
            after = after?.let { Json.encodeToString(PartgMappingAuditSnapshot.serializer(), it) },
            occurredAt = now,
        )
    }

    /** Adds mode, booking count and editability to the overview rows and drops the units the actor may not see. */
    private fun enrichOverview(
        year: Int,
        rows: List<PartgOverviewRowDto>,
        actor: PartyReportActor,
    ): List<PartgOverviewRowDto> {
        val modes = PartgLedgerLinkStore.modes(year)
        val counts = PartgLedgerReader.postedCountsByChapter(year)
        return rows
            .filter { actor.allowsKey(it.unit.unitKey) }
            .map { row ->
                val unit = PartgUnit.parse(row.unit.unitKey)
                if (unit == null || unit.level == PartgUnitLevel.SUBORDINATE) {
                    row
                } else {
                    row.copy(
                        mode = modes[unit.key()] ?: PartgBookkeepingMode.C,
                        bookingCount = counts[PartgLedgerProposals.chapterOf(unit)] ?: 0,
                        modeEditable = actor is PartyReportActor.Federal,
                    )
                }
            }
    }

    private fun mappingView(
        year: Int,
        unit: PartgUnit,
        editable: Boolean,
    ): PartgAccountMappingViewDto {
        PartgReportStore.unitDto(unit)
        val accounts = PartgLedgerReader.accounts().sortedBy { it.number }
        val mappings = PartgLedgerLinkStore.mappings()
        val chapterId = PartgLedgerProposals.chapterOf(unit)
        val noBookings = unit.level == PartgUnitLevel.SUBORDINATE
        val flows = if (noBookings) emptyMap() else PartgLedgerReader.flowsByAccount(year = year, chapterId = chapterId)
        val balances =
            if (noBookings) {
                emptyMap()
            } else {
                PartgLedgerReader.balancesByAccount(asOf = kotlinx.datetime.LocalDate(year, 12, 31), chapterId = chapterId)
            }
        val templates = StandardPartyChart.accounts.associateBy { it.account.accountNumber }
        val rows =
            accounts.map { account ->
                val mapping = mappings[account.id]
                val section = PartgLedgerRules.sectionOf(account.type)
                val sums = if (section != null && PartgLedgerRules.isFlow(section)) flows[account.id] else balances[account.id]
                val balance =
                    if (section == null) {
                        null
                    } else {
                        PartgLedgerRules.signedAmountCents(
                            section = section,
                            debitCents = sums?.debitCents ?: 0L,
                            creditCents =
                                sums?.creditCents ?: 0L,
                        )
                    }
                val template = templates[account.number]?.takeIf { mapping == null && it.account.type == account.type }
                val templateSuggestion =
                    template?.suggestedPosition?.takeIf {
                        PartgLedgerRules.allowedTarget(accountType = account.type, positionCode = it)
                    }
                // V1.9.292 (R4b): a guess from the NAME, only for a federal caller, only for an unmapped account the standard chart says nothing
                // about (the exact chart hit always wins); the position is checked against the account type once more
                val byName =
                    if (editable && mapping == null && templateSuggestion == null && template?.suggestExcluded != true) {
                        PartyAccountNameHeuristic
                            .suggest(name = account.name, type = account.type)
                            ?.takeIf { PartgLedgerRules.allowedTarget(accountType = account.type, positionCode = it.position) }
                    } else {
                        null
                    }
                PartgAccountMappingRowDto(
                    accountId = account.id.toString(),
                    number = account.number,
                    name = account.name,
                    type = account.type,
                    active = account.active,
                    positionCode = mapping?.positionCode,
                    excluded = mapping?.excluded ?: false,
                    templateSuggestion = templateSuggestion,
                    templateSuggestsExcluded = template?.suggestExcluded ?: false,
                    balanceCents = balance,
                    postingCount = flows[account.id]?.postingCount ?: 0,
                    nameSuggestion = byName?.position,
                    nameSuggestionKeyword = byName?.keyword,
                )
            }
        val unmapped = rows.filter { it.positionCode == null && !it.excluded && (it.active || it.postingCount > 0) }
        return PartgAccountMappingViewDto(
            rows = rows,
            unmappedCount = unmapped.size,
            unmappedWithActivityCount = unmapped.count { it.postingCount > 0 },
            editable = editable,
            year = year,
            unitKey = unit.key(),
        )
    }

    /** Must run inside a transaction. */
    private fun annexDto(
        year: Int,
        unit: PartgUnit,
    ): PartgAnnexDto {
        val dto = PartgReportStore.unitDto(unit)
        val annex = PartgAnnexStore.findAnnex(year = year, unit = unit)
        val today = ServerClock.todayIn(OrganizationTimeZone.current())
        val enterpriseRows = annex?.let { PartgStatementStore.enterprises(it.id) }.orEmpty()
        // R1 is only READ here, never written. The check carries a status, never an amount.
        val r1Values = PartgReportStore.findSheet(year = year, unit = unit)?.let { PartgReportStore.values(it.id) }.orEmpty()
        return PartgAnnexDto(
            year = year,
            unit = dto,
            revision = annex?.revision ?: 0L,
            deductions = PartgDeduction.entries.associateWith { annex?.deductions?.get(it) },
            memberCount = annex?.memberCount,
            memberCountSuggestion = PartgMemberCountSuggestion.suggest(unit = unit, year = year, today = today, currentYear = today.year),
            donors =
                annex
                    ?.let { PartgAnnexStore.donors(it.id) }
                    .orEmpty()
                    .mapIndexed { index, d ->
                        PartgLargeDonorDto(
                            id = d.id.toString(),
                            rowNumber = index + 1,
                            personKind = d.personKind,
                            name = d.name,
                            street = d.street,
                            postalCode = d.postalCode,
                            city = d.city,
                            country = d.country,
                            contributionKind = d.contributionKind,
                            amountCents = d.amountCents,
                            origin = PartgDonorProposalRules.originOf(sourceAppliedAt = d.sourceAppliedAt, updatedAt = d.updatedAt),
                            sourceAppliedAt = d.sourceAppliedAt,
                        )
                    },
            sponsors =
                annex
                    ?.let { PartgStatementStore.sponsors(it.id) }
                    .orEmpty()
                    .mapIndexed { index, sp ->
                        PartgSponsorDto(
                            id = sp.id.toString(),
                            rowNumber = index + 1,
                            personKind = sp.personKind,
                            name = sp.name,
                            street = sp.street,
                            postalCode = sp.postalCode,
                            city = sp.city,
                            country = sp.country,
                            entries =
                                sp.entries.map {
                                    PartgSponsoringEntryDto(
                                        id = it.id.toString(),
                                        date = it.date,
                                        amountCents = it.amountCents,
                                        purpose = it.purpose,
                                    )
                                },
                            yearlySumCents = sp.yearlySumCents,
                            qualification = PartgStatementRules.qualification(sp.entries.map { it.amountCents }),
                        )
                    },
            youthOrgs =
                annex
                    ?.let { PartgStatementStore.youthOrgs(it.id) }
                    .orEmpty()
                    .mapIndexed { index, y ->
                        PartgYouthOrgDto(
                            id = y.id.toString(),
                            rowNumber = index + 1,
                            name = y.name,
                            relationship = y.relationship,
                            incomeCents = y.incomeCents,
                            expensesCents = y.expensesCents,
                        )
                    },
            enterprises =
                enterpriseRows.mapIndexed { index, e ->
                    PartgEnterpriseDto(
                        id = e.id.toString(),
                        rowNumber = index + 1,
                        name = e.name,
                        legalForm = e.legalForm,
                        seat = e.seat,
                        shareBasisPoints = e.shareBasisPoints,
                        incomeCents = e.incomeCents,
                        expensesCents = e.expensesCents,
                    )
                },
            enterpriseChecks =
                listOfNotNull(
                    enterpriseCheck(
                        positionCode = PartgEnterpriseRules.INCOME_POSITION,
                        figures = enterpriseRows.map { it.incomeCents },
                        r1Values = r1Values,
                    ),
                    enterpriseCheck(
                        positionCode = PartgEnterpriseRules.EXPENSE_POSITION,
                        figures = enterpriseRows.map { it.expensesCents },
                        r1Values = r1Values,
                    ),
                ),
            donorProposal =
                when {
                    unit !is PartgUnit.Federal -> PartgDonorProposalAvailability.NOT_APPLICABLE
                    PartgLedgerProposals.available(unit = unit, mode = PartgLedgerLinkStore.modeOf(year = year, unit = unit)) ->
                        PartgDonorProposalAvailability.AVAILABLE
                    else -> PartgDonorProposalAvailability.MODE_C
                },
        )
    }

    private fun enterpriseCheck(
        positionCode: String,
        figures: List<Long?>,
        r1Values: Map<String, Long>,
    ): PartgEnterpriseCheckDto? =
        PartgEnterpriseRules.check(figures = figures, positionCents = r1Values[positionCode])?.let {
            PartgEnterpriseCheckDto(positionCode = positionCode, status = it)
        }

    private fun notesDto(year: Int): PartgNotesDto {
        val sheet = PartgAnnexStore.findNotes(year)
        return PartgNotesDto(
            year = year,
            revision = sheet?.revision ?: 0L,
            notes = PartgAnnexStore.notes(sheet?.id),
            holdings = PartgAnnexStore.holdings(sheet?.id),
        )
    }

    private fun normalizeHint(hint: String?): String? {
        val trimmed = hint?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_HINT_LENGTH) throw PartyReportInvalidInputException("Hint too long")
        if (trimmed.any { it.isISOControl() && it != '\n' }) throw PartyReportInvalidInputException("Hint contains control characters")
        return trimmed
    }

    private companion object {
        const val MAX_CHANGES = 2 * 35
        const val MAX_MAPPING_CHANGES = 2000
        const val MAX_HINT_LENGTH = 500
        const val PARTG_MAX_CENTS = network.lapis.cloud.shared.domain.PARTG_MAX_AMOUNT_CENTS
    }
}
