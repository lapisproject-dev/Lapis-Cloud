package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * V1.9.143 -- optional recording of the PULPIT of an encounter room.
 *
 * Whether and under which conditions a recording of a person is lawful depends on the country of the instance. Lapis Cloud therefore does not
 * decide that: the feature is OFF by default, an ADMIN switches it on per instance after acknowledging an operator notice, and a BOARD/ADMIN
 * allows it per room. Only the camera and microphone of the person holding the PULPIT office are recorded; the congregation never is.
 */

/**
 * Version of the operator notice an ADMIN acknowledges before switching the recording on. Bump on ANY change of the German reference text
 * (`PulpitRecordingNotice.TEXT_DE` on the server; `PulpitRecordingNoticePinTest` fails the build otherwise).
 */
const val PULPIT_RECORDING_NOTICE_VERSION: String = "1"

/** V1.9.143 -- sent ONLY by the server (LiveKit RoomService/SendData); clients accept it only without a participant. */
const val ENCOUNTER_RECORDING_TOPIC = "lapis-encounter-recording"

/** Largest recording packet in bytes (`{"v":1,"a":1,"k":1}` is 19). Longer packets are dropped unread. */
const val ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES = 32

/** At most this many pulpit persons are recorded at the same time in one room. */
const val PULPIT_RECORDING_MAX_CONCURRENT_PER_ROOM = 2

/** What a recording packet says. Carries no identity of anybody. [active] = a recording runs; [ask] = the receiver (a pulpit person) is asked to confirm. */
data class EncounterRecordingSignal(
    val active: Boolean,
    val ask: Boolean = false,
)

/** The wire payload of [signal]: `{"v":1,"a":0|1[,"k":1]}`. */
fun encounterRecordingPayload(signal: EncounterRecordingSignal): String =
    buildString {
        append("""{"v":1,"a":""")
        append(if (signal.active) 1 else 0)
        if (signal.ask) append(""","k":1""")
        append('}')
    }

/**
 * Parses a recording packet. `null` if [fromParticipant] (a packet that carries a sending participant is a forgery), the length is outside
 * `1..ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES`, keys other than `v`/`a`/`k` occur, `v` is not 1, `a` is not 0/1 or `k` is present but not 1.
 * Never throws, never reads an identity.
 */
fun encounterRecordingPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): EncounterRecordingSignal? {
    if (fromParticipant || payload.size !in 1..ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES) return null
    return try {
        val obj = Json.parseToJsonElement(payload.decodeToString()) as? JsonObject ?: return null
        if (!obj.keys.all { it == "v" || it == "a" || it == "k" }) return null

        fun intOf(key: String): Int? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (intOf("v") != 1) return null
        val active = intOf("a") ?: return null
        if (active !in 0..1) return null
        val ask =
            if (obj.containsKey("k")) {
                if (intOf("k") != 1) return null
                true
            } else {
                false
            }
        EncounterRecordingSignal(active = active == 1, ask = ask)
    } catch (e: Exception) {
        null
    }
}

/**
 * V1.9.143 -- the recording state as an office holder (PULPIT/STEWARD present in the room) sees it. [startable]: a recording may be started
 * now. [confirmationRequired]: the pulpit has to confirm first. [confirmedByMe]: the caller (a pulpit person) has confirmed in this session.
 * [pendingConfirmation]: a start waits for the pulpit. [maxReached]: the per-room limit stopped a pulpit person from being recorded.
 */
@Serializable
data class PulpitRecordingStateDto(
    val active: Boolean,
    val startable: Boolean,
    val confirmationRequired: Boolean,
    val confirmedByMe: Boolean,
    val pendingConfirmation: Boolean,
    val maxReached: Boolean,
)

/** The two texts of the pulpit recording as the caller has to show them. [pulpitVersionLabel]/[pulpitSha256] are sent back unmodified in `confirmPulpitRecording`. */
@Serializable
data class PulpitRecordingTextsDto(
    val pulpitVersionLabel: String,
    val pulpitSha256: String,
    val pulpitText: String,
    val roomText: String,
    /** V1.9.196 -- format and server-rendered sanitized HTML (`null` for plain text) of each of the two texts. */
    val pulpitFormat: AgreementTextFormat = AgreementTextFormat.PLAIN,
    val pulpitHtml: String? = null,
    val roomFormat: AgreementTextFormat = AgreementTextFormat.PLAIN,
    val roomHtml: String? = null,
)

/** One stored pulpit recording as BOARD/ADMIN list it. Names no person. */
@Serializable
data class PulpitRecordingDocumentDto(
    val documentId: String,
    val spaceId: String,
    val spaceTitle: String,
    val recordedAt: LocalDateTime,
    val fileSizeBytes: Long,
)
