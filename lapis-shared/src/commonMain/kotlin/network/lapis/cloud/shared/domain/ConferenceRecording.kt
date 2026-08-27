package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- see
 * `lapis-server/src/main/kuml/28-conference-recording.kuml.kts` file header for the full fachlich
 * model (Track Egress does not transcode -> N raw per-track files -> a separate later async
 * composition step), and [network.lapis.cloud.shared.rpc.IConferenceRecordingService] KDoc for the
 * RPC surface / authorization matrix these types travel over.
 *
 * This is a REAL, authoritative state machine (not a display convenience) -- see
 * `28-conference-recording.kuml.kts` file header "Liveness/lifecycle via nullable timestamps plus
 * an explicit status enum" for why `stoppedAt`/`readyAt` on [ConferenceRecordingDto] alone could not
 * express it. `network.lapis.cloud.server.rpc.ConferenceRecordingService.startRecording`/
 * `stopRecording` (this wave) only ever write [RECORDING]/[STOPPING] -- a later wave's
 * `RecordingPoller` drives every transition after that (`STOPPING -> PROCESSING -> `[READY]`/`
 * [FAILED]`). Literal order is load-bearing: `ConferenceRecordingSchemaDriftTest` pins it against
 * `28-conference-recording.kuml.kts`'s `conferenceRecordingStatus` enum.
 */
@Serializable
enum class ConferenceRecordingStatus { RECORDING, STOPPING, PROCESSING, READY, FAILED }

/**
 * Mirrors LiveKit's own `livekit.proto` `TrackSource` enum -- see
 * `network.lapis.cloud.server.conference.LiveKitTrackInfo` KDoc for which literals are empirically
 * wire-verified against a live container (`CAMERA` only, as of this wave) versus transcribed from
 * the proto and still unverified (`MICROPHONE`/`SCREEN_SHARE`/`SCREEN_SHARE_AUDIO`). Literal order
 * is load-bearing (`ConferenceRecordingSchemaDriftTest`, same mechanism as
 * [ConferenceRecordingStatus]).
 */
@Serializable
enum class ConferenceRecordingTrackSource { CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO, UNKNOWN }

/**
 * This wave's OWN per-track state machine -- deliberately independent of LiveKit's own
 * `EgressStatus` wire strings (`network.lapis.cloud.server.conference.LiveKitEgressInfo.status`,
 * kept as a plain `String` there precisely so this codebase's own vocabulary and LiveKit's wire
 * vocabulary can never silently drift into being treated as the same thing). A later wave's
 * `RecordingPoller` is the one place that maps LiveKit's `EGRESS_STARTING`/`EGRESS_ACTIVE`/
 * `EGRESS_ENDING`/`EGRESS_COMPLETE`/`EGRESS_FAILED`/`EGRESS_ABORTED`/`EGRESS_LIMIT_REACHED` onto
 * these five literals. Literal order is load-bearing (same mechanism as
 * [ConferenceRecordingStatus]).
 */
@Serializable
enum class ConferenceRecordingTrackStatus { STARTING, ACTIVE, COMPLETE, FAILED, ABORTED }

/**
 * One conference recording -- see
 * [network.lapis.cloud.shared.rpc.IConferenceRecordingService] KDoc for the full RPC contract this
 * travels over. [roomTitle]/[startedByDisplayName] are denormalized for the Lobby's "Aufzeichnungen"
 * list, which must remain readable long after the owning [network.lapis.cloud.shared.domain.ConferenceRoomDto]
 * itself has ended (a recording OUTLIVES its room). [mediaUrl] is computed SERVER-SIDE, per caller,
 * from [network.lapis.cloud.server.security.CurrentMember]'s own access predicate -- see
 * [ConferenceRecordingAvailabilityDto] sibling KDoc and `ConferenceRecordingAccess.mayAccess` (a
 * later wave) for the exact rule; it is never a bare path string this DTO's own field name might
 * tempt a client into constructing itself, and is `null` whenever [status] is not [ConferenceRecordingStatus.READY]
 * OR the caller may not access it. [failureReason] is a SECURITY boundary, not just a UX field --
 * see its own KDoc below.
 */
@Serializable
data class ConferenceRecordingDto(
    val id: String,
    val roomId: String,
    val roomTitle: String,
    val status: ConferenceRecordingStatus,
    val startedByMemberId: String,
    val startedByDisplayName: String,
    val startedAt: LocalDateTime,
    val stoppedAt: LocalDateTime?,
    val readyAt: LocalDateTime?,
    /** From the composed file, `null` before [status] reaches [ConferenceRecordingStatus.READY]. */
    val durationSeconds: Long?,
    val accessLevel: DocumentAccessLevel,
    /** `null` until [status] reaches [ConferenceRecordingStatus.READY] -- the Dokumentenablage row backing the composed file. */
    val documentId: String?,
    /** `"/api/conference/recordings/{id}/media"` once READY and access-checked for THIS caller, `null` otherwise -- see class KDoc. Bytes never travel over Kilua RPC (this codebase's stated rule), only this URL does. */
    val mediaUrl: String?,
    val fileSizeBytes: Long?,
    /** How many raw track egresses fed the composition -- `0` before any track egress has started. */
    val trackCount: Int,
    /**
     * SANITIZED German text only, populated from a fixed vocabulary -- NEVER raw ffmpeg stderr,
     * NEVER a raw Twirp error body, NEVER a filesystem path. Same "never trust that a third-party
     * payload or a JVM exception's `toString()` cannot contain something sensitive" discipline
     * [network.lapis.cloud.server.conference.LiveKitAdminException] KDoc establishes for its own
     * error surface -- raw ffmpeg/Twirp detail goes to `kotlin-logging` server-side and nowhere
     * near this field. `null` unless [status] is [ConferenceRecordingStatus.FAILED].
     */
    val failureReason: String?,
)

/**
 * Bündelt [network.lapis.cloud.shared.rpc.IConferenceRecordingService.listRecordings]'s filter plus
 * its offset pagination into ONE object -- same shape [MemberAdminQuery] already establishes for
 * `listMembersForAdministration` (this codebase's only list-with-a-real-pager precedent), and for
 * the same two reasons: Kilua RPC's generated client `call()` overloads have a hard reified
 * parameter-count ceiling (a query object keeps every future filter free), and one object keeps
 * "what is filtered" and "which slice" legible at the call site.
 *
 * [limit]/[offset] are re-clamped SERVER-side against [MAX_LIMIT] (see
 * `network.lapis.cloud.server.rpc.ConferenceRecordingService.listRecordings`) -- these constants are
 * a client convenience, never a trust anchor. [roomId] `null` lists across ALL rooms (the Lobby's
 * "Aufzeichnungen" section -- recordings OUTLIVE their rooms, so they must stay reachable long after
 * the room itself is gone).
 */
@Serializable
data class ConferenceRecordingListQuery(
    val roomId: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
) {
    companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
    }
}

/**
 * One page of [ConferenceRecordingDto]s -- mirrors [MemberAdminPageDto]'s own shape. [totalCount] is
 * the size of the caller's OWN accessible row set (the access predicate
 * `ConferenceRecordingAccess.mayAccess` is applied in the SQL `WHERE` clause, so `COUNT(*)`,
 * `LIMIT` and `OFFSET` are all computed against the SAME already-filtered rows) -- never the raw
 * table count, which would let a caller infer how many recordings exist that they may not see, and
 * would strand the pager on pages that render empty.
 *
 * [limit]/[offset] are echoed back as the values the SERVER actually applied after clamping, not
 * the ones the client asked for -- the pager must step by the real page size, not by a rejected one.
 */
@Serializable
data class ConferenceRecordingPageDto(
    val rows: List<ConferenceRecordingDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.IConferenceRecordingService.getRecordingAvailability]
 * -- the UI's ONE signal for whether to show any recording-related control at all, deliberately
 * separate from [network.lapis.cloud.shared.domain.ConferenceAvailabilityDto] (Wave 1) -- see
 * [network.lapis.cloud.shared.rpc.IConferenceRecordingService] KDoc "a second, independent
 * availability gate" for why growing the Wave 1 DTO instead would have muddled "can I confer" with
 * "can I record". [enabled] is `true` iff `ConferenceConfig.enabled` AND
 * `ConferenceRecordingConfig.enabled` AND [ffmpegAvailable] all hold; [ffmpegAvailable] is reported
 * SEPARATELY so an operator/developer can see WHICH half is missing rather than a single opaque
 * `false`.
 */
@Serializable
data class ConferenceRecordingAvailabilityDto(
    val enabled: Boolean,
    val ffmpegAvailable: Boolean,
    val maxDurationMinutes: Int,
)
