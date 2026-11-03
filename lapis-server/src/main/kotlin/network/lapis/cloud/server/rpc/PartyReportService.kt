package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgAnnexWriter
import network.lapis.cloud.server.partg.PartgMemberCountSuggestion
import network.lapis.cloud.server.partg.PartgReportSnapshotReader
import network.lapis.cloud.server.partg.PartgReportStore
import network.lapis.cloud.server.partg.PartgSheetWriter
import network.lapis.cloud.server.partg.PartgYearRules
import network.lapis.cloud.server.partg.report.PartgPlausibility
import network.lapis.cloud.server.partg.requirePartyReportAccess
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesDto
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgPositionHintSnapshot
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgSaveResultDto
import network.lapis.cloud.shared.domain.PartgSheetDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.rpc.IPartyReportService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.225 -- see [IPartyReportService] and `docs/features/partg-report.adoc`. EVERY method starts with `requirePartyReportAccess(call)`
 * (pinned by `PartyReportAccessTripwireTest`). Writes: 60 per minute and member. Nothing is logged except counts; no amount, no hint text.
 */
class PartyReportService internal constructor(
    private val call: ApplicationCall,
    private val writeRateLimiter: FederationInboxRateLimiter,
    private val findingsRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30),
) : IPartyReportService {
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
        requirePartyReportAccess(call)
        return transaction { PartgReportStore.units() }
    }

    override suspend fun overview(year: Int): List<PartgOverviewRowDto> {
        requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        return transaction { PartgReportStore.overview(year) }
    }

    override suspend fun getSheet(
        year: Int,
        unitKey: String,
    ): PartgSheetDto {
        requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
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
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        val unit = PartgUnit.parse(unitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
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
        val current = requirePartyReportAccess(call)
        PartgYearRules.requireValid(year)
        if (!findingsRateLimiter.checkAndRecord("partg:findings:${current.memberId}")) throw RateLimitedException()
        val snapshot = PartgReportSnapshotReader.read(listOf(year, year - 1))
        return PartgPlausibility.check(
            year = year,
            units = snapshot.units,
            entered = snapshot.entered.values,
            annex = snapshot.annex,
            notes = snapshot.notes,
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
                        )
                    },
        )
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
        const val MAX_HINT_LENGTH = 500
        const val PARTG_MAX_CENTS = network.lapis.cloud.shared.domain.PARTG_MAX_AMOUNT_CENTS
    }
}
