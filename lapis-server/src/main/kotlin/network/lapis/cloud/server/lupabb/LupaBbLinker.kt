package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * V1.9.226 -- the manual decision of an ambiguous record: the ADMIN links LupaBB person [PlanRow.personNumber] to ONE of the candidates the
 * comparison offered. Sets ONLY `member.lupabb_person_number` (under the member row lock); the data of the record is written by the NEXT sync, which
 * then finds the member by its number. Refuses a candidate that was not offered for this row, an anonymised member and a member that is linked
 * already; a person number that became taken meanwhile ends in [ConflictException] (unique violation under a savepoint).
 */
internal object LupaBbLinker {
    /** Inside a transaction. */
    fun link(
        row: PlanRow,
        candidate: PlanCandidate,
        actor: CurrentMember,
        now: LocalDateTime,
    ) {
        val locked =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq candidate.memberId }
                .forMemberUpdate()
                .singleOrNull()
                ?: throw NotFoundException("Member not found")
        if (locked[MemberTable.anonymizedAt] != null) throw ConflictException("Member has been anonymized and can no longer be edited")
        if (locked[MemberTable.lupabbPersonNumber] != null) throw ConflictException("The member is linked to LupaBB already")
        try {
            withSavepoint(name = "lupabb_link") {
                MemberTable.update({ MemberTable.id eq candidate.memberId }) { it[lupabbPersonNumber] = row.personNumber }
            }
        } catch (e: Exception) {
            if (e.isUniqueViolation()) throw ConflictException("This LupaBB person is linked to another member already")
            throw e
        }
        val before =
            MemberChangeSnapshot(displayNameChanged = false, emailChanged = false, status = locked[MemberTable.status], role = null)
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.MEMBER,
            entityId = candidate.memberId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
            after =
                Json.encodeToString(
                    MemberChangeSnapshot.serializer(),
                    before.copy(lupabbFields = listOf(LupaBbField.PERSON_NUMBER.name), lupabbLinked = true),
                ),
            occurredAt = now,
        )
    }
}
