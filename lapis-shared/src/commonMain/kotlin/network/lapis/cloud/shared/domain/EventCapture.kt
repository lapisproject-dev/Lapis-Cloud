package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * V1.9.156 -- version of the OPERATOR notice an ADMIN acknowledges before switching on "recording and live streaming in event rooms". Same
 * contract as [PULPIT_RECORDING_NOTICE_VERSION]: a change of the reference text needs a new value here AND in `EventCaptureNotice`
 * (`EventCaptureNoticePinTest` pins both and the client paragraphs).
 */
const val EVENT_CAPTURE_NOTICE_VERSION: String = "1"

/**
 * V1.9.156 -- what a participant acknowledges before entering an event room whose organizer allowed recording / live streaming: the version
 * label and hash of the CURRENT published `EVENT_REC` text. The server re-checks both against the live text; it never trusts the client.
 */
@Serializable
data class EventCaptureAcknowledgmentInput(
    val consentVersion: String,
    val consentSha256: String,
)

/** V1.9.156 -- the consent text a participant is shown ([headline] and [keyPoints] follow the paragraph rule, [fullText] is hashed verbatim). */
@Serializable
data class EventCaptureNoticeDto(
    val versionLabel: String,
    val sha256: String,
    val headline: String,
    val keyPoints: List<String>,
    val fullText: String,
    /** `true` while no version was published (the built-in text is only a placeholder; capture cannot be switched on then). */
    val placeholder: Boolean = false,
    /** V1.9.196 -- how the text is displayed; [renderedHtml] is the server-rendered sanitized HTML of a MARKDOWN text (`null` for plain text). */
    val format: AgreementTextFormat = AgreementTextFormat.PLAIN,
    val renderedHtml: String? = null,
)

/** Why the release of one event cannot be changed right now (`NONE` = it can). */
@Serializable
enum class EventCaptureChangeLock {
    NONE,

    /** The instance switch is off (or the operator notice is out of date). */
    INSTANCE_OFF,

    /**
     * Not emitted since V1.9.280 (the release is no longer frozen while the room is active); the constant stays so that a client that still has an old
     * answer in memory deserializes it.
     */
    ROOM_ACTIVE,

    /** V1.9.280 -- a recording or a stream is running: the release can be switched on at any time, but off only after they ended. */
    CAPTURE_RUNNING,

    /** The caller may see the state but not change it. */
    NOT_PRIVILEGED,
}

/** V1.9.156 -- the capture state of ONE event, for the event form (organizers) and the join dialog (participants). */
@Serializable
data class EventCaptureStateDto(
    val instanceEnabled: Boolean,
    val allowed: Boolean,
    /** `true` iff entering this event requires the capture consent ([allowed] AND the instance switch is on AND the event is a Lapis conference). */
    val noticeRequired: Boolean,
    val changeLock: EventCaptureChangeLock,
    val mayChange: Boolean,
    val notice: EventCaptureNoticeDto? = null,
    /** V1.9.280 -- `true` while the online window is open or the room lives: a release switched on now is asked for inside the room. */
    val roomOpen: Boolean = false,
)

/** V1.9.156 -- whether the event room is being recorded / streamed right now. The banner reads only this and never lies by omission. */
@Serializable
data class EventCaptureLiveDto(
    val recording: Boolean,
    val streaming: Boolean,
)

/**
 * V1.9.156 -- the capture variant of the guest notice, used instead of [EventGuestNotice] in an event that allows recording or streaming (the
 * old text claims "Es wird nichts aufgezeichnet."). [EventGuestNotice] itself stays byte for byte unchanged: it is the proof of earlier
 * acknowledgments. [SHA256] = SHA-256 over [TEXT] alone, the same formula (`EventCaptureBuiltinTextPinTest`).
 */
object EventGuestNoticeCapture {
    const val VERSION: String = "2026-10-18.capture.v1"
    const val TEXT: String = "Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus."
    const val SHA256: String = "c9bc01afdd159ec7489eccb32224a9bf991cb4c7ba4b6ff36b7beae55c0f1fa3"
}

/** V1.9.156 -- sent ONLY by the server (LiveKit RoomService/SendData); clients accept it only without a participant (a guest may publish data). */
const val EVENT_CAPTURE_TOPIC = "lapis-event-capture"

/** Largest capture packet in bytes (`{"v":1,"r":1,"s":1}` is 19). Longer packets are dropped unread. */
const val EVENT_CAPTURE_MAX_PAYLOAD_BYTES = 32

/** The wire payload of [live]: `{"v":1,"r":0|1,"s":0|1}`. Carries no identity of anybody. */
fun eventCapturePayload(live: EventCaptureLiveDto): String =
    """{"v":1,"r":${if (live.recording) 1 else 0},"s":${if (live.streaming) 1 else 0}}"""

/**
 * Parses a capture packet. `null` if [fromParticipant] (a packet that carries a sending participant is a forgery of "nothing is recorded"), the
 * length is outside `1..EVENT_CAPTURE_MAX_PAYLOAD_BYTES`, keys other than `v`/`r`/`s` occur, or a value is not exactly the expected 1 / 0 / 1.
 * Never throws, never reads an identity.
 */
