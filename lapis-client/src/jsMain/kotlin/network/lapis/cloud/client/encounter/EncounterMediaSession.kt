package network.lapis.cloud.client.encounter

import network.lapis.cloud.client.livekit.ConferenceConnectFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.client.livekit.Room
import network.lapis.cloud.client.livekit.RoomOptions
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.client.livekit.TrackPublication
import network.lapis.cloud.shared.domain.ConferenceChatMessage
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterTableTokenDto

/**
 * V1.9.62 Begegnungsraum (B2) -- THE place where the listen-only rule of the congregation is enforced on the client, in three layers:
 *
 * 1. **The server** mints the LiveKit token with `canPublish = false` for everybody who is not an office holder (the real lock; a
 *    client cannot override it).
 * 2. **The type**: a congregation member gets an [EncounterListenerSession], an interface WITHOUT any media method. There is nothing
 *    to call to switch a camera or microphone on -- the code that could do it does not compile for a listener. Only an entry with
 *    `canPublish = true` yields an [EncounterSpeakerSession].
 * 3. **The runtime flag**: `LiveKitRoomSession(publishEnabled = entry.canPublish)` makes the underlying session refuse every media call
 *    and every device enumeration even if layer 2 were bypassed.
 *
 * `ClientEncounterPrivacyTripwireTest` additionally pins that `getUserMedia`/`setCamera`/`setMicrophone`/`enumerateDevices` appear in
 * the encounter code only here and in [EncounterPulpitControls].
 */
internal interface EncounterListenerSession {
    /** Connects to the room. `null` = connected, otherwise why not. Publishes nothing, whatever the role. */
    suspend fun connect(): ConferenceConnectFailure?

    /** Sends a reaction; `false` (and nothing sent) when the person may not publish data (silenced). */
    suspend fun sendReaction(reaction: EncounterReaction): Boolean

    /** Sends a chat line (the caller already trimmed and capped it); `false` when the person may not publish data. */
    suspend fun sendChat(text: String): Boolean

    /**
     * V1.9.79: tells the others to reload the seat list (content-free nudge, no seat, no name); `false` (nothing sent) when the person may
     * not publish data. Not a media call: the listener/speaker type split is unaffected.
     */
    suspend fun sendSeatNudge(): Boolean

    /** Resumes audio blocked by the browser's autoplay policy (call from a click). */
    suspend fun startAudio()

    suspend fun disconnect()
}

/** An office holder's session: the listener plus the two media switches. Never handed to a congregation member. */
internal interface EncounterSpeakerSession : EncounterListenerSession {
    suspend fun setCamera(enabled: Boolean): ConferenceDeviceFailure?

    suspend fun setMicrophone(enabled: Boolean): ConferenceDeviceFailure?
}

/**
 * What the room view wants to hear from the session. Everything arrives with the SDK-verified identity; [onChat] carries the
 * overwritten sender fields of `LiveKitRoomSession`'s chat trust boundary.
 */
internal class EncounterSessionCallbacks(
    val onRemoteTrack: (identity: String, displayName: String, track: Track, publication: TrackPublication) -> Unit,
    val onRemoteTrackGone: (identity: String, track: Track, publication: TrackPublication) -> Unit,
    val onParticipantJoined: (identity: String, displayName: String) -> Unit,
    val onParticipantLeft: (identity: String) -> Unit,
    val onLocalVideoTrack: (Track?) -> Unit,
    val onLocalTrackMuteChanged: (source: String, muted: Boolean) -> Unit,
    val onChat: (identity: String, displayName: String, text: String) -> Unit,
    val onReaction: (identity: String, reaction: EncounterReaction) -> Unit,
    val onAudioPlaybackChanged: (canPlay: Boolean) -> Unit,
    val onReconnecting: () -> Unit,
    val onReconnected: () -> Unit,
    val onDisconnected: (DisconnectCause) -> Unit,
    /** V1.9.79: a content-free "reload the seat list" packet; the identity is the SDK-verified sender. */
    val onSeatNudge: (identity: String) -> Unit = {},
    /** V1.9.79: the identities LiveKit currently reports as speaking (the room only looks at office holders' tiles). Volatile, never stored. */
    val onActiveSpeakers: (identities: List<String>) -> Unit = {},
)

