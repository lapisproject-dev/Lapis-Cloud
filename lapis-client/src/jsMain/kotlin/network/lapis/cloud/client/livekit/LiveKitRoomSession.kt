package network.lapis.cloud.client.livekit

import io.kvision.utils.obj
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ConferenceChatMessage
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import kotlin.js.unsafeCast

private external interface PublishDataOptions {
    var reliable: Boolean?
    var topic: String?
}

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- owns exactly one [Room] and turns its
 * callback-shaped JS event stream into idiomatic Kotlin callbacks + `suspend` functions. This is the
 * ONLY place in the client where a raw `dynamic` value coming out of `livekit-client` is unwrapped
 * (via [kotlin.js.unsafeCast] -- see `LiveKitJs.kt` file KDoc for why plain `as`/`as?` cannot be used
 * against these `external interface` types) -- `ConferenceScreen.kt` only ever sees the typed
 * [Track]/[TrackPublication]/`String` identity values this class hands it.
 *
 * **Roster-seeding gotcha** (this wave's plan, "Three client gotchas"): `RoomEvent.ParticipantConnected`
 * does NOT fire for participants already in the room at the moment [connect] resolves. [connect]
 * therefore iterates [Room.remoteParticipants] itself, right after the underlying `room.connect(...)`
 * promise resolves, and fires [onParticipantJoined] once per already-present participant -- exactly
 * the same callback a LATER join fires through the ordinary `ParticipantConnected` event. Any already-
 * published track for those participants still arrives the ordinary way, through `TrackSubscribed`,
 * once LiveKit's default auto-subscribe completes -- this class does not special-case that.
 *
 * **Local self-view** ([onLocalVideoTrack]): `LocalParticipant.setCameraEnabled` resolves to a
 * `LocalTrackPublication` typed as `dynamic` in `LiveKitJs.kt` on purpose (see that file's KDoc "only
 * what a concrete call site needs") -- [setCamera] pulls the one field it needs (`.track`) off that
 * dynamic value directly, rather than growing the pinned external-interface surface for a single
 * field only this method touches.
 *
 * **Recording signal** (V1.0 Wave 2 "Aufzeichnung"): [onRecordingStatusChanged] is invoked BOTH (a)
 * on every `RoomEvent.RecordingStatusChanged` push from LiveKit for the lifetime of the connection,
 * AND (b) exactly once, synchronously, right after [connect]'s underlying `room.connect(...)`
 * promise resolves, with [Room.isRecording]'s value AT THAT MOMENT -- design review D4 "late joiners
 * get the banner too": `RecordingStatusChanged` only fires on a CHANGE, so a participant joining a
 * room that is ALREADY recording would otherwise see neither the persistent badge nor the one-time
 * notice banner until some LATER stop/start transition, if one ever happens before they leave. This
 * class never reads [Room.isRecording] itself outside these two call sites -- `ConferenceScreen.kt`
 * owns all resulting UI state (badge visibility, banner, `document.title`), this class only relays
 * the raw signal.
 *
 * **Chat trust boundary** (see [ConferenceChatMessage] KDoc): a `DataReceived` payload's own
 * `senderMemberId`/`senderDisplayName` fields are attacker-controllable by any room participant. This
 * class OVERWRITES both with the SDK-supplied [RemoteParticipant.identity]/`.name` of the participant
 * argument the event itself carries (server-verified via the signed join token's `sub`/`name` claims)
 * before ever calling [onChat] -- callers never see the untrusted, self-reported values. Own,
 * locally-sent messages are never delivered back to the sender by LiveKit's data channel, so
 * `ConferenceScreen.kt` renders its own outgoing chat messages itself, immediately on [sendChat]
 * returning, rather than waiting for an echo through [onChat].
 *
 * **Speaking-priority signal** (V1.0 Videokonferenzen Wave 4 "Politur", D3): [onActiveSpeakersChanged]
 * relays [RoomEvent.ActiveSpeakersChanged] verbatim as a list of identities -- this class does not
 * debounce or cache it. `ConferenceScreen.kt`'s own periodic sweep (not this class) is what turns raw,
 * sub-second speaking-level transitions into a calm, non-strobing grid reflow -- see that file's own
 * KDoc "D3" for why the debounce lives there, not here.
 *
 * **Reconnect signal** (V1.0 Videokonferenzen Wave 4 "Politur", D10): [onReconnecting]/[onReconnected]
 * relay [RoomEvent.Reconnecting]/[RoomEvent.Reconnected] verbatim -- both fire with zero JS arguments,
 * same "extra Kotlin lambda parameters bind to `undefined`" pattern already used for
 * [RoomEvent.Disconnected] below. `ConferenceScreen.kt`'s own named connection-state machine (not this
 * class) owns what a transition means for the UI -- this class only relays the raw LiveKit signal.
 */
class LiveKitRoomSession(
    private val onRemoteTrack: (identity: String, displayName: String, track: Track, publication: TrackPublication) -> Unit,
    private val onRemoteTrackGone: (identity: String, track: Track, publication: TrackPublication) -> Unit,
    private val onParticipantJoined: (identity: String, displayName: String) -> Unit,
    private val onParticipantLeft: (identity: String) -> Unit,
    private val onLocalVideoTrack: (Track?) -> Unit,
    private val onRecordingStatusChanged: (isRecording: Boolean) -> Unit,
    private val onActiveSpeakersChanged: (identities: List<String>) -> Unit,
    private val onChat: (ConferenceChatMessage) -> Unit,
    private val onReconnecting: () -> Unit,
    private val onReconnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var room: Room? = null

    /**
     * @param turnServers audit-round-1 fix -- fresh, short-lived TURN relay credentials from
     *   `ConferenceJoinTokenDto.turnServers` (see that field's own KDoc), passed straight through as
     *   `RoomOptions.rtcConfig.iceServers`, a real WebRTC `RTCConfiguration` field `livekit-client`
     *   forwards to the underlying `RTCPeerConnection` unchanged. Empty (the default) iff the server
     *   has no TURN configured -- `rtcConfig` is then left `null` entirely, matching this method's
     *   pre-fix behaviour exactly (no ICE servers beyond whatever LiveKit itself provides).
     */
    suspend fun connect(
        serverUrl: String,
        token: String,
        turnServers: List<ConferenceTurnServer> = emptyList(),
    ) {
        val options =
            obj<RoomOptions> {
                adaptiveStream = true
                dynacast = true
                if (turnServers.isNotEmpty()) {
                    rtcConfig =
                        obj<RTCConfiguration> {
                            iceServers =
                                turnServers
                                    .map { server ->
                                        obj<RTCIceServer> {
                                            urls = server.urls.toTypedArray()
                                            username = server.username
                                            credential = server.credential
                                        }
                                    }.toTypedArray()
                        }
                }
            }
        val newRoom = Room(options)
        wireEvents(newRoom)
        room = newRoom
        newRoom.connect(serverUrl, token).await()
        // See class KDoc "Recording signal" -- the late-joiner seed, fired once per connect, BEFORE
        // seedRoster (ordering between the two does not matter functionally, but this mirrors the
        // "recording state is a room-level fact, established before the roster is" precedence).
        onRecordingStatusChanged(newRoom.isRecording)
        seedRoster(newRoom)
    }

    private fun wireEvents(room: Room) {
        room.on(RoomEvent.ParticipantConnected) { p0, _, _, _ ->
            val participant = p0.unsafeCast<RemoteParticipant>()
            onParticipantJoined(participant.identity, participant.name ?: participant.identity)
        }
        room.on(RoomEvent.ParticipantDisconnected) { p0, _, _, _ ->
            val participant = p0.unsafeCast<RemoteParticipant>()
            onParticipantLeft(participant.identity)
        }
        room.on(RoomEvent.TrackSubscribed) { p0, p1, p2, _ ->
            val track = p0.unsafeCast<Track>()
            val publication = p1.unsafeCast<TrackPublication>()
            val participant = p2.unsafeCast<RemoteParticipant>()
            onRemoteTrack(participant.identity, participant.name ?: participant.identity, track, publication)
        }
        room.on(RoomEvent.TrackUnsubscribed) { p0, p1, p2, _ ->
            val track = p0.unsafeCast<Track>()
            val publication = p1.unsafeCast<TrackPublication>()
            val participant = p2.unsafeCast<RemoteParticipant>()
            onRemoteTrackGone(participant.identity, track, publication)
        }
        room.on(RoomEvent.Disconnected) { _, _, _, _ -> onDisconnected() }
        room.on(RoomEvent.Reconnecting) { _, _, _, _ -> onReconnecting() }
        room.on(RoomEvent.Reconnected) { _, _, _, _ -> onReconnected() }
        room.on(RoomEvent.RecordingStatusChanged) { p0, _, _, _ ->
            // p0 is a raw JS boolean primitive here (LiveKit calls the listener with exactly one
            // argument), not one of this file's own `external interface` types -- see
            // `LiveKitJs.kt` file KDoc "Values returned from a Promise<dynamic>..." for why
            // `unsafeCast` (not `as`/`as?`) is this codebase's uniform cast discipline regardless.
            onRecordingStatusChanged(p0.unsafeCast<Boolean>())
        }
        room.on(RoomEvent.ActiveSpeakersChanged) { p0, _, _, _ ->
            // p0 is a raw JS array here (LiveKit calls the listener with exactly one argument, an
            // Array<Participant>) -- unsafeCast to Array<dynamic> first (no RTTI for the element
            // type either), then unsafeCast each element to this file's own ActiveSpeaker shape,
            // same two-step discipline `LiveKitJs.kt`'s file KDoc documents for every other
            // `external interface` value pulled out of a `dynamic` here.
            val speakers = p0.unsafeCast<Array<dynamic>>()
            onActiveSpeakersChanged(speakers.map { it.unsafeCast<ActiveSpeaker>().identity })
        }
        room.on(RoomEvent.DataReceived) { p0, p1, _, p3 ->
            val payload = p0.unsafeCast<org.khronos.webgl.Uint8Array?>() ?: return@on
            val participant = p1.unsafeCast<RemoteParticipant?>() ?: return@on
            val topic = p3
            if (topic != CHAT_TOPIC) return@on
            runCatching {
                val json = TextDecoder().decode(payload)
                val raw = Json.decodeFromString(ConferenceChatMessage.serializer(), json)
                // Trust boundary, see class KDoc "Chat trust boundary" -- overwrite the
                // self-reported sender fields with the SDK-verified participant identity/name.
                onChat(
                    raw.copy(
                        senderMemberId = participant.identity,
                        senderDisplayName = participant.name ?: participant.identity,
                    ),
                )
            }
        }
    }

    /** See class KDoc "Roster-seeding gotcha". */
    private fun seedRoster(room: Room) {
        val forEachCallback: (dynamic, dynamic) -> Unit = { value, _ ->
            val participant = value.unsafeCast<RemoteParticipant>()
            onParticipantJoined(participant.identity, participant.name ?: participant.identity)
        }
        room.remoteParticipants.forEach(forEachCallback)
    }

    /** See class KDoc "Local self-view". */
    suspend fun setCamera(enabled: Boolean) {
        val currentRoom = room ?: return
        val result = currentRoom.localParticipant.setCameraEnabled(enabled).await()
        val rawTrack = if (enabled) result?.track else null
        onLocalVideoTrack(if (rawTrack != null) rawTrack.unsafeCast<Track>() else null)
    }

    suspend fun setMicrophone(enabled: Boolean) {
        room?.localParticipant?.setMicrophoneEnabled(enabled)?.await()
    }

    suspend fun setScreenShare(enabled: Boolean) {
        room?.localParticipant?.setScreenShareEnabled(enabled)?.await()
    }

    suspend fun sendChat(message: ConferenceChatMessage) {
        val currentRoom = room ?: return
        val json = Json.encodeToString(ConferenceChatMessage.serializer(), message)
        val bytes = TextEncoder().encode(json)
        // House convention for building a small JS-object argument (AuthHttp.kt's own `obj<T> {}`
        // usage) -- deliberately not `js("{...}")`, which needs a compile-time string literal and
        // cannot safely reference the [CHAT_TOPIC] constant.
        val options =
            obj<PublishDataOptions> {
                reliable = true
                topic = CHAT_TOPIC
            }
        currentRoom.localParticipant.publishData(bytes, options).await()
    }

    suspend fun disconnect() {
        room?.disconnect()?.await()
        room = null
    }

    companion object {
        /** Matches [ConferenceChatMessage] KDoc's "topic `lapis-chat`". */
        const val CHAT_TOPIC = "lapis-chat"
    }
}
