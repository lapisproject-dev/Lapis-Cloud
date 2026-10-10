package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window

/**
 * V1.9.97 -- the sound of the bell (the call) and of the blessing: two short RECORDINGS (CC0, provenance in
 * `lapis-client/src/jsMain/webAssets/encounter-sounds-v1/PROVENANCE.adoc`), played through WebAudio. They replace the synthesis of V1.9.96.
 *
 * Why WebAudio and not an `<audio>` element: the speaker the person chose acts on exactly ONE audio context (`EncounterAudioOutput`), a
 * second sink path would clash with its all-or-nothing selection; an element with a `src` could be pre-loaded by the browser before the
 * person switched the sound on; and a gain node caps the level deterministically.
 *
 * The files are loaded ONLY after the person's own decision: in [BrowserEncounterBellSound.probe] (the click that switches the sound on)
 * or in [BrowserEncounterBellSound.prime] (the first gesture after a reload with the switch remembered as "on"). A ring never starts a load.
 * Nothing is loaded in a room without a bell, for a person with the sound off, or before a user activation.
 *
 * The sound is quiet by construction: the fixed gains below cap the level (the recordings peak at -2.2 / -2.7 dBFS), at most ONE voice sounds
 * per room, a second call of the same kind is ignored, a different kind fades the running voice out first, nothing ever starts in a
 * hidden tab, and an exception or a refused promise of the browser can never keep the visible sign away.
 */
internal const val ENCOUNTER_BELL_PEAK_GAIN = 0.25

/** Gain of the blessing (one low strike, a little quieter than the call). */
internal const val ENCOUNTER_BLESSING_GAIN = 0.18

/** Seconds a running voice needs to fade out when it is replaced or silenced. */
internal const val ENCOUNTER_SOUND_FADE_S = 0.25

/** Length in seconds of the sample that confirms the switch (the blessing strike, cut short). */
internal const val ENCOUNTER_PROBE_LENGTH_S = 3.0

/** Seconds of the fade-out at the end of the sample. */
internal const val ENCOUNTER_PROBE_FADE_S = 0.5

/** A sound that is ready later than this many milliseconds after it was asked for is dropped (it would no longer match the sign). */
internal const val ENCOUNTER_SOUND_MAX_LATENESS_MS = 1500

private const val START_DELAY_S = 0.02

/** What is played. [PROBE] is the short sample of the switch. */
internal enum class EncounterSoundKind { CALL, BLESSING, PROBE }

/** The sound of the room as it sees it. Every method is safe to call at any time and never throws. */
internal interface EncounterBellSound {
    /** Creates / resumes the audio context and starts loading the recordings (called from a user's own gesture after a reload). */
    fun prime()

    /** In the click that switched the sound on: the context synchronously, the loading (one new attempt after a failure), then the sample. */
    fun probe()

    /** The call (the bell rung from the pulpit). */
    fun ringCall()

    /** The blessing. */
    fun ringBlessing()

    /** Fades the running voice out and forgets a waiting request (hidden tab, switch turned off); the loaded recordings stay. */
    fun silence()

    /** Stops at once, closes the audio context and drops the recordings. */
    fun dispose()
}

/** The context seam: a `jsTest` hands over a fake. Returns the new audio context, or `null` when the browser has no WebAudio. May throw. */
internal fun interface EncounterAudioContextFactory {
    fun create(): dynamic
}

/** The real factory: `AudioContext`, or `webkitAudioContext` (older Safari). */
internal val browserAudioContextFactory =
    EncounterAudioContextFactory {
        val scope = window.asDynamic()
        val constructor = if (present(scope.AudioContext)) scope.AudioContext else scope.webkitAudioContext
        if (!present(constructor)) null else js("Reflect").construct(constructor, js("[]"))
    }

private fun present(value: dynamic): Boolean = value != null && value != undefined

/** `false` only while the browser positively says the page has had no user activation yet (then an audio context would only earn a console warning). */
internal fun browserHasBeenActive(): Boolean =
    try {
        val activation = window.navigator.asDynamic().userActivation
        if (!present(activation)) true else activation.hasBeenActive != false
    } catch (e: Throwable) {
        true
    }

private val ignoreRejection: (dynamic) -> Unit = { }

/** Attaches a no-op rejection handler to a promise-like value, so a refused promise is never an unhandled rejection. */
private fun swallow(promise: dynamic) {
    try {
        if (present(promise) && present(promise["catch"])) promise["catch"](ignoreRejection)
    } catch (e: Throwable) {
        // nothing to do
    }
}

