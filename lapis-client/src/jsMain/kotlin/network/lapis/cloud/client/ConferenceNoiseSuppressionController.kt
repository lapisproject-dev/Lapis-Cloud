package network.lapis.cloud.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    suspend fun attach(): NoiseSuppressionFailure?

    /** Takes the filter out of the path again (idempotent). */
    suspend fun detach()

    /**
     * Best effort: the browser's own noise reduction on the raw microphone. Off while RNNoise works (no double filtering), on again whenever
     * RNNoise is off or not available. Echo cancellation and automatic gain are NEVER touched. A browser may refuse; that is ignored.
     */
    suspend fun setBrowserNoiseSuppression(enabled: Boolean)

    /** V1.9.119 -- numbers only (context state/rate, track rate/channels), for the one console line of a failure. Never a device name or id. */
    fun diagnostics(trigger: String): NoiseSuppressionDiagnostics = NoiseSuppressionDiagnostics(trigger)

    /** V1.9.119 -- where a failure AFTER a successful attach (a device change that cannot be followed) is reported; `null` removes the listener. */
    fun setRuntimeFailureListener(listener: ((NoiseSuppressionFailure) -> Unit)?) {}
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
 *
 * V1.9.119: a failure carries its stage ([NoiseSuppressionFailure]) and is no longer final for the whole page. Only the failures that cannot
 * change ([NoiseSuppressionUnavailable.retryable] = false) lock the switch; for every other one a click, a new microphone track or (for a
 * context the browser kept suspended because no gesture had happened yet) the person's next click anywhere on the page tries again. Each
 * failure writes ONE console line per (stage, trigger) with numbers only -- no device names, no ids, no URLs with a query.
 */
