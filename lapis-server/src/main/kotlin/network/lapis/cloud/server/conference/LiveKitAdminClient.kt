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
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_TOPIC
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_TOPIC
import network.lapis.cloud.shared.domain.ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_TOPIC
import network.lapis.cloud.shared.domain.ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ENCOUNTER_RECORDING_TOPIC
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_TOPIC
import java.security.SecureRandom
import kotlin.io.encoding.Base64

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
        departureTimeoutSeconds: Int? = null,
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

    /**
     * `POST .../SendData` (V1.9.95) -- RELIABLE data packet to everybody in [room] (no destination identities); the packet carries NO
     * sender, so receivers see it as "from the server". Gated on `roomAdmin`/`roomSend`-style room-scoped admin token (same scoping
     * requirement as [listParticipants]). No default implementation on purpose: a silent no-op would hide a missing override.
     */
    suspend fun sendData(
        room: String,
        topic: String,
        payload: ByteArray,
    )

    /**
     * `POST .../MutePublishedTrack` (V1.9.108) -- mutes one published track of [identity]. **Always `muted = true`**: there is
     * deliberately no parameter and no other method that could switch a microphone on (a moderator may only ask, never force).
     * Gated on `roomAdmin`, room-scoped token like [listParticipants].
     */
    suspend fun muteMicrophone(
        room: String,
        identity: String,
        trackSid: String,
    )

    /**
     * `POST .../UpdateParticipant` (V1.9.108) -- replaces the WHOLE permission of [identity] (LiveKit does not merge), so [permission]
     * is always complete; build it with [conferencePermission]. Never touches metadata, attributes or the name.
     */
    suspend fun updateParticipantPermission(
        room: String,
        identity: String,
        permission: LiveKitParticipantPermissionUpdate,
    )

    /**
     * `POST .../SendData` with `destination_identities` (V1.9.108) -- like [sendData] but only [destinationIdentities] receive the packet
     * (1..[MAX_SEND_DATA_DESTINATIONS], never empty: an empty list would mean "everybody" in LiveKit's API).
     */
    suspend fun sendDataTo(
        room: String,
        topic: String,
        payload: ByteArray,
        destinationIdentities: List<String>,
    )

    /**
     * `POST .../UpdateSubscriptions` (V1.9.161, preparation for the proximity audio of encounter stage 3) -- the SERVER subscribes
     * [identity] to ([subscribe] = `true`) or unsubscribes it from (`false`) the tracks [trackSids] (1..[MAX_UPDATE_SUBSCRIPTION_TRACKS],
     * never empty: LiveKit answers an empty list with 200 and does nothing, which would hide a caller bug). Gated on `roomAdmin`, room-scoped
     * token like [listParticipants] (without the room claim LiveKit answers 401). Measured against LiveKit v1.13.5: a subscription is in
     * place after about 40 ms, an unsubscription stops the audio within 500 ms, and a flip-flop faster than about 300 ms per participant and
     * track is merged by LiveKit (the caller needs a minimum interval and a hysteresis). An identity that is not in the room is answered
     * with 503, mapped to [LiveKitAdminException] like every other failure.
     *
     * The DEFAULT throws on purpose (a loud [UnsupportedOperationException], not a silent no-op): nothing calls this method yet, so the 17
     * test fakes of this interface do not have to grow an override that nothing exercises. The first caller must override it in its fake.
     */
    suspend fun updateSubscriptions(
        room: String,
        identity: String,
        trackSids: List<String>,
        subscribe: Boolean,
    ): Unit = throw UnsupportedOperationException("updateSubscriptions is not implemented by this LiveKitAdminClient")
}

/** Most destination identities of one targeted `SendData` call. */
const val MAX_SEND_DATA_DESTINATIONS = 50

/** Most tracks of one `UpdateSubscriptions` call. */
const val MAX_UPDATE_SUBSCRIPTION_TRACKS = 50

/** A LiveKit track sid: `TR_` followed by letters and digits only (what LiveKit issues); anything else never reaches the wire. */
private val TRACK_SID_PATTERN = Regex("^TR_[A-Za-z0-9]{1,60}$")

/** The publish sources of an ordinary conference participant WITHOUT the microphone (LiveKit's protojson enum names). */
private val CONFERENCE_SOURCES_WITHOUT_MICROPHONE = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO")

/**
 * The one place that builds a participant permission for [LiveKitAdminClient.updateParticipantPermission]: always subscribe + publish +
 * publish-data (what an ordinary participant token grants), with the microphone listed in `can_publish_sources` only if
 * [microphoneAllowed]. Every value is explicit -- `UpdateParticipant` replaces the whole permission, so a missing field would silently
 * take sound, camera or screen share away from the person.
 */
