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
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.dsgvo.nowUtc
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * The Auskunftsbuendel (Art. 15/20 DSGVO — every registered `PersonalDataContributor`'s full
 * export for one member) travels over this dedicated HTTP route, not Kilua RPC — same reasoning
 * as [registerDocumentRoutes]: the payload can grow large and Kilua RPC is tuned for small typed
 * payloads. [network.lapis.cloud.shared.rpc.IDsgvoService] only exposes the lightweight
 * `ExportManifestDto` (row counts per section) over RPC.
 *
 * Access control mirrors [network.lapis.cloud.server.rpc.DsgvoService]: the subject themselves,
 * or ADMIN — enforced here independently, not only in a UI. Every call writes one
 * [network.lapis.cloud.server.db.generated.DsgvoAuditLogTable] row (metadata/counts only, see that
 * table's KDoc), inside the same transaction as the read, so the log can never diverge from what
 * was actually exported.
 *
 * **Bewusst nicht umgesetzt in dieser Welle**: a `?format=zip` variant that also bundles the
 * subject's authored document *file bytes* (`DocumentPersonalData` only exports metadata about
 * them). See `docs/architecture/dsgvo.adoc` for the rationale — the metadata/structured-JSON
 * export already satisfies Art. 15/20 for every entity added so far; bundling arbitrary file
 * bytes safely (size caps, storage-key-only access, streaming) is left to a follow-up wave.
 */
fun Route.registerDsgvoRoutes() {
    get("/api/dsgvo/members/{id}/export") {
        val subjectId = runCatching { Uuid.parse(call.parameters["id"]!!) }.getOrNull()
        if (subjectId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid member id")
            return@get
        }
        val current =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (current.memberId != subjectId && current.role != AccountRole.ADMIN) {
            throw ForbiddenException("Nur das betroffene Mitglied selbst oder ADMIN duerfen diesen Export abrufen")
        }

        val bundle: JsonObject =
            transaction {
                val sections =
                    buildJsonObject {
                        PersonalDataRegistry.contributors.forEach { contributor ->
                            put(contributor.sectionKey, contributor.export(subjectId))
                        }
                    }
                DsgvoAuditLogTable.insert {
                    it[id] = Uuid.random()
                    it[occurredAt] = nowUtc()
                    it[actorMemberId] = current.memberId
                    it[actorRole] = current.role
                    it[action] = DsgvoAuditAction.EXPORT
                    it[DsgvoAuditLogTable.subjectMemberId] = subjectId
                    it[requestId] = null
                    it[outcomeSummary] = null
                    it[legalBasis] = "Art. 15/20 DSGVO"
                }
                buildJsonObject {
                    put("subjectMemberId", subjectId.toString())
                    put("generatedAt", nowUtc().toString())
                    put("sections", sections)
                }
            }

        call.respondText(Json.encodeToString(JsonObject.serializer(), bundle), contentType = ContentType.Application.Json)
    }
}
