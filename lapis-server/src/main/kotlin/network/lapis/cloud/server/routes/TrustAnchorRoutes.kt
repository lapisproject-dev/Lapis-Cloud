package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.TrustAnchorPoolStore
import network.lapis.cloud.server.federation.TrustAnchorStatements
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Spec-mandated OpenID Federation content type (RFC 9678 §5.1) -- a compact JWS, not JSON. */
private const val ENTITY_STATEMENT_JWT_CONTENT_TYPE = "application/entity-statement+jwt"

/**
 * V0.8.3 Trust-Anchor-Governance PUBLISHING routes -- the spec-shaped, pre-auth counterpart to
 * `network.lapis.cloud.shared.rpc.ITrustAnchorService`'s ADMIN-only RPC surface. Dedicated Ktor
 * routes, NOT Kilua RPC -- same "spec-mandated path, external payload shape (a bare compact JWT,
 * not JSON)" reasoning [registerFederationRoutes]/[registerOidcRoutes] already establish for their
 * own non-RPC surfaces.
 *
 * **Opt-in via non-empty pool**: this server's Trust-Anchor identity/signing key is provisioned
 * unconditionally at boot (see `network.lapis.cloud.server.federation.TrustAnchorSigningKeyProvisioner`),
 * but the Trust Anchor ROLE itself is only "active" once an ADMIN has added at least one pool
 * member. Both routes below 404 while the pool is empty -- deliberately no separate "is Trust
 * Anchor role enabled" config flag/table (see `26-trust-anchor.kuml.kts` file header), an empty
 * pool already means "vouches for nobody", which is functionally identical to "not acting as an
 * anchor at all" from any verifier's point of view.
 */
fun Route.registerTrustAnchorRoutes() {
    get("/.well-known/openid-federation") {
        val (poolIsEmpty, organizationName, entityConfig) =
            transaction {
                val poolEmpty = TrustAnchorPoolStore.listAll().isEmpty()
                val orgName =
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .singleOrNull()
                        ?.get(OrganizationSettingsTable.name) ?: "Lapis Cloud"
                val config = if (poolEmpty) null else TrustAnchorStatements.buildEntityConfiguration(orgName)
                Triple(poolEmpty, orgName, config)
            }
        if (poolIsEmpty) {
            call.respond(HttpStatusCode.NotFound, "This server is not acting as a Trust Anchor (empty pool)")
            return@get
        }
        if (entityConfig == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "Trust-Anchor signing key not yet provisioned")
            return@get
        }
        call.respondText(entityConfig, contentType = ContentType.parse(ENTITY_STATEMENT_JWT_CONTENT_TYPE))
    }

    get("/federation/trust-anchor/fetch") {
        val sub = call.request.queryParameters["sub"]
        if (sub.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing required 'sub' query parameter")
            return@get
        }
        val statement =
            transaction {
                if (TrustAnchorPoolStore.listAll().isEmpty()) return@transaction null
                TrustAnchorStatements.buildSubordinateStatement(sub)
            }
        if (statement == null) {
            call.respond(HttpStatusCode.NotFound, "No current Subordinate Statement for '$sub'")
            return@get
        }
        call.respondText(statement, contentType = ContentType.parse(ENTITY_STATEMENT_JWT_CONTENT_TYPE))
    }
}
