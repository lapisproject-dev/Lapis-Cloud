package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/** Hard cap on how many bytes of a LiveKit Twirp response body are ever read into memory -- mirrors [network.lapis.cloud.server.economy.oracle.MAX_ORACLE_RESPONSE_BYTES]/[network.lapis.cloud.server.federation.MAX_FEDERATION_RESPONSE_BYTES]'s own per-package convention (this codebase rolls one capped-read helper per outbound-HTTP package rather than a single shared util -- see those two files). A `ListRooms`/`ListParticipants` response is bounded by [ConferenceConfig.maxParticipants]-sized rooms/rosters, so 256 KiB is generous headroom, not a tight fit. */
internal const val MAX_LIVEKIT_RESPONSE_BYTES = 256 * 1024

/** Lenient JSON codec for LiveKit Twirp bodies -- `ignoreUnknownKeys` because every response DTO below only declares the fields this wave actually reads (e.g. [LiveKitRoomInfo] omits `turn_password`/`enabled_codecs`/`version`, all present on the real wire response -- see that class KDoc). */
internal val LIVEKIT_JSON = Json { ignoreUnknownKeys = true }

/**
 * Thrown by every [LiveKitAdminClient] failure path -- network error, non-2xx HTTP status, or an
 * unparseable response body. [message] is always a short, generic/templated description (method
 * name + status code or exception class name), **never** the raw response body or a raw exception
 * message verbatim -- same "never trust that a third-party payload or a JVM exception's
 * `toString()` cannot contain something sensitive" discipline
 * [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s KDoc establishes for its own
 * outbound HTTP call. Never carries the `Authorization` header value or [ConferenceConfig.apiSecret].
 */
class LiveKitAdminException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The pluggable LiveKit Room-Service admin boundary -- this wave's analogue of
 * [network.lapis.cloud.server.economy.LtrBalanceProvider]/
 * [network.lapis.cloud.server.postal.PostalMailProvider]: a future wave's `ConferenceService`
 * depends on this interface, not on [HttpLiveKitAdminClient] directly, so tests can substitute a
 * fake with zero network involved (see [LiveKitAdminClientTest] for the real implementation's own
 * `ktor-client-mock`-backed coverage; a future wave's `ConferenceServiceTest` gets an in-memory fake
 * of THIS interface instead).
 */
interface LiveKitAdminClient {
    /** `POST .../CreateRoom` -- gated on the admin token's `roomCreate` grant (verified empirically to need no `room` claim, see [LiveKitAccessToken] KDoc). Idempotent from LiveKit's own side in practice (creating an already-existing room returns its existing [LiveKitRoomInfo] rather than erroring), but this wave always calls it with a freshly generated, collision-proof `lc-<uuid4>` name, so idempotency is never actually relied upon. */
    suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo

    /** `POST .../DeleteRoom` -- gated on `roomCreate`, same as [createRoom]. Disconnects every current participant. */
    suspend fun deleteRoom(name: String)

    /** `POST .../ListRooms` -- gated on `roomList`, global (no `room` scoping applies or is needed). */
    suspend fun listRooms(): List<LiveKitRoomInfo>

    /** `POST .../ListParticipants` -- gated on `roomAdmin`; REQUIRES the admin token's `video.room` claim to equal [room] (empirically verified, see [LiveKitAccessToken] KDoc) or LiveKit returns `401 permissions denied`. */
    suspend fun listParticipants(room: String): List<LiveKitParticipantInfo>

    /** `POST .../RemoveParticipant` -- gated on `roomAdmin`, same room-scoping requirement as [listParticipants]. */
    suspend fun removeParticipant(
        room: String,
        identity: String,
    )
}

