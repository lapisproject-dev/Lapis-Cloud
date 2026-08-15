package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout

private val logger = KotlinLogging.logger {}

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the pluggable LiveKit Track-Egress
 * admin boundary, mirroring [LiveKitAdminClient]'s own "thin Twirp-over-HTTP/JSON client, hand-
 * rolled, not a generated gRPC/protobuf stub" shape exactly (three methods needed, pulling in a
 * protobuf/gRPC toolchain for that is disproportionate -- same reasoning [LiveKitAdminClient] KDoc
 * gives for its own five methods). A later wave's `RecordingPoller` depends on this interface, not
 * on [HttpLiveKitEgressClient] directly, so tests can substitute a fake with zero network involved
 * -- same pluggable-boundary pattern [LiveKitAdminClient]/
 * [network.lapis.cloud.server.postal.PostalMailProvider] already establish.
 *
 * **Served by the LiveKit SERVER's Twirp API** (`{apiUrl}/twirp/livekit.Egress/...`, the SAME base
 * URL [HttpLiveKitAdminClient] already uses, port 7880) -- the egress WORKER processes themselves
 * are reached through Redis (see `deploy/local/docker-compose.yml`'s `egress`/`redis` services) and
 * are never addressed directly by this client.
 *
 * Every request/response JSON shape below was checked against a REAL, locally-running LiveKit
 * v1.13.5 + egress v1.13.0 instance (`deploy/local/docker-compose.yml`, 2026-08-09), using
 * `livekit-cli`'s `room join --publish-demo` to get a real published video track and a real
 * completed Track Egress recording -- not merely reconstructed from documentation. See each DTO's
 * own KDoc for the exact verified sample.
 *
 * ## V1.0 Wave 3 "Externes Streaming" -- [startRoomCompositeEgress]/[startParticipantEgress]/[updateStream]
 *
 * Track Egress ([startTrackEgress]) CANNOT do RTMP -- `TrackEgressRequest`'s output oneof is only
 * `file`/`websocket`. Streaming needs a COMPOSITED request instead: `StartRoomCompositeEgress`
 * (Chrome + web template, for [ConferenceStreamLayout.GRID]/[ConferenceStreamLayout.SPEAKER]) or
 * `StartParticipantEgress` (SDK-composited, no Chrome/template, for
 * [ConferenceStreamLayout.SINGLE_PARTICIPANT]) -- see `network.lapis.cloud.shared.domain.ConferenceStreamLayout`
 * KDoc for why the split exists and which path is verifiable in a network-restricted environment.
 * Reuses the SAME [LiveKitAccessToken.mintEgressToken] grant, the SAME
 * [defaultLiveKitAdminHttpClient]/[readCappedLiveKitBodyOrNull]/[MAX_LIVEKIT_RESPONSE_BYTES]
 * hardening, and the SAME [LiveKitAdminException] mapping every other method in this file already
 * uses -- one exception type across every LiveKit-touching client in this codebase, not a second
 * one for streaming.
 *
 * **`preset`/`advanced` is a protobuf `oneof` on `StreamOutput` -- setting both is invalid.** Rather
 * than one request DTO with two nullable fields (which risks silently emitting both, or an explicit
 * JSON `null` for the unused one), this file declares FOUR separate internal request DTOs
 * (preset/advanced x room-composite/participant) so the oneof constraint is enforced by the Kotlin
 * type system itself -- a request instance simply cannot carry both fields at once. See
 * [LiveKitStartRoomCompositeEgressPresetRequest]/[LiveKitStartRoomCompositeEgressAdvancedRequest]/
 * [LiveKitStartParticipantEgressPresetRequest]/[LiveKitStartParticipantEgressAdvancedRequest].
 * [ConferenceStreamLatencyMode.STANDARD] maps to `preset: "H264_720P_30"`; [ConferenceStreamLatencyMode.LOW_LATENCY]
 * maps to an `advanced` block with `key_frame_interval: 1` (see [LOW_LATENCY_ENCODING_OPTIONS]) --
 * **both verified working live** against `deploy/local/docker-compose.yml` (2026-08-09): a
 * `StartParticipantEgress` carrying the `advanced` block reached `EGRESS_ACTIVE`, and a real
 * `bluenviron/mediamtx` sink logged `stream is available and online, 2 tracks (H264, MPEG-4 Audio)`
 * -- closing the "NOT yet verified" gap [ConferenceStreamLatencyMode.LOW_LATENCY]'s own KDoc flagged.
 *
 * **Multi-destination partial failure -- also verified live, and it is GOOD news.** A single
 * `StartParticipantEgress` with TWO `stream_outputs.urls` -- one reachable, one pointed at a
 * completely unresolvable host -- reached `EGRESS_ACTIVE` overall, with `stream_results` showing the
 * good destination `"status":"ACTIVE"` and the bad one `"status":"FAILED"`, `"error":"Failed to
 * connect: Error resolving “nonexistent-bad-host-xyz”: Name or service not known"` --
 * INDEPENDENTLY. One bad stream key/unreachable destination does NOT abort a simultaneous good
 * stream to a different destination. This resolves the open question the Wave 3 scope-decisions doc
 * left explicitly unverified ("whether one bad URL among several fails only its own `stream_results`
 * entry, or aborts the whole egress") -- confirmed: only its own entry.
 *
 * See [StreamUrlFingerprint] for why [LiveKitStreamInfo.url] cannot be matched back to a destination
 * by exact string equality against the URL this client SENT, and how that is worked around.
 */
interface LiveKitEgressClient {
    /**
     * `POST .../StartTrackEgress` -- gated on the [LiveKitAccessToken.mintEgressToken] `roomRecord`
     * grant. [outputFilepathWithoutExtension] is written verbatim into
     * [LiveKitStartTrackEgressRequest.file]'s `filepath` -- deliberately carries NO file extension
     * (Track Egress does not transcode: the container depends entirely on the published track's own
     * codec, H.264 -> `.mp4`/VP8 -> `.webm`/Opus -> `.ogg`). LiveKit appends the correct extension
     * itself and reports the REAL resulting filename in the response's `file_results[0].filename` --
     * see [LiveKitEgressInfo.firstFileResult] for why a caller must read that back rather than guess
     * the extension.
     */
    suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo

    /**
     * `POST .../StopEgress` -- gated on the same `roomRecord` grant as [startTrackEgress].
     * [roomName] is NOT part of the Twirp request body (`StopEgressRequest` only carries
     * `egress_id`) -- empirically verified (2026-08-09, same live-container pass as every other
     * shape in this file) that LiveKit accepts a `StopEgress` call from a `roomRecord: true` token
     * carrying NO `room` claim at all, unlike `ListParticipants`/`RemoveParticipant`'s own
     * `roomAdmin` grant (see [LiveKitAccessToken] KDoc "Empirically verified"). [roomName] is taken
     * here anyway, purely for LEAST-PRIVILEGE token minting -- so this call's admin token is scoped
     * to the one room it actually needs, exactly like [startTrackEgress]/[listEgress], rather than a
     * token that would (if ever logged, replayed, or otherwise mishandled) authorize stopping ANY
     * egress in ANY room server-wide.
     */
    suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo

    /** `POST .../ListEgress` -- gated on `roomRecord`, scoped to [roomName]'s egresses only. */
    suspend fun listEgress(roomName: String): List<LiveKitEgressInfo>

    /**
     * V1.0 Wave 3 "Externes Streaming" -- `POST .../StartRoomCompositeEgress`, gated on the same
     * `roomRecord` grant as every other method here. [layout] MUST be
     * [ConferenceStreamLayout.GRID] or [ConferenceStreamLayout.SPEAKER] -- passing
     * [ConferenceStreamLayout.SINGLE_PARTICIPANT] here is a caller error ([startParticipantEgress]
     * is the correct call for that layout) and throws [IllegalArgumentException] before any network
     * call is made. [rtmpUrls] becomes a single `StreamOutput{protocol:"RTMP", urls:[...]}` --
     * multiple simultaneous destinations in ONE Twirp call, verified live (see class KDoc "Multi-
     * destination partial failure").
     *
     * **Renders through a Chrome-loaded web template** (`https://template.livekit.io` by default) --
     * see `network.lapis.cloud.shared.domain.ConferenceStreamLayout` KDoc "MAJOR GOTCHA" for the
     * exact `error_code 412`/"Start signal not received" failure signature this implies when that
     * host is unreachable. This is the reason [ConferenceStreamLayout.SINGLE_PARTICIPANT] /
     * [startParticipantEgress] is the only path verifiable in a network-restricted environment.
     */
    suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo

    /**
     * V1.0 Wave 3 "Externes Streaming" -- `POST .../StartParticipantEgress`, gated on the same
     * `roomRecord` grant. [identity] is the LiveKit participant identity to capture (this codebase's
     * own convention: always the member's UUID string, matching
     * [LiveKitAccessToken.mintParticipantToken]'s own `identity` contract) -- the SDK composites
     * that one participant's tracks with NO Chrome/template involved, which is why this is the
     * offline-verifiable egress path (see [startRoomCompositeEgress] KDoc "MAJOR GOTCHA"). Same
     * [rtmpUrls]/multi-destination/`preset`-`advanced`-oneof handling as [startRoomCompositeEgress].
     */
    suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo

    /**
     * V1.0 Wave 3 "Externes Streaming" -- `POST .../UpdateStream`, gated on the same `roomRecord`
     * grant. Verified live: `add_output_urls` attaches a NEW destination to a LIVE egress (the RTMP
     * sink logged the new publish ~21s later), and removing every URL via `remove_output_urls`
     * drives the egress to `EGRESS_ENDING` -> `egress_complete` (LiveKit has NO pause primitive --
     * see `network.lapis.cloud.shared.domain.ConferenceStreamStatus` KDoc "pauseStream = StopEgress
     * + PAUSED"). This wave uses [updateStream] internally NEVER -- the moderator-facing destination
     * set is fixed at [startStream][startRoomCompositeEgress]/[startParticipantEgress] time (see the
     * Wave 3 "out of scope" doc "Mid-stream destination add/remove") -- this method exists purely so
     * that fact is verified and available to a LATER wave, not left as an untested assumption.
     * [roomName] is passed for LEAST-PRIVILEGE token scoping only, same as [stopEgress] -- the
     * request body itself (`UpdateStreamRequest`) carries no room name.
     */
    suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String> = emptyList(),
        removeUrls: List<String> = emptyList(),
    ): LiveKitEgressInfo
}

/**
 * `HttpClient`-backed [LiveKitEgressClient] -- mirrors [HttpLiveKitAdminClient] EXACTLY: bounded
 * connect/request/socket timeouts, `followRedirects = false`, `expectSuccess = false`, a capped
 * response-body read ([readCappedLiveKitBodyOrNull]/[MAX_LIVEKIT_RESPONSE_BYTES], the SAME shared
 * constant/helper -- an `EgressInfo` response is bounded in exactly the same way a `ParticipantInfo`
 * roster is), a fresh [LiveKitAccessToken.mintEgressToken] minted for every single call (never
 * cached, never reused across calls -- same blast-radius reasoning [LiveKitAccessToken]'s own
 * KDoc gives for the admin-token shape), and every failure path (network error, non-2xx, oversized
 * body, unparseable JSON) mapped to the SAME [LiveKitAdminException] type [HttpLiveKitAdminClient]
 * already throws -- `ConferenceRecordingService`/`RecordingPoller` (a later wave) therefore need
 * exactly one exception type to catch across both LiveKit-touching clients, not two.
 *
 * **Deliberately reuses [defaultLiveKitAdminHttpClient]/[readCappedLiveKitBodyOrNull]** rather than
 * duplicating them -- both are already package-`internal`, already hardened, already used
 * identically by [HttpLiveKitAdminClient]; this class shares the same `HttpClient` DEFAULT (a fresh
 * instance per construction, same as [HttpLiveKitAdminClient]'s own default) rather than forcing a
 * second, near-identical client-hardening block to be kept in sync by hand.
 *
 * **Same deliberate non-application of the SSRF/private-range guard** as [HttpLiveKitAdminClient] --
 * [apiUrl] is OPERATOR environment configuration ([ConferenceConfig.livekitApiUrl], reused as-is;
 * this class carries no LiveKit credentials of its own, see [ConferenceRecordingConfig] KDoc), never
 * user input.
 */
class HttpLiveKitEgressClient(
    private val apiUrl: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val httpClient: HttpClient = defaultLiveKitAdminHttpClient(),
) : LiveKitEgressClient {
    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo =
        call(
            method = "StartTrackEgress",
            room = roomName,
            request =
                LiveKitStartTrackEgressRequest(
                    roomName = roomName,
                    trackId = trackId,
                    file = LiveKitDirectFileOutput(filepath = outputFilepathWithoutExtension, disableManifest = true),
                ),
        )

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo = call(method = "StopEgress", room = roomName, request = LiveKitStopEgressRequest(egressId = egressId))

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> =
        call<LiveKitListEgressRequest, LiveKitListEgressResponse>(
            method = "ListEgress",
            room = roomName,
            request = LiveKitListEgressRequest(roomName = roomName),
        ).items

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        require(layout != ConferenceStreamLayout.SINGLE_PARTICIPANT) {
            "startRoomCompositeEgress does not support ConferenceStreamLayout.SINGLE_PARTICIPANT -- " +
                "use startParticipantEgress instead (see LiveKitEgressClient KDoc)"
        }
        val wireLayout =
            when (layout) {
                ConferenceStreamLayout.GRID -> "grid"
                ConferenceStreamLayout.SPEAKER -> "speaker"
                ConferenceStreamLayout.SINGLE_PARTICIPANT -> error("unreachable, guarded above")
            }
        val streamOutputs = listOf(LiveKitStreamOutput(protocol = "RTMP", urls = rtmpUrls))
        return when (latencyMode) {
            ConferenceStreamLatencyMode.STANDARD ->
                call(
                    method = "StartRoomCompositeEgress",
                    room = roomName,
                    request =
                        LiveKitStartRoomCompositeEgressPresetRequest(
                            roomName = roomName,
                            layout = wireLayout,
                            preset = STANDARD_ENCODING_PRESET,
                            streamOutputs = streamOutputs,
                        ),
                )
            ConferenceStreamLatencyMode.LOW_LATENCY ->
                call(
                    method = "StartRoomCompositeEgress",
                    room = roomName,
                    request =
                        LiveKitStartRoomCompositeEgressAdvancedRequest(
                            roomName = roomName,
                            layout = wireLayout,
                            advanced = LOW_LATENCY_ENCODING_OPTIONS,
                            streamOutputs = streamOutputs,
                        ),
                )
        }
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        val streamOutputs = listOf(LiveKitStreamOutput(protocol = "RTMP", urls = rtmpUrls))
        return when (latencyMode) {
            ConferenceStreamLatencyMode.STANDARD ->
                call(
                    method = "StartParticipantEgress",
                    room = roomName,
                    request =
                        LiveKitStartParticipantEgressPresetRequest(
                            roomName = roomName,
                            identity = identity,
                            preset = STANDARD_ENCODING_PRESET,
                            streamOutputs = streamOutputs,
                        ),
                )
            ConferenceStreamLatencyMode.LOW_LATENCY ->
                call(
                    method = "StartParticipantEgress",
                    room = roomName,
                    request =
                        LiveKitStartParticipantEgressAdvancedRequest(
                            roomName = roomName,
                            identity = identity,
                            advanced = LOW_LATENCY_ENCODING_OPTIONS,
                            streamOutputs = streamOutputs,
                        ),
                )
        }
    }

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo =
        call(
            method = "UpdateStream",
            room = roomName,
            request = LiveKitUpdateStreamRequest(egressId = egressId, addOutputUrls = addUrls, removeOutputUrls = removeUrls),
        )

    /**
     * Mirrors [HttpLiveKitAdminClient]'s own private `call` helper byte-for-byte (same timeout/
     * capped-read/exception-mapping discipline), except the token mint calls
     * [LiveKitAccessToken.mintEgressToken] (`roomRecord`) instead of
     * [LiveKitAccessToken.mintAdminToken] (`roomCreate`/`roomAdmin`/`roomList`) -- see that
     * function's own KDoc "the three shapes are never mixed". [room] is REQUIRED here (unlike
     * [HttpLiveKitAdminClient]'s optional `room`) because [LiveKitAccessToken.mintEgressToken]
     * itself requires a room name -- every [LiveKitEgressClient] call site here passes one, even
     * [stopEgress] (whose request BODY only needs `egress_id` -- `room` is passed purely for
     * least-privilege token scoping, see that method's own KDoc).
     */
    private suspend inline fun <reified TReq, reified TRes> call(
        method: String,
        room: String,
        request: TReq,
    ): TRes {
        val egressToken = LiveKitAccessToken.mintEgressToken(apiKey = apiKey, apiSecret = apiSecret, room = room)
        val response =
            try {
                httpClient.post("$apiUrl/twirp/livekit.Egress/$method") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $egressToken")
                    setBody(LIVEKIT_JSON.encodeToString(request))
                }
            } catch (e: Exception) {
                logger.warn { "LiveKit Egress $method request failed (${e::class.simpleName ?: "unknown error"})" }
                throw LiveKitAdminException(
                    message = "LiveKit Egress $method request failed (${e::class.simpleName ?: "unknown error"})",
                    cause = e,
                )
            }
        if (!response.status.isSuccess()) {
            throw LiveKitAdminException(message = "LiveKit Egress $method returned HTTP ${response.status.value}")
        }
        val bytes =
            response.readCappedLiveKitBodyOrNull()
                ?: throw LiveKitAdminException(message = "LiveKit Egress $method response exceeded $MAX_LIVEKIT_RESPONSE_BYTES bytes")
        return try {
            LIVEKIT_JSON.decodeFromString(bytes.decodeToString())
        } catch (e: Exception) {
            throw LiveKitAdminException(message = "LiveKit Egress $method returned an unparseable response body")
        }
    }
}

