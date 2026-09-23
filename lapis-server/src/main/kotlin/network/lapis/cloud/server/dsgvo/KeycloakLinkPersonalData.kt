package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.7.1b "Keycloak als externe Benutzerverwaltung" -- owns [KeycloakAccountLinkTable], the one
 * table this wave adds with a `member_id` FK (`keycloak_login_attempt` carries no member FK by
 * design, same pre-auth-scratch-state treatment `oidc_rp_login_attempt` already gets, see
 * `OidcGuestPersonalData` KDoc). Modelled directly on that contributor's shape.
 */
object KeycloakLinkPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "keycloak_login"
    override val displayName = "Keycloak-Anmeldung"
    override val coveredTables = setOf(KeycloakAccountLinkTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val row = KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq memberId }.singleOrNull()
            if (row != null) {
                put(
                    "keycloakLink",
                    buildJsonObject {
                        put("keycloakIssuer", row[KeycloakAccountLinkTable.keycloakIssuer])
                        put("linkedAt", row[KeycloakAccountLinkTable.linkedAt].toString())
                        put("lastLoginAt", row[KeycloakAccountLinkTable.lastLoginAt]?.toString())
                        // keycloakSubject deliberately omitted from export -- an opaque foreign
                        // identifier, not meaningful "our own" personal data beyond what Keycloak
                        // itself already holds about this account.
                    },
                )
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = KeycloakAccountLinkTable.deleteWhere { KeycloakAccountLinkTable.memberId eq memberId }
        return listOf(TableErasureOutcome(table = "keycloak_account_link", rowsDeleted = deleted))
    }
}
