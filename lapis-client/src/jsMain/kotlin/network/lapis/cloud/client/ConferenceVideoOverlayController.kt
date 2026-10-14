package network.lapis.cloud.client

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayLibraryDto

/*
 * V1.9.129 "Video-Overlays" -- the owner of everything the overlay feature needs in ONE call: the person's remembered [VideoOverlaySettings],
 * the library of the instance, the [VideoOverlayScene] derived from both, the [VideoOverlayCompositor] (drawing step) and the hand-over to
 * the [ConferenceBackgroundController] (which owns the single processor on the camera track). Pure orchestration, no DOM -- testable with
 * fakes through the constructor seams.
 *
 * **One scene, loaded before it is shown.** A change builds the new [VideoOverlayScene], loads ALL its bitmaps ([VideoOverlayCompositor.prepare])
 * and only then swaps it in -- the video never shows a half-loaded scene. A request that was overtaken by a newer one is discarded.
 *
 * **Failures stay on the overlay side.** A bitmap that cannot be loaded is left out ([VideoOverlayFailure.LOAD_FAILED], one message per
 * session); a drawing failure passes the ORIGINAL frames on ([VideoOverlayFailure.DRAW_FAILED]). The person's settings are never touched by
 * a failure and the background effect keeps its own state.
 */

internal data class VideoOverlayUiState(
    val offered: Boolean,
    val availability: ConferenceBackgroundAvailability,
    val settings: VideoOverlaySettings,
    val library: List<VideoOverlayItemDto>,
    /** `true` once the library answer is in (an unreachable server leaves it `false` and the panel without choices). */
    val libraryLoaded: Boolean,
    /** What is (to be) drawn right now; `null` = nothing, and no hint about a changed image is shown. */
    val scene: VideoOverlayScene?,
    val state: VideoOverlayState,
    /**
     * `true` while the scene really reaches the video (the compositor has one and is not suspended). Drives the "So sehen andere Sie" note
     * and the panel hint -- NOT [state]'s phase: a failing single element (phase FAILED_FALLBACK) leaves the rest drawn and visible to
     * others, and a draw failure leaves a scene that draws nothing (no invisible mode, no false promise).
     */
    val drawing: Boolean = scene != null,
)

private const val DEFAULT_FRAME_WIDTH = 1280
private const val DEFAULT_FRAME_HEIGHT = 720
internal const val OVERLAY_PREVIEW_WIDTH = 320
internal const val OVERLAY_PREVIEW_HEIGHT = 180

