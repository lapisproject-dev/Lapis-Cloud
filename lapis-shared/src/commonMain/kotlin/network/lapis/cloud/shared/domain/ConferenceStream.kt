package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

// V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- see
// `lapis-server/src/main/kuml/29-conference-streaming.kuml.kts` file header for the full fachlich
// model (RTMP egress to N external destinations, encrypted-at-rest credentials, no automatic
// secret-vote pause) and `network.lapis.cloud.shared.rpc.IConferenceStreamingService` KDoc for the
// RPC surface / authorization matrix these types travel over.
//
// The enum vocabulary below was added by this wave's persistence/schema step (so
// 29-conference-streaming.kuml.kts's hand-written Exposed Table objects could reference real,
// already-existing Kotlin enum types instead of forward declarations). The DTOs below it
// (ConferenceStreamDestinationDto/ConferenceStreamDto/ConferenceStreamTargetDto/etc.) are added by
// this wave's RPC/DTO step, alongside IConferenceStreamingService.

/**
 * Which external platform a [network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable]
 * row targets -- pure UX/validation metadata (prefilled ingest URL + hint text for
 * `YOUTUBE`/`TWITCH`, hint-only for `PEERTUBE`/`OWNCAST`, no preset for `GENERIC_RTMP`, and `X` -- no preset either, hint only: the ingest address of X is not reliably known). **The
 * server has zero platform-specific code paths** -- every platform is `<rtmpUrl>/<streamKey>`
 * built from the same two stored columns, see that table's own KDoc. Literal order is load-bearing:
 * `ConferenceStreamSchemaDriftTest` pins it against `29-conference-streaming.kuml.kts`'s
 * `conferenceStreamPlatform` enum.
 */
@Serializable
enum class ConferenceStreamPlatform { YOUTUBE, TWITCH, PEERTUBE, OWNCAST, GENERIC_RTMP, X }

/**
 * V1.9.274 -- how the stream key of a destination is handled. [PERSISTENT]: the ADMIN stores the key (sealed) on the destination and every broadcast
 * uses it. [PER_BROADCAST]: the platform issues a new key for every broadcast (Facebook Live, a scheduled X broadcast); the moderator types it at
 * the start, it lives only on the target row of that one broadcast and is deleted when the stream ends. Literal order is load-bearing (same
 * mechanism as [ConferenceStreamPlatform]).
 */
@Serializable
enum class ConferenceStreamKeyKind { PERSISTENT, PER_BROADCAST }

/**
 * V1.9.274 -- which hint text the start dialog shows for the key of a [ConferenceStreamKeyKind.PER_BROADCAST] destination. Derived on the server
 * from the platform and the ingest host, never a URL and never an input. Literal order is load-bearing.
 */
@Serializable
enum class ConferenceStreamKeyHint { FACEBOOK, X, OTHER }

/** V1.9.274 -- the single place the Facebook ingest host pattern lives; the client preset references it, the server derives [ConferenceStreamKeyHint] from it. */
object ConferenceStreamHosts {
    val FACEBOOK_INGEST_HOST = Regex("^live-api(-[a-z0-9]+)?\\.facebook\\.com$")
}

/**
 * Which LiveKit egress shape [network.lapis.cloud.server.db.generated.ConferenceStreamTable]'s
 * `startStream` uses. `GRID`/`SPEAKER` -> `StartRoomCompositeEgress` (Chrome + web template,
 * `https://template.livekit.io` by default -- see the wave's scope-decisions doc "MAJOR GOTCHA" for
 * the `error_code 412`/"Start signal not received" failure signature this implies when that host is
 * unreachable). `SINGLE_PARTICIPANT` -> `StartParticipantEgress` (SDK-composited, no
 * Chrome/template, the only egress path verifiable in a network-restricted environment). Literal
 * order is load-bearing (same mechanism as [ConferenceStreamPlatform]).
 */
@Serializable
enum class ConferenceStreamLayout { GRID, SPEAKER, SINGLE_PARTICIPANT }