/**
 * `HttpClient`-backed [LiveKitAdminClient] -- a thin Twirp-over-HTTP/JSON client, not a generated
 * gRPC/protobuf stub (this wave needs exactly five methods; pulling in a protobuf/gRPC toolchain
 * for that is disproportionate, see the Wave 1 plan's own "no SDK, hand-rolled" decision). Every
 * request/response JSON shape below was checked against a REAL, locally-running LiveKit v1.13.5
 * instance (`deploy/local/docker-compose.yml`, 2026-08-09) -- see each DTO's own KDoc for the exact
 * verified sample. This is a firmer footing than
 * [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s or
 * [network.lapis.cloud.server.economy.oracle.BitcoinPriceSources]' own "reconstructed from
 * documentation, NOT verified against a live response" disclaimer -- those two integrations have no
 * reachable sandbox; this one does (a self-hosted Docker image), so the shapes here were actually
 * observed, not guessed. Still worth a human's final check against LiveKit's own protobuf source if
 * a future LiveKit version changes the wire format.
 *
 * **Deliberate non-application of the SSRF/private-range guard** (unlike
 * [network.lapis.cloud.server.economy.oracle.requireAllowlistedHttpsUrl]/
 * [network.lapis.cloud.server.federation.requireSafeFederationUrl]): [apiUrl] is OPERATOR
 * environment configuration ([ConferenceConfig.livekitApiUrl]), never user input, and in every
 * realistic deployment LiveKit lives on loopback or a private address reachable only from this
 * server -- routing it through a private-range blocklist would break the normal case entirely. This
 * is intentional, not an oversight the security-audit loop should "fix".
 *
 * Ktor client hardening mirrors [network.lapis.cloud.server.economy.oracle.oracleHttpClient]/
 * [network.lapis.cloud.server.federation.federationHttpClient]: bounded connect/request/socket
 * timeouts, `followRedirects = false`, `expectSuccess = false` (every call site inspects
 * [HttpResponse.status] itself), and a capped response-body read
 * ([MAX_LIVEKIT_RESPONSE_BYTES]) so a misbehaving/compromised LiveKit instance can never stall or
 * OOM this server. A fresh [LiveKitAccessToken.mintAdminToken] is minted for every single call (see
 * that function's KDoc for the TTL/blast-radius reasoning) -- never cached, never reused across
 * calls.
 */
class HttpLiveKitAdminClient(
    private val apiUrl: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val httpClient: HttpClient = defaultLiveKitAdminHttpClient(),
) : LiveKitAdminClient {
    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo =
        call(
            method = "CreateRoom",
            room = name,
            request = LiveKitCreateRoomRequest(name = name, maxParticipants = maxParticipants, emptyTimeout = emptyTimeoutSeconds),
        )

    override suspend fun deleteRoom(name: String) {
        call<LiveKitRoomIdentifier, LiveKitEmptyResponse>(method = "DeleteRoom", room = name, request = LiveKitRoomIdentifier(room = name))
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> =
        call<LiveKitEmptyRequest, LiveKitListRoomsResponse>(method = "ListRooms", room = null, request = LiveKitEmptyRequest()).rooms

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> =
        call<LiveKitRoomIdentifier, LiveKitListParticipantsResponse>(
            method = "ListParticipants",
            room = room,
            request = LiveKitRoomIdentifier(room = room),
        ).participants

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        call<LiveKitRoomParticipantIdentity, LiveKitEmptyResponse>(
            method = "RemoveParticipant",
            room = room,
            request = LiveKitRoomParticipantIdentity(room = room, identity = identity),
        )
    }

    /**
     * One Twirp call: mint a fresh admin token scoped to [room] (see [LiveKitAdminClient] method
     * KDocs for which calls actually need the scoping vs. which ignore it), POST [request] as JSON
     * to `{apiUrl}/twirp/livekit.RoomService/{method}`, and decode the response as [TRes]. Every
     * failure path -- connect/timeout, non-2xx status, oversized body, unparseable JSON -- maps to
     * [LiveKitAdminException] with a sanitized message (see that class KDoc); nothing propagates a
     * raw exception, response body, or the `Authorization` header value.
     */
    private suspend inline fun <reified TReq, reified TRes> call(
        method: String,
        room: String?,
        request: TReq,
    ): TRes {
        val adminToken = LiveKitAccessToken.mintAdminToken(apiKey = apiKey, apiSecret = apiSecret, room = room)
        val response =
            try {
                httpClient.post("$apiUrl/twirp/livekit.RoomService/$method") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                    setBody(LIVEKIT_JSON.encodeToString(request))
                }
            } catch (e: Exception) {
                logger.warn { "LiveKit $method request failed (${e::class.simpleName ?: "unknown error"})" }
                throw LiveKitAdminException(
                    message = "LiveKit $method request failed (${e::class.simpleName ?: "unknown error"})",
                    cause = e,
                )
            }
        if (!response.status.isSuccess()) {
            throw LiveKitAdminException(message = "LiveKit $method returned HTTP ${response.status.value}")
        }
        val bytes =
            response.readCappedLiveKitBodyOrNull()
                ?: throw LiveKitAdminException(message = "LiveKit $method response exceeded $MAX_LIVEKIT_RESPONSE_BYTES bytes")
        return try {
            LIVEKIT_JSON.decodeFromString(bytes.decodeToString())
        } catch (e: Exception) {
            throw LiveKitAdminException(message = "LiveKit $method returned an unparseable response body")
        }
    }
}

