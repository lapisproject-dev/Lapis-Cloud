@file:JsModule("livekit-client")
@file:JsNonModule
@file:Suppress("unused", "PropertyName")

package network.lapis.cloud.client.livekit

import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLMediaElement
import kotlin.js.Promise

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- minimal `livekit-client` 2.21.0 externals.
 *
 * **Both `@JsModule` and `@JsNonModule` are required, not either alone**: `lapis-client`'s Kotlin/JS
 * output is UMD (verified empirically against the generated `Lapis-Cloud-lapis-client.js` bundle,
 * which opens with the classic `if (typeof define === 'function' && define.amd) ... else if
 * (typeof exports === 'object') ... else ...` preamble). KVision's own `bootstrap`/`navigo`/
 * `snabbdom` externals already resolve correctly through this exact webpack config with the same
 * pair of annotations -- this is not new machinery, just the first HAND-DECLARED npm dependency in
 * this codebase (see `build.gradle.kts` KDoc on the `npm("livekit-client", "2.21.0")` line).
 *
 * **Deliberately minimal.** No `Track.Source` enum, no `ConnectionState`, no `RoomConnectOptions`,
 * no `VideoPresets`. [RoomEvent.ActiveSpeakersChanged]/[ActiveSpeaker] (V1.0 Videokonferenzen Wave 4
 * "Politur", D3 speaking-priority reflow for 13-25 participants) is the ONE addition since Wave 1 --
 * [ActiveSpeaker] deliberately exposes ONLY `identity`, no `audioLevel` or other telemetry, since
 * `ConferenceScreen.kt`'s grid-reflow partition needs nothing else (see that file's own KDoc "D3").
 * `"screen_share"` is discriminated by string-comparing [TrackPublication.source] to that literal
 * client-side (see `ConferenceScreen.kt`), never a dedicated enum type here. [Room.isRecording]/
 * [RoomEvent.RecordingStatusChanged] (Wave 2 "Aufzeichnung") are the ONE exception to "no
 * egress/recording types here" -- this is the client's sole recording-related surface, deliberately
 * NOT the Twirp Egress API itself (that lives server-side only, `LiveKitEgressClient.kt`); the
 * browser only ever needs to know WHETHER the room is being recorded, never how.
 *
 * A field or method missing here is a DELIBERATE omission, not an oversight -- add only what a
 * concrete call site in [network.lapis.cloud.client.livekit.LiveKitRoomSession]/`ConferenceScreen.kt`
 * actually needs, mirroring how every other external-declaration file in this ecosystem (none yet
 * exist in THIS module, but see the JVM side's `LiveKitAdminClient.kt`/`LiveKitAccessToken.kt` for
 * the same "only the five Twirp calls Wave 1 needs" discipline) stays minimal on purpose.
 *
 * Values returned from a `Promise<dynamic>` (e.g. `LocalParticipant.setCameraEnabled`'s resolved
 * `LocalTrackPublication`) are deliberately NOT typed as an external interface here -- callers pull
 * the one field they need (`.track`) directly off the `dynamic` result via [kotlin.js.unsafeCast],
 * see [LiveKitRoomSession.setCamera]. Casting a genuinely external-interface-shaped JS value (one of
 * OUR OWN externals below, e.g. [Track]/[TrackPublication]/[RemoteParticipant]) must always go
 * through [kotlin.js.unsafeCast] too, never `as`/`as?` -- Kotlin/JS has no runtime type information
 * for `external interface` (unlike the REAL browser DOM types `org.w3c.dom.*` this file also touches,
 * which back onto genuine JS prototype chains and support ordinary checked casts).
 *
 * [RoomOptions.rtcConfig] (audit-round-1 fix) is a real, standard WebRTC `RTCConfiguration` --
 * `livekit-client` passes it straight through to the underlying `RTCPeerConnection` it constructs,
 * it is not a livekit-specific shape. [RTCConfiguration]/[RTCIceServer] below are declared minimally
 * here (only `iceServers`/`urls`/`username`/`credential`) rather than pulled from a browser-DOM
 * WebRTC binding, same "deliberately minimal" discipline as every other type in this file.
 */
external interface RoomOptions {
    var adaptiveStream: Boolean?
    var dynacast: Boolean?
    var rtcConfig: RTCConfiguration?
}

external interface RTCConfiguration {
    var iceServers: Array<RTCIceServer>?
}

