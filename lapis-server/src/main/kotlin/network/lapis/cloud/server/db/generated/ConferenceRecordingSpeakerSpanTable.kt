// Hand-written per ADR-0016 Option B (see 28-conference-recording.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * V1.9.115 -- one span of the SMOOTHED speaker timeline of a recording: [trackId] (the person's video track row) was the large tile from
 * [startMs] to [endMs] of the composed file. No name, no identity. Read and written ONLY by
 * `network.lapis.cloud.server.conference.RecordingSpeakerTimelineStore` (a tripwire guards that); deleted with the raw files and by
 * `ON DELETE CASCADE` with the recording / the track row. Excluded from the organization backup.
 */
public object ConferenceRecordingSpeakerSpanTable : Table("conference_recording_speaker_span") {
    public val id: Column<Uuid> = uuid("id")
    public val recordingId: Column<Uuid> = reference("recording_id", ConferenceRecordingTable.id)
    public val trackId: Column<Uuid> = reference("track_id", ConferenceRecordingTrackTable.id)
    public val startMs: Column<Long> = long("start_ms")
    public val endMs: Column<Long> = long("end_ms")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