/**
 * V1.0 Wave 3 "Externes Streaming" -- [network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode.STANDARD]'s
 * `preset` value. Verified working live (2026-08-09) against `deploy/local/docker-compose.yml`.
 */
internal const val STANDARD_ENCODING_PRESET = "H264_720P_30"

/**
 * V1.0 Wave 3 "Externes Streaming" -- [network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode.LOW_LATENCY]'s
 * `advanced` block. `key_frame_interval = 1` is the field that actually drives lower RTMP latency
 * (LiveKit exposes no dedicated "low latency" flag; latency is dominated by keyframe interval, see
 * [ConferenceStreamLatencyMode] KDoc). Every other field mirrors [STANDARD_ENCODING_PRESET]'s own
 * 720p30 profile so the ONLY behavioural difference between the two modes is keyframe cadence, not
 * an incidental resolution/bitrate change. **Verified working live** (2026-08-09): a
 * `StartParticipantEgress` carrying exactly this block reached `EGRESS_ACTIVE` and a real
 * `bluenviron/mediamtx` sink logged `stream is available and online, 2 tracks (H264, MPEG-4
 * Audio)` -- real media, not merely an accepted request.
 */
internal val LOW_LATENCY_ENCODING_OPTIONS =
    LiveKitEncodingOptions(
        width = 1280,
        height = 720,
        framerate = 30,
        videoCodec = "H264_MAIN",
        videoBitrate = 3000,
        audioCodec = "AAC",
        audioBitrate = 128,
        keyFrameInterval = 1,
    )

