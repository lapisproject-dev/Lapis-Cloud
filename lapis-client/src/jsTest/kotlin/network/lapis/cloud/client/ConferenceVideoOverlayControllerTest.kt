package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayLibraryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.129 -- the processor plan and the overlay orchestration through their seams: NONE -> LIGHTWEIGHT -> SEGMENTING -> LIGHTWEIGHT -> NONE,
 * a scene swap never rebuilds a segmenting processor, a failure of the overlay side never touches the background state, and a failing
 * element is reported once.
 */
class ConferenceVideoOverlayControllerTest {
    private class FakeProcessor(
        override val kind: VideoProcessorKind,
    ) : BackgroundProcessorHandle {
        var hooked = false
        val switched = mutableListOf<ConferenceBackgroundChoice>()

        override suspend fun switchTo(choice: ConferenceBackgroundChoice) {
            switched += choice
        }

        override fun appliedBackgroundImagePath(): String? = null
    }

    private class FakeTrack : BackgroundTrack {
        var current: BackgroundProcessorHandle? = null
        val setCalls = mutableListOf<VideoProcessorKind>()
        var stopCalls = 0
        var failSetFor: VideoProcessorKind? = null

        override suspend fun setProcessor(handle: BackgroundProcessorHandle) {
            setCalls += handle.kind
            if (handle.kind == failSetFor) error("setProcessor failed")
            current = handle
        }

        override suspend fun stopProcessor() {
            stopCalls++
            current = null
        }

        override fun currentProcessor(): BackgroundProcessorHandle? = current
    }

    private class FakePort : OverlayProcessorPort {
        var wantedNow = false
        override val wanted: Boolean get() = wantedNow
        val overlayOnly = mutableListOf<FakeProcessor>()
        val hooked = mutableListOf<BackgroundProcessorHandle>()

        override fun createLightweightHandle(): BackgroundProcessorHandle =
            FakeProcessor(VideoProcessorKind.LIGHTWEIGHT).also {
                overlayOnly +=
                    it
            }

        override fun hookSegmenting(handle: BackgroundProcessorHandle) {
            hooked += handle
            (handle as? FakeProcessor)?.hooked = true
        }
    }

    private class Harness {
        val port = FakePort()
        val created = mutableListOf<FakeProcessor>()
        val messages = mutableListOf<String>()
        val overlayFailures = mutableListOf<VideoOverlayFailure>()
        val states = mutableListOf<ConferenceBackgroundState>()
        val controller =
            ConferenceBackgroundController(
                notifyFailure = { messages += it },
                onProcessedStreamSwapped = {},
                onStateChanged = { states += it },
                supported = true,
                processorFactory = { FakeProcessor(VideoProcessorKind.SEGMENTING).also { created += it } },
                imageProbe = {},
                launchDetachedCleanup = {},
                overlayPort = port,
                onOverlayFailure = { overlayFailures += it },
            )
    }

