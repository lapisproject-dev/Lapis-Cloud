// Hand-written per ADR-0016 Option B (see 28-conference-recording.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

public object ConferenceRecordingTrackTable : Table("conference_recording_track") {
    public val id: Column<Uuid> = uuid("id")
    public val recordingId: Column<Uuid> = reference("recording_id", ConferenceRecordingTable.id)
    public val egressId: Column<String> = varchar("egress_id", 64).uniqueIndex()
    public val livekitTrackId: Column<String> = varchar("livekit_track_id", 64)
    public val participantIdentity: Column<String> = varchar("participant_identity", 64)
    public val trackSource: Column<ConferenceRecordingTrackSource> =
        enumerationByName<ConferenceRecordingTrackSource>("track_source", 19)
    public val status: Column<ConferenceRecordingTrackStatus> = enumerationByName<ConferenceRecordingTrackStatus>("status", 8)
    public val startedAtEpochNanos: Column<Long?> = long("started_at_epoch_nanos").nullable()
    public val endedAtEpochNanos: Column<Long?> = long("ended_at_epoch_nanos").nullable()
    public val fileName: Column<String?> = varchar("file_name", 512).nullable()
    public val durationMs: Column<Long?> = long("duration_ms").nullable()
    public val sizeBytes: Column<Long?> = long("size_bytes").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 1 index declared on this entity is not emitted -- Exposed's index {} DSL needs typed
    // column references, not wired up in this wave (the egress_id UNIQUE above is expressed
    // directly via .uniqueIndex() instead, same as ConferenceRoomTable.livekitRoomName).
}