// ── Wire shapes -- verified against a live LiveKit v1.13.5 + egress v1.13.0 instance, see
// HttpLiveKitEgressClient KDoc ──

/**
 * `POST .../StartTrackEgress`/`StopEgress`/`ListEgress` response element (`livekit.EgressInfo`).
 * Real verified sample (`ListEgress` after a completed recording, 2026-08-09 -- see
 * [HttpLiveKitEgressClient] KDoc for how this was captured):
 * ```json
 * {"egress_id":"EG_WfWZUA9KKHxJ","room_id":"RM_Ed69chmBzgbN","room_name":"lc-egress-probe",
 *  "source_type":"EGRESS_SOURCE_TYPE_SDK","status":"EGRESS_COMPLETE",
 *  "started_at":"1786260219527104099","ended_at":"1786260242312546575",
 *  "updated_at":"1786260242312546575",
 *  "track":{"room_name":"lc-egress-probe","track_id":"TR_VCWo2vnDTWBCuu",
 *           "file":{"filepath":"/out/probe-test/egress-probe-publisher__CAMERA__TR_VCWo2vnDTWBCuu",
 *                   "disable_manifest":true},"webhooks":[]},
 *  "file_results":[{"filename":"/out/probe-test/egress-probe-publisher__CAMERA__TR_VCWo2vnDTWBCuu.mp4",
 *                   "started_at":"1786260219805661967","ended_at":"1786260242301779335",
 *                   "duration":"22496117368","size":"3810442",
 *                   "location":"/out/probe-test/....mp4"}],
 *  "error":"","error_code":0,"details":"End reason: StopEgress API"}
 * ```
 * **`status` is confirmed EGRESS_STARTING -> EGRESS_ACTIVE -> EGRESS_ENDING -> EGRESS_COMPLETE**
 * across this one live-observed lifecycle (2026-08-09) -- `EGRESS_FAILED`/`EGRESS_ABORTED`/
 * `EGRESS_LIMIT_REACHED` are transcribed from `livekit_egress.proto`'s `EgressStatus` enum and
 * remain UNVERIFIED against a live sample (no failure/abort was exercised) until a future wave
 * actually observes one. Kept as a plain [String] rather than a typed enum here -- same
 * "unparsed status the caller's own state machine maps, not a closed enum this DTO commits to"
 * posture [LiveKitRoomInfo]'s own trimmed-field philosophy already establishes; `RecordingPoller`
 * (a later wave) is the one place that interprets these strings against
 * `network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus`. `started_at`/`ended_at`/
 * `updated_at` are int64 NANOSECOND epoch timestamps (protobuf-JSON canonical string encoding,
 * same "kept as String, parse via `.toLongOrNull()` at the call site" reasoning [LiveKitRoomInfo]
 * KDoc gives for its own `creation_time`) -- note this is NANOSECONDS, not the SECONDS
 * [LiveKitRoomInfo.creationTimeEpochSeconds]/[LiveKitParticipantInfo.joinedAtEpochSeconds] use, a
 * genuine cross-endpoint inconsistency in LiveKit's own wire format, not a mistake in this DTO.
 * Every other field on the real response (`room_id`/`source_type`/`track`/
 * `segment_results`/`image_results`/`error_code`/`manifest_location`/`backup_storage_used`/
 * `retry_count`/the redundant top-level `file`) is deliberately NOT declared here -- unread,
 * covered by [LIVEKIT_JSON]'s `ignoreUnknownKeys`.
 *
 * **[streamResults] added V1.0 Wave 3 "Externes Streaming"** -- populated for
 * `StartRoomCompositeEgress`/`StartParticipantEgress` responses (empty for `StartTrackEgress`, which
 * has no `stream` output at all). Real verified sample (`StartParticipantEgress` with two RTMP
 * destinations, one reachable, one not, 2026-08-09 -- see [LiveKitStreamInfo] KDoc for the full
 * capture and [StreamUrlFingerprint] for why `stream_results` cannot be matched back to a
 * destination by URL equality or array position):
 * ```json
 * "stream_results":[
 *   {"url":"rtmp://bad-host:1935/live/{bad...HIJ}","status":"FAILED",
 *    "error":"Failed to connect: Error resolving “bad-host”: Name or service not known", ...},
 *   {"url":"rtmp://good-host:1935/live/{goo...789}","status":"ACTIVE","error":"", ...}
 * ]
 * ```
 */