    private val blur = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_LIGHT)

    private fun withCleanStorage(block: suspend () -> Unit) =
        GlobalScope.promise {
            localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
            try {
                block()
            } finally {
                localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
            }
        }

    @Test
    fun theWholeLadder_noneOverlayOnlySegmentingOverlayOnlyNone() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            // Nothing wanted, no effect: no processor at all.
            h.controller.onOverlayChanged(track)
            assertNull(track.current)
            assertTrue(track.setCalls.isEmpty())

            // Overlays appear, no effect: the LIGHTWEIGHT processor (and no segmenting one, i.e. no MediaPipe).
            h.port.wantedNow = true
            h.controller.onOverlayChanged(track)
            assertEquals(listOf(VideoProcessorKind.LIGHTWEIGHT), track.setCalls)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, track.current?.kind)
            assertTrue(h.created.isEmpty())
            // Asking again changes nothing.
            h.controller.onOverlayChanged(track)
            assertEquals(1, track.setCalls.size)

            // A background effect: ONE segmenting processor that REPLACES the overlay-only one and carries the hook.
            h.controller.select(blur, track)
            assertEquals(listOf(VideoProcessorKind.LIGHTWEIGHT, VideoProcessorKind.SEGMENTING), track.setCalls)
            assertEquals(VideoProcessorKind.SEGMENTING, track.current?.kind)
            assertEquals(1, h.created.size)
            assertTrue(h.created[0].hooked, "the overlay hook went into the segmenting processor before it was set")
            assertEquals(blur, h.controller.state.applied)

            // A scene swap or vanishing while an effect runs: no rebuild, no stop.
            h.port.wantedNow = false
            h.controller.onOverlayChanged(track)
            h.port.wantedNow = true
            h.controller.onOverlayChanged(track)
            assertEquals(2, track.setCalls.size)
            assertEquals(0, track.stopCalls)

            // The effect goes off while overlays stay: back to the overlay-only processor, still no stop of the camera.
            h.controller.select(CONFERENCE_BACKGROUND_OFF, track)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, track.current?.kind)
            assertEquals(3, track.setCalls.size)
            assertEquals(CONFERENCE_BACKGROUND_OFF, h.controller.state.applied)

            // Overlays go: nothing left to do -> no processor.
            h.port.wantedNow = false
            h.controller.onOverlayChanged(track)
            assertNull(track.current)
            assertEquals(1, track.stopCalls)
        }

    @Test
    fun anEffectWithoutOverlays_neverBuildsAnOverlayOnlyProcessor_andOffStopsIt() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(blur, track)
            assertEquals(listOf(VideoProcessorKind.SEGMENTING), track.setCalls)
            assertTrue(h.port.overlayOnly.isEmpty())
            h.controller.select(CONFERENCE_BACKGROUND_OFF, track)
            assertNull(track.current)
            assertEquals(1, track.stopCalls)
        }

    @Test
    fun aFailingBackgroundEffect_fallsBackToTheOverlays_andNotifiesOnlyTheBackgroundFailure() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.port.wantedNow = true
            track.failSetFor = VideoProcessorKind.SEGMENTING
            h.controller.select(blur, track)
            // The effect failed and fell back to "Aus" -- the overlays keep running on their own processor.
            assertEquals(CONFERENCE_BACKGROUND_OFF, h.controller.state.applied)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertEquals(blur, h.controller.state.desired, "the intent survives")
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, track.current?.kind)
            assertEquals(1, h.messages.size)
            assertTrue(h.overlayFailures.isEmpty())
        }

    /**
     * Models livekit's FIFO `trackChangeLock`: every call is queued AT CALL TIME and runs strictly one after the other; a Kotlin timeout
     * cancels the caller's await but never removes the queued operation.
     */
    private class SerialLockTrack : BackgroundTrack {
        var current: BackgroundProcessorHandle? = null
        val log = mutableListOf<String>()
        val segmentingGate = CompletableDeferred<Unit>()
        private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

        init {
            GlobalScope.launch { for (op in queue) op() }
        }

        private suspend fun enqueue(op: suspend () -> Unit) {
            val done = CompletableDeferred<Unit>()
            queue.trySend {
                op()
                done.complete(Unit)
            }
            done.await()
        }

        override suspend fun setProcessor(handle: BackgroundProcessorHandle) =
            enqueue {
                if (handle.kind == VideoProcessorKind.SEGMENTING) segmentingGate.await()
                log += "set ${handle.kind}"
                current = handle
            }

        override suspend fun stopProcessor() =
            enqueue {
                log += "stop"
                current = null
            }

        override fun currentProcessor(): BackgroundProcessorHandle? = current
    }

    @Test
    fun aBackgroundTimeout_restoresTheOverlaysOnlyAfterTheDetachedStop_soTheStopCannotRemoveThem() =
        withCleanStorage {
            val port = FakePort().also { it.wantedNow = true }
            val detached = mutableListOf<suspend () -> Unit>()
            val controller =
                ConferenceBackgroundController(
                    notifyFailure = {},
                    onProcessedStreamSwapped = {},
                    onStateChanged = {},
                    supported = true,
                    processorFactory = { FakeProcessor(VideoProcessorKind.SEGMENTING) },
                    imageProbe = {},
                    applyTimeoutMs = 30L,
                    launchDetachedCleanup = { detached += it },
                    overlayPort = port,
                    onOverlayFailure = {},
                )
            val track = SerialLockTrack()
            // The blur takes longer than applyTimeoutMs but finishes while the capped stop in removeProcessor still waits.
            GlobalScope.launch {
                delay(120)
                track.segmentingGate.complete(Unit)
            }
            controller.select(blur, track)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, controller.state.phase)
            assertEquals(1, detached.size, "the unbounded stop is detached")
            // The detached stop runs (queued behind everything else), THEN the overlay-only processor is set.
            detached.forEach { it() }
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, track.current?.kind, "the overlays survive: ${track.log}")
            assertEquals("set LIGHTWEIGHT", track.log.last(), track.log.toString())
        }

    @Test
    fun aFailingOverlayOnlyProcessor_isReportedToTheOverlaySide_andTheBackgroundStateStaysUntouched() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(blur, track)
            h.controller.select(CONFERENCE_BACKGROUND_OFF, track)
            val before = h.controller.state
            h.port.wantedNow = true
            track.failSetFor = VideoProcessorKind.LIGHTWEIGHT
            h.controller.onOverlayChanged(track)
            assertEquals(listOf(VideoOverlayFailure.DRAW_FAILED), h.overlayFailures)
            assertEquals(before, h.controller.state)
            assertTrue(h.messages.isEmpty(), "the background has nothing to say about it")
            assertNull(track.current)
        }

    @Test
    fun anOverlayChangeWithoutATrack_appliesNothing_theNextTrackDoes() =
        withCleanStorage {
            val h = Harness()
            h.port.wantedNow = true
            h.controller.onOverlayChanged(null)
            val track = FakeTrack()
            h.controller.onLocalCameraTrack(track)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, track.current?.kind)
            h.controller.onLocalCameraTrack(track)
            assertEquals(1, track.setCalls.size, "idempotent for the same track")
        }

    @Test
    fun withoutAnOverlayPort_everythingBehavesAsBeforeTheOverlays() =
        withCleanStorage {
            val created = mutableListOf<FakeProcessor>()
            val c =
                ConferenceBackgroundController(
                    notifyFailure = {},
                    onProcessedStreamSwapped = {},
                    onStateChanged = {},
                    supported = true,
                    processorFactory = { FakeProcessor(VideoProcessorKind.SEGMENTING).also { created += it } },
                    imageProbe = {},
                    launchDetachedCleanup = {},
                )
            val track = FakeTrack()
            c.onOverlayChanged(track)
            assertTrue(track.setCalls.isEmpty())
            c.select(blur, track)
            assertFalse(created[0].hooked)
        }

    // ── the overlay controller on top ────────────────────────────────────────────

    private class FakeLoader : OverlayBitmapLoader {
        val loads = mutableListOf<String>()
        var fail = false

        override suspend fun loadImage(
            path: String,
            targetWidth: Int,
            targetHeight: Int,
        ): OverlayBitmap {
            loads += path
            if (fail) error("failed")
            return object : OverlayBitmap {
                override val width = if (targetWidth > 0) targetWidth else 600
                override val height = if (targetHeight > 0) targetHeight else 300

                override fun close() = Unit
            }
        }

        override suspend fun renderName(
            text: String,
            boxHeightPx: Double,
            frameWidth: Int,
        ): OverlayBitmap? =
            object : OverlayBitmap {
                override val width = (boxHeightPx * 3).toInt()
                override val height = boxHeightPx.toInt()

                override fun close() = Unit
            }
    }

    private class NoSurface : OverlayDrawSurface {
        override fun begin(
            width: Int,
            height: Int,
        ) = Unit

        override fun drawFrame(frame: OverlayFrame) = Unit

        override fun drawBitmap(
            bitmap: OverlayBitmap,
            rect: OverlayRect,
            alpha: Double,
        ) = Unit

        override fun toFrame(timestamp: Double): OverlayFrame = error("not used")
    }

    private class FakeOverlayFrameForTest : OverlayFrame {
        override val width = 1280
        override val height = 720
        override val timestamp = 1.0

        override fun close() = Unit
    }

    private val logoId = "11111111-1111-4111-8111-111111111111"
    private val logo = VideoOverlayItemDto(logoId, VideoOverlayKind.LOGO, "Logo", 600, 300, "/api/video-overlays/${"A".repeat(43)}")

    private class OverlayHarness(
        offered: Boolean = true,
        availability: ConferenceBackgroundAvailability = ConferenceBackgroundAvailability.AVAILABLE,
        library: VideoOverlayLibraryDto? = null,
        initial: VideoOverlaySettings = VideoOverlaySettings(),
        name: String = "Erika",
    ) {
        val loader = FakeLoader()
        val compositor = VideoOverlayCompositor(loader, NoSurface())
        val port = CompositorOverlayPortForTest(compositor)
        val track = FakeTrack()
        val messages = mutableListOf<String>()
        val uis = mutableListOf<VideoOverlayUiState>()
        val saved = mutableListOf<VideoOverlaySettings>()
        var libraryCalls = 0
        val background =
            ConferenceBackgroundController(
                notifyFailure = {},
                onProcessedStreamSwapped = {},
                onStateChanged = {},
                supported = availability == ConferenceBackgroundAvailability.AVAILABLE,
                processorFactory = { FakeProcessor(VideoProcessorKind.SEGMENTING) },
                imageProbe = {},
                launchDetachedCleanup = {},
                overlayPort = port,
            )
        val controller =
            ConferenceVideoOverlayController(
                offered = offered,
                availability = availability,
                displayName = name,
                compositor = compositor,
                backgroundController = background,
                currentTrack = { track },
                frameSize = { 1280 to 720 },
                loadLibrary = {
                    libraryCalls++
                    library
                },
                notifyFailure = { messages += it },
                onChanged = { uis += it },
                loadSettings = { initial },
                saveSettings = { saved += it },
                launch = {},
            )
    }

    /** Production port logic over a fake processor: "wanted" mirrors the compositor, handles are fakes. */
    private class CompositorOverlayPortForTest(
        private val compositor: VideoOverlayCompositor,
    ) : OverlayProcessorPort {
        override val wanted: Boolean get() = compositor.hasScene

        override fun createLightweightHandle(): BackgroundProcessorHandle = FakeProcessor(VideoProcessorKind.LIGHTWEIGHT)

        override fun hookSegmenting(handle: BackgroundProcessorHandle) = Unit
    }

    private fun on(vararg corners: Pair<OverlayCorner, CornerSlot>) = VideoOverlaySettings(enabled = true, corners = corners.toMap())

    @Test
    fun offeredFalse_nothingHappensNoRequestNoDrawing() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    offered = false,
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.controller.start()
            assertEquals(0, h.libraryCalls)
            assertTrue(h.uis.isEmpty())
            assertNull(h.track.current)
            assertFalse(h.compositor.hasScene)
        }

    @Test
    fun anUnsupportedBrowser_makesNoRequestAndDrawsNothing() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    availability = ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER,
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial = on(OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName)),
                )
            h.controller.start()
            assertEquals(0, h.libraryCalls)
            assertNull(h.track.current)
        }

    @Test
    fun start_loadsTheLibraryOnce_buildsTheSceneAndAppliesTheOverlayOnlyProcessor() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.Library(logoId)),
                            OverlayCorner.BOTTOM_RIGHT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.controller.start()
            assertEquals(1, h.libraryCalls)
            assertTrue(h.compositor.hasScene)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, h.track.current?.kind)
            assertEquals(VideoOverlayPhase.ACTIVE, h.controller.state.phase)
            assertNotNull(h.controller.scene)
            assertEquals(1, h.loader.loads.size)
            assertTrue(h.uis.isNotEmpty())
            assertTrue(h.messages.isEmpty())
        }

    @Test
    fun update_persistsAndAppliesWhileSwitchingOffRemovesTheProcessorAgain() =
        GlobalScope.promise {
            val h = OverlayHarness(library = VideoOverlayLibraryDto(true, listOf(logo)))
            h.controller.start()
            assertNull(h.track.current)
            val settings = on(OverlayCorner.TOP_RIGHT to CornerSlot(OverlayContent.OwnName, OverlaySize.LARGE, OverlayOpacity.STRONG))
            h.controller.update(settings)
            assertEquals(listOf(settings), h.saved)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, h.track.current?.kind)
            h.controller.update(settings.copy(enabled = false))
            assertEquals(2, h.saved.size)
            assertNull(h.track.current)
            assertFalse(h.compositor.hasScene)
            assertEquals(VideoOverlayPhase.OFF, h.controller.state.phase)
        }

    @Test
    fun aDeletedElementOfTheLibrary_becomesAnEmptyCornerWithoutAnyMessage() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, emptyList()),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.Library(logoId)),
                        ),
                )
            h.controller.start()
            assertNull(h.controller.scene)
            assertNull(h.track.current)
            assertTrue(h.messages.isEmpty())
            assertEquals(0, h.loader.loads.size)
        }

    @Test
    fun aFailingImage_isReportedOnce_theRestOfTheSceneStays() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.Library(logoId)),
                            OverlayCorner.BOTTOM_RIGHT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.loader.fail = true
            h.controller.start()
            assertEquals(1, h.messages.size)
            assertEquals(VideoOverlayPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertTrue(h.compositor.hasScene, "the name still shows")
            h.controller.onCameraTrack()
            assertEquals(1, h.messages.size, "one message per cause per session")
        }

    @Test
    fun aPartlyFailingScene_stillCountsAsDrawing_aClearedSceneDoesNot() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName),
                            OverlayCorner.TOP_RIGHT to CornerSlot(OverlayContent.Library(logoId)),
                        ),
                )
            h.loader.fail = true
            h.controller.start()
            assertEquals(VideoOverlayPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertTrue(h.uis.last().drawing, "the name is still sent to everyone, so the self-note stays")
            // Nothing left to draw: nothing reaches the video, the hint must not promise it.
            h.compositor.setScene(null)
            assertFalse(h.controller.uiState().drawing)
        }

    @Test
    fun aProcessorFailure_meansNotDrawing_untilTheNextSceneRequest() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.controller.start()
            assertTrue(h.controller.uiState().drawing)
            // The overlay-only processor could not be put on the track: the compositor keeps its scene, but no frame reaches it.
            h.controller.onProcessorFailure(VideoOverlayFailure.DRAW_FAILED)
            assertTrue(h.compositor.isDrawing, "the compositor itself cannot know")
            assertFalse(h.controller.uiState().drawing, "no self-note, no panel hint")
            assertFalse(h.uis.last().drawing)
            // A new request tries again and clears the flag (a repeated failure would set it again).
            h.controller.onCameraTrack()
            assertTrue(h.controller.uiState().drawing)
        }

    @Test
    fun aCompositorDrawFailure_meansNotDrawing() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.controller.start()
            assertTrue(h.controller.uiState().drawing)
            // NoSurface cannot make a frame, so the first frame throws inside the compositor and suspends it.
            h.compositor.process(FakeOverlayFrameForTest()) { }
            assertFalse(h.controller.uiState().drawing)
        }

    @Test
    fun aDrawingFailure_isReportedOnce() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.controller.start()
            h.controller.onDrawFailure()
            h.controller.onDrawFailure()
            assertEquals(1, h.messages.size)
            assertEquals(VideoOverlayPhase.FAILED_FALLBACK, h.controller.state.phase)
        }

    @Test
    fun aLibraryThatTheServerDoesNotOffer_drawsNothing() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(false, emptyList()),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.controller.start()
            assertNull(h.controller.scene)
            assertNull(h.track.current)
        }

    @Test
    fun dispose_closesTheCompositor_andLaterCallsDoNothing() =
        GlobalScope.promise {
            val h =
                OverlayHarness(
                    library = VideoOverlayLibraryDto(true, listOf(logo)),
                    initial =
                        on(
                            OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName),
                        ),
                )
            h.controller.start()
            h.controller.dispose()
            assertFalse(h.compositor.hasScene)
            val before = h.track.setCalls.size
            h.controller.update(on(OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName)))
            h.controller.onCameraTrack()
            assertEquals(before, h.track.setCalls.size)
            assertSame(h.controller, h.controller)
        }
}