internal fun defaultLiveKitAdminHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
        followRedirects = false
    }

/** See [MAX_LIVEKIT_RESPONSE_BYTES] KDoc for why this package rolls its own capped-read helper rather than sharing one across packages. */
internal suspend fun HttpResponse.readCappedLiveKitBodyOrNull(): ByteArray? {
    val channel = bodyAsChannel()
    val buffer = ByteArray(MAX_LIVEKIT_RESPONSE_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_LIVEKIT_RESPONSE_BYTES) null else buffer.copyOf(total)
}

// ── Wire shapes -- verified against a live LiveKit v1.13.5 instance, see HttpLiveKitAdminClient KDoc ──

/**
 * `POST .../CreateRoom` response, and one element of `POST .../ListRooms`' `rooms` array -- both
 * return the SAME `livekit.Room` protobuf message. Real verified sample (`docker compose -f
 * deploy/local/docker-compose.yml up -d`, 2026-08-09):
 * ```json
 * {"sid":"RM_ZDQHUipfqNzr","name":"lc-test-room","empty_timeout":300,"departure_timeout":20,
 *  "max_participants":25,"creation_time":"1786241201","creation_time_ms":"1786241201429",
 *  "turn_password":"...","enabled_codecs":[...],"metadata":"","num_participants":0,
 *  "num_publishers":0,"active_recording":false,"version":null}
 * ```
 * **snake_case, not camelCase** -- corrects an assumption the Wave 1 plan made from LiveKit's
 * documentation alone (protobuf-JSON's *default* canonical mapping is camelCase, but LiveKit's
 * actual Twirp JSON output preserves the proto field names verbatim). `creation_time`/
 * `creation_time_ms` are int64 fields and arrive as JSON STRINGS (protobuf-JSON's canonical int64
 * encoding, to avoid precision loss for JS clients) -- kept as [String] here rather than [Long] for
 * exactly that reason; a future wave that needs to compute with them should parse via
 * `.toLongOrNull()` at the call site rather than this DTO silently assuming success.
 * `turn_password`/`enabled_codecs`/`version`/`departure_timeout`/`metadata` are intentionally NOT
 * declared here -- this wave never reads them, and [LIVEKIT_JSON]'s `ignoreUnknownKeys` means
 * omitting a field is simply "not parsed", not an error. `turn_password` in particular MUST NOT be
 * added to this DTO without a matching "never logged" discipline the way [ConferenceConfig.apiSecret]
 * has one.
 */
@Serializable
data class LiveKitRoomInfo(
    val sid: String = "",
    val name: String = "",
    @SerialName("empty_timeout") val emptyTimeoutSeconds: Int = 0,
    @SerialName("max_participants") val maxParticipants: Int = 0,
    @SerialName("creation_time") val creationTimeEpochSeconds: String = "0",
    @SerialName("num_participants") val numParticipants: Int = 0,
)

