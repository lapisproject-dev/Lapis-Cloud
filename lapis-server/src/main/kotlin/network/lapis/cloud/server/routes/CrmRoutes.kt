package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.crm.CrmContactStore
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.dsgvo.CrmPersonalData
import network.lapis.cloud.server.dsgvo.DataSubject
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- the Auskunftsbuendel (Art. 15/20 DSGVO full
 * export) for ONE `crm_contact`, structurally 1:1 to [registerDsgvoRoutes]'s own member-export
 * route: dedicated HTTP route (not Kilua RPC, same "can grow large" reasoning), access control
 * enforced independently of the UI (BOARD/ADMIN -- see [ICrmService] KDoc's role table; wider than
 * the member route's "subject-or-ADMIN" because a CRM contact has no login of their own to be "the
 * subject" through), one `dsgvo_audit_log` row per call in the SAME transaction as the read.
 */
fun Route.registerCrmRoutes() {
    get("/api/dsgvo/crm-contacts/{id}/export") {
        val contactId = runCatching { Uuid.parse(call.parameters["id"]!!) }.getOrNull()
        if (contactId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid crm-contact id")
            return@get
        }
        val current =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (current.role != AccountRole.BOARD && current.role != AccountRole.ADMIN) {
            call.respond(HttpStatusCode.Forbidden)
            return@get
        }

        val bundle: JsonObject? =
            transaction {
                if (CrmContactStore.getOrNull(contactId) == null) return@transaction null
                val export = CrmPersonalData.export(DataSubject.CrmContact(contactId))
                DsgvoAuditLogTable.insert {
                    it[id] = Uuid.random()
                    it[occurredAt] = DbClock.nowLocalDateTime()
                    it[actorMemberId] = current.memberId
                    it[actorRole] = current.role
                    it[action] = DsgvoAuditAction.EXPORT
                    it[subjectMemberId] = contactId
                    it[requestId] = null
                    it[outcomeSummary] = null
                    it[legalBasis] = "Art. 15/20 DSGVO"
                    it[subjectKind] = DsgvoSubjectKind.CRM_CONTACT
                }
                buildJsonObject {
                    put("subjectId", contactId.toString())
                    put("generatedAt", DbClock.nowLocalDateTime().toString())
                    put("crm", export)
                }
            }
        if (bundle == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(Json.encodeToString(JsonObject.serializer(), bundle), contentType = ContentType.Application.Json)
    }
}
