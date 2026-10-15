package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.localStorage
import kotlinx.coroutines.launch
import org.w3c.dom.get
import org.w3c.dom.set

/*
 * V1.9.142 "Kamera-Filter" -- the owner of everything the camera filters need in ONE call: the person's remembered [CameraFilterSettings],
 * the frame stage ([CameraFilterStage]) that applies them, the session-only state of the skin smoothing (paused for performance or because
 * the face detector could not start), and the hand-over to the [ConferenceBackgroundController] (which owns the single processor on the
 * camera track). Pure orchestration, no DOM -- testable with fakes through the constructor seams.
 *
 * **Failures stay on the filter side.** A failing stage passes the ORIGINAL frames on (the stage guarantees it), the controller tells the
 * person once per cause per session and never touches the slider values; a paused smoothing keeps its slider position and can be tried again.
 * The background effect and the overlays keep their own state either way.
 *
 * **One processor per track.** Whether a processor sits on the track at all follows [CameraFilterStage.active] through
 * [OverlayProcessorPort.wanted]: this class only says "the pipeline changed" ([ConferenceBackgroundController.onPipelineChanged]).
 */

internal enum class CameraFilterPhase { IDLE, APPLYING, ACTIVE, FAILED, UNSUPPORTED }

/** Why the skin smoothing is not running although its slider is above zero (session only, never remembered). */
internal enum class SmoothingPause { NONE, PERFORMANCE, UNAVAILABLE }

internal data class CameraFilterUiState(
    /** `false` => no panel at all (guests, and every join that does not offer the panel). */
    val offered: Boolean,
    val availability: ConferenceBackgroundAvailability,
    val settings: CameraFilterSettings,
    val phase: CameraFilterPhase,
    val smoothingPause: SmoothingPause = SmoothingPause.NONE,
    /** Stage 1 of the performance governor: the detector runs less often and the blur works at a quarter of the resolution. */
    val degraded: Boolean = false,
    /** One sentence for the `aria-live` region (changes only on a deliberate action); `null` = leave it alone. */
    val liveMessage: String? = null,
) {
    /** `true` while the filters really change the frames that reach others (drives the notice and the "So sehen andere Sie" mark). */
    val filtering: Boolean get() = phase == CameraFilterPhase.ACTIVE
}

private fun defaultLoadRaw(): String? =
    try {
        localStorage[CAMERA_FILTER_STORAGE_KEY]
    } catch (e: Throwable) {
        null
    }

private fun defaultSaveRaw(value: String) {
    try {
        localStorage[CAMERA_FILTER_STORAGE_KEY] = value
    } catch (e: Throwable) {
        // Storage blocked: the filters then hold for this session only.
    }
}

internal fun cameraFilterFailureMessage(): String =
    gettext("Kamera-Filter konnten nicht angewendet werden. Ihr Bild wird unverändert gesendet.")

internal fun cameraFilterPauseMessage(reason: SmoothingPause): String =
    when (reason) {
        SmoothingPause.PERFORMANCE ->
            gettext("Die Hautglättung wurde pausiert, weil sie Ihr Gerät zu stark belastet. Ihre Einstellung bleibt gespeichert.")
        SmoothingPause.UNAVAILABLE ->
            gettext("Die Hautglättung konnte nicht gestartet werden. Ihre übrigen Filter laufen weiter.")
        SmoothingPause.NONE -> ""
    }

