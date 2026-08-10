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
 * `31-conference-breakout.kuml.kts` file header for the full fachlich model. APPEND-ONLY per
 * assignment -- a member reassigned from one breakout room to another gets a NEW row, [recalledAt]
 * stamped on the old one. The authorization-critical query ("does member X hold an OPEN assignment
 * to breakout room Y") is `WHERE breakoutRoomId = Y AND memberId = X AND recalledAt IS NULL` -- see
 * [network.lapis.cloud.server.rpc.ConferenceBreakoutService.requestBreakoutJoinToken].
 */
public object ConferenceBreakoutAssignmentTable : Table("conference_breakout_assignment") {
    public val id: Column<Uuid> = uuid("id")
    public val breakoutRoomId: Column<Uuid> = reference("breakout_room_id", ConferenceBreakoutRoomTable.id)
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val assignedAt: Column<LocalDateTime> = datetime("assigned_at")
    public val recalledAt: Column<LocalDateTime?> = datetime("recalled_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