fun conferencePermission(microphoneAllowed: Boolean): LiveKitParticipantPermissionUpdate =
    LiveKitParticipantPermissionUpdate(
        canSubscribe = true,
        canPublish = true,
        canPublishData = true,
        canPublishSources =
            if (microphoneAllowed) CONFERENCE_SOURCES_WITHOUT_MICROPHONE + "MICROPHONE" else CONFERENCE_SOURCES_WITHOUT_MICROPHONE,
    )

/**
 * V1.9.138 -- the ONE permission of the temporary floor in an encounter space: subscribe, and publish the MICROPHONE only. Never camera,
 * never screen share, never built via [conferencePermission]. [canPublishSources] is `["MICROPHONE"]` even when the floor is TAKEN AWAY
 * ([granted] `false`): an empty list means "all sources" in LiveKit, so should `can_publish` ever be evaluated wrongly, the camera would
 * still stay locked (defence in depth). Every value is explicit -- `UpdateParticipant` replaces the whole permission.
 */
fun encounterFloorPermission(
    granted: Boolean,
    canPublishData: Boolean,
): LiveKitParticipantPermissionUpdate =
    LiveKitParticipantPermissionUpdate(
        canSubscribe = true,
        canPublish = granted,
        canPublishData = canPublishData,
        canPublishSources = listOf("MICROPHONE"),
    )

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
        departureTimeoutSeconds: Int?,
    ): LiveKitRoomInfo =
        call(
            method = "CreateRoom",
            room = name,
            request =
                LiveKitCreateRoomRequest(
                    name = name,
                    maxParticipants = maxParticipants,
                    emptyTimeout = emptyTimeoutSeconds,
                    departureTimeout = departureTimeoutSeconds,
                ),
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

    override suspend fun sendData(
        room: String,
        topic: String,
        payload: ByteArray,
    ) {
        require(payload.size <= sendDataPayloadLimit(topic)) { "SendData payload too large" }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        call<LiveKitSendDataRequest, LiveKitEmptyResponse>(
            method = "SendData",
            room = room,
            request =
                LiveKitSendDataRequest(
                    room = room,
                    data = Base64.Default.encode(payload),
                    kind = "RELIABLE",
                    topic = topic,
                    nonce = Base64.Default.encode(nonce),
                ),
        )
    }

    override suspend fun muteMicrophone(
        room: String,
        identity: String,
        trackSid: String,
    ) {
        call<LiveKitMutePublishedTrackRequest, LiveKitEmptyResponse>(
            method = "MutePublishedTrack",
            room = room,
            request = LiveKitMutePublishedTrackRequest(room = room, identity = identity, trackSid = trackSid),
        )
    }

    override suspend fun updateParticipantPermission(
        room: String,
        identity: String,
        permission: LiveKitParticipantPermissionUpdate,
    ) {
        call<LiveKitUpdateParticipantRequest, LiveKitEmptyResponse>(
            method = "UpdateParticipant",
            room = room,
            request = LiveKitUpdateParticipantRequest(room = room, identity = identity, permission = permission),
        )
    }

    override suspend fun sendDataTo(
        room: String,
        topic: String,
        payload: ByteArray,
        destinationIdentities: List<String>,
    ) {
        require(destinationIdentities.isNotEmpty()) { "SendData needs at least one destination (an empty list would address everybody)" }
        require(destinationIdentities.size <= MAX_SEND_DATA_DESTINATIONS) { "SendData has too many destinations" }
        require(payload.size <= sendDataPayloadLimit(topic)) { "SendData payload too large" }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        call<LiveKitSendDataRequest, LiveKitEmptyResponse>(
            method = "SendData",
            room = room,
            request =
                LiveKitSendDataRequest(
                    room = room,
                    data = Base64.Default.encode(payload),
                    kind = "RELIABLE",
                    topic = topic,
                    nonce = Base64.Default.encode(nonce),
                    destinationIdentities = destinationIdentities,
                ),
        )
    }

    override suspend fun updateSubscriptions(
        room: String,
        identity: String,
        trackSids: List<String>,
        subscribe: Boolean,
    ) {
        require(trackSids.isNotEmpty()) { "UpdateSubscriptions needs at least one track (an empty list is a silent no-op in LiveKit)" }
        require(trackSids.size <= MAX_UPDATE_SUBSCRIPTION_TRACKS) { "UpdateSubscriptions has too many tracks" }
        require(trackSids.all { TRACK_SID_PATTERN.matches(it) }) { "UpdateSubscriptions: not a LiveKit track sid" }
        call<LiveKitUpdateSubscriptionsRequest, LiveKitEmptyResponse>(
            method = "UpdateSubscriptions",
            room = room,
            request = LiveKitUpdateSubscriptionsRequest(room = room, identity = identity, trackSids = trackSids, subscribe = subscribe),
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

private const val NONCE_BYTES = 16

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
    /** The grants of the token the participant is connected with (`livekit.ParticipantPermission`); `null` if LiveKit did not report them. */
    val permission: LiveKitParticipantPermission? = null,
)

/**
 * `livekit.ParticipantPermission` -- only the two grants the encounter poller compares against the server's current decision
 * (snake_case like every other field of this API). A field that LiveKit omits (protobuf JSON drops `false`) decodes as `false`.
 */
@Serializable
data class LiveKitParticipantPermission(
    @SerialName("can_publish") val canPublish: Boolean = false,
    @SerialName("can_publish_data") val canPublishData: Boolean = false,
    @SerialName("can_subscribe") val canSubscribe: Boolean = false,
    /** LiveKit's protojson enum names (`CAMERA`, `MICROPHONE`, ...). Empty = "all sources allowed" in LiveKit's semantics. */
    @SerialName("can_publish_sources") val canPublishSources: List<String> = emptyList(),
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
    // Null = omitted from the JSON (default Json does not encode defaults) so LiveKit applies its own 20 s default.
    @SerialName("departure_timeout") val departureTimeout: Int? = null,
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

/**
 * `SendData` request shape (protojson: `data` is Base64 of the bytes; `nonce` is 16 random bytes, Base64 -- server-side dedup, not a
 * counter). [destinationIdentities] `null` (omitted) = broadcast, exactly as before V1.9.108.
 */
@Serializable
internal data class LiveKitSendDataRequest(
    val room: String,
    val data: String,
    val kind: String,
    val topic: String,
    val nonce: String,
    @SerialName("destination_identities") val destinationIdentities: List<String>? = null,
)

/**
 * `UpdateSubscriptions` request shape. [subscribe] has NO default value on purpose: [LIVEKIT_JSON] omits properties that equal their default
 * (pitfall S1), and `false` is the unsubscribe -- a defaulted `subscribe = false` would vanish from the wire and LiveKit would read the proto
 * default, which is also `false`, but a defaulted `true` would turn every unsubscribe into a subscription.
 */
@Serializable
internal data class LiveKitUpdateSubscriptionsRequest(
    val room: String,
    val identity: String,
    @SerialName("track_sids") val trackSids: List<String>,
    val subscribe: Boolean,
)

/**
 * `MutePublishedTrack` request shape. **Pitfall S1:** [LIVEKIT_JSON] does not encode default values, so a plain `muted = true` default would
 * be OMITTED and LiveKit would read `false` -- the mute would silently do nothing. `@EncodeDefault(ALWAYS)` forces it onto the wire, and
 * `init { require(muted) }` makes an "unmute" request impossible to construct at all.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class LiveKitMutePublishedTrackRequest(
    val room: String,
    val identity: String,
    @SerialName("track_sid") val trackSid: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val muted: Boolean = true,
) {
    init {
        require(muted) { "A forced unmute is never sent" }
    }
}

/**
 * `livekit.ParticipantPermission` as SENT by `UpdateParticipant`. LiveKit replaces the permission as a whole, so every field is explicit and
 * has no default (pitfall S2). Build via [conferencePermission].
 */
@Serializable
data class LiveKitParticipantPermissionUpdate(
    @SerialName("can_subscribe") val canSubscribe: Boolean,
    @SerialName("can_publish") val canPublish: Boolean,
    @SerialName("can_publish_data") val canPublishData: Boolean,
    @SerialName("can_publish_sources") val canPublishSources: List<String>,
)

/** `UpdateParticipant` request shape -- carries ONLY the permission (never metadata, attributes or name). */
@Serializable
internal data class LiveKitUpdateParticipantRequest(
    val room: String,
    val identity: String,
    val permission: LiveKitParticipantPermissionUpdate,
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

/**
 * Largest payload [LiveKitAdminClient.sendData]/`sendDataTo` accepts for [topic]. V1.9.133: one limit per topic -- before, only the
 * moderation topic had its own limit and every other topic (including the raised-hand queue of V1.9.127, about 47 bytes even when empty) fell
 * back to the 16-byte encounter-blessing limit and failed with an `IllegalArgumentException`.
 */
internal fun sendDataPayloadLimit(topic: String): Int =
    when (topic) {
        CONFERENCE_MODERATION_TOPIC -> CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES
        CONFERENCE_HANDS_TOPIC -> CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
        CONFERENCE_TIMER_TOPIC -> CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
        ENCOUNTER_FLOOR_TOPIC -> ENCOUNTER_FLOOR_MAX_PAYLOAD_BYTES
        // V1.9.143: without this entry the topic would fall back to the 16-byte blessing limit and every send would fail.
        ENCOUNTER_RECORDING_TOPIC -> ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES
        // V1.9.156: the event-room capture banner (same reason: without this entry the send would fail on the 16-byte fallback).
        EVENT_CAPTURE_TOPIC -> EVENT_CAPTURE_MAX_PAYLOAD_BYTES
        else -> ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES
    }