private enum class SlotState { IDLE, LOADING, READY, FAILED }

/** One recording: where it is in its life and, once ready, the decoded buffer. */
private class Slot(
    val url: String,
) {
    var state: SlotState = SlotState.IDLE
    var buffer: dynamic = null
}

/** The one voice that sounds (or fades). */
private class Voice(
    val kind: EncounterSoundKind,
    val source: dynamic,
    val gain: dynamic,
)

/** The one request that waits for a recording that is still loading. */
private class WaitingRequest(
    val kind: EncounterSoundKind,
    val requestedAt: Double,
)

/**
 * The browser implementation: ONE audio context per room, created lazily and only after a user activation. [applySink] gives the room the
 * chance to point the context at the speaker the person chose (best effort; the room hands over a lambda, never a device id).
 */
internal class BrowserEncounterBellSound(
    private val factory: EncounterAudioContextFactory = browserAudioContextFactory,
    private val applySink: (dynamic) -> Unit = {},
    private val hasBeenActive: () -> Boolean = ::browserHasBeenActive,
    private val loader: EncounterSoundLoader = browserEncounterSoundLoader,
    private val now: () -> Double = { window.performance.now() },
    private val pageVisible: () -> Boolean = { (document.asDynamic().visibilityState as? String) != "hidden" },
) : EncounterBellSound {
    private var context: dynamic = null
    private var disposed = false
    private val callSlot = Slot(ENCOUNTER_CALL_BELL_URL)
    private val blessingSlot = Slot(ENCOUNTER_BLESSING_BELL_URL)
    private var current: Voice? = null
    private var waiting: WaitingRequest? = null

    private fun readyContext(): dynamic {
        if (disposed) return null
        if (present(context)) {
            resume(context)
            return context
        }
        if (!hasBeenActive()) return null
        val created =
            try {
                factory.create()
            } catch (e: Throwable) {
                null
            }
        if (!present(created)) return null
        context = created
        try {
            applySink(created)
        } catch (e: Throwable) {
            // the system default speaker is used
        }
        resume(created)
        return created
    }

    private fun resume(ctx: dynamic) {
        try {
            if (ctx.state == "suspended") swallow(ctx.resume())
        } catch (e: Throwable) {
            // a context that cannot resume stays silent; the sign has already been shown
        }
    }

    override fun prime() {
        try {
            val ctx = readyContext()
            if (present(ctx)) startLoading(ctx)
        } catch (e: Throwable) {
            // silent
        }
    }

    override fun probe() {
        try {
            // synchronous inside the click (a browser only lets a sound start there): the context first, before anything is awaited
            val ctx = readyContext()
            if (!present(ctx)) return
            // exactly one new attempt after a failure
            if (callSlot.state == SlotState.FAILED) callSlot.state = SlotState.IDLE
            if (blessingSlot.state == SlotState.FAILED) blessingSlot.state = SlotState.IDLE
            startLoading(ctx)
            play(EncounterSoundKind.PROBE)
        } catch (e: Throwable) {
            // silent
        }
    }

    override fun ringCall() = play(EncounterSoundKind.CALL)

    override fun ringBlessing() = play(EncounterSoundKind.BLESSING)

    private fun slotOf(kind: EncounterSoundKind): Slot = if (kind == EncounterSoundKind.CALL) callSlot else blessingSlot

    private fun startLoading(ctx: dynamic) {
        load(callSlot, ctx)
        load(blessingSlot, ctx)
    }

    private fun load(
        slot: Slot,
        ctx: dynamic,
    ) {
        if (slot.state != SlotState.IDLE) return
        slot.state = SlotState.LOADING
        val pending =
            try {
                loader.load(ctx, slot.url)
            } catch (e: Throwable) {
                null
            }
        if (!present(pending)) {
            slot.state = SlotState.FAILED
            return
        }
        // the derived promise of `then` gets its own handler, so neither a refusal nor an exception here is ever unhandled
        swallow(
            pending!!.then(
                { buffer: dynamic -> onLoaded(slot, buffer) },
                { _: Throwable -> onFailed(slot) },
            ),
        )
    }

    private fun onFailed(slot: Slot) {
        if (disposed) return
        slot.state = SlotState.FAILED
    }

    private fun onLoaded(
        slot: Slot,
        buffer: dynamic,
    ) {
        try {
            if (disposed) return
            if (!present(buffer)) {
                slot.state = SlotState.FAILED
                return
            }
            slot.buffer = buffer
            slot.state = SlotState.READY
            val request = waiting ?: return
            if (slotOf(request.kind) !== slot) return
            waiting = null
            val onTime = now() - request.requestedAt <= ENCOUNTER_SOUND_MAX_LATENESS_MS
            if (onTime && pageVisible()) start(request.kind, slot)
        } catch (e: Throwable) {
            slot.state = SlotState.FAILED
        }
    }

    private fun play(kind: EncounterSoundKind) {
        try {
            if (disposed || !pageVisible()) return
            // a second call of the kind that already sounds is ignored
            if (current?.kind == kind) return
            val slot = slotOf(kind)
            when (slot.state) {
                SlotState.READY -> start(kind, slot)
                // the one waiting request: a newer one replaces the older
                SlotState.LOADING -> waiting = WaitingRequest(kind, now())
                // never loaded (the sound is off) or failed: stay quiet, never retry on a ring
                SlotState.IDLE, SlotState.FAILED -> Unit
            }
        } catch (e: Throwable) {
            // a failing sound never takes anything else down
        }
    }

    private fun gainOf(kind: EncounterSoundKind): Double =
        if (kind ==
            EncounterSoundKind.BLESSING
        ) {
            ENCOUNTER_BLESSING_GAIN
        } else {
            ENCOUNTER_BELL_PEAK_GAIN
        }

    private fun start(
        kind: EncounterSoundKind,
        slot: Slot,
    ) {
        val ctx = readyContext()
        if (!present(ctx)) return
        val t = ctx.currentTime as Double
        val running = current
        var begin = t + START_DELAY_S
        if (running != null) {
            // one voice at a time: the running one fades out first, the new one starts after it
            fadeOut(running, t)
            begin += ENCOUNTER_SOUND_FADE_S
        }
        val level = gainOf(kind)
        val gain = ctx.createGain()
        gain.gain.value = level
        gain.connect(ctx.destination)
        val source = ctx.createBufferSource()
        source.buffer = slot.buffer
        source.connect(gain)
        val voice = Voice(kind, source, gain)
        val ended: () -> Unit = { if (current === voice) current = null }
        source.onended = ended
        if (kind == EncounterSoundKind.PROBE) {
            val fadeAt = begin + ENCOUNTER_PROBE_LENGTH_S
            gain.gain.setValueAtTime(level, fadeAt)
            gain.gain.linearRampToValueAtTime(0.0, fadeAt + ENCOUNTER_PROBE_FADE_S)
        }
        current = voice
        source.start(begin)
        if (kind == EncounterSoundKind.PROBE) stopQuietly(source, begin + ENCOUNTER_PROBE_LENGTH_S + ENCOUNTER_PROBE_FADE_S)
    }

    private fun fadeOut(
        voice: Voice,
        t: Double,
    ) {
        try {
            val param = voice.gain.gain
            param.cancelScheduledValues(t)
            param.setValueAtTime(param.value, t)
            param.linearRampToValueAtTime(0.0, t + ENCOUNTER_SOUND_FADE_S)
        } catch (e: Throwable) {
            // the voice is stopped anyway
        }
        stopQuietly(voice.source, t + ENCOUNTER_SOUND_FADE_S)
    }

    /** `stop` throws an `InvalidStateError` for a source that was never started or already stopped. */
    private fun stopQuietly(
        source: dynamic,
        at: Double? = null,
    ) {
        try {
            if (at == null) source.stop() else source.stop(at)
        } catch (e: Throwable) {
            // already stopped
        }
    }

    override fun silence() {
        try {
            waiting = null
            val running = current ?: return
            current = null
            val ctx = context
            fadeOut(running, if (present(ctx)) ctx.currentTime as Double else 0.0)
        } catch (e: Throwable) {
            // silent
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        waiting = null
        val running = current
        current = null
        if (running != null) stopQuietly(running.source)
        for (slot in listOf(callSlot, blessingSlot)) {
            slot.state = SlotState.IDLE
            slot.buffer = null
        }
        val ctx = context
        context = null
        try {
            if (present(ctx)) swallow(ctx.close())
        } catch (e: Throwable) {
            // closing is best effort
        }
    }
}
