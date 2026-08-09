// Hand-written per ADR-0016 Option B (see 27-conference.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceRole
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object ConferenceParticipationTable : Table("conference_participation") {
    public val id: Column<Uuid> = uuid("id")
    public val roomId: Column<Uuid> = reference("room_id", ConferenceRoomTable.id)
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val role: Column<ConferenceRole> = enumerationByName<ConferenceRole>("role", 11)
    public val joinedAt: Column<LocalDateTime> = datetime("joined_at")
    public val leftAt: Column<LocalDateTime?> = datetime("left_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
