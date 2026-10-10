package network.lapis.cloud.client.encounter

import kotlinx.browser.window

/**
 * V1.9.96 -- the bell's sound, synthesised with WebAudio: NO sound file. So there is no third-party asset (no licence or provenance
 * question), the bundle grows by 0 bytes, and nothing is fetched or decoded -- no network artefact that could be linked to a visit.
 *
 * A strike is seven sine partials of a bell-like spectrum (ratios and amplitudes below, the amplitudes normalised to a sum of 1), each
 * with its own envelope (a 5 ms attack, an exponential decay of [ENCOUNTER_BELL_DECAY_S]), summed into ONE master gain of at most
 * [ENCOUNTER_BELL_PEAK_GAIN]. A ring is [ENCOUNTER_BELL_STRIKES] strikes [ENCOUNTER_BELL_STRIKE_GAP_S] apart, a little under five seconds.
 *
 * The sound is quiet by construction: it is never louder than the fixed peak gain, never played in a hidden tab (the display decides),
 * never without a user's own switch, and an exception or a refused promise of the browser can never keep the visible sign away.
 */
internal const val ENCOUNTER_BELL_PEAK_GAIN = 0.25

/** The fundamental of the bell, in hertz. */
internal const val ENCOUNTER_BELL_BASE_HZ = 440.0

/** Frequency ratios of the partials (the minor-third "hum" tone, the fundamental, the tierce, the fifth and the upper partials). */
internal val ENCOUNTER_BELL_PARTIAL_RATIOS: DoubleArray = doubleArrayOf(0.5, 1.0, 1.2, 1.5, 2.0, 2.5, 3.0)

/** Relative amplitudes of the partials (normalised by their sum when a strike is built). */
internal val ENCOUNTER_BELL_PARTIAL_AMPLITUDES: DoubleArray = doubleArrayOf(0.6, 1.0, 0.5, 0.35, 0.45, 0.2, 0.15)

/** Strikes per ring. */
internal const val ENCOUNTER_BELL_STRIKES = 3

/** Seconds between two strikes. */
internal const val ENCOUNTER_BELL_STRIKE_GAP_S = 1.2

/** Seconds from the attack to the end of the audible decay of one strike. */
internal const val ENCOUNTER_BELL_DECAY_S = 2.5

/** Seconds from the start of the first strike to the end of the last audible decay (under five seconds, like the sign). */
internal fun encounterBellTotalSeconds(): Double = (ENCOUNTER_BELL_STRIKES - 1) * ENCOUNTER_BELL_STRIKE_GAP_S + ENCOUNTER_BELL_DECAY_S

private const val ATTACK_S = 0.005
private const val SILENCE = 0.0001
private const val START_DELAY_S = 0.02
private const val STOP_MARGIN_S = 0.1

/** The sound of the bell as the room sees it. Every method is safe to call at any time and never throws. */
internal interface EncounterBellSound {
    /** Creates / resumes the audio context without a sound (called from a user's own gesture after a reload). */
    fun prime()

    /** The full ring (three strikes). */
    fun ring()

    /** One strike, played synchronously inside the click that switched the sound on (a browser gesture rule). */
    fun probe()

    /** Closes the audio context. */
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

/**
 * The browser implementation: ONE audio context per room, created lazily and only after a user activation. [applySink] gives the room the
 * chance to point the context at the speaker the person chose (best effort; the room hands over a lambda, never a device id).
 */
internal class BrowserEncounterBellSound(
    private val factory: EncounterAudioContextFactory = browserAudioContextFactory,
    private val applySink: (dynamic) -> Unit = {},
    private val hasBeenActive: () -> Boolean = ::browserHasBeenActive,
) : EncounterBellSound {
    private var context: dynamic = null
    private var disposed = false

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
            readyContext()
        } catch (e: Throwable) {
            // silent
        }
    }

    override fun ring() = play(ENCOUNTER_BELL_STRIKES)

    override fun probe() = play(1)

    private fun play(strikes: Int) {
        try {
            val ctx = readyContext()
            if (!present(ctx)) return
            val master = ctx.createGain()
            master.gain.value = ENCOUNTER_BELL_PEAK_GAIN
            master.connect(ctx.destination)
            val start = (ctx.currentTime as Double) + START_DELAY_S
            for (k in 0 until strikes) strike(ctx, master, start + k * ENCOUNTER_BELL_STRIKE_GAP_S)
        } catch (e: Throwable) {
            // a failing sound never takes anything else down
        }
    }

    private fun strike(
        ctx: dynamic,
        master: dynamic,
        at: Double,
    ) {
        val sum = ENCOUNTER_BELL_PARTIAL_AMPLITUDES.sum()
        for (i in ENCOUNTER_BELL_PARTIAL_RATIOS.indices) {
            val oscillator = ctx.createOscillator()
            oscillator.type = "sine"
            oscillator.frequency.value = ENCOUNTER_BELL_BASE_HZ * ENCOUNTER_BELL_PARTIAL_RATIOS[i]
            val envelope = ctx.createGain()
            envelope.gain.setValueAtTime(SILENCE, at)
            envelope.gain.linearRampToValueAtTime(ENCOUNTER_BELL_PARTIAL_AMPLITUDES[i] / sum, at + ATTACK_S)
            envelope.gain.exponentialRampToValueAtTime(SILENCE, at + ENCOUNTER_BELL_DECAY_S)
            oscillator.connect(envelope)
            envelope.connect(master)
            oscillator.start(at)
            oscillator.stop(at + ENCOUNTER_BELL_DECAY_S + STOP_MARGIN_S)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        val ctx = context
        context = null
        try {
            if (present(ctx)) swallow(ctx.close())
        } catch (e: Throwable) {
            // closing is best effort
        }
    }
}
