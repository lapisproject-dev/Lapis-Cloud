// Hand-written per ADR-0016 Option B (see 28-conference-recording.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object ConferenceRecordingTable : Table("conference_recording") {
    public val id: Column<Uuid> = uuid("id")
    public val roomId: Column<Uuid> = reference("room_id", ConferenceRoomTable.id)
    public val startedByMemberId: Column<Uuid> = reference("started_by_member_id", MemberTable.id)
    public val startedAt: Column<LocalDateTime> = datetime("started_at")
    public val stoppedAt: Column<LocalDateTime?> = datetime("stopped_at").nullable()
    public val readyAt: Column<LocalDateTime?> = datetime("ready_at").nullable()
    public val status: Column<ConferenceRecordingStatus> = enumerationByName<ConferenceRecordingStatus>("status", 10)
    public val accessLevel: Column<DocumentAccessLevel> = enumerationByName<DocumentAccessLevel>("access_level", 14)
    public val documentId: Column<Uuid?> = optReference("document_id", DocumentTable.id)
    public val rawDir: Column<String> = varchar("raw_dir", 64)
    public val durationSeconds: Column<Long?> = long("duration_seconds").nullable()
    public val fileSizeBytes: Column<Long?> = long("file_size_bytes").nullable()
    public val failureReason: Column<String?> = varchar("failure_reason", 500).nullable()
    public val composeAttempts: Column<Int> = integer("compose_attempts").default(0)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 3 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