/**
 * `POST .../ListParticipants` response element (`livekit.ParticipantInfo`). Field NAMES follow
 * [LiveKitRoomInfo]'s confirmed snake_case convention. Wave 1 originally shipped this shape
 * verified only for the EMPTY-roster case -- [tracks] (V1.0 Wave 2 "Aufzeichnung") closes that gap:
 * verified against a REAL connected participant with a real published video track (`docker compose
 * -f deploy/local/docker-compose.yml up -d` + `livekit/livekit-cli`'s `room join --publish-demo`,
 * 2026-08-09). Real verified sample (trimmed to the fields this codebase actually reads -- the raw
 * response additionally carries `permission`/`region`/`is_publisher`/`kind`/`attributes`/
 * `disconnect_reason` and more, all covered by [LIVEKIT_JSON]'s `ignoreUnknownKeys`):
 * ```json
 * {"sid":"PA_WJT5hYBQPSm6","identity":"probe-publisher","state":"ACTIVE",
 *  "tracks":[{"sid":"TR_VCnrGXdDfrQmBL","type":"VIDEO","name":"demo","muted":false,
 *             "source":"CAMERA", "...(simulcast layers, codecs, mime_type, etc. -- not read here)"}],
 *  "joined_at":"1786260143","joined_at_ms":"1786260143075","name":""}
 * ```
 */
@Serializable
data class LiveKitParticipantInfo(
    val sid: String = "",
    val identity: String = "",
    val name: String = "",
    val state: String = "",
    @SerialName("joined_at") val joinedAtEpochSeconds: String = "0",
    val tracks: List<LiveKitTrackInfo> = emptyList(),
)

/**
 * One element of [LiveKitParticipantInfo.tracks] (`livekit.TrackInfo`) -- only the four fields
 * [network.lapis.cloud.server.RecordingPoller] (a later wave) actually needs to decide which tracks
 * need their own `StartTrackEgress`: [sid] (the `track_id` [LiveKitEgressClient.startTrackEgress]
 * takes), [type] (`AUDIO`/`VIDEO`, real verified value `"VIDEO"` above), [source] (`CAMERA`/
 * `MICROPHONE`/`SCREEN_SHARE`/`SCREEN_SHARE_AUDIO`/`UNKNOWN` per `livekit.proto`'s `TrackSource`
 * enum -- only `CAMERA` empirically observed so far, the rest transcribed from the proto and
 * unverified against a live sample until a future wave actually exercises a microphone/screen-share
 * publish against this stack), and [muted]. Every other field on the real wire response (simulcast
 * `layers`, `codecs`, `mime_type`, `width`/`height`, `stream`, `version`, ...) is deliberately NOT
 * declared here -- this wave never reads them, see [LIVEKIT_JSON]'s `ignoreUnknownKeys`.
 */
@Serializable
data class LiveKitTrackInfo(
    val sid: String = "",
    val type: String = "",
    val source: String = "",
    val muted: Boolean = false,
)

@Serializable
internal data class LiveKitCreateRoomRequest(
    val name: String,
    @SerialName("max_participants") val maxParticipants: Int,
    @SerialName("empty_timeout") val emptyTimeout: Int,
)

/** `DeleteRoom`/`ListParticipants` request shape -- both take a single `room` field. */
@Serializable
internal data class LiveKitRoomIdentifier(
    val room: String,
)

/** `RemoveParticipant` request shape. */
@Serializable
internal data class LiveKitRoomParticipantIdentity(
    val room: String,
    val identity: String,
)

/** `ListRooms` takes an empty request body (`{}`) -- an optional `names` filter exists on the real proto but this wave always lists every room, matching the "lazy reconciliation" use case a future wave's `ConferenceService` needs. */
@Serializable
internal class LiveKitEmptyRequest

/** Real verified sample: `{"rooms":[]}` / `{"rooms":[{...LiveKitRoomInfo...}]}`. */
@Serializable
internal data class LiveKitListRoomsResponse(
    val rooms: List<LiveKitRoomInfo> = emptyList(),
)

/** Real verified sample (empty room): `{"participants":[]}`. */
@Serializable
internal data class LiveKitListParticipantsResponse(
    val participants: List<LiveKitParticipantInfo> = emptyList(),
)

/** `DeleteRoom`/`RemoveParticipant` both return `google.protobuf.Empty` -- real verified sample: `{}`. Nothing to read; only used to give [HttpLiveKitAdminClient.call] a `reified` type to decode into so the shared helper stays uniform across every method. */
@Serializable
internal class LiveKitEmptyResponse
