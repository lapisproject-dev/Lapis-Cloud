package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.crm.CrmContactPolicy
import network.lapis.cloud.server.crm.CrmContactStore
import network.lapis.cloud.server.crm.toCrmUuid
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.dsgvo.CrmPersonalData
import network.lapis.cloud.server.dsgvo.DataSubject
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactPageDto
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionDto
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.TableErasureOutcomeDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ICrmService
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val outcomeListSerializer = ListSerializer(TableErasureOutcomeDto.serializer())

private val CRM_READ_WRITE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- see [ICrmService] KDoc for the overall shape
 * (BOARD/ADMIN read/write, ADMIN-only erase). Every method resolves [network.lapis.cloud.server
 * .security.CurrentMember] exactly once via [resolveCurrentMember], same house rule the rest of the
 * `rpc` package follows.
 *
 * **Deliberately does NOT write to `audit_log_entry`** (the GoBD hash-chained ledger,
 * [network.lapis.cloud.server.audit.AuditLogRecorder]) -- see `38-crm.kuml.kts` file header "Why
 * NOT audit_log_entry". The only record of CRM mutations is this table's own rows plus, for
 * export/erase, a `dsgvo_audit_log` entry this class writes directly (mirrors
 * `network.lapis.cloud.server.routes.DsgvoRoutes`' own posture of writing that table without going
 * through `DsgvoService`).
 */
class CrmService(
    private val call: ApplicationCall,
    private val contactWriteRateLimiter: FederationInboxRateLimiter,
    private val interactionWriteRateLimiter: FederationInboxRateLimiter,
) : ICrmService {
    override suspend fun listContacts(
        filterType: CrmContactType?,
        onlyRetentionOverdue: Boolean,
        includeArchived: Boolean,
        limit: Int,
        offset: Int,
    ): CrmContactPageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        return transaction {
            CrmContactStore.list(
                filterType = filterType,
                onlyRetentionOverdue = onlyRetentionOverdue,
                includeArchived = includeArchived,
                limit = limit,
                offset = offset,
            )
        }
    }

    override suspend fun getContact(id: String): CrmContactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        return transaction { CrmContactStore.getOrThrow(id.toCrmUuid()) }
    }

    override suspend fun createContact(input: CrmContactInput): CrmContactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        requireWithinRate(limiter = contactWriteRateLimiter, memberId = current.memberId)
        CrmContactPolicy.validate(input = input, now = DbClock.nowLocalDateTime())
        return transaction { CrmContactStore.create(input = input, createdBy = current.memberId) }
    }

    override suspend fun updateContact(
        id: String,
        input: CrmContactInput,
    ): CrmContactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        requireWithinRate(limiter = contactWriteRateLimiter, memberId = current.memberId)
        CrmContactPolicy.validate(input = input, now = DbClock.nowLocalDateTime())
        return transaction { CrmContactStore.update(id = id.toCrmUuid(), input = input) }
    }

    override suspend fun archiveContact(id: String): CrmContactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        return transaction { CrmContactStore.setArchived(id = id.toCrmUuid(), archived = true) }
    }

    override suspend fun unarchiveContact(id: String): CrmContactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        return transaction { CrmContactStore.setArchived(id = id.toCrmUuid(), archived = false) }
    }

    override suspend fun withdrawConsent(id: String): CrmContactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        return transaction { CrmContactStore.withdrawConsent(id = id.toCrmUuid()) }
    }

    override suspend fun listInteractions(
        contactId: String,
        limit: Int,
        offset: Int,
    ): List<CrmInteractionDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        return transaction { CrmContactStore.listInteractions(contactId = contactId.toCrmUuid(), limit = limit, offset = offset) }
    }

    override suspend fun recordInteraction(input: CrmInteractionInput): CrmInteractionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CRM_READ_WRITE_ROLES)
        requireWithinRate(limiter = interactionWriteRateLimiter, memberId = current.memberId)
        CrmContactPolicy.validateInteraction(input = input, now = DbClock.nowLocalDateTime())
        return transaction { CrmContactStore.recordInteraction(input = input, recordedBy = current.memberId) }
    }

    /**
     * Role: ADMIN (enforced above the [CRM_READ_WRITE_ROLES] tier used by every other method here,
     * see [ICrmService.eraseContact] KDoc). Delegates to [CrmPersonalData] (the actual DELETE) and
     * writes exactly one `dsgvo_audit_log` row in the SAME transaction, so the log can never
     * diverge from what was actually erased -- same discipline
     * [network.lapis.cloud.server.routes.DsgvoRoutes] establishes for the member-export route.
     */
    override suspend fun eraseContact(id: String): List<TableErasureOutcomeDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val contactId = id.toCrmUuid()
        return transaction {
            CrmContactStore.getOrThrow(contactId)
            // mode is ignored on the CRM_CONTACT branch of CrmPersonalData.erase (real DELETE
            // regardless) -- ANONYMIZE passed here purely because ErasureMode has no "N/A" literal
            // and every erase() signature requires one, see that function's own KDoc.
            val outcomeDtos =
                CrmPersonalData
                    .erase(subject = DataSubject.CrmContact(contactId), mode = ErasureMode.ANONYMIZE)
                    .map { outcome ->
                        TableErasureOutcomeDto(
                            table = outcome.table,
                            rowsAnonymized = outcome.rowsAnonymized,
                            rowsDeleted = outcome.rowsDeleted,
                            rowsRetained = outcome.rowsRetained,
                            retentionReason = outcome.retentionReason,
                        )
                    }
            DsgvoAuditLogTable.insert {
                // Explicitly qualified (DsgvoAuditLogTable.id, not bare `id`) -- this method's own
                // `id: String` parameter would otherwise shadow the implicit Table-receiver column.
                it[DsgvoAuditLogTable.id] = Uuid.random()
                it[occurredAt] = DbClock.nowLocalDateTime()
                it[actorMemberId] = current.memberId
                it[actorRole] = current.role
                it[action] = DsgvoAuditAction.ERASURE_EXECUTED
                it[subjectMemberId] = contactId
                it[requestId] = null
                it[outcomeSummary] = if (outcomeDtos.isEmpty()) null else Json.encodeToString(outcomeListSerializer, outcomeDtos)
                it[legalBasis] = "Art. 17 DSGVO"
                it[subjectKind] = DsgvoSubjectKind.CRM_CONTACT
            }
            outcomeDtos
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }
}