@Serializable
data class LiveKitEgressInfo(
    @SerialName("egress_id") val egressId: String = "",
    @SerialName("room_name") val roomName: String = "",
    val status: String = "",
    @SerialName("started_at") val startedAtEpochNanos: String = "0",
    @SerialName("ended_at") val endedAtEpochNanos: String = "0",
    val error: String = "",
    @SerialName("file_results") val fileResults: List<LiveKitEgressFileInfo> = emptyList(),
    @SerialName("stream_results") val streamResults: List<LiveKitStreamInfo> = emptyList(),
) {
    /** Convenience accessor -- `file_results` is a single-element array for a Track Egress (one file per track), never read positionally elsewhere. `null` before the file result is populated (e.g. immediately after `StartTrackEgress`, before any bytes have been written -- see `filename` being `""` in that call's own real sample). */
    val firstFileResult: LiveKitEgressFileInfo?
        get() = fileResults.firstOrNull()?.takeIf { it.filename.isNotBlank() }
}

/**
 * One element of [LiveKitEgressInfo.fileResults] (`livekit.FileInfo`). [filename] is the REAL,
 * LiveKit-assigned path INCLUDING the extension it picked based on the published track's codec
 * (real verified sample: input `filepath` had NO extension, output `filename` gained `.mp4` because
 * the demo track published H.264 -- see [HttpLiveKitEgressClient.startTrackEgress] KDoc). `size`/
 * `duration` are int64 STRINGS (same protobuf-JSON canonical encoding as
 * [LiveKitEgressInfo]'s own timestamp fields) -- `duration` is NANOSECONDS (real verified sample:
 * `"22496117368"` for a ~22.5-second recording).
 */
