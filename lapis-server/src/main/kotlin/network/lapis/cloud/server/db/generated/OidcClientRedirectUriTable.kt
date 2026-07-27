// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/** Every registered `redirect_uri` for an [OidcClientRegistrationTable] row -- authorize/token exact-match, open-redirect prevention. */
public object OidcClientRedirectUriTable : Table("oidc_client_redirect_uri") {
    public val id: Column<Uuid> = uuid("id")
    public val clientRegistrationId: Column<Uuid> = reference("client_registration_id", OidcClientRegistrationTable.id)
    public val redirectUri: Column<String> = varchar("redirect_uri", 2048)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
