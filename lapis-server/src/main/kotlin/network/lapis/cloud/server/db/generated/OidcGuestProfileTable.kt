// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/** RP side: guest-specific profile fields that don't belong on the shared member/account tables -- 1:1 with a local `Member(status=GAST)` row. Carries personal data, covered by [network.lapis.cloud.server.dsgvo.OidcGuestPersonalData]. */
public object OidcGuestProfileTable : Table("oidc_guest_profile") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val pictureUrl: Column<String?> = varchar("picture_url", 2048).nullable()
    public val homeserverUrl: Column<String> = varchar("homeserver_url", 2048)
    public val membershipStatus: Column<String?> = varchar("membership_status", 100).nullable()
    public val grantedScope: Column<String> = varchar("granted_scope", 500)
    public val lastLoginAt: Column<LocalDateTime> = datetime("last_login_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