@Serializable
data class LiveKitEgressFileInfo(
    val filename: String = "",
    @SerialName("started_at") val startedAtEpochNanos: String = "0",
    @SerialName("ended_at") val endedAtEpochNanos: String = "0",
    val duration: String = "0",
    val size: String = "0",
)

@Serializable
internal data class LiveKitStartTrackEgressRequest(
    @SerialName("room_name") val roomName: String,
    @SerialName("track_id") val trackId: String,
    val file: LiveKitDirectFileOutput,
)

/** `livekit.DirectFileOutput` -- see [HttpLiveKitEgressClient.startTrackEgress] KDoc "deliberately carries NO file extension". */
@Serializable
internal data class LiveKitDirectFileOutput(
    val filepath: String,
    @SerialName("disable_manifest") val disableManifest: Boolean,
)

@Serializable
internal data class LiveKitStopEgressRequest(
    @SerialName("egress_id") val egressId: String,
)

@Serializable
internal data class LiveKitListEgressRequest(
    @SerialName("room_name") val roomName: String,
)

/** Real verified sample: `{"items":[...LiveKitEgressInfo...],"next_page_token":null}` -- `items` (NOT `egress_items`/`egresses`), confirmed live 2026-08-09, closing the "unverified until observed" gap the Wave 2 plan flagged for this exact field name. `next_page_token` is unread -- this wave never paginates ([ConferenceRecordingConfig.maxTracks] bounds the result size per room to a small, DoS-safe ceiling). */
@Serializable
internal data class LiveKitListEgressResponse(
    val items: List<LiveKitEgressInfo> = emptyList(),
)

