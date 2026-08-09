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
 * no egress/recording types, no `VideoPresets`, no `ActiveSpeakersChanged` (that belongs to the
 * design review's D3 speaking-priority reflow for 13-25 participants -- a later, polish-pass step,
 * not this one). `"screen_share"` is discriminated by string-comparing [TrackPublication.source] to
 * that literal client-side (see `ConferenceScreen.kt`), never a dedicated enum type here.
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
}
