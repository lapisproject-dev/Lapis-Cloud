package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.PartgReportStore
import network.lapis.cloud.server.partg.PartgSheetWriter
import network.lapis.cloud.server.partg.PartgYearRules
import network.lapis.cloud.server.partg.requirePartyReportAccess
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
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