// ── V1.0 Wave 3 "Externes Streaming" wire shapes -- verified against a live LiveKit v1.13.5 +
// egress v1.13.0 instance, 2026-08-09, see HttpLiveKitEgressClient KDoc "V1.0 Wave 3" section ──

/**
 * `livekit.StreamOutput`, one element of a `StartRoomCompositeEgress`/`StartParticipantEgress`
 * request's `stream_outputs` array. Real verified sample (as echoed back inside the response's
 * `participant.stream_outputs`, key redacted by LiveKit itself -- see [StreamUrlFingerprint]):
 * ```json
 * {"protocol":"RTMP","urls":["rtmp://lapis-rtmp-probe:1935/live/{goo...789}"]}
 * ```
 * [urls] carries EVERY simultaneous RTMP destination for this ONE `StreamOutput` -- multi-
 * destination is a single Twirp call with N URLs, NOT N separate calls (verified live, see
 * [HttpLiveKitEgressClient] KDoc "Multi-destination partial failure"). [protocol] is always
 * literally `"RTMP"` in this codebase (Wave 3 explicitly excludes SRT output, see the Wave 3
 * "out of scope" doc) -- kept as a field rather than hardcoded into the request DTOs so the wire
 * shape stays a faithful transcription of `livekit.StreamOutput`, not a narrowed reinterpretation.
 */
