// Hand-written per ADR-0016 Option B -- see V45__keycloak_login.sql / KeycloakAccountLinker KDoc.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A confirmed 1:1 link between a local [MemberTable] row and an external Keycloak identity
 * `(keycloak_issuer, keycloak_subject)`. The subject is authoritative once linked -- a later email
 * change on the Keycloak side never re-points an existing link (see
 * `network.lapis.cloud.server.keycloak.KeycloakAccountLinker` KDoc).
 *
 * [linkedBy] is `NULL` for an auto-link (the common case: a Keycloak login whose email matched
 * exactly one, not-yet-linked local member) and a real member id for a future admin-initiated
 * manual link -- see the vault spec "Keycloak Externe Benutzerverwaltung.md" decision 5.
 *
 * The composite unique index `(keycloak_issuer, keycloak_subject)` cannot be expressed by
 * Exposed's single-column `uniqueIndex()` -- it is created by the Flyway migration directly (see
 * `V45__keycloak_login.sql`) and verified structurally by `KeycloakLoginSchemaDriftTest`, same
 * treatment `AccountTable.oidcIssuer` KDoc documents for its own composite pair.
 */
public object KeycloakAccountLinkTable : Table("keycloak_account_link") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id).uniqueIndex()
    public val keycloakIssuer: Column<String> = varchar("keycloak_issuer", 2048)
    public val keycloakSubject: Column<String> = varchar("keycloak_subject", 255)
    public val linkedAt: Column<LocalDateTime> = datetime("linked_at")
    public val linkedBy: Column<Uuid?> = uuid("linked_by").nullable()
    public val lastLoginAt: Column<LocalDateTime?> = datetime("last_login_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