/** The factory seam of the room view (a `jsTest` hands over a fake). The real one is [openEncounterSession]. */
internal typealias EncounterSessionOpener = (entry: EncounterEntryDto, callbacks: EncounterSessionCallbacks) -> EncounterListenerSession

private fun defaultLiveKitFactory(
    callbacks: EncounterSessionCallbacks,
    publishEnabled: Boolean,
): LiveKitRoomSession =
    LiveKitRoomSession(
        onRemoteTrack = callbacks.onRemoteTrack,
        onRemoteTrackGone = callbacks.onRemoteTrackGone,
        onParticipantJoined = callbacks.onParticipantJoined,
        onParticipantLeft = callbacks.onParticipantLeft,
        onLocalVideoTrack = callbacks.onLocalVideoTrack,
        onLocalTrackMuteChanged = callbacks.onLocalTrackMuteChanged,
        onRecordingStatusChanged = {},
        onActiveSpeakersChanged = callbacks.onActiveSpeakers,
        onActiveDeviceChanged = { _, _ -> },
        onMediaDevicesChanged = {},
        onMediaDevicesError = { _, _ -> },
        onChat = { message -> callbacks.onChat(message.senderMemberId, message.senderDisplayName, message.text) },
        onWhiteboardPreview = { _, _, _ -> },
        onWhiteboardCommit = { _, _, _ -> },
        onNotesCommit = { _, _, _ -> },
        onReconnecting = callbacks.onReconnecting,
        onReconnected = callbacks.onReconnected,
        onDisconnected = callbacks.onDisconnected,
        publishEnabled = publishEnabled,
        onEncounterReaction = callbacks.onReaction,
        onAudioPlaybackChanged = callbacks.onAudioPlaybackChanged,
        onEncounterSeatNudge = callbacks.onSeatNudge,
    )

/**
 * Builds the session of [entry]: an [EncounterSpeakerSession] ONLY when `entry.canPublish`, otherwise a plain [EncounterListenerSession].
 * [liveKitFactory] is the test seam for the underlying session; it receives the `publishEnabled` flag the production code derives from
 * the entry.
 */
internal fun openEncounterSession(
    entry: EncounterEntryDto,
    callbacks: EncounterSessionCallbacks,
    liveKitFactory: (EncounterSessionCallbacks, Boolean) -> LiveKitRoomSession = ::defaultLiveKitFactory,
): EncounterListenerSession {
    val liveKit = liveKitFactory(callbacks, entry.canPublish)
    return if (entry.canPublish) {
        SpeakerSession(liveKit = liveKit, entry = entry)
    } else {
        ListenerSession(liveKit = liveKit, entry = entry)
    }
}

private open class ListenerSession(
    protected val liveKit: LiveKitRoomSession,
    private val entry: EncounterEntryDto,
) : EncounterListenerSession {
    override suspend fun connect(): ConferenceConnectFailure? =
        liveKit.connect(entry.join.serverUrl, entry.join.token, entry.join.turnServers)

    override suspend fun sendReaction(reaction: EncounterReaction): Boolean {
        if (!entry.canPublishData) return false
        liveKit.sendEncounterReaction(reaction)
        return true
    }

    override suspend fun sendChat(text: String): Boolean {
        if (!entry.canPublishData) return false
        // The wire DTO has a time field the room never shows; it is sent as 0 so the chat leaves no time trail.
        liveKit.sendChat(
            ConferenceChatMessage(
                senderMemberId = entry.join.identity,
                senderDisplayName = entry.join.displayName,
                text = text,
                sentAtEpochMs = 0L,
            ),
        )
        return true
    }

    override suspend fun sendSeatNudge(): Boolean {
        if (!entry.canPublishData) return false
        liveKit.sendEncounterSeatNudge()
        return true
    }

    override suspend fun startAudio() = liveKit.startAudio()

    override suspend fun disconnect() = liveKit.disconnect()
}

private class SpeakerSession(
    liveKit: LiveKitRoomSession,
    entry: EncounterEntryDto,
) : ListenerSession(liveKit, entry),
    EncounterSpeakerSession {
    override suspend fun setCamera(enabled: Boolean): ConferenceDeviceFailure? = liveKit.setCamera(enabled)

    override suspend fun setMicrophone(enabled: Boolean): ConferenceDeviceFailure? = liveKit.setMicrophone(enabled)
}

// ── V1.9.80 Stage 2b: the audio session of a TABLE ─────────────────────────────────────────────────────────────────────

