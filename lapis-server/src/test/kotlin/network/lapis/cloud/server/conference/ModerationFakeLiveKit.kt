package network.lapis.cloud.server.conference

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One recorded `muteMicrophone` call. */
internal data class RecordedMute(
    val room: String,
    val identity: String,
    val trackSid: String,
)

/** One recorded targeted or broadcast data packet ([destinations] is `null` for a broadcast). */
internal data class RecordedPacket(
    val room: String,
    val topic: String,
    val payload: String,
    val destinations: List<String>?,
)

/** One recorded `updateParticipantPermission` call. */
internal data class RecordedPermission(
    val room: String,
    val identity: String,
    val permission: LiveKitParticipantPermissionUpdate,
)

/**
 * V1.9.108 -- hermetic stand-in for the moderation part of [LiveKitAdminClient]. Keeps a live roster per room and REFLECTS the
 * effect of a mute / permission update on it (so the reconciler tests see consistent state), and records every call with its exact
 * arguments. There is deliberately NO method that unmutes (mirroring the production interface).
 */
internal class ModerationFakeLiveKit : LiveKitAdminClient {
    val participants = ConcurrentHashMap<String, MutableList<LiveKitParticipantInfo>>()
    val mutes = CopyOnWriteArrayList<RecordedMute>()
    val permissionUpdates = CopyOnWriteArrayList<RecordedPermission>()
    val packets = CopyOnWriteArrayList<RecordedPacket>()
    var failList = false
    var failMute = false
    var failPermission = false
    var failSendTo = false

    /** Identities whose `updateParticipantPermission` throws (partial failure). */
    val failPermissionFor = mutableSetOf<String>()

    /** Runs right after the roster snapshot of `listParticipants` was taken (simulates something committing while the call is in flight). */
    var afterListSnapshot: (() -> Unit)? = null

    /** Identities whose `muteMicrophone` throws (partial failure). */
    val failMuteFor = mutableSetOf<String>()

    fun add(
        room: String,
        participant: LiveKitParticipantInfo,
    ) {
        participants.getOrPut(room) { CopyOnWriteArrayList() }.add(participant)
    }

    fun roster(room: String): List<LiveKitParticipantInfo> = participants[room].orEmpty()

    fun find(
        room: String,
        identity: String,
    ): LiveKitParticipantInfo? = roster(room).firstOrNull { it.identity == identity }

    private fun replace(
        room: String,
        identity: String,
        transform: (LiveKitParticipantInfo) -> LiveKitParticipantInfo,
    ) {
        val list = participants[room] ?: return
        val index = list.indexOfFirst { it.identity == identity }
        if (index >= 0) list[index] = transform(list[index])
    }

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
        departureTimeoutSeconds: Int?,
    ): LiveKitRoomInfo = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants)

    override suspend fun deleteRoom(name: String) {
        participants.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> =
        participants.keys.map {
            LiveKitRoomInfo(
                name = it,
                numParticipants =
                    participants[it]?.size ?: 0,
            )
        }

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> {
        if (failList) throw LiveKitAdminException(message = "simulated LiveKit failure")
        return roster(room).toList().also { afterListSnapshot?.invoke() }
    }

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        participants[room]?.removeIf { it.identity == identity }
    }

    override suspend fun sendData(
        room: String,
        topic: String,
        payload: ByteArray,
    ) {
        packets += RecordedPacket(room = room, topic = topic, payload = payload.decodeToString(), destinations = null)
    }

    override suspend fun muteMicrophone(
        room: String,
        identity: String,
        trackSid: String,
    ) {
        if (failMute || identity in failMuteFor) throw LiveKitAdminException(message = "simulated LiveKit failure")
        mutes += RecordedMute(room = room, identity = identity, trackSid = trackSid)
        replace(room = room, identity = identity) { p ->
            p.copy(
                tracks =
                    p.tracks.map {
                        if (it.sid ==
                            trackSid
                        ) {
                            it.copy(muted = true)
                        } else {
                            it
                        }
                    },
            )
        }
    }

    override suspend fun updateParticipantPermission(
        room: String,
        identity: String,
        permission: LiveKitParticipantPermissionUpdate,
    ) {
        if (failPermission || identity in failPermissionFor) throw LiveKitAdminException(message = "simulated LiveKit failure")
        permissionUpdates += RecordedPermission(room = room, identity = identity, permission = permission)
        replace(room = room, identity = identity) { p ->
            p.copy(
                permission =
                    LiveKitParticipantPermission(
                        canPublish = permission.canPublish,
                        canPublishData = permission.canPublishData,
                        canSubscribe = permission.canSubscribe,
                        canPublishSources = permission.canPublishSources,
                    ),
            )
        }
    }

    override suspend fun sendDataTo(
        room: String,
        topic: String,
        payload: ByteArray,
        destinationIdentities: List<String>,
    ) {
        if (failSendTo) throw LiveKitAdminException(message = "simulated LiveKit failure")
        packets += RecordedPacket(room = room, topic = topic, payload = payload.decodeToString(), destinations = destinationIdentities)
    }
}

/** A live participant with one microphone track ([microphoneOpen] = published and not muted) and the permission of an ordinary token. */
internal fun liveParticipant(
    identity: String,
    microphoneOpen: Boolean = true,
    hasMicrophoneTrack: Boolean = true,
    cameraOpen: Boolean = false,
    permissionSources: List<String> = emptyList(),
): LiveKitParticipantInfo {
    val tracks = mutableListOf<LiveKitTrackInfo>()
    if (hasMicrophoneTrack) {
        tracks +=
            LiveKitTrackInfo(sid = "TR_mic_$identity", type = "AUDIO", source = "MICROPHONE", muted = !microphoneOpen)
    }
    if (cameraOpen) tracks += LiveKitTrackInfo(sid = "TR_cam_$identity", type = "VIDEO", source = "CAMERA", muted = false)
    return LiveKitParticipantInfo(
        identity = identity,
        name = identity,
        tracks = tracks,
        permission =
            LiveKitParticipantPermission(
                canPublish = true,
                canPublishData = true,
                canSubscribe = true,
                canPublishSources = permissionSources,
            ),
    )
}