internal class ConferenceCameraFilterController(
    /** `false` => nothing happens at all (no stage, no storage access, no panel). */
    private val offered: Boolean,
    val availability: ConferenceBackgroundAvailability,
    /** `null` when this browser cannot run the stage (no WebGL2, ...). */
    private val stage: CameraFilterStage?,
    private val backgroundController: ConferenceBackgroundController,
    private val currentTrack: () -> BackgroundTrack?,
    private val notifyFailure: (String) -> Unit,
    private val onChanged: (CameraFilterUiState) -> Unit,
    private val loadRaw: () -> String? = ::defaultLoadRaw,
    private val saveRaw: (String) -> Unit = ::defaultSaveRaw,
    private val launch: (suspend () -> Unit) -> Unit = { block -> AppScope.launch { block() } },
) {
    val supported: Boolean get() = offered && availability == ConferenceBackgroundAvailability.AVAILABLE && stage != null

    var settings: CameraFilterSettings = CameraFilterSettings()
        private set
    var phase: CameraFilterPhase = if (offered) CameraFilterPhase.IDLE else CameraFilterPhase.UNSUPPORTED
        private set
    var smoothingPause: SmoothingPause = SmoothingPause.NONE
        private set
    var degraded: Boolean = false
        private set

    private var disposed = false
    private var failureNotified = false
    private val pauseNotified = mutableSetOf<SmoothingPause>()

    /** Reads the remembered sliders and (if the filter is on) puts the stage on the camera track. Call once after construction. */
    suspend fun start() {
        if (disposed) return
        if (!supported) {
            phase = CameraFilterPhase.UNSUPPORTED
            publish()
            return
        }
        settings = decodeCameraFilterSettings(runCatching { loadRaw() }.getOrNull())
        stage?.configure(settings, smoothingPaused())
        phase = derivedPhase()
        publish()
        if (stage?.active == true) handOver()
    }

    /** The one place where slider values change. [live] is the sentence for the `aria-live` region (or `null`). */
    suspend fun update(
        next: CameraFilterSettings,
        live: String? = null,
    ) {
        if (!supported || disposed) return
        settings = next.clamped()
        runCatching { saveRaw(encodeCameraFilterSettings(settings)) }
        stage?.configure(settings, smoothingPaused())
        phase = CameraFilterPhase.APPLYING
        publish(live)
        phase = derivedPhase()
        publish(live)
        handOver()
    }

    /** A slider was moved: the main switch goes on with it ("Regler schaltet ein"), announced once. */
    suspend fun onSliderMoved(change: (CameraFilterSettings) -> CameraFilterSettings) {
        if (!supported || disposed) return
        val moved = change(settings)
        if (!settings.enabled && !moved.enabled) {
            update(moved.copy(enabled = true), live = gettext("Filter eingeblendet"))
        } else {
            update(moved)
        }
    }

    suspend fun setEnabled(on: Boolean) {
        if (!supported || disposed) return
        update(settings.copy(enabled = on), live = if (on) gettext("Filter eingeblendet") else gettext("Filter ausgeblendet"))
    }

    /** "Alle Filter aus": every slider neutral AND the main switch off. */
    suspend fun resetAll() {
        if (!supported || disposed) return
        update(CameraFilterSettings(), live = gettext("Alle Filter ausgeschaltet"))
    }

    /** "Erneut versuchen" after a pause of the smoothing: full quality, the detector may start again. */
    suspend fun retrySmoothing() {
        if (!supported || disposed) return
        smoothingPause = SmoothingPause.NONE
        degraded = false
        pauseNotified.clear()
        stage?.retrySmoothing()
        stage?.configure(settings, smoothingPaused())
        phase = derivedPhase()
        publish()
        handOver()
    }

    /** The stage threw: the original frames already pass on. Tell the person once; the processor is taken off if nothing else needs it. */
    fun onStageFailure() {
        if (disposed) return
        phase = CameraFilterPhase.FAILED
        publish()
        if (!failureNotified) {
            failureNotified = true
            notifyFailure(cameraFilterFailureMessage())
        }
        launch { handOver() }
    }

    /** The processor itself could not be applied to the camera track (it then runs without the filter). */
    fun onProcessorFailure() {
        if (disposed) return
        // Deliberately NO hand-over here: the stage is still `active`, so a new hand-over would build and set the processor again, fail again and
        // report again -- an endless retry loop under the track lock. The person retries by switching the filter off and on (or moving a slider).
        phase = CameraFilterPhase.FAILED
        publish()
        if (!failureNotified) {
            failureNotified = true
            notifyFailure(cameraFilterFailureMessage())
        }
    }

    fun onPerfAction(action: PerfAction) {
        if (disposed) return
        when (action) {
            PerfAction.NONE -> Unit
            PerfAction.REDUCED -> {
                degraded = true
                publish()
            }
            PerfAction.PAUSED -> pauseSmoothing(SmoothingPause.PERFORMANCE)
        }
    }

    fun onSmoothingUnavailable() {
        if (disposed) return
        pauseSmoothing(SmoothingPause.UNAVAILABLE)
    }

    /** A new or re-published camera track: the processor is put on it again (idempotent in the background controller). */
    suspend fun onCameraTrack() {
        if (!supported || disposed) return
        if (stage?.active == true) handOver()
    }

    fun dispose() {
        disposed = true
        stage?.release()
    }

    private fun pauseSmoothing(reason: SmoothingPause) {
        smoothingPause = reason
        stage?.configure(settings, true)
        phase = derivedPhase()
        publish()
        if (pauseNotified.add(reason)) notifyFailure(cameraFilterPauseMessage(reason))
        launch { handOver() }
    }

    private fun smoothingPaused(): Boolean = smoothingPause != SmoothingPause.NONE

    private fun derivedPhase(): CameraFilterPhase =
        when {
            !supported -> CameraFilterPhase.UNSUPPORTED
            stage?.active == true -> CameraFilterPhase.ACTIVE
            // A failed stage stays failed while the person keeps the filter on; switching it off and on again is the fresh start.
            phase == CameraFilterPhase.FAILED && settings.enabled -> CameraFilterPhase.FAILED
            else -> CameraFilterPhase.IDLE
        }

    private suspend fun handOver() {
        runCatching { backgroundController.onPipelineChanged(currentTrack()) }
    }

    private fun publish(live: String? = null) {
        runCatching {
            onChanged(
                CameraFilterUiState(
                    offered = offered,
                    availability = availability,
                    settings = settings,
                    phase = phase,
                    smoothingPause = smoothingPause,
                    degraded = degraded,
                    liveMessage = live,
                ),
            )
        }
    }
}
