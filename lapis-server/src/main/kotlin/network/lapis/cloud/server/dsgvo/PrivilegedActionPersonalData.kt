package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.PrivilegedActionRequestTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- owns [PrivilegedActionRequestTable], the requests of one administrator against
 * another that a second administrator must approve.
 *
 * Like `member_email_change` this is a transient access-control artifact (which approval is pending), not an activity
 * record anybody must keep: the requests in which the erased member is the TARGET are hard-deleted in every
 * [ErasureMode]. In requests where the erased member was only the requester or the approver (the row belongs to the
 * target), the free-text `reason` is replaced by a placeholder -- the member rows stay (anonymized), so the foreign
 * keys remain valid. The hash-chained audit log carries neither reason nor token (see `PeerActionAuditFacts`).
 *
 * The export carries every row the subject is part of (Art. 15), the reason included because it is data about the
 * person -- never the token hash.
 */
object PrivilegedActionPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "privilegedActions"
    override val displayName = "Admin-Peer-Schutz (Freigabeanträge)"
    override val coveredTables = setOf(PrivilegedActionRequestTable)

    private const val ERASED_REASON = "[erased]"

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            put(
                "privilegedActionRequests",
                buildJsonArray {
                    PrivilegedActionRequestTable
                        .selectAll()
                        .where {
                            (PrivilegedActionRequestTable.targetMemberId eq memberId) or
                                (PrivilegedActionRequestTable.actorMemberId eq memberId) or
                                (PrivilegedActionRequestTable.approverMemberId eq memberId)
                        }.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[PrivilegedActionRequestTable.id].toString())
                                    put("action", row[PrivilegedActionRequestTable.action])
                                    put("subjectIsTarget", row[PrivilegedActionRequestTable.targetMemberId] == memberId)
                                    put("subjectIsRequester", row[PrivilegedActionRequestTable.actorMemberId] == memberId)
                                    put("subjectIsApprover", row[PrivilegedActionRequestTable.approverMemberId] == memberId)
                                    put("status", row[PrivilegedActionRequestTable.status])
                                    put("reason", row[PrivilegedActionRequestTable.reason])
                                    put("createdAt", row[PrivilegedActionRequestTable.createdAt].toString())
                                    put("expiresAt", row[PrivilegedActionRequestTable.expiresAt].toString())
                                    put("decidedAt", row[PrivilegedActionRequestTable.decidedAt]?.toString())
                                    put("resolvedAt", row[PrivilegedActionRequestTable.resolvedAt]?.toString())
                                },
                            )
                        }
                },
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        // Requests the subject made or approved against ANOTHER member stay (they belong to the target); only the free text goes.
        PrivilegedActionRequestTable.update(
            {
                (PrivilegedActionRequestTable.targetMemberId neq memberId) and
                    (
                        (PrivilegedActionRequestTable.actorMemberId eq memberId) or
                            (PrivilegedActionRequestTable.approverMemberId eq memberId)
                    )
            },
        ) { it[reason] = ERASED_REASON }
        val deleted = PrivilegedActionRequestTable.deleteWhere { PrivilegedActionRequestTable.targetMemberId eq memberId }
        return listOf(TableErasureOutcome(table = "privileged_action_request", rowsDeleted = deleted))
    }
}