fun eventCapturePacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): EventCaptureLiveDto? {
    if (fromParticipant || payload.size !in 1..EVENT_CAPTURE_MAX_PAYLOAD_BYTES) return null
    return try {
        val obj = Json.parseToJsonElement(payload.decodeToString()) as? JsonObject ?: return null
        if (obj.keys.size != 3 || !obj.keys.all { it == "v" || it == "r" || it == "s" }) return null

        fun intOf(key: String): Int? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (intOf("v") != 1) return null
        val recording = intOf("r") ?: return null
        val streaming = intOf("s") ?: return null
        if (recording !in 0..1 || streaming !in 0..1) return null
        EventCaptureLiveDto(recording = recording == 1, streaming = streaming == 1)
    } catch (e: Exception) {
        null
    }
}

/** V1.9.280 -- sent ONLY by the server, ONLY to the people in the room who have not acknowledged the current capture consent (`{"v":1}`). */
const val EVENT_CAPTURE_ASK_TOPIC = "lapis-event-capture-ask"

/** Largest ask packet in bytes (`{"v":1}` is 7). */
const val EVENT_CAPTURE_ASK_MAX_PAYLOAD_BYTES = 16

/** V1.9.280 -- sent ONLY by the server, ONLY to the moderators of the room: `{"v":1,"n":N,"m":M}` (N of M acknowledged). Carries no identity. */
const val EVENT_CAPTURE_COUNT_TOPIC = "lapis-event-capture-count"

/** Largest count packet in bytes (`{"v":1,"n":999,"m":999}` is 25). */
const val EVENT_CAPTURE_COUNT_MAX_PAYLOAD_BYTES = 32

/** Counts are clamped to this on the wire. */
const val EVENT_CAPTURE_COUNT_MAX = 999

/** V1.9.280 -- "N of M people in the room acknowledged the capture consent". A number pair; names or decisions of individuals never leave the server. */
@Serializable
data class EventCaptureConsentCountDto(
    val acknowledged: Int,
    val total: Int,
)

/** The wire payload of the ask packet. */
fun eventCaptureAskPayload(): String = """{"v":1}"""

/** The wire payload of [count] (both numbers clamped to `0..EVENT_CAPTURE_COUNT_MAX`, and `n <= m`). */
fun eventCaptureCountPayload(count: EventCaptureConsentCountDto): String {
    val m = count.total.coerceIn(0, EVENT_CAPTURE_COUNT_MAX)
    val n = count.acknowledged.coerceIn(0, m)
    return """{"v":1,"n":$n,"m":$m}"""
}

/**
 * Parses an ask packet: `true` only for a packet WITHOUT a sending participant (a guest may publish data, so a packet with a sender is a forgery),
 * with a length in `1..EVENT_CAPTURE_ASK_MAX_PAYLOAD_BYTES` and exactly the key `v` = 1. Never throws.
 */
fun eventCaptureAskPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): Boolean {
    if (fromParticipant || payload.size !in 1..EVENT_CAPTURE_ASK_MAX_PAYLOAD_BYTES) return false
    return try {
        val obj = Json.parseToJsonElement(payload.decodeToString()) as? JsonObject ?: return false
        obj.keys.size == 1 && ((obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() == 1)
    } catch (e: Exception) {
        false
    }
}

/**
 * Parses a count packet. `null` if [fromParticipant], the length is outside `1..EVENT_CAPTURE_COUNT_MAX_PAYLOAD_BYTES`, other keys than `v`/`n`/`m`
 * occur, a value is not an integer, a count is outside `0..EVENT_CAPTURE_COUNT_MAX` or `n > m`. Never throws.
 */
fun eventCaptureCountPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): EventCaptureConsentCountDto? {
    if (fromParticipant || payload.size !in 1..EVENT_CAPTURE_COUNT_MAX_PAYLOAD_BYTES) return null
    return try {
        val obj = Json.parseToJsonElement(payload.decodeToString()) as? JsonObject ?: return null
        if (obj.keys.size != 3 || !obj.keys.all { it == "v" || it == "n" || it == "m" }) return null

        fun intOf(key: String): Int? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (intOf("v") != 1) return null
        val n = intOf("n") ?: return null
        val m = intOf("m") ?: return null
        if (n !in 0..EVENT_CAPTURE_COUNT_MAX || m !in 0..EVENT_CAPTURE_COUNT_MAX || n > m) return null
        EventCaptureConsentCountDto(acknowledged = n, total = m)
    } catch (e: Exception) {
        null
    }
}

/**
 * V1.9.280 -- body of `POST /api/events/{slug}/online-guest/capture-consent`: a guest WITHOUT an account who is already inside acknowledges the current
 * capture consent. The LiveKit [token] the server gave them proves who they are (it is in the BODY, never in a URL).
 */
@Serializable
data class EventGuestCaptureConsentRequestDto(
    val token: String,
    val consentVersion: String,
    val consentSha256: String,
)
