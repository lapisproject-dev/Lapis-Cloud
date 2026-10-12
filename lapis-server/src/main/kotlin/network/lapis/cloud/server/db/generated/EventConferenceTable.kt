// Hand-written per ADR-0016 Option B (see 67-event-online.kuml.kts) -- not codegen-emitted.
// Welle V1.9.113 "Online-Teilnahme an Veranstaltungen".

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/** Lifecycle of an event's conference -- see [EventConferenceTable] and `EventConferenceProvisioner`. */
public object EventConferenceStatus {
    public const val PENDING: String = "PENDING"
    public const val ACTIVE: String = "ACTIVE"
    public const val ENDED: String = "ENDED"
    public const val FAILED: String = "FAILED"
}

public object EventConferenceTable : Table("event_conference") {
    public val eventId: Column<Uuid> = reference("event_id", EventTable.id)
    public val conferenceRoomId: Column<Uuid?> = optReference("conference_room_id", ConferenceRoomTable.id)
    public val livekitRoomName: Column<String?> = varchar("livekit_room_name", 64).nullable()
    public val status: Column<String> = varchar("status", 16)
    public val claimToken: Column<Uuid> = uuid("claim_token")
    public val claimedAt: Column<LocalDateTime> = datetime("claimed_at")
    public val windowOpensAt: Column<LocalDateTime> = datetime("window_opens_at")
    public val windowClosesAt: Column<LocalDateTime> = datetime("window_closes_at")
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val endedAt: Column<LocalDateTime?> = datetime("ended_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(eventId)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
