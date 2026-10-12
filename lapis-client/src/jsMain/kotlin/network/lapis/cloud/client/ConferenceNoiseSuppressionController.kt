package network.lapis.cloud.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * V1.9.114 -- the microphone's filter chain as far as the controller needs it. A seam: a `jsTest` hands over a fake; the real one wraps
 * the local microphone track of a `LiveKitRoomSession` ([network.lapis.cloud.client.livekit.LiveKitRoomSession.noiseSuppressionChain]).
 *
 * The three calls never `unpublish` and never touch the mute flag: the track is the same before and after, only a processor is put in its path.
 */
internal interface NoiseSuppressionChain {
    /**
     * Runs synchronously INSIDE the person's click (the browser starts an audio context only inside a gesture): creates and resumes the
     * context, nothing else. Must not throw.
     */
    fun prepare()

    /** Puts the filter into the microphone's path. `null` = in place (or nothing to do yet), otherwise why not; the filter is then removed again. */
    suspend fun attach(): NoiseSuppressionUnavailable?

    /** Takes the filter out of the path again (idempotent). */
    suspend fun detach()

    /**
     * Best effort: the browser's own noise reduction on the raw microphone. Off while RNNoise works (no double filtering), on again whenever
     * RNNoise is off or not available. Echo cancellation and automatic gain are NEVER touched. A browser may refuse; that is ignored.
     */
    suspend fun setBrowserNoiseSuppression(enabled: Boolean)
}

/** How long taking the filter out (or switching the browser's own reduction) may take; the switch must never hang behind a stuck load. */
internal const val NOISE_SUPPRESSION_STOP_TIMEOUT_MS = 3_000L

/** How long the loading of the filter may take before it counts as `LOAD_FAILED` (injected, so a test needs no clock). */
internal const val NOISE_SUPPRESSION_LOAD_TIMEOUT_MS = 8_000L

/**
 * V1.9.114 -- the one state machine of the noise suppression switch, shared by the conference and the pulpit of the encounter room.
 *
 * - The remembered choice is read ONCE at construction and applied as soon as a microphone track exists ([onMicTrackPublished]); the first
 *   state is announced to nobody (no announcement while loading).
 * - A person's [toggle] flips the choice. Announcements ([observe]'s second argument) come only after a person's own change that ends
 *   `On`, and on a failure; going Off announces nothing (the switch itself says it).
 * - Whatever goes wrong (no AudioWorklet, no WebAssembly, a load error, a timeout, a suspended audio context) ends in
 *   [NoiseSuppressionState.Unavailable]: the filter is taken out, the browser's own noise reduction is switched back on, the choice stays
 *   remembered, and NO error dialog opens.
 * - Operations never overlap (one [Mutex]); a click while one runs is dropped, so a quick double click cannot stack two filters.
 */
internal class ConferenceNoiseSuppressionController(
    private val preference: NoiseSuppressionPreference,
    private val chain: () -> NoiseSuppressionChain?,
    private val capability: () -> NoiseSuppressionUnavailable?,
    private val loadTimeoutMs: Long = NOISE_SUPPRESSION_LOAD_TIMEOUT_MS,
    private val stopTimeoutMs: Long = NOISE_SUPPRESSION_STOP_TIMEOUT_MS,
) {
    private val listeners = mutableListOf<(NoiseSuppressionState, Boolean) -> Unit>()
    private val lock = Mutex()
    private var wantOn: Boolean = runCatching { preference.read() }.getOrDefault(false)

    var state: NoiseSuppressionState =
        capability()?.let { NoiseSuppressionState.Unavailable(it) }
            ?: if (wantOn) NoiseSuppressionState.On else NoiseSuppressionState.Off
        private set

    /** `true` while a person's switch is being processed. */
    var busy: Boolean = false
        private set

    /** Tells [listener] every change: the new state and whether it is to be announced. It is called once at once with the current state (never announced). */
    fun observe(listener: (NoiseSuppressionState, announce: Boolean) -> Unit) {
        listeners += listener
        listener(state, false)
    }

    /** Called synchronously inside the click: lets the chain create its audio context inside the gesture. */
    fun prepareFromGesture() {
        if (state is NoiseSuppressionState.Unavailable || busy) return
        runCatching { chain()?.prepare() }
    }

    /** A person flips the switch. Dropped while another change runs or while the filter is not available. */
    suspend fun toggle() {
        if (busy || state is NoiseSuppressionState.Unavailable) return
        busy = true
        publish(state, announce = false)
        try {
            lock.withLock {
                if (state is NoiseSuppressionState.On) turnOff() else turnOn(announceSuccess = true)
            }
        } finally {
            busy = false
            publish(state, announce = false)
        }
    }

    /**
     * A microphone track was (re-)published (first switch-on, device change, reconnect): applies the remembered choice. Idempotent, and it
     * never retries a filter that already failed in this page.
     */
    suspend fun onMicTrackPublished() {
        if (state is NoiseSuppressionState.Unavailable) return
        lock.withLock {
            if (state is NoiseSuppressionState.Unavailable || !wantOn) return@withLock
            turnOn(announceSuccess = false)
        }
    }

    private suspend fun turnOn(announceSuccess: Boolean) {
        val reason = capability()
        if (reason != null) {
            fail(reason)
            return
        }
        val current = chain()
        if (current == null) {
            // no microphone yet: remembered, applied by onMicTrackPublished
            wantOn = true
            preference.write(true)
            setState(NoiseSuppressionState.On, announce = announceSuccess)
            return
        }
        val outcome = attach(current)
        if (outcome != null) {
            fail(outcome)
            return
        }
        wantOn = true
        preference.write(true)
        bounded { current.setBrowserNoiseSuppression(false) }
        setState(NoiseSuppressionState.On, announce = announceSuccess)
    }

    private suspend fun turnOff() {
        wantOn = false
        preference.write(false)
        val current = chain()
        if (current != null) {
            bounded { current.detach() }
            bounded { current.setBrowserNoiseSuppression(true) }
        }
        setState(NoiseSuppressionState.Off, announce = false)
    }

    /** `null` = attached. Any exception or a timeout is `LOAD_FAILED`; a cancellation is passed on. */
    private suspend fun attach(current: NoiseSuppressionChain): NoiseSuppressionUnavailable? =
        try {
            val answered = withTimeoutOrNull(loadTimeoutMs) { Attached(current.attach()) }
            if (answered == null) NoiseSuppressionUnavailable.LOAD_FAILED else answered.failure
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            NoiseSuppressionUnavailable.LOAD_FAILED
        }

    private class Attached(
        val failure: NoiseSuppressionUnavailable?,
    )

    /** The filter does not work: out of the path, browser noise reduction back on, the choice stays, state `Unavailable`, announced. */
    private suspend fun fail(reason: NoiseSuppressionUnavailable) {
        val current = chain()
        if (current != null) {
            bounded { current.detach() }
            bounded { current.setBrowserNoiseSuppression(true) }
        }
        setState(NoiseSuppressionState.Unavailable(reason), announce = true)
    }

    private fun setState(
        next: NoiseSuppressionState,
        announce: Boolean,
    ) {
        state = next
        publish(next, announce)
    }

    private fun publish(
        next: NoiseSuppressionState,
        announce: Boolean,
    ) {
        listeners.toList().forEach { runCatching { it(next, announce) } }
    }

    /** A clean-up step: never throws (a cancellation excepted), never waits longer than [stopTimeoutMs]. */
    private suspend fun bounded(step: suspend () -> Unit) {
        try {
            withTimeoutOrNull(stopTimeoutMs) { step() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // best effort
        }
    }
}
