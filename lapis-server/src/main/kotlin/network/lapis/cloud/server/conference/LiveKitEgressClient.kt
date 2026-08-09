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
                throw LiveKitAdminException("LiveKit Egress $method request failed (${e::class.simpleName ?: "unknown error"})", e)
            }
        if (!response.status.isSuccess()) {
            throw LiveKitAdminException("LiveKit Egress $method returned HTTP ${response.status.value}")
        }
        val bytes =
            response.readCappedLiveKitBodyOrNull()
                ?: throw LiveKitAdminException("LiveKit Egress $method response exceeded $MAX_LIVEKIT_RESPONSE_BYTES bytes")
        return try {
            LIVEKIT_JSON.decodeFromString(bytes.decodeToString())
        } catch (e: Exception) {
            throw LiveKitAdminException("LiveKit Egress $method returned an unparseable response body")
        }
    }
}

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
 * Every other field on the real response (`room_id`/`source_type`/`track`/`stream_results`/
 * `segment_results`/`image_results`/`error_code`/`manifest_location`/`backup_storage_used`/
 * `retry_count`/the redundant top-level `file`) is deliberately NOT declared here -- unread,
 * covered by [LIVEKIT_JSON]'s `ignoreUnknownKeys`.
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
