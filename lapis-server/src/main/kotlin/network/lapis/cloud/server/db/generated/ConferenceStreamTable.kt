// Hand-written per ADR-0016 Option B (see 29-conference-streaming.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object ConferenceStreamTable : Table("conference_stream") {
    public val id: Column<Uuid> = uuid("id")
    public val roomId: Column<Uuid> = reference("room_id", ConferenceRoomTable.id)
    public val startedByMemberId: Column<Uuid> = reference("started_by_member_id", MemberTable.id)
    public val status: Column<ConferenceStreamStatus> = enumerationByName<ConferenceStreamStatus>("status", 8)
    public val layout: Column<ConferenceStreamLayout> = enumerationByName<ConferenceStreamLayout>("layout", 19)
    public val latencyMode: Column<ConferenceStreamLatencyMode> =
        enumerationByName<ConferenceStreamLatencyMode>("latency_mode", 11)
    public val participantIdentity: Column<String?> = varchar("participant_identity", 64).nullable()
    public val livekitEgressId: Column<String?> = varchar("livekit_egress_id", 64).nullable()
    public val startedAt: Column<LocalDateTime> = datetime("started_at")
    public val pausedAt: Column<LocalDateTime?> = datetime("paused_at").nullable()
    public val endedAt: Column<LocalDateTime?> = datetime("ended_at").nullable()
    public val restartCount: Column<Int> = integer("restart_count").default(0)
    public val failureReason: Column<String?> = varchar("failure_reason", 500).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
