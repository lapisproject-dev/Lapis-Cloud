package network.lapis.cloud.client.livekit

import kotlin.js.Promise

/*
 * V1.9.114 -- the audio side of `LocalTrack` of `livekit-client` 2.21.0, as far as the optional noise suppression needs it (verified against
 * `dist/src/room/track/LocalAudioTrack.d.ts`, not guessed). Deliberately minimal, like `LiveKitJs.kt`: a missing member is an omission on purpose.
 *
 * `setProcessor` needs an `AudioContext` on the track (`setAudioContext`, @internal/@experimental but public); without one the call throws.
 * The session hands the track its OWN context instead of switching the room-wide `webAudioMix` on, which would route every remote voice through
 * WebAudio (a change of playback for everybody, and pinned off by `ClientEncounterPrivacyTripwireTest`).
 */
external interface LocalAudioTrack : Track {
    val isMuted: Boolean

    /** `processor` is a plain JS object with `name`, `init`, `restart`, `destroy`, `processedTrack` (see [RnnoiseTrackProcessor]); `dynamic` on purpose. */
    fun setProcessor(processor: dynamic): Promise<Unit>

    fun stopProcessor(keepElement: Boolean = definedExternally): Promise<Unit>

    fun setAudioContext(audioContext: dynamic)

    /** The processor that is set, or `undefined`. */
    fun getProcessor(): dynamic

    /** Re-applies `noiseSuppression`/`echoCancellation`/`autoGainControl` to the running raw track (no new track, no mute change). */
    fun applyConstraints(constraints: dynamic): Promise<Unit>
}
