package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.db.generated.ErasureRequestTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.dsgvo.TableErasureOutcome
import network.lapis.cloud.server.dsgvo.nowUtc
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.DsgvoAuditLogEntryDto
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureRequestDto
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.ExportManifestDto
import network.lapis.cloud.shared.domain.TableErasureOutcomeDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IDsgvoService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val outcomeListSerializer = ListSerializer(TableErasureOutcomeDto.serializer())

/**
 * See [IDsgvoService] KDoc for the overall design (registry-driven export/erasure, HTTP route
 * for the full export bundle). Every method here resolves [CurrentMember] exactly once via
 * [resolveCurrentMember] and checks subject-or-ADMIN / ADMIN-only exactly like the rest of the
 * `rpc` package (see [network.lapis.cloud.server.security.RequestContext] KDoc) — no bespoke
 * authorization logic.
 */
class DsgvoService(
    private val call: ApplicationCall,
) : IDsgvoService {
    override suspend fun exportManifest(memberId: String): ExportManifestDto {
        val current = resolveCurrentMember(call)
        val subjectId = memberId.toDsgvoUuid()
        requireSelfOrAdmin(current, subjectId)
        return transaction {
            val sectionCounts =
                PersonalDataRegistry.contributors.associate { contributor ->
                    contributor.sectionKey to contributor.export(subjectId).elementCount()
                }
            writeAuditLog(
                current,
                DsgvoAuditAction.EXPORT,
                subjectId,
                requestId = null,
                outcome = emptyList(),
                legalBasis = "Art. 15/20 DSGVO",
            )
            ExportManifestDto(
                subjectMemberId = subjectId.toString(),
                generatedAt = nowUtc(),
                sectionCounts = sectionCounts,
            )
        }
    }

    override suspend fun requestErasure(
        subjectMemberId: String,
        reason: String,
        mode: ErasureMode,
    ): ErasureRequestDto {
        val current = resolveCurrentMember(call)
        val subjectId = subjectMemberId.toDsgvoUuid()
        requireSelfOrAdmin(current, subjectId)
        return transaction {
            val id = Uuid.random()
            ErasureRequestTable.insert {
                it[ErasureRequestTable.id] = id
                it[ErasureRequestTable.subjectMemberId] = subjectId
                it[requestedAt] = nowUtc()
                it[requestedBy] = current.memberId
                it[ErasureRequestTable.reason] = reason
                it[ErasureRequestTable.mode] = mode
                it[status] = ErasureStatus.REQUESTED
                it[legalHold] = false
            }
            writeAuditLog(current, DsgvoAuditAction.ERASURE_REQUESTED, subjectId, id, emptyList(), "Art. 17 DSGVO")
            loadErasureRequest(id)
        }
    }

    override suspend fun listErasureRequests(status: ErasureStatus?): List<ErasureRequestDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val baseQuery = erasureRequestJoin().selectAll()
            val rows = if (status != null) baseQuery.where { ErasureRequestTable.status eq status } else baseQuery
            rows.map { it.toErasureRequestDto() }
        }
    }

    override suspend fun decideErasure(
        requestId: String,
        approve: Boolean,
        note: String?,
    ): ErasureRequestDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = requestId.toDsgvoUuid()
        return transaction {
            val row =
                ErasureRequestTable.selectAll().where { ErasureRequestTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("ErasureRequest $requestId not found")
            if (row[ErasureRequestTable.status] != ErasureStatus.REQUESTED) {
                throw ConflictException("ErasureRequest $requestId is not in REQUESTED state")
            }
            val newStatus = if (approve) ErasureStatus.APPROVED else ErasureStatus.REJECTED
            ErasureRequestTable.update({ ErasureRequestTable.id eq id }) {
                it[status] = newStatus
                it[decidedBy] = current.memberId
                it[decidedAt] = nowUtc()
                it[decisionNote] = note
            }
            val action = if (approve) DsgvoAuditAction.ERASURE_APPROVED else DsgvoAuditAction.ERASURE_REJECTED
            writeAuditLog(current, action, row[ErasureRequestTable.subjectMemberId], id, emptyList(), "Art. 17 DSGVO")
            loadErasureRequest(id)
        }
    }

    override suspend fun executeErasure(requestId: String): ErasureRequestDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = requestId.toDsgvoUuid()
        return transaction {
            val row =
                ErasureRequestTable.selectAll().where { ErasureRequestTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("ErasureRequest $requestId not found")
            if (row[ErasureRequestTable.status] != ErasureStatus.APPROVED) {
                throw ConflictException("ErasureRequest $requestId is not APPROVED")
            }
            val subjectId = row[ErasureRequestTable.subjectMemberId]
            val mode = row[ErasureRequestTable.mode]
            val outcomeDtos = PersonalDataRegistry.contributors.flatMap { it.erase(subjectId, mode) }.map { it.toDto() }
            ErasureRequestTable.update({ ErasureRequestTable.id eq id }) {
                it[status] = ErasureStatus.COMPLETED
                it[executedAt] = nowUtc()
                it[outcomeSummary] = Json.encodeToString(outcomeListSerializer, outcomeDtos)
            }
            writeAuditLog(current, DsgvoAuditAction.ERASURE_EXECUTED, subjectId, id, outcomeDtos, "Art. 17 DSGVO")
            loadErasureRequest(id)
        }
    }

    override suspend fun listAuditLog(subjectMemberId: String?): List<DsgvoAuditLogEntryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val baseQuery = DsgvoAuditLogTable.selectAll()
            val rows =
                if (subjectMemberId != null) {
                    baseQuery.where { DsgvoAuditLogTable.subjectMemberId eq subjectMemberId.toDsgvoUuid() }
                } else {
                    baseQuery
                }
            rows.map { it.toAuditLogEntryDto() }
        }
    }

    private fun requireSelfOrAdmin(
        current: CurrentMember,
        subjectId: Uuid,
    ) {
        if (current.memberId != subjectId && current.role != AccountRole.ADMIN) {
            throw ForbiddenException("Nur das betroffene Mitglied selbst oder ADMIN duerfen diese DSGVO-Operation ausfuehren")
        }
    }

    private fun writeAuditLog(
        actor: CurrentMember,
        action: DsgvoAuditAction,
        subjectMemberId: Uuid,
        requestId: Uuid?,
        outcome: List<TableErasureOutcomeDto>,
        legalBasis: String?,
    ) {
        DsgvoAuditLogTable.insert {
            it[id] = Uuid.random()
            it[occurredAt] = nowUtc()
            it[actorMemberId] = actor.memberId
            it[actorRole] = actor.role
            it[DsgvoAuditLogTable.action] = action
            it[DsgvoAuditLogTable.subjectMemberId] = subjectMemberId
            it[DsgvoAuditLogTable.requestId] = requestId
            it[outcomeSummary] = if (outcome.isEmpty()) null else Json.encodeToString(outcomeListSerializer, outcome)
            it[DsgvoAuditLogTable.legalBasis] = legalBasis
        }
    }

    private fun loadErasureRequest(id: Uuid): ErasureRequestDto =
        erasureRequestJoin()
            .selectAll()
            .where { ErasureRequestTable.id eq id }
            .single()
            .toErasureRequestDto()

    /**
     * Explicit join, not `ErasureRequestTable innerJoin MemberTable`: [ErasureRequestTable] has
     * three separate FKs to [MemberTable] (`subject_member_id`/`requested_by`/`decided_by`), so
     * Exposed's implicit FK-based join resolution can't tell which path to use and throws
     * `IllegalStateException: ... multiple primary key <-> foreign key references` — same
     * disambiguation issue `ContributionService.contributionJoin` documents. `subjectMemberId` is
     * the one [toErasureRequestDto] actually needs (`subjectDisplayName`).
     */
    private fun erasureRequestJoin() =
        ErasureRequestTable.join(MemberTable, JoinType.INNER, ErasureRequestTable.subjectMemberId, MemberTable.id)
}