internal class ConferenceNoiseSuppressionController(
    private val preference: NoiseSuppressionPreference,
    private val chain: () -> NoiseSuppressionChain?,
    private val capability: () -> NoiseSuppressionUnavailable?,
    private val loadTimeoutMs: Long = NOISE_SUPPRESSION_LOAD_TIMEOUT_MS,
    private val stopTimeoutMs: Long = NOISE_SUPPRESSION_STOP_TIMEOUT_MS,
    private val gestureHook: GestureHook = DocumentGestureHook,
    private val logger: NoiseSuppressionLogger = ConsoleNoiseSuppressionLogger,
    private val scope: CoroutineScope = AppScope,
    /**
     * V1.9.124 -- the instance default of the organisation (an ADMIN's setting, delivered with the join token). It applies ONLY while the
     * person never chose here ([choice] == null); a stored choice, on or off, always wins. The automatic paths never write the preference,
     * so the default stays a default and does not turn into the person's own choice.
     */
    private val instanceDefault: Boolean = false,
) {
    private val listeners = mutableListOf<(NoiseSuppressionState, Boolean) -> Unit>()
    private val lock = Mutex()
    private var choice: Boolean? = runCatching { preference.read() }.getOrNull()
    private val wantOn: Boolean get() = choice ?: instanceDefault
    private val logged = mutableSetOf<Pair<NoiseSuppressionUnavailable, String>>()
    private var gestureRemover: (() -> Unit)? = null
    private var gestureTried = false
    private var disposed = false

    /** `true` while the filter is wanted only because of the instance default (the person never chose). Read by the switch for its hint line. */
    val fromDefault: Boolean get() = choice == null && instanceDefault

    var state: NoiseSuppressionState =
        capability()?.let { NoiseSuppressionState.Unavailable(NoiseSuppressionFailure(it)) }
            ?: if (wantOn) NoiseSuppressionState.On else NoiseSuppressionState.Off
        private set

    /** `true` while a person's switch is being processed. */
    var busy: Boolean = false
        private set

    init {
        // a browser without AudioWorklet/WebAssembly is also one console line (stage and numbers only), once per page
        (state as? NoiseSuppressionState.Unavailable)?.let { log(it.failure, "init", null) }
    }

    /** Tells [listener] every change: the new state and whether it is to be announced. It is called once at once with the current state (never announced). */
    fun observe(listener: (NoiseSuppressionState, announce: Boolean) -> Unit) {
        listeners += listener
        listener(state, false)
    }

    /** Takes the "next gesture" hook away (the page is going away). Idempotent; nothing is tried afterwards. */
    fun dispose() {
        disposed = true
        removeGestureHook()
    }

    /** `true` for a failure that another attempt cannot fix. */
    private fun permanentlyUnavailable(): Boolean = (state as? NoiseSuppressionState.Unavailable)?.failure?.reason?.retryable == false

    /** Called synchronously inside the click: lets the chain create (and resume) its audio context inside the gesture. */
    fun prepareFromGesture() {
        removeGestureHook()
        if (permanentlyUnavailable() || busy) return
        runCatching { chainOrNull()?.prepare() }
    }

    /** A person flips the switch. Dropped while another change runs or after a failure that cannot change; after any other failure it tries again. */
    suspend fun toggle() {
        if (busy || permanentlyUnavailable()) return
        busy = true
        publish(state, announce = false)
        try {
            lock.withLock {
                if (state is NoiseSuppressionState.On) turnOff() else turnOn(announceSuccess = true, trigger = "click")
            }
        } finally {
            busy = false
            publish(state, announce = false)
        }
    }

    /**
     * A microphone track was (re-)published (first switch-on, device change, reconnect): applies the remembered choice. Idempotent. After a
     * failure that may be temporary it tries again (the mutex, the once-per-stage log line and the single gesture hook keep that cheap); after
     * a failure that cannot change it does nothing.
     */
    suspend fun onMicTrackPublished() {
        if (permanentlyUnavailable() || disposed) return
        lock.withLock {
            if (permanentlyUnavailable() || !wantOn) return@withLock
            turnOn(announceSuccess = false, trigger = "auto")
        }
    }

    private fun chainOrNull(): NoiseSuppressionChain? =
        chain()?.also { current ->
            runCatching { current.setRuntimeFailureListener { failure -> scope.launch { lock.withLock { failRuntime(failure) } } } }
        }

    private suspend fun turnOn(
        announceSuccess: Boolean,
        trigger: String,
    ) {
        val persist = trigger == "click"
        val reason = capability()
        if (reason != null) {
            fail(NoiseSuppressionFailure(reason), trigger)
            return
        }
        val current = chainOrNull()
        if (current == null) {
            // no microphone yet: applied by onMicTrackPublished; only a person's click is remembered, never the default
            if (persist) {
                choice = true
                preference.write(true)
            }
            setState(NoiseSuppressionState.On, announce = announceSuccess)
            return
        }
        val outcome = attach(current)
        if (outcome != null) {
            fail(outcome, trigger)
            return
        }
        if (persist) {
            choice = true
            preference.write(true)
        }
        removeGestureHook()
        bounded { current.setBrowserNoiseSuppression(false) }
        setState(NoiseSuppressionState.On, announce = announceSuccess)
    }

    private suspend fun turnOff() {
        choice = false
        preference.write(false)
        removeGestureHook()
        val current = chainOrNull()
        if (current != null) {
            bounded { current.detach() }
            bounded { current.setBrowserNoiseSuppression(true) }
        }
        setState(NoiseSuppressionState.Off, announce = false)
    }

    /** `null` = attached. A timeout is `TIMEOUT`, a stage failure keeps its stage, any other exception is `LOAD_FAILED`; a cancellation is passed on. */
    private suspend fun attach(current: NoiseSuppressionChain): NoiseSuppressionFailure? =
        try {
            val answered = withTimeoutOrNull(loadTimeoutMs) { Attached(current.attach()) }
            if (answered == null) NoiseSuppressionFailure(NoiseSuppressionUnavailable.TIMEOUT) else answered.failure
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoiseSuppressionStageException) {
            e.failure
        } catch (e: Throwable) {
            failureOf(NoiseSuppressionUnavailable.LOAD_FAILED, e)
        }

    private class Attached(
        val failure: NoiseSuppressionFailure?,
    )

    /**
     * The filter does not work: out of the path, browser noise reduction back on, ONE console line (per stage and trigger), state
     * `Unavailable`, announced (not again for the same stage after an automatic retry). The remembered choice is NOT touched.
     */
    private suspend fun fail(
        failure: NoiseSuppressionFailure,
        trigger: String,
    ) {
        val current = chainOrNull()
        if (current != null) {
            bounded { current.detach() }
            bounded { current.setBrowserNoiseSuppression(true) }
        }
        log(failure, trigger, current)
        val previous = (state as? NoiseSuppressionState.Unavailable)?.failure?.reason
        val repeated = trigger == "auto" && previous == failure.reason
        // V1.9.124: a failure of the instance default (nobody clicked) is shown in the switch but never announced
        val silentDefault = fromDefault && trigger != "click"
        setState(NoiseSuppressionState.Unavailable(failure), announce = !repeated && !silentDefault)
        if (trigger == "auto" && failure.reason == NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING && wantOn) registerGestureHook()
    }

    /** A failure after the filter was in place (a device change LiveKit could not follow): same fallback, trigger `restart`. */
    private suspend fun failRuntime(failure: NoiseSuppressionFailure) {
        if (disposed || state !is NoiseSuppressionState.On) return
        fail(failure, "restart")
    }

    private fun log(
        failure: NoiseSuppressionFailure,
        trigger: String,
        current: NoiseSuppressionChain?,
    ) {
        if (!logged.add(failure.reason to trigger)) return
        val diagnostics = runCatching { current?.diagnostics(trigger) }.getOrNull() ?: NoiseSuppressionDiagnostics(trigger)
        runCatching { logger.warn(noiseSuppressionLogLine(failure, diagnostics)) }
    }

    /** The browser kept the context suspended (no gesture yet): the person's next click or key press anywhere starts it, ONCE. */
    private fun registerGestureHook() {
        if (disposed || gestureRemover != null || gestureTried) return
        gestureRemover = runCatching { gestureHook.registerOnce(::onGesture) }.getOrNull()
    }

    private fun removeGestureHook() {
        val remover = gestureRemover ?: return
        gestureRemover = null
        runCatching { remover() }
    }

    private fun onGesture() {
        gestureRemover = null
        gestureTried = true
        if (disposed) return
        // synchronous, inside the gesture: the audio context may only start here
        runCatching { chainOrNull()?.prepare() }
        if (busy || state !is NoiseSuppressionState.Unavailable || !wantOn) return
        busy = true
        publish(state, announce = false)
        scope.launch {
            try {
                lock.withLock {
                    if (!disposed &&
                        state is NoiseSuppressionState.Unavailable &&
                        wantOn
                    ) {
                        turnOn(announceSuccess = false, trigger = "gesture")
                    }
                }
            } finally {
                busy = false
                publish(state, announce = false)
            }
        }
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
