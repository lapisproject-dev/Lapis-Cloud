@file:JsModule("livekit-client")
@file:JsNonModule
@file:Suppress("unused", "PropertyName")

package network.lapis.cloud.client.livekit

import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.mediacapture.MediaDeviceInfo
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
 *
 * **V1.3.x Geräteauswahl -- device-kind strings, never [org.w3c.dom.mediacapture.MediaDeviceKind].**
 * [Room.getLocalDevices]/[Room.getActiveDevice]/[Room.switchActiveDevice] are declared here with
 * `kind: String` (verified against the pinned `livekit-client` 2.21.0 `Room.d.ts`, where the
 * parameter's *declared* TypeScript type is the union `MediaDeviceKind`, but that type is itself
 * nothing more than the string-literal union `"audioinput" | "audiooutput" | "videoinput"` --
 * functionally a plain string at the JS runtime boundary this file crosses) -- same "no dedicated
 * enum type here" discipline [TrackPublication.source] already documents for `"screen_share"`.
 * [network.lapis.cloud.client.livekit.LiveKitRoomSession.ConferenceDeviceKind] is the ONE place
 * those three literals are named on the Kotlin side. Reading [MediaDeviceInfo.kind] back OFF a
 * result array element is different -- that field's static type is fixed by the stdlib
 * `org.w3c.dom.mediacapture` binding, not by this file, so [kotlin.js.unsafeCast] (never `==`
 * against a `String`) is required there too -- see
 * [network.lapis.cloud.client.livekit.LiveKitRoomSession.listDevices] KDoc.
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

    /** V1.3.x Geräteauswahl -- current active device id for [kind] ("audioinput"/"videoinput"/
     * "audiooutput"), or `undefined` (surfaces as Kotlin `null`) if none is known yet. Purely a
     * synchronous getter, no enumeration/permission side effect -- see
     * [network.lapis.cloud.client.livekit.LiveKitRoomSession.activeDeviceId]. */
    fun getActiveDevice(kind: String): String?

    /** V1.3.x Geräteauswahl -- see [network.lapis.cloud.client.livekit.LiveKitRoomSession.switchDevice]
     * for the Kotlin-side call site and its failure handling. [exact] defaults to `true` on the JS
     * side (`livekit-client` 2.21.0's own default) -- verified against the compiled
     * `livekit-client.esm.mjs`: a mismatched-and-`exact`-constrained device makes the underlying
     * `MediaStreamTrack.applyConstraints`/`getUserMedia` call reject, and that rejection propagates
     * out of this Promise (the `try { ... } catch (e) { ...; throw e }` wrapper around each
     * device-kind branch re-throws), it does NOT silently resolve `false` -- resolves `true`/`false`
     * only reflects the innermost `setDeviceId(...)` call's own success flag once it actually ran. */
    fun switchActiveDevice(
        kind: String,
        deviceId: String,
        exact: Boolean = definedExternally,
    ): Promise<Boolean>

    companion object {
        /** V1.3.x Geräteauswahl -- Kotlin/JS `external class` `companion object` members compile to
         * plain static properties on the underlying JS class (the same mechanism every other
         * Kotlin/JS interop layer in the ecosystem relies on for a `static` TS method) -- verified
         * against `Room.d.ts`'s own `static getLocalDevices(...)` declaration and the compiled
         * `livekit-client.esm.mjs` class body. [requestPermissions] is always passed explicitly
         * `false` from every call site in this codebase (see
         * [network.lapis.cloud.client.livekit.LiveKitRoomSession.listDevices] KDoc "no second
         * permission prompt") -- by the time this is ever called, [LiveKitRoomSession.setMicrophone]/
         * `.setCamera` has already resolved the one-and-only browser permission prompt this screen
         * needs. */
        fun getLocalDevices(
            kind: String = definedExternally,
            requestPermissions: Boolean = definedExternally,
        ): Promise<Array<MediaDeviceInfo>>
    }
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

    /** V1.3.x Geräteauswahl -- fires `(kind: string, deviceId: string) => void` ONLY as a
     * consequence of a call to [Room.switchActiveDevice] (never from a raw OS-level hotplug alone,
     * that is [MediaDevicesChanged] below) -- verified against the compiled
     * `livekit-client.esm.mjs`, which emits this event from inside `switchActiveDevice` itself, past
     * the point the new device is already active. See
     * [network.lapis.cloud.client.livekit.LiveKitRoomSession.wireEvents]. */
    val ActiveDeviceChanged: String

    /** V1.3.x Geräteauswahl -- fires with ZERO JS arguments (verified against the compiled bundle:
     * `this.emit(RoomEvent.MediaDevicesChanged)`) whenever the OS-level device set changes (a
     * microphone/camera/speaker plugged or unplugged) -- the browser's own
     * `navigator.mediaDevices.devicechange` event, relayed. This is the HOTPLUG signal; it carries
     * no information about WHICH device kind changed, so the Kotlin side must re-enumerate all
     * three lists on every firing. See
     * [network.lapis.cloud.client.livekit.LiveKitRoomSession.onMediaDevicesChanged]. */
    val MediaDevicesChanged: String

    /** V1.3.x Geräteauswahl -- fires `(error: Error, kind: string | undefined) => void`. [kind] is
     * `"audioinput"`/`"videoinput"` for a microphone/camera device error, or `undefined` (Kotlin
     * `null`) for every other source this event's underlying `sourceToKind(...)` cannot map --
     * verified against the compiled bundle, which derives it from the LOCAL publish attempt's own
     * `Track.Source`, never from an explicit caller argument. This event is NEVER the source of a
     * speaker/`audiooutput` failure -- [Room.switchActiveDevice]'s own rejected Promise is, see
     * [network.lapis.cloud.client.livekit.LiveKitRoomSession.switchDevice]. */
    val MediaDevicesError: String
}

/** V1.3.x Geräteauswahl -- verified against the compiled `livekit-client.esm.mjs`: exactly these
 * four string constants (`"PermissionDenied"`/`"NotFound"`/`"DeviceInUse"`/`"Other"`), plus a single
 * `getFailure(error: any): MediaDeviceFailure | undefined` classifier function that pattern-matches
 * a raw `getUserMedia`/`DOMException` name. [getFailure] returning `undefined` (Kotlin `null`) is a
 * real, expected outcome (an error `livekit-client` itself cannot classify), not a
 * binding-incompleteness bug -- see
 * [network.lapis.cloud.client.livekit.LiveKitRoomSession.classifyDeviceFailure] for the Kotlin-side
 * fallback to `OTHER`. */
external object MediaDeviceFailure {
    val PermissionDenied: String
    val NotFound: String
    val DeviceInUse: String
    val Other: String

    fun getFailure(error: dynamic): String?
}
