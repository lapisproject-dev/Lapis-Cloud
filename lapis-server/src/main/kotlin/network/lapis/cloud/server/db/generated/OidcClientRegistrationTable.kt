// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/** Issuer side: other Lapis-Cloud instances that have Dynamic-Client-Registered against US as their OAuth client. */
public object OidcClientRegistrationTable : Table("oidc_client_registration") {
    public val id: Column<Uuid> = uuid("id")
    public val clientId: Column<String> = varchar("client_id", 64).uniqueIndex()
    public val clientSecretHash: Column<String> = varchar("client_secret_hash", 64)
    public val clientName: Column<String> = varchar("client_name", 200)
    public val backchannelLogoutUri: Column<String?> = varchar("backchannel_logout_uri", 2048).nullable()
    public val createdAt: Column<LocalDateTime> = datetime("created_at")

    // Welle V1.8.1 MCP-Server -- "client_secret_post" (default, every pre-existing row) or "none"
    // (a public client, PKCE-only -- see OidcRedirectUriMatcher/McpConfig KDoc).
    public val tokenEndpointAuthMethod: Column<String> = varchar("token_endpoint_auth_method", 40).default("client_secret_post")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
