// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/** Issuer side: single-use authorization codes minted for OUR OWN local member (the subject) requesting a session at a remote RP. */
public object OidcAuthorizationCodeTable : Table("oidc_authorization_code") {
    public val id: Column<Uuid> = uuid("id")
    public val codeHash: Column<String> = varchar("code_hash", 64).uniqueIndex()
    public val clientRegistrationId: Column<Uuid> = reference("client_registration_id", OidcClientRegistrationTable.id)
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val redirectUri: Column<String> = varchar("redirect_uri", 2048)
    public val scope: Column<String> = varchar("scope", 500)
    public val codeChallenge: Column<String> = varchar("code_challenge", 128)
    public val nonce: Column<String?> = varchar("nonce", 255).nullable()
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
    public val consumedAt: Column<LocalDateTime?> = datetime("consumed_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