internal class ConferenceVideoOverlayController(
    /** The join answer's `videoOverlaysOffered`; `false` => no request, no drawing, no panel. */
    private val offered: Boolean,
    private val availability: ConferenceBackgroundAvailability,
    /** The person's own display name as the server knows it (the join token's `displayName`). */
    private val displayName: String,
    private val compositor: VideoOverlayCompositor,
    private val backgroundController: ConferenceBackgroundController,
    private val currentTrack: () -> BackgroundTrack?,
    /** The camera's size (pixels) as far as known; `null` -> 1280 x 720. */
    private val frameSize: () -> Pair<Int, Int>?,
    private val loadLibrary: suspend () -> VideoOverlayLibraryDto?,
    private val notifyFailure: (String) -> Unit,
    private val onChanged: (VideoOverlayUiState) -> Unit,
    private val loadSettings: () -> VideoOverlaySettings = ::loadStoredVideoOverlaySettings,
    private val saveSettings: (VideoOverlaySettings) -> Unit = ::storeVideoOverlaySettings,
    private val launch: (suspend () -> Unit) -> Unit = { block -> AppScope.launch { block() } },
) {
    private val mutex = Mutex()
    private var generation = 0
    private var disposed = false
    private var sizeOverride: Pair<Int, Int>? = null

    var settings: VideoOverlaySettings = VideoOverlaySettings()
        private set
    var library: List<VideoOverlayItemDto> = emptyList()
        private set
    var libraryOffered: Boolean = offered
        private set
    var libraryLoaded: Boolean = false
        private set
    var scene: VideoOverlayScene? = null
        private set
    var state: VideoOverlayState = VideoOverlayState()
        private set

    /** The overlay-only processor could not be put on the camera track (since the last scene request): the scene draws nothing. */
    private var processorFailed = false

    private val usable: Boolean get() = offered && availability == ConferenceBackgroundAvailability.AVAILABLE

    fun uiState(): VideoOverlayUiState =
        VideoOverlayUiState(
            offered = offered,
            availability = availability,
            settings = settings,
            library = library,
            libraryLoaded = libraryLoaded,
            scene = scene,
            state = state,
            drawing = compositor.isDrawing && !processorFailed,
        )

    private fun transition(event: VideoOverlayEvent) {
        state = videoOverlayReduce(state, event)
    }

    private fun publish() {
        runCatching { onChanged(uiState()) }
    }

    /** Once per join: remembered settings + library, then the first scene. Without the option or the browser support: nothing at all. */
    suspend fun start() {
        if (!usable || disposed) return
        settings = loadSettings()
        val answer = runCatching { loadLibrary() }.getOrNull()
        if (answer != null) {
            libraryOffered = answer.offered
            library = answer.items
            libraryLoaded = true
        }
        publish()
        mutex.withLock { applyLocked() }
    }

    /** The person changed something in the panel: remember it and apply it. */
    suspend fun update(newSettings: VideoOverlaySettings) {
        if (!usable || disposed) return
        settings = newSettings
        saveSettings(newSettings)
        publish()
        mutex.withLock { applyLocked() }
    }

    /** A new or re-published camera track: the processor must be (re)applied with the current scene. */
    suspend fun onCameraTrack() {
        if (!usable || disposed) return
        mutex.withLock {
            sizeOverride = null
            applyLocked()
        }
    }

    /** The drawing step saw a camera size other than the prepared one: prepare again for it (once per size). */
    fun onFrameSizeMismatch(
        width: Int,
        height: Int,
    ) {
        if (!usable || disposed) return
        launch {
            mutex.withLock {
                sizeOverride = width to height
                applyLocked()
            }
        }
    }

    /** The drawing step threw: the original frames pass on (see [VideoOverlayCompositor.process]); tell the person once. */
    fun onDrawFailure() = reportFailure(VideoOverlayFailure.DRAW_FAILED)

    /** The overlay-only processor could not be applied to the camera track. */
    fun onProcessorFailure(failure: VideoOverlayFailure) {
        // No processor on the camera track: the compositor still holds its scene, but no frame ever reaches it, so nothing is drawn.
        processorFailed = true
        reportFailure(failure)
    }

    private fun reportFailure(failure: VideoOverlayFailure) {
        if (disposed) return
        val notify = videoOverlayShouldNotify(state, failure)
        transition(VideoOverlayEvent.Failed(failure))
        publish()
        if (notify) notifyFailure(videoOverlayFailureMessage(failure))
    }

    private suspend fun applyLocked() {
        if (disposed) return
        val mine = ++generation
        // A new attempt: the processor is (re)applied at its end (ConferenceBackgroundController.onOverlayChanged) and reports again if it fails.
        processorFailed = false
        val wanted =
            effectiveVideoOverlayScene(
                settings = settings,
                library = library,
                offered = usable && libraryOffered,
                availability = availability,
                displayName = displayName,
            )
        scene = wanted
        if (wanted == null) {
            compositor.setScene(null)
            transition(VideoOverlayEvent.SceneCleared)
            publish()
            backgroundController.onOverlayChanged(currentTrack())
            return
        }
        transition(VideoOverlayEvent.SceneRequested)
        publish()
        val (width, height) = sizeOverride ?: frameSize() ?: (DEFAULT_FRAME_WIDTH to DEFAULT_FRAME_HEIGHT)
        val prepared = compositor.prepare(scene = wanted, frameWidth = width, frameHeight = height)
        if (disposed || mine != generation) {
            compositor.discard(prepared)
            return
        }
        if (prepared.ops.isEmpty()) {
            compositor.discard(prepared)
            compositor.setScene(null)
        } else {
            compositor.setScene(prepared)
        }
        if (prepared.failedElements > 0) {
            reportFailure(VideoOverlayFailure.LOAD_FAILED)
        } else {
            transition(if (prepared.ops.isEmpty()) VideoOverlayEvent.SceneCleared else VideoOverlayEvent.SceneActive)
            publish()
        }
        backgroundController.onOverlayChanged(currentTrack())
    }

    /**
     * A throw-away scene for the panel's static preview (camera off): prepared for a [OVERLAY_PREVIEW_WIDTH] x [OVERLAY_PREVIEW_HEIGHT]
     * picture, handed to [draw], and closed again. `null` while nothing is to be drawn.
     */
    suspend fun withPreviewScene(draw: (PreparedOverlayScene) -> Unit) {
        val wanted = scene ?: return
        if (disposed) return
        val prepared = compositor.prepare(wanted, OVERLAY_PREVIEW_WIDTH, OVERLAY_PREVIEW_HEIGHT, current = null)
        try {
            draw(prepared)
        } finally {
            // Nothing of a preview scene is ever shared with the running one (current = null), so everything may close.
            prepared.bitmaps.values.forEach { runCatching { it.close() } }
        }
    }

    /** Leaving the call: the drawing step ends and every bitmap closes. */
    fun dispose() {
        disposed = true
        generation++
        compositor.close()
    }
}
