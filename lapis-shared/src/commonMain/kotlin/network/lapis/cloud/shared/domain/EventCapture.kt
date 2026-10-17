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
)

/** Why the release of one event cannot be changed right now (`NONE` = it can). */
@Serializable
enum class EventCaptureChangeLock {
    NONE,

    /** The instance switch is off (or the operator notice is out of date). */
    INSTANCE_OFF,

    /** The online window is open, the room lives, a recording / stream runs or somebody sits in the room: the release is frozen. */
    ROOM_ACTIVE,

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