/**
 * `preset`/`advanced` are a protobuf `oneof` on LiveKit's `StreamOutput` -- setting both is invalid,
 * so this enum maps to exactly one of the two, never both. `STANDARD` -> `preset: "H264_720P_30"`.
 * `LOW_LATENCY` -> an `advanced` encoding block with `key_frame_interval: 1`. **GO decision, BOTH
 * branches verified live** against `deploy/local/docker-compose.yml` (2026-08-09, closing the "NOT
 * yet verified" gap this KDoc previously flagged, see D10 in the Wave 3 design review): a
 * `StartParticipantEgress` carrying the `advanced` block reached `EGRESS_ACTIVE`, and a real
 * `bluenviron/mediamtx` sink logged `stream is available and online, 2 tracks (H264, MPEG-4
 * Audio)` -- real media, not merely an accepted request. See
 * `network.lapis.cloud.server.conference.LOW_LATENCY_ENCODING_OPTIONS` for the exact verified block
 * and `network.lapis.cloud.server.conference.HttpLiveKitEgressClient` KDoc "preset/advanced is a
 * protobuf oneof" for how the two branches are kept structurally exclusive (two separate request
 * DTOs, never one with two nullable fields). Literal order is load-bearing (same mechanism as
 * [ConferenceStreamPlatform]).
 */
@Serializable
enum class ConferenceStreamLatencyMode { LOW_LATENCY, STANDARD }

/**
 * This wave's OWN per-stream lifecycle state machine -- see
 * `29-conference-streaming.kuml.kts` file header "Liveness/lifecycle via an explicit status enum"
 * for why a nullable-timestamp-only scheme cannot express it (mirrors
 * [ConferenceRecordingStatus]'s own precedent). `startStream` writes `STARTING` inside its first
 * transaction, then (OUTSIDE that transaction, after the synchronous LiveKit call -- see
 * `network.lapis.cloud.server.rpc.ConferenceStreamingService` KDoc, a later wave step) either
 * `LIVE` or `FAILED` in a second transaction. `pauseStream` = `StopEgress` + `PAUSED` (LiveKit has
 * NO pause primitive, verified live -- pause is genuinely stop, meeting untouched).
 * `resumeStream` = a fresh `Start...Egress`, `LIVE` again, on the SAME row.
 * `StreamPoller` (a later wave step) drives `LIVE -> FAILED` and any auto-stop transition.
 * Literal order is load-bearing (same mechanism as [ConferenceStreamPlatform]).
 *
 * [PAUSING] is a V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" addition, APPENDED at the end
 * (not reordered next to [LIVE]/[PAUSED] where it would read more "naturally" -- literal order is
 * load-bearing, see above). It is the fail-closed intermediate state between "DB says pausing" and
 * "LiveKit has confirmed the egress actually stopped publishing": `StopEgress` is asynchronous at
 * LiveKit (a "requested", not "stopped" acknowledgement, and the egress keeps publishing for 1-3s
 * after the call returns), so a status flip straight to [PAUSED] would let a secret ballot's voting
 * phase open while the egress is still emitting. See
 * `network.lapis.cloud.server.rpc.SecretBallotStreamLock`/
 * `network.lapis.cloud.server.conference.SecretBallotStreamGuard` for the writers of this state and
 * [ConferenceStreamPauseReason] for why a stream is paused in the first place.
 */
@Serializable
enum class ConferenceStreamStatus { STARTING, LIVE, PAUSED, STOPPING, ENDED, FAILED, PAUSING }

/**
 * V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- why a
 * [network.lapis.cloud.server.db.generated.ConferenceStreamTable] row is currently
 * [ConferenceStreamStatus.PAUSING]/[ConferenceStreamStatus.PAUSED]. [SECRET_BALLOT] is the ONLY
 * value that auto-resumes (see `network.lapis.cloud.server.conference.SecretBallotStreamGuard
 * .resumeStreamsForMeeting`) -- a manual `pauseStream` call while a stream is already
 * `PAUSING`/`PAUSED` with [SECRET_BALLOT] ESCALATES this column to [MANUAL] (one-way, NEVER back to
 * [SECRET_BALLOT]) -- once a moderator has manually intervened during a secret ballot's pause, they
 * have taken control and the system must never auto-resume out from under them. Literal order is
 * load-bearing (same mechanism as [ConferenceStreamPlatform]).
 */
@Serializable
enum class ConferenceStreamPauseReason { MANUAL, SECRET_BALLOT, ROOM_PAUSE }

