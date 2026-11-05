package network.lapis.cloud.server.conference

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * V1.9.282 -- which account-less event guest (if any) a moderator allowed to share the screen, per room. **In memory on purpose** and bound
 * to the LiveKit PARTICIPANT SID: LiveKit gives a new sid at every join, so a guest who reconnects (with the same deterministic ticket
 * identity, or with a token kept from the time of the grant -- LiveKit refreshes the token of a connected client with its current
 * permission) loses the grant deterministically; an ICE resume keeps the sid and with it the grant. A server restart empties the registry,
 * and the reconciler then takes every guest share back (fail closed): the moderator has to allow it again.
 *
 * At most ONE grant per room. A process-wide singleton wired in `Application.module` (the services are built per RPC call), no default instance.
 * Every whole replacement of a guest permission runs under [ConferenceAudioGrantState.withRoomLock] (not reentrant).
 */
class EventGuestScreenShareGrants {
    data class Grant(
        val identity: String,
        val participantSid: String,
    )

    sealed interface TryGrant {
        data object Granted : TryGrant

        data object AlreadyHeld : TryGrant

        data object Taken : TryGrant
    }

    private val grants = ConcurrentHashMap<Uuid, Grant>()

    /** Atomic: [Granted] if the room had no grant (or a stale one of the same identity with another sid), [AlreadyHeld] for the same identity AND sid, [Taken] otherwise. */
    fun tryGrant(
        roomId: Uuid,
        identity: String,
        participantSid: String,
    ): TryGrant {
        var result: TryGrant = TryGrant.Granted
        grants.compute(roomId) { _, existing ->
            when {
                existing == null -> Grant(identity = identity, participantSid = participantSid)
                existing.identity == identity && existing.participantSid == participantSid -> {
                    result = TryGrant.AlreadyHeld
                    existing
                }
                existing.identity == identity -> Grant(identity = identity, participantSid = participantSid)
                else -> {
                    result = TryGrant.Taken
                    existing
                }
            }
        }
        return result
    }

    /** Removes the grant of [identity]; `true` if there was one. */
    fun revoke(
        roomId: Uuid,
        identity: String,
    ): Boolean {
        var removed = false
        grants.computeIfPresent(roomId) { _, existing ->
            if (existing.identity == identity) {
                removed = true
                null
            } else {
                existing
            }
        }
        return removed
    }

    fun current(roomId: Uuid): Grant? = grants[roomId]

    fun isGranted(
        roomId: Uuid,
        identity: String,
        participantSid: String,
    ): Boolean = grants[roomId]?.let { it.identity == identity && it.participantSid == participantSid } == true

    /** Drops the grants of rooms that ended. */
    fun pruneInactiveRooms(activeRoomIds: Set<Uuid>) {
        grants.keys.removeIf { it !in activeRoomIds }
    }

    /** Drops the grant of [roomId] when its holder's sid is not among [presentSids] (the guest left or reconnected). */
    fun dropIfAbsent(
        roomId: Uuid,
        presentSids: Set<String>,
    ) {
        grants.computeIfPresent(roomId) { _, existing -> if (existing.participantSid in presentSids) existing else null }
    }
}

/** The LiveKit participant kind of the recording/stream egress -- never a guest, never touched by the guest screen share logic. */
internal const val LIVEKIT_KIND_EGRESS = "EGRESS"

/** `true` for a track that is a screen share (video or audio), whatever its mute state. */
internal fun LiveKitTrackInfo.isScreenShareTrack(): Boolean = source.uppercase() in LIVEKIT_SCREEN_SHARE_SOURCES

/** The screen share tracks of this participant that are switched on (not muted). */
internal fun LiveKitParticipantInfo.openScreenShareTracks(): List<LiveKitTrackInfo> = tracks.filter { it.isScreenShareTrack() && !it.muted }

/** `true` if the participant's current permission lets it publish the microphone (an empty source list means "every source"). */
internal fun LiveKitParticipantInfo.mayPublishMicrophoneNow(): Boolean {
    val permission = permission ?: return false
    return permission.canPublish &&
        (
            permission.canPublishSources.isEmpty() ||
                permission.canPublishSources.any { it.equals(LIVEKIT_MICROPHONE_SOURCE, ignoreCase = true) }
        )
}
