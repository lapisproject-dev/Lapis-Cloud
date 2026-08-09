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

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
