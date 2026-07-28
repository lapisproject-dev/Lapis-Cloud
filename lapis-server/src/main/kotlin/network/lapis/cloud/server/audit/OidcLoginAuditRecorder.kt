package network.lapis.cloud.server.audit

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.shared.domain.OidcLoginEventType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Forensic, non-hash-chained login/logout audit trail for V0.8.2 OIDC guest access -- writes
 * [OidcGuestLoginEventTable] rows. Deliberately NOT [network.lapis.cloud.server.audit.AuditLogRecorder]
 * (the GoBD hash-chained `audit_log_entry` table) -- `AuditEntityType`'s literal set is explicitly,
 * deliberately bounded to GoBD financial/legal scope (its own KDoc: "EXPLICITLY OUT OF SCOPE: ...
 * Member CRUD"), the exact same reasoning V0.8.1's `federation_inbox_delivery_log` already
 * established for its own forensic log. Called from every branch (success AND every distinct
 * failure reason) of `network.lapis.cloud.server.routes.OidcRoutes`' Issuer and RP handlers.
 */
object OidcLoginAuditRecorder {
    fun record(
        eventType: OidcLoginEventType,
        memberId: Uuid? = null,
        remoteParty: String? = null,
        reason: String? = null,
    ) {
        val now: LocalDateTime = DbClock.nowLocalDateTime()
        transaction {
            OidcGuestLoginEventTable.insert {
                it[id] = Uuid.random()
                it[occurredAt] = now
                it[OidcGuestLoginEventTable.eventType] = eventType
                it[OidcGuestLoginEventTable.memberId] = memberId
                it[OidcGuestLoginEventTable.remoteParty] = remoteParty?.take(2048)
                it[OidcGuestLoginEventTable.reason] = reason?.take(255)
            }
        }
    }
}
