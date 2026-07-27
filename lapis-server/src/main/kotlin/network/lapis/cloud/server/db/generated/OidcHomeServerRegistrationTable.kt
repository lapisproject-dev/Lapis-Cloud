// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/** RP side: this server's own DCR client registration against a guest's claimed home server -- one row per distinct home-server issuer we have ever registered against. */
public object OidcHomeServerRegistrationTable : Table("oidc_home_server_registration") {
    public val id: Column<Uuid> = uuid("id")
    public val issuerUrl: Column<String> = varchar("issuer_url", 2048).uniqueIndex()
    public val authorizationEndpoint: Column<String> = varchar("authorization_endpoint", 2048)
    public val tokenEndpoint: Column<String> = varchar("token_endpoint", 2048)
    public val jwksUri: Column<String> = varchar("jwks_uri", 2048)
    public val clientId: Column<String> = varchar("client_id", 200)

    // Genuinely round-trippable secret (needed at every token-exchange/refresh call against the
    // home server) -- same DB-is-the-trust-boundary posture as FederationActorKeyTable.privateKeyPem.
    public val clientSecret: Column<String> = varchar("client_secret", 500)
    public val registeredAt: Column<LocalDateTime> = datetime("registered_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
