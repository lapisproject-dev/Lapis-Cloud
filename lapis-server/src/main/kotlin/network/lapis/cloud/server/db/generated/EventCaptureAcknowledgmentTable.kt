// Welle V1.9.156 "Aufzeichnung in Veranstaltungsräumen" -- hand-edited, see 73-event-capture.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import kotlin.uuid.Uuid

public object EventCaptureAcknowledgmentTable : Table("event_capture_acknowledgment") {
    public val id: Column<Uuid> = uuid("id")
    public val eventId: Column<Uuid> = reference("event_id", EventTable.id)
    public val memberId: Column<Uuid?> = reference("member_id", MemberTable.id).nullable()
    public val guestKey: Column<String?> = varchar("guest_key", 64).nullable()
    public val consentVersion: Column<String> = varchar("consent_version", 50)
    public val consentSha256: Column<String> = varchar("consent_sha256", 64)
    public val acknowledgedOn: Column<LocalDate> = date("acknowledged_on")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
