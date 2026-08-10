// Hand-written per ADR-0016 Option B (see 27-conference.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 6 "Breakout-Räume" -- see
 * `31-conference-breakout.kuml.kts` file header for the full fachlich model. At most one open batch
 * (`closedAt IS NULL`) per [parentRoomId] at a time -- enforced in
 * [network.lapis.cloud.server.rpc.ConferenceBreakoutService], never at the DB level.
 */
public object ConferenceBreakoutRoomTable : Table("conference_breakout_room") {
    public val id: Column<Uuid> = uuid("id")
    public val parentRoomId: Column<Uuid> = reference("parent_room_id", ConferenceRoomTable.id)
    public val label: Column<String> = varchar("label", 120)
    public val livekitRoomName: Column<String> = varchar("livekit_room_name", 64).uniqueIndex()
    public val createdByMemberId: Column<Uuid> = reference("created_by_member_id", MemberTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val closedAt: Column<LocalDateTime?> = datetime("closed_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