@Serializable
internal data class LiveKitStreamOutput(
    val protocol: String,
    val urls: List<String>,
)

/**
 * `livekit.EncodingOptions`, the `advanced` half of the `preset`/`advanced` protobuf `oneof` on a
 * room-composite/participant egress request. See [LOW_LATENCY_ENCODING_OPTIONS] for this codebase's
 * ONE populated instance, and [HttpLiveKitEgressClient] KDoc "preset/advanced is a protobuf oneof"
 * for why this never travels alongside a `preset` field on the SAME request DTO.
 */
@Serializable
internal data class LiveKitEncodingOptions(
    val width: Int,
    val height: Int,
    val framerate: Int,
    @SerialName("video_codec") val videoCodec: String,
    @SerialName("video_bitrate") val videoBitrate: Int,
    @SerialName("audio_codec") val audioCodec: String,
    @SerialName("audio_bitrate") val audioBitrate: Int,
    @SerialName("key_frame_interval") val keyFrameInterval: Int,
)

/** `StartRoomCompositeEgress` request, `preset` branch of the oneof -- see [HttpLiveKitEgressClient] KDoc "preset/advanced is a protobuf oneof". */
@Serializable
internal data class LiveKitStartRoomCompositeEgressPresetRequest(
    @SerialName("room_name") val roomName: String,
    val layout: String,
    val preset: String,
    @SerialName("stream_outputs") val streamOutputs: List<LiveKitStreamOutput>,
)

/** `StartRoomCompositeEgress` request, `advanced` branch of the oneof -- see [LiveKitStartRoomCompositeEgressPresetRequest] sibling KDoc. */
@Serializable
internal data class LiveKitStartRoomCompositeEgressAdvancedRequest(
    @SerialName("room_name") val roomName: String,
    val layout: String,
    val advanced: LiveKitEncodingOptions,
    @SerialName("stream_outputs") val streamOutputs: List<LiveKitStreamOutput>,
)