/**
 * V1.9.255 -- state of the pause feed (still picture plus optional music) the SERVER sends to the stream targets while the room pause
 * holds the live stream. Held in memory only (never stored): `OFF` = no feed (also: the feature is off, the stream is not paused by the
 * room, or the process restarted and the poller has not started it yet), `STARTING` = process launched but no progress yet, `ACTIVE` = the
 * feed is sending, `FAILED` = no feed could be started or it kept failing -- the stream is interrupted until the resume (the V1.9.254
 * behaviour). Literal order is load-bearing (same mechanism as [ConferenceStreamPlatform]).
 */
@Serializable
enum class ConferenceStreamPauseFeedState { OFF, STARTING, ACTIVE, FAILED }

/**
 * Mirrors LiveKit's own `livekit.StreamInfo.Status` (`ACTIVE`/`FINISHED`/`FAILED`), plus a
 * `PENDING` pre-start state this codebase adds for the row's own life between `INSERT` and the
 * first successful `StreamPoller` reconciliation against `ListEgress`'s `stream_results` (matched
 * via `url_fingerprint`, never by exact URL or array index -- see
 * `network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable` KDoc "why url_fingerprint
 * exists" for the live-verified redaction/reordering behaviour this column works around). Literal
 * order is load-bearing (same mechanism as [ConferenceStreamPlatform]).
 */
@Serializable
enum class ConferenceStreamTargetStatus { PENDING, ACTIVE, FINISHED, FAILED }

// ── RPC-facing DTOs -- added alongside network.lapis.cloud.shared.rpc.IConferenceStreamingService,
// see that interface's KDoc for the full authorization matrix these travel over ──

/**
 * ADMIN-only view of a configured stream destination -- see
 * [network.lapis.cloud.shared.rpc.IConferenceStreamingService] KDoc "credential storage model" for
 * the full redisplay rule this DTO's shape enforces.
 *
 * **The stream key is NEVER part of this DTO, under any role, at any time -- including immediately
 * after saving it.** [streamKeyMask] is ALWAYS the constant string `"********"`, never the last four
 * characters, never a length hint, never a prefix -- see that field's own KDoc for why. [rtmpUrl] is
 * the ingest BASE url only (e.g. `rtmp://a.rtmp.youtube.com/live2`) -- the key is a SEPARATE stored
 * column and the two are concatenated ONLY in-memory, server-side, at the moment
 * `network.lapis.cloud.server.rpc.ConferenceStreamingService.startStream`/`resumeStream` (a later
 * wave step) builds the URL it hands to LiveKit; that concatenated string is never persisted, never
 * logged, never returned to any client.
 */
@Serializable
data class ConferenceStreamDestinationDto(
    val id: String,
    val label: String,
    val platform: ConferenceStreamPlatform,
    val rtmpUrl: String,
    /** V1.9.274 -- [ConferenceStreamKeyKind.PER_BROADCAST] destinations store no key at all. */
    val keyKind: ConferenceStreamKeyKind = ConferenceStreamKeyKind.PERSISTENT,
    /**
     * ALWAYS the literal string `"********"` -- never derived from the real key's length or content
     * in any way. The operational question "which destination is this?" is fully answered by
     * [label] (mandatory, unique) + [rtmpUrl] + [streamKeySetAt] + [createdByDisplayName]; returning
     * ANY key material -- even a masked suffix -- would buy nothing and reopen a "how many
     * characters is safe to show" argument in every future review. See
     * [network.lapis.cloud.shared.rpc.IConferenceStreamingService] KDoc for the full rationale.
     */
    val streamKeyMask: String,
    /** `null` = no key stored (a [ConferenceStreamKeyKind.PER_BROADCAST] destination, or a persistent one restored from a backup). */
    val streamKeySetAt: LocalDateTime?,
    val createdByDisplayName: String,
    val enabled: Boolean,
)

/**
 * The ONLY destination shape a non-ADMIN caller ever receives -- deliberately narrower than
 * [ConferenceStreamDestinationDto]: NO [ConferenceStreamDestinationDto.rtmpUrl], no key material of
 * any kind. A BOARD moderator choosing among approved destinations for [network.lapis.cloud.shared.rpc.IConferenceStreamingService.startStream]
 * never learns the ingest URL, let alone the key.
 */
