package network.lapis.cloud.client.livekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_CAPTURE_PAUSE
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_CAPTURE_RESUME
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_LOCK
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_MUTED
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_MUTED_PEER
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_REACTIONS_OFF
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_REACTIONS_ON
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_UNLOCK
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_UNMUTE_REQUEST
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES

/**
 * V1.9.108 -- the moderation signals the SERVER sends through LiveKit data packets (topic [network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_TOPIC]).
 * A signal only ever informs the UI ("you were muted", "please switch on"); it never changes anything by itself and never switches a
 * microphone on.
 */
sealed interface ConferenceModerationSignal {
    /** A moderator muted the own microphone. */
    data object Muted : ConferenceModerationSignal

    /** Moderators only: [memberId] was muted by a moderator. */
    data class MutedPeer(
        val memberId: String,
    ) : ConferenceModerationSignal

    /** A moderator asks the own person to switch the microphone on (the person does it themselves). */
    data object UnmuteRequest : ConferenceModerationSignal

    /** The room's microphones were locked. */
    data object Lock : ConferenceModerationSignal

    /** The lock was lifted. */
    data object Unlock : ConferenceModerationSignal

    /** V1.9.127 -- a moderator switched the room's reactions on / off. */
    data object ReactionsOn : ConferenceModerationSignal

    data object ReactionsOff : ConferenceModerationSignal

    /** V1.9.254 -- the pause mode of the room started / ended: re-read the recording and stream state (the packet itself carries no state). */
    data object CapturePauseChanged : ConferenceModerationSignal
}

private val MEMBER_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * The ONE place a data packet of the moderation topic becomes a [ConferenceModerationSignal], strict by design:
 *
 *  - **never from a participant**: participants hold `can_publish_data`, so anything on this topic that carries a sending participant is
 *    a forgery ("your microphone was muted" / "unmute now" from a peer) and is dropped. Server packets (`SendData`) carry no sender;
 *  - 1..[CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES] bytes;
 *  - exactly `{"v":1,"k":<kind>}` (plus `"id":<uuid>` for `muted_peer` and nothing else); an unknown kind, a wrong version, an extra key, a
 *    missing/ill-formed id or invalid JSON yield `null`. Never throws.
 */
internal fun conferenceModerationPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): ConferenceModerationSignal? {
    if (fromParticipant) return null
    if (payload.isEmpty() || payload.size > CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES) return null
    val obj =
        runCatching { Json.parseToJsonElement(payload.decodeToString()) }.getOrNull() as? JsonObject ?: return null
    val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    if (version != 1) return null
    val kind = (obj["k"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    val expectedKeys = if (kind == CONFERENCE_MODERATION_KIND_MUTED_PEER) setOf("v", "k", "id") else setOf("v", "k")
    if (obj.keys != expectedKeys) return null
    return when (kind) {
        CONFERENCE_MODERATION_KIND_MUTED -> ConferenceModerationSignal.Muted
        CONFERENCE_MODERATION_KIND_UNMUTE_REQUEST -> ConferenceModerationSignal.UnmuteRequest
        CONFERENCE_MODERATION_KIND_LOCK -> ConferenceModerationSignal.Lock
        CONFERENCE_MODERATION_KIND_UNLOCK -> ConferenceModerationSignal.Unlock
        CONFERENCE_MODERATION_KIND_REACTIONS_ON -> ConferenceModerationSignal.ReactionsOn
        CONFERENCE_MODERATION_KIND_REACTIONS_OFF -> ConferenceModerationSignal.ReactionsOff
        CONFERENCE_MODERATION_KIND_CAPTURE_PAUSE,
        CONFERENCE_MODERATION_KIND_CAPTURE_RESUME,
        -> ConferenceModerationSignal.CapturePauseChanged
        CONFERENCE_MODERATION_KIND_MUTED_PEER -> {
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            if (!MEMBER_UUID.matches(id)) return null
            ConferenceModerationSignal.MutedPeer(memberId = id.lowercase())
        }
        else -> null
    }
}

/** `TrackSource.MICROPHONE` of @livekit/protocol (livekit-client 2.21.0 `TrackSource` enum: UNKNOWN 0, CAMERA 1, MICROPHONE 2, ...). */
internal const val LIVEKIT_TRACK_SOURCE_MICROPHONE = 2

/**
 * Whether a participant permission lets the person publish a microphone: they may publish at all (`canPublish`) AND the microphone is
 * among the allowed sources -- an EMPTY source list means "every source" in LiveKit. The server's room lock removes MICROPHONE from the
 * list (see `LiveKitAccessToken.mintParticipantToken`, `UpdateParticipant`). This permission, not a data packet, is the truth for the
 * state of the microphone button.
 */
internal fun microphoneAllowedByPermission(
    canPublish: Boolean,
    publishSources: List<Int>,
): Boolean = canPublish && (publishSources.isEmpty() || LIVEKIT_TRACK_SOURCE_MICROPHONE in publishSources)

/** `TrackSource.SCREEN_SHARE` of @livekit/protocol (UNKNOWN 0, CAMERA 1, MICROPHONE 2, SCREEN_SHARE 3, SCREEN_SHARE_AUDIO 4). */
internal const val LIVEKIT_TRACK_SOURCE_SCREEN_SHARE = 3

/**
 * V1.9.282 -- whether a participant permission lets the person publish a screen share. FAIL-CLOSED, unlike [microphoneAllowedByPermission]: an
 * EMPTY source list means "every source" in LiveKit, but here it counts as NOT allowed, because the server never leaves a guest's list empty
 * (it always names the camera) -- an empty list is an unknown state and the button must not appear on a guess. Only the event guest call view
 * uses this; the server enforces the rule regardless.
 */
internal fun screenShareAllowedByPermission(
    canPublish: Boolean,
    publishSources: List<Int>,
): Boolean = canPublish && LIVEKIT_TRACK_SOURCE_SCREEN_SHARE in publishSources
