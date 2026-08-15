// Hand-written per ADR-0016 Option B (see 27-conference.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object ConferenceRoomTable : Table("conference_room") {
    public val id: Column<Uuid> = uuid("id")
    public val title: Column<String> = varchar("title", 200)
    public val description: Column<String> = varchar("description", 1000)
    public val livekitRoomName: Column<String> = varchar("livekit_room_name", 64).uniqueIndex()
    public val createdByMemberId: Column<Uuid> = reference("created_by_member_id", MemberTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val endedAt: Column<LocalDateTime?> = datetime("ended_at").nullable()
    public val maxParticipants: Column<Int> = integer("max_participants")

    /**
     * V1.0 Wave 5 "Föderations-Gastbeitritt" -- per-room opt-in, default FALSE. See
     * 27-conference.kuml.kts. `.default(false)` matches the SQL-level `DEFAULT FALSE`
     * (V1__baseline.sql) -- deliberate deviation from this codebase's usual "no Exposed-level
     * default, caller always writes explicitly" convention (e.g. OrganizationSettingsTable's own
     * booleans): unlike organization_settings (a seeded singleton, never freshly inserted by test
     * code), conference_room is inserted directly by numerous pre-existing test fixtures across
     * this codebase that have no reason to know about this new column -- an Exposed-level default
     * keeps them green without a mass edit, while ConferenceService.createRoom still writes the
     * value explicitly either way (input.allowFederationGuests, defaulting to false itself).
     */
    public val allowFederationGuests: Column<Boolean> = bool("allow_federation_guests").default(false)

    /**
     * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- see
     * 27-conference.kuml.kts. `null` means this room is not bound to any Sitzung (the default for
     * every existing and newly-created room). Set from INSIDE a running room via
     * IConferenceService.setRoomMeeting -- never at createRoom time, see that column's own kUML
     * comment.
     */
    public val meetingId: Column<Uuid?> = optReference("meeting_id", MeetingTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
