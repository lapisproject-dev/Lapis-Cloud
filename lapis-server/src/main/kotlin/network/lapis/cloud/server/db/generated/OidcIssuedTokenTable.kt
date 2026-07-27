// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/** Issuer side: access+refresh token pair issued to one RP for one of our own local members. */
public object OidcIssuedTokenTable : Table("oidc_issued_token") {
    public val id: Column<Uuid> = uuid("id")
    public val clientRegistrationId: Column<Uuid> = reference("client_registration_id", OidcClientRegistrationTable.id)
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val accessTokenHash: Column<String> = varchar("access_token_hash", 64).uniqueIndex()
    public val refreshTokenHash: Column<String> = varchar("refresh_token_hash", 64).uniqueIndex()
    public val scope: Column<String> = varchar("scope", 500)
    public val issuedAt: Column<LocalDateTime> = datetime("issued_at")
    public val accessExpiresAt: Column<LocalDateTime> = datetime("access_expires_at")
    public val refreshExpiresAt: Column<LocalDateTime> = datetime("refresh_expires_at")
    public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
