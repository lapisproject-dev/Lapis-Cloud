// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/** RP side: short-lived, single-use PKCE/state/nonce scratch state for one in-flight guest-login attempt -- NO member FK (resolved to a guest member only after successful verification). */
public object OidcRpLoginAttemptTable : Table("oidc_rp_login_attempt") {
    public val id: Column<Uuid> = uuid("id")
    public val stateHash: Column<String> = varchar("state_hash", 64).uniqueIndex()
    public val homeServerRegistrationId: Column<Uuid> = reference("home_server_registration_id", OidcHomeServerRegistrationTable.id)
    public val codeVerifier: Column<String> = varchar("code_verifier", 128)
    public val nonce: Column<String> = varchar("nonce", 255)
    public val redirectUri: Column<String> = varchar("redirect_uri", 2048)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
    public val consumedAt: Column<LocalDateTime?> = datetime("consumed_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