@Serializable
data class ConferenceStreamTargetDto(
    val id: String,
    val label: String,
    val platform: ConferenceStreamPlatform,
    /** V1.9.274 */
    val keyKind: ConferenceStreamKeyKind = ConferenceStreamKeyKind.PERSISTENT,
    /** V1.9.274 -- hint text for the key field of a [ConferenceStreamKeyKind.PER_BROADCAST] destination. */
    val keyHint: ConferenceStreamKeyHint = ConferenceStreamKeyHint.OTHER,
    /** V1.9.274 -- a [ConferenceStreamKeyKind.PERSISTENT] destination without a stored key (after a backup restore): not startable until the ADMIN enters it again. */
    val storedKeyMissing: Boolean = false,
)

/**
 * Per-destination LIVE status for one running (or historical) [ConferenceStreamDto] -- what a
 * participant/moderator actually sees in the stream indicator/control panel. [status] renders as
 * three DISTINCT states, never collapsed into a binary "streaming: yes/no" (UI/UX design review D7,
 * "the danger is building a UI that implies more certainty than the system has"): [ConferenceStreamTargetStatus.PENDING]
 * ("Verbindung wird hergestellt…"), [ConferenceStreamTargetStatus.ACTIVE] ("Live"),
 * [ConferenceStreamTargetStatus.FINISHED]/[ConferenceStreamTargetStatus.FAILED] (ended/failed, with
 * [failureReason] if the latter). [retries] is `StreamPoller`'s (a later wave step) own retry
 * counter, mirrored from `livekit.StreamInfo.retries` -- surfaced so a moderator can distinguish "a
 * transient reconnect happened but it's fine now" from "this has never worked".
 */
@Serializable
data class ConferenceStreamTargetStatusDto(
    val destinationId: String,
    val label: String,
    val platform: ConferenceStreamPlatform,
    val status: ConferenceStreamTargetStatus,
    val retries: Int,
    /**
     * SANITIZED German text only, populated from a FIXED vocabulary -- NEVER raw LiveKit error text.
     * This is a hard requirement, not a style preference: a real LiveKit per-URL error observed live
     * (2026-08-09) reads `"Failed to connect: Error resolving “nonexistent-bad-host-xyz”: Name or
     * service not known"` -- LiveKit's OWN error string echoes the destination HOST back. Same
     * "never trust that a third-party payload or a JVM exception's `toString()` cannot contain
     * something sensitive" discipline [network.lapis.cloud.server.conference.LiveKitAdminException]
     * KDoc establishes, and the SAME security-boundary rule [ConferenceRecordingDto.failureReason]
     * already enforces for Wave 2. `null` unless [status] is [ConferenceStreamTargetStatus.FAILED].
     */
    val failureReason: String?,
    /** V1.9.274 */
    val keyKind: ConferenceStreamKeyKind = ConferenceStreamKeyKind.PERSISTENT,
    /**
     * V1.9.274 -- moderators only (always `false` for everybody else): a [ConferenceStreamKeyKind.PER_BROADCAST] target of a stream that has not ended
     * and holds no key (e.g. after a failed restart). Reserved for the reconnect action of the next wave.
     */
    val needsBroadcastKey: Boolean = false,
    /** V1.9.274 -- moderators only (always `null` for everybody else): the normalised viewer page address behind the "Kommentare öffnen" link. */
    val viewerUrl: String? = null,
    /**
     * V1.9.275 -- the sanitised class of the failure; only set while [status] is [ConferenceStreamTargetStatus.FAILED], otherwise `null`. Never a URL,
     * never a key, never raw LiveKit text (see [failureReason]).
     */
    val failureKind: ConferenceStreamFailureKind? = null,
    /** V1.9.275 -- number of automatic attempts of the current window (0..[ConferenceStreamRetryLimits.MAX_AUTO_ATTEMPTS]); while [status] is PENDING and this is above 0 the target is being retried. */
    val autoRetryAttempt: Int = 0,
    /** V1.9.275 -- moderators only (everybody else gets [ConferenceStreamKeyHint.OTHER]): derived server-side from the destination, used for the platform specific hint. */
    val keyHint: ConferenceStreamKeyHint = ConferenceStreamKeyHint.OTHER,
)

/**
 * One conference stream -- the RPC-layer counterpart to
 * `network.lapis.cloud.server.db.generated.ConferenceStreamTable`. [roomTitle]/[startedByDisplayName]
 * are denormalized for the Lobby/admin history view, mirroring [ConferenceRecordingDto]'s own
 * precedent. [restartCount] increments every `resumeStream` call (a later wave step) -- each resume
 * mints a NEW `livekit_egress_id` on this SAME row (LiveKit has no pause primitive, verified live;
 * pause is genuinely stop+restart, see [ConferenceStreamStatus] KDoc). [failureReason] is the
 * STREAM-level (not per-target) sanitized reason -- see [ConferenceStreamTargetStatusDto.failureReason]
 * sibling KDoc for the same security-boundary rule, applied here to the aggregate row.
 */