/**
 * V1.9.80 -- the second, separate LiveKit session of a person who sits at a table: audio only. The type has NO camera, NO screen share
 * and NO data method (the narrowest possible interface); the server's table token additionally grants only the microphone source
 * (`canPublishSources = ["microphone"]`) and no data channel. The microphone is OFF after [connect]; [microphone] is the only media call
 * and it needs a token with `canPublish = true` (a quieted table's token has none, and the runtime flag `publishEnabled` refuses the
 * call too).
 */
internal interface EncounterTableSession {
    /** Connects to the table's room. `null` = connected, otherwise why not. Publishes nothing. */
    suspend fun connect(): ConferenceConnectFailure?

    /** Switches the own microphone; a typed refusal when the token may not publish. */
    suspend fun microphone(on: Boolean): ConferenceDeviceFailure?

    /** Leaves the table room and stops the local audio track. */
    suspend fun disconnect()
}

/** What the table view wants to hear: audio tracks only (a video track is dropped unread), the speakers, and the end of the connection. */
internal class EncounterTableCallbacks(
    val onAudioTrack: (identity: String, track: Track) -> Unit,
    val onAudioTrackGone: (identity: String, track: Track) -> Unit,
    val onActiveSpeakers: (identities: List<String>) -> Unit,
    val onDisconnected: (DisconnectCause) -> Unit,
    val onReconnecting: () -> Unit = {},
    val onReconnected: () -> Unit = {},
)

/** The factory seam of the table view (a `jsTest` hands over a fake). The real one is [openEncounterTableSession]. */
internal typealias EncounterTableSessionOpener = (
    token: EncounterTableTokenDto,
    callbacks: EncounterTableCallbacks,
) -> EncounterTableSession

private fun tableLiveKitSession(
    callbacks: EncounterTableCallbacks,
    publishEnabled: Boolean,
    roomFactory: (RoomOptions) -> Room,
): LiveKitRoomSession =
    LiveKitRoomSession(
        // Only audio is ever handed on: a video track at a table is not wanted (the reconciler of the server rotates the table anyway).
        onRemoteTrack = { identity, _, track, _ -> if (track.kind == "audio") callbacks.onAudioTrack(identity, track) },
        onRemoteTrackGone = { identity, track, _ -> if (track.kind == "audio") callbacks.onAudioTrackGone(identity, track) },
        onParticipantJoined = { _, _ -> },
        onParticipantLeft = { _ -> },
        onLocalVideoTrack = { _ -> },
        onLocalTrackMuteChanged = { _, _ -> },
        onRecordingStatusChanged = {},
        onActiveSpeakersChanged = callbacks.onActiveSpeakers,
        onActiveDeviceChanged = { _, _ -> },
        onMediaDevicesChanged = {},
        onMediaDevicesError = { _, _ -> },
        onChat = { _ -> },
        onWhiteboardPreview = { _, _, _ -> },
        onWhiteboardCommit = { _, _, _ -> },
        onNotesCommit = { _, _, _ -> },
        onReconnecting = callbacks.onReconnecting,
        onReconnected = callbacks.onReconnected,
        onDisconnected = callbacks.onDisconnected,
        roomFactory = roomFactory,
        publishEnabled = publishEnabled,
    )

/**
 * Builds the audio session of [token]; the underlying session gets `publishEnabled = token.canPublish` (a quieted table's token has none).
 * [roomFactory] is the test seam for the LiveKit room (a fake room in a `jsTest`).
 */
internal fun openEncounterTableSession(
    token: EncounterTableTokenDto,
    callbacks: EncounterTableCallbacks,
    roomFactory: (RoomOptions) -> Room = { options -> Room(options) },
): EncounterTableSession =
    TableAudioSession(
        liveKit = tableLiveKitSession(callbacks = callbacks, publishEnabled = token.canPublish, roomFactory = roomFactory),
        token = token,
    )

private class TableAudioSession(
    private val liveKit: LiveKitRoomSession,
    private val token: EncounterTableTokenDto,
) : EncounterTableSession {
    override suspend fun connect(): ConferenceConnectFailure? =
        liveKit.connect(token.join.serverUrl, token.join.token, token.join.turnServers)

    override suspend fun microphone(on: Boolean): ConferenceDeviceFailure? = liveKit.setMicrophone(on)

    override suspend fun disconnect() = liveKit.disconnect()
}
