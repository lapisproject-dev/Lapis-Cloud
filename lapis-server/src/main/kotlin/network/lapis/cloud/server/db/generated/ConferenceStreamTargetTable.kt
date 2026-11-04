// Hand-written per ADR-0016 Option B (see 29-conference-streaming.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object ConferenceStreamTargetTable : Table("conference_stream_target") {
    public val id: Column<Uuid> = uuid("id")
    public val streamId: Column<Uuid> = reference("stream_id", ConferenceStreamTable.id)
    public val destinationId: Column<Uuid> = reference("destination_id", ConferenceStreamDestinationTable.id)
    public val status: Column<ConferenceStreamTargetStatus> =
        enumerationByName<ConferenceStreamTargetStatus>("status", 8)
    public val urlFingerprint: Column<String> = varchar("url_fingerprint", 255)
    public val startedAtEpochNanos: Column<Long?> = long("started_at_epoch_nanos").nullable()
    public val endedAtEpochNanos: Column<Long?> = long("ended_at_epoch_nanos").nullable()
    public val retries: Column<Int> = integer("retries").default(0)
    public val failureReason: Column<String?> = varchar("failure_reason", 500).nullable()
    public val broadcastKeyCiphertext: Column<String?> = varchar("broadcast_key_ciphertext", 1024).nullable()
    public val viewerUrl: Column<String?> = varchar("viewer_url", 512).nullable()
    public val failureKind: Column<ConferenceStreamFailureKind?> =
        enumerationByName<ConferenceStreamFailureKind>("failure_kind", 16).nullable()
    public val autoRetryAttempt: Column<Int> = integer("auto_retry_attempt").default(0)
    public val autoRetryNextAt: Column<LocalDateTime?> = datetime("auto_retry_next_at").nullable()
    public val autoRetryIssuedAt: Column<LocalDateTime?> = datetime("auto_retry_issued_at").nullable()
    public val retryWindowStartedAt: Column<LocalDateTime?> = datetime("retry_window_started_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 1 index declared on this entity is not emitted -- Exposed's index {} DSL needs typed
    // column references, not wired up in this wave (mirrors ConferenceRecordingTrackTable's own
    // note for its recording_id index).
}