external interface RTCIceServer {
    var urls: Array<String>
    var username: String?
    var credential: String?
}

external class Room(
    options: RoomOptions = definedExternally,
) {
    val localParticipant: LocalParticipant
    val remoteParticipants: dynamic // JS Map<identity, RemoteParticipant> -- see LiveKitRoomSession.seedRoster

    /**
     * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- server-authoritative: `true`
     * whenever ANY egress (this wave: per-track egress) is currently active against this room,
     * pushed to every connected client the instant it changes (see [RoomEvent.RecordingStatusChanged]).
     * This is the design review's D1 badge signal -- LiveKit itself, never client-side RPC polling
     * and never spoofable by a participant. Read once right after `connect()` resolves (see
     * `LiveKitRoomSession.connect` "late-joiner seed") so a participant who joins an
     * ALREADY-recording room sees the indicator immediately, not only on the next transition.
     */
    val isRecording: Boolean

    fun connect(
        url: String,
        token: String,
    ): Promise<Unit>

    fun disconnect(stopTracks: Boolean = definedExternally): Promise<Unit>

    fun on(
        event: String,
        listener: (dynamic, dynamic, dynamic, dynamic) -> Unit,
    ): Room

    fun off(
        event: String,
        listener: (dynamic, dynamic, dynamic, dynamic) -> Unit,
    ): Room
}

external class LocalParticipant {
    val identity: String
    val name: String?

    fun setCameraEnabled(enabled: Boolean): Promise<dynamic>

    fun setMicrophoneEnabled(enabled: Boolean): Promise<dynamic>

    fun setScreenShareEnabled(enabled: Boolean): Promise<dynamic>

    fun publishData(
        data: Uint8Array,
        options: dynamic = definedExternally,
    ): Promise<Unit>
}

external class RemoteParticipant {
    val identity: String
    val name: String?
}

external interface Track {
    val kind: String // "audio" | "video"

    fun attach(): HTMLMediaElement

    fun detach(): Array<HTMLMediaElement>
}

external interface TrackPublication {
    val trackSid: String
    val source: String // "camera" | "microphone" | "screen_share" | ...
}

/** V1.0 Videokonferenzen Wave 4 "Politur", D3 -- see [Room] class KDoc "Deliberately minimal" for why
 * this carries ONLY [identity]. */
external interface ActiveSpeaker {
    val identity: String
}

external object RoomEvent {
    val Connected: String
    val Disconnected: String
    val Reconnecting: String
    val Reconnected: String
    val ParticipantConnected: String
    val ParticipantDisconnected: String
    val TrackSubscribed: String
    val TrackUnsubscribed: String
    val LocalTrackPublished: String
    val LocalTrackUnpublished: String
    val DataReceived: String

    /** Bug fix (GitHub issue #3, "Audio Mute and Camera Toggle Controls Are Unreliable") -- fires
     * `(publication: TrackPublication, participant: Participant) => void` for EVERY track mute state
     * change in the room, local or remote, regardless of cause (this client's own `setMicrophoneEnabled`/
     * `setCameraEnabled` call, LiveKit's own reconnect re-publish, a device error, a permission
     * revocation mid-call). `LiveKitRoomSession.wireEvents` filters to the LOCAL participant and uses
     * this as the single source of truth for `micEnabled`/`cameraEnabled` -- see that file's KDoc
     * "Local mute state is event-driven, never purely optimistic" for why the previous click-only
     * update left the button lying about the real track state. */
    val TrackMuted: String

    /** See [TrackMuted] KDoc -- same event, opposite direction. */
    val TrackUnmuted: String

    /** V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- see [Room.isRecording] KDoc.
     * Fires with a single JS `boolean` argument (`(isRecording: boolean) => void`), unlike this
     * file's other events -- `LiveKitRoomSession.wireEvents` reads that single argument off the
     * listener's first parameter and ignores the other three, same "extra Kotlin lambda parameters
     * simply bind to `undefined`" pattern already used there for zero/one-argument JS events. */
    val RecordingStatusChanged: String

    /** V1.0 Videokonferenzen Wave 4 "Politur", D3 -- fires with a single JS array argument
     * (`(speakers: Array<Participant>) => void`), each element carrying at least `.identity`. See
     * `ConferenceScreen.kt`'s own KDoc "D3" for the speaking-priority reflow this feeds. */
    val ActiveSpeakersChanged: String
}
