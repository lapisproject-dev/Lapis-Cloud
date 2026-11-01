// Hand-written per ADR-0016 Option B (see 28-conference-recording.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * V1.9.254 -- one pause of a recording: pause [seq] ends stage `seq - 1`, [resumedAt] is `null` while the pause is open. No person column.
 * At most ONE open row per recording, enforced in code under the room lock (H2 has no partial index). Written ONLY by
 * `ConferenceRoomPauseCoordinator` and `RecordingPoller.transitionToStopping` (a tripwire guards that); deleted with the recording
 * (`ON DELETE CASCADE`, V134).
 */
public object ConferenceRecordingPauseTable : Table("conference_recording_pause") {
    public val id: Column<Uuid> = uuid("id")
    public val recordingId: Column<Uuid> = reference("recording_id", ConferenceRecordingTable.id)
    public val seq: Column<Short> = short("seq")
    public val pausedAt: Column<LocalDateTime> = datetime("paused_at")
    public val resumedAt: Column<LocalDateTime?> = datetime("resumed_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
