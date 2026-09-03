package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [SessionTable] (V0.7.1 Authentifizierung) -- the only member-FK-bearing table the login-
 * session domain adds (`member_id`). See `network.lapis.cloud.server.security.SessionStore` KDoc
 * for the write path.
 *
 * **No retention duty, unlike [AuditLogPersonalData]/[BackupOperationPersonalData]'s deliberate
 * "kept forever" accountability records** -- a session row is purely an access-control artifact
 * (which device is currently allowed in), not an activity record anyone has a legal or
 * organizational interest in keeping after the member is erased. Hard-deleted regardless of
 * [ErasureMode], same "no retention duty" treatment [CommunicationPersonalData] already applies
 * to `mailing_list_subscription`/`mailing_delivery_log`.
 *
 * [export] deliberately omits [SessionTable.tokenHash] -- a one-way hash of a bearer secret, not
 * meaningful personal data on its own, and exporting it (even hashed) serves no purpose for the
 * data subject while adding a security-adjacent value to the export payload for no benefit.
 */
object SessionPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "sessions"
    override val displayName = "Login-Sitzungen"
    override val coveredTables = setOf(SessionTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonArray {
            SessionTable
                .selectAll()
                .where { SessionTable.memberId eq memberId }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[SessionTable.id].toString())
                            put("createdAt", row[SessionTable.createdAt].toString())
                            put("expiresAt", row[SessionTable.expiresAt].toString())
                            put("lastUsedAt", row[SessionTable.lastUsedAt]?.toString())
                            put("revokedAt", row[SessionTable.revokedAt]?.toString())
                        },
                    )
                }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = SessionTable.deleteWhere { SessionTable.memberId eq memberId }
        return listOf(TableErasureOutcome(table = "session", rowsDeleted = deleted))
    }
}