private fun String.toDsgvoUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun TableErasureOutcome.toDto() =
    TableErasureOutcomeDto(
        table = table,
        rowsAnonymized = rowsAnonymized,
        rowsDeleted = rowsDeleted,
        rowsRetained = rowsRetained,
        retentionReason = retentionReason,
    )

private fun JsonElement.elementCount(): Int =
    when (this) {
        is JsonArray -> size
        is JsonObject -> if (isEmpty()) 0 else 1
        else -> 1
    }

private fun ResultRow.toErasureRequestDto(): ErasureRequestDto =
    ErasureRequestDto(
        id = this[ErasureRequestTable.id].toString(),
        subjectMemberId = this[ErasureRequestTable.subjectMemberId].toString(),
        subjectDisplayName = this[MemberTable.displayName],
        requestedAt = this[ErasureRequestTable.requestedAt],
        requestedBy = this[ErasureRequestTable.requestedBy].toString(),
        reason = this[ErasureRequestTable.reason],
        mode = this[ErasureRequestTable.mode],
        status = this[ErasureRequestTable.status],
        decidedBy = this[ErasureRequestTable.decidedBy]?.toString(),
        decidedAt = this[ErasureRequestTable.decidedAt],
        decisionNote = this[ErasureRequestTable.decisionNote],
        executedAt = this[ErasureRequestTable.executedAt],
        legalHold = this[ErasureRequestTable.legalHold],
        outcome = this[ErasureRequestTable.outcomeSummary]?.let { Json.decodeFromString(outcomeListSerializer, it) } ?: emptyList(),
    )

private fun ResultRow.toAuditLogEntryDto(): DsgvoAuditLogEntryDto =
    DsgvoAuditLogEntryDto(
        id = this[DsgvoAuditLogTable.id].toString(),
        occurredAt = this[DsgvoAuditLogTable.occurredAt],
        actorMemberId = this[DsgvoAuditLogTable.actorMemberId]?.toString(),
        actorRole = this[DsgvoAuditLogTable.actorRole],
        action = this[DsgvoAuditLogTable.action],
        subjectMemberId = this[DsgvoAuditLogTable.subjectMemberId].toString(),
        requestId = this[DsgvoAuditLogTable.requestId]?.toString(),
        outcome = this[DsgvoAuditLogTable.outcomeSummary]?.let { Json.decodeFromString(outcomeListSerializer, it) } ?: emptyList(),
        legalBasis = this[DsgvoAuditLogTable.legalBasis],
    )