@Serializable
data class ConferenceStreamDto(
    val id: String,
    val roomId: String,
    val roomTitle: String,
    val status: ConferenceStreamStatus,
    val layout: ConferenceStreamLayout,
    val latencyMode: ConferenceStreamLatencyMode,
    val startedByMemberId: String,
    val startedByDisplayName: String,
    val startedAt: LocalDateTime,
    val pausedAt: LocalDateTime?,
    val endedAt: LocalDateTime?,
    val restartCount: Int,
    val targets: List<ConferenceStreamTargetStatusDto>,
    val failureReason: String?,
    /**
     * V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- `null` unless [status] is
     * [ConferenceStreamStatus.PAUSING]/[ConferenceStreamStatus.PAUSED]. See
     * [ConferenceStreamPauseReason] KDoc for the [ConferenceStreamPauseReason.SECRET_BALLOT]-vs-
     * [ConferenceStreamPauseReason.MANUAL] distinction and the one-way escalation rule that governs
     * it -- this field is exactly the UI's signal for whether to show the "unterbrochen wegen
     * geheimer Abstimmung, wird automatisch fortgesetzt" vs. plain "unterbrochen" badge.
     */
    val pauseReason: ConferenceStreamPauseReason?,
    /** V1.9.255 -- state of the server-side pause feed; [ConferenceStreamPauseFeedState.OFF] unless the room pause holds this stream. */
    val pauseFeed: ConferenceStreamPauseFeedState = ConferenceStreamPauseFeedState.OFF,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.IConferenceStreamingService.getStreamingAvailability] --
 * the UI's ONE signal for whether to show any streaming-related control at all, mirroring
 * [ConferenceRecordingAvailabilityDto]'s own precedent (a SEPARATE gate from both
 * [ConferenceAvailabilityDto] and [ConferenceRecordingAvailabilityDto] -- a deployment can
 * legitimately stream without being able to record, and vice versa). [enabled] is `true` iff
 * `ConferenceConfig.enabled` AND `ConferenceStreamingConfig.enabled` AND [encryptionConfigured] all
 * hold; [encryptionConfigured] is reported SEPARATELY so an operator sees WHICH half is missing
 * rather than a single opaque `false` -- same "report the two halves separately" pattern
 * [ConferenceRecordingAvailabilityDto.ffmpegAvailable] already establishes for its own second gate.
 */
@Serializable
data class ConferenceStreamAvailabilityDto(
    val enabled: Boolean,
    val encryptionConfigured: Boolean,
    val maxDestinations: Int,
    val configuredDestinationCount: Int,
)

/**
 * V1.9.275 -- the sanitised class of the last failure of ONE target (derived from LiveKit's raw error text by the server, which never leaves the
 * server). The order is load-bearing (it matches the kUML model `29-conference-streaming.kuml.kts`): only append.
 */
@Serializable
enum class ConferenceStreamFailureKind { REJECTED, TIMEOUT, CONNECTION, UNKNOWN }

/** V1.9.275 -- limits shared by server and client ("Wird erneut verbunden (n von 3)"). The SQL CHECK of V141 repeats [MAX_AUTO_ATTEMPTS] (a test keeps both equal). */
object ConferenceStreamRetryLimits {
    const val MAX_AUTO_ATTEMPTS = 3
}

/**
 * V1.9.274 -- what the moderator types for ONE destination when starting a stream: the key of this broadcast (required for a
 * [ConferenceStreamKeyKind.PER_BROADCAST] destination, forbidden for a persistent one) and optionally the viewer page address. A plain class, NOT a
 * `data class`, so that no generated `toString`/`copy`/`component` can ever print the key; [toString] is fixed.
 */
@Serializable
class ConferenceStreamBroadcastInput(
    val destinationId: String,
    val streamKey: String? = null,
    val viewerUrl: String? = null,
) {
    override fun toString(): String =
        "ConferenceStreamBroadcastInput(destinationId=$destinationId, streamKey=${if (streamKey == null) "null" else "<redacted>"}, " +
            "viewerUrl=<redacted>)"
}