/** `StartParticipantEgress` request, `preset` branch of the oneof -- see [LiveKitStartRoomCompositeEgressPresetRequest] sibling KDoc. No `layout` field -- a Participant Egress composites exactly one participant's own tracks, there is nothing to lay out. */
@Serializable
internal data class LiveKitStartParticipantEgressPresetRequest(
    @SerialName("room_name") val roomName: String,
    val identity: String,
    val preset: String,
    @SerialName("stream_outputs") val streamOutputs: List<LiveKitStreamOutput>,
)

/** `StartParticipantEgress` request, `advanced` branch of the oneof -- see [LiveKitStartParticipantEgressPresetRequest] sibling KDoc. */
@Serializable
internal data class LiveKitStartParticipantEgressAdvancedRequest(
    @SerialName("room_name") val roomName: String,
    val identity: String,
    val advanced: LiveKitEncodingOptions,
    @SerialName("stream_outputs") val streamOutputs: List<LiveKitStreamOutput>,
)

/**
 * `UpdateStream` request (`livekit.UpdateStreamRequest`). Real verified sample (adding a
 * destination to a LIVE egress): `{"egress_id":"EG_...","add_output_urls":["rtmp://..."]}`; removing
 * every URL: `{"egress_id":"EG_...","remove_output_urls":["rtmp://...","rtmp://..."]}` -- the latter
 * drives the egress to `EGRESS_ENDING` -> `egress_complete` (see
 * [HttpLiveKitEgressClient.updateStream] KDoc "LiveKit has NO pause primitive"). Both list fields
 * default to empty rather than being nullable -- an empty `repeated` field and an absent one are
 * wire-equivalent for Twirp/protobuf-JSON, so there is no oneof-style ambiguity here the way there
 * is for `preset`/`advanced` above.
 */
@Serializable
internal data class LiveKitUpdateStreamRequest(
    @SerialName("egress_id") val egressId: String,
    @SerialName("add_output_urls") val addOutputUrls: List<String> = emptyList(),
    @SerialName("remove_output_urls") val removeOutputUrls: List<String> = emptyList(),
)

/**
 * One element of [LiveKitEgressInfo.streamResults] (`livekit.StreamInfo`). Real verified sample
 * (`StartParticipantEgress` with two destinations, one unreachable, 2026-08-09 -- see
 * [HttpLiveKitEgressClient] KDoc "Multi-destination partial failure" for the full story):
 * ```json
 * {"url":"rtmp://nonexistent-bad-host-xyz:1935/live/{bad...HIJ}",
 *  "started_at":"1786277867171965027","ended_at":"1786277867171965027","duration":"0",
 *  "status":"FAILED",
 *  "error":"Failed to connect: Error resolving “nonexistent-bad-host-xyz”: Name or service not known",
 *  "last_retry_at":"0","retries":0}
 * ```
 * [url] is the REDACTED form LiveKit itself computed (see [StreamUrlFingerprint] for the exact rule
 * and why a caller must recompute the SAME redaction over the plaintext URL it sent, rather than
 * comparing against the plaintext directly, to know which destination this result belongs to) --
 * **`stream_results` array order does NOT match the request's `urls` order** (live-verified: two
 * URLs sent as (key1, key2) came back as (key2, key1)), so positional/index matching is ALSO wrong.
 * `status` is kept as a plain [String] -- real verified values `"ACTIVE"`/`"FINISHED"`/`"FAILED"`,
 * matching `livekit.StreamInfo.Status`'s three literals exactly -- same "the caller's own state
 * machine maps it, this DTO commits to no closed enum" posture [LiveKitEgressInfo.status]'s own KDoc
 * establishes; `network.lapis.cloud.server.StreamPoller` (a later wave) is the one place that maps
 * these onto `network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus`. [error] is RAW
 * LiveKit text and, per the real sample above, can ECHO BACK THE DESTINATION HOST -- it must NEVER
 * reach a client-facing DTO verbatim; `network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto.failureReason`
 * (a later wave) is a fixed, sanitized German vocabulary specifically because of this. `started_at`/
 * `ended_at`/`last_retry_at` are int64 NANOSECOND-epoch strings, same encoding as
 * [LiveKitEgressInfo]'s own timestamp fields.
 */
@Serializable
data class LiveKitStreamInfo(
    val url: String = "",
    @SerialName("started_at") val startedAtEpochNanos: String = "0",
    @SerialName("ended_at") val endedAtEpochNanos: String = "0",
    val duration: String = "0",
    val status: String = "",
    val error: String = "",
    @SerialName("last_retry_at") val lastRetryAtEpochNanos: String = "0",
    val retries: Int = 0,
)
