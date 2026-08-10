// Hand-written per ADR-0016 Option B (see 30-conference-guest-access.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object ConferenceGuestConsentAcknowledgmentTable : Table("conference_guest_consent_acknowledgment") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val roomId: Column<Uuid> = reference("room_id", ConferenceRoomTable.id)
    public val acknowledgedAt: Column<LocalDateTime> = datetime("acknowledged_at")
    public val consentVersion: Column<String> = varchar("consent_version", 50)
    public val consentSha256: Column<String> = varchar("consent_sha256", 64)
    public val homeserverUrl: Column<String> = varchar("homeserver_url", 2048)
    public val organizationName: Column<String> = varchar("organization_name", 300)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
