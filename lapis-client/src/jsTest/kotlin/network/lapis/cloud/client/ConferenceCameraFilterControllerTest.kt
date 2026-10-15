package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.142 -- the camera filter orchestration through its seams (fake stage, fake track, fake processors): on / off / slider switches on /
 * reset, the processor ladder NONE <-> LIGHTWEIGHT <-> SEGMENTING without a second processor or a second segmentation, one message per cause,
 * the performance steps with the stage's own reports, a throwing storage, and that no failure on one side touches the state of the others.
 */
class ConferenceCameraFilterControllerTest {
    private class FakeStage : CameraFilterStage {
        var settings = CameraFilterSettings()
        var paused = false
        var released = false
        var retries = 0
        val configured = mutableListOf<Pair<CameraFilterSettings, Boolean>>()

        override val active: Boolean
            get() {
                val p = cameraFilterParams(settings, paused)
                return !released && settings.enabled && !(p.lightIsNeutral && p.smoothStrength == 0f)
            }

        override fun configure(
            settings: CameraFilterSettings,
            smoothingPaused: Boolean,
        ) {
            this.settings = settings
            paused = smoothingPaused
            configured += settings to smoothingPaused
        }

        override fun process(frame: dynamic): dynamic = frame

        override fun retrySmoothing() {
            retries++
        }

        override fun release() {
            released = true
        }
    }

    private class FakeProcessor(
        override val kind: VideoProcessorKind,
    ) : BackgroundProcessorHandle {
        var hooked = false

        override suspend fun switchTo(choice: ConferenceBackgroundChoice) = Unit

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

    private class FakePort(
        private val stage: FakeStage,
    ) : OverlayProcessorPort {
        override val wanted: Boolean get() = stage.active
        val lightweight = mutableListOf<FakeProcessor>()
        val hooked = mutableListOf<BackgroundProcessorHandle>()

        override fun createLightweightHandle(): BackgroundProcessorHandle =
            FakeProcessor(VideoProcessorKind.LIGHTWEIGHT).also {
                lightweight +=
                    it
            }

        override fun hookSegmenting(handle: BackgroundProcessorHandle) {
            hooked += handle
            (handle as? FakeProcessor)?.hooked = true
        }
    }

    private class Harness(
        offered: Boolean = true,
        availability: ConferenceBackgroundAvailability = ConferenceBackgroundAvailability.AVAILABLE,
        stored: String? = null,
        throwingStorage: Boolean = false,
        wireProcessorFailure: Boolean = false,
    ) {
        val stage = FakeStage()
        val port = FakePort(stage)
        val track = FakeTrack()
        val created = mutableListOf<FakeProcessor>()
        val backgroundMessages = mutableListOf<String>()
        val overlayFailures = mutableListOf<VideoOverlayFailure>()
        val messages = mutableListOf<String>()
        val states = mutableListOf<CameraFilterUiState>()
        val saved = mutableListOf<String>()
        val detached = mutableListOf<suspend () -> Unit>()
        val background =
            ConferenceBackgroundController(
                notifyFailure = { backgroundMessages += it },
                onProcessedStreamSwapped = {},
                onStateChanged = {},
                supported = true,
                processorFactory = { FakeProcessor(VideoProcessorKind.SEGMENTING).also { created += it } },
                imageProbe = {},
                launchDetachedCleanup = {},
                overlayPort = port,
                onOverlayFailure = {
                    overlayFailures += it
                    if (wireProcessorFailure) controller.onProcessorFailure()
                },
            )
        val controller: ConferenceCameraFilterController =
            ConferenceCameraFilterController(
                offered = offered,
                availability = availability,
                stage = stage,
                backgroundController = background,
                currentTrack = { track },
                notifyFailure = { messages += it },
                onChanged = { states += it },
                loadRaw = { if (throwingStorage) error("storage blocked") else stored },
                saveRaw = { if (throwingStorage) error("storage blocked") else saved += it },
                launch = { block -> detached += block },
            )

        /** Runs what the controller handed to its `launch` seam (callbacks of the stage run detached in production). */
        suspend fun drain() {
            while (detached.isNotEmpty()) detached.removeAt(0)()
        }
    }

    private val blur = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_LIGHT)

    private fun clean(block: suspend () -> Unit) =
        GlobalScope.promise {
            localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
            try {
                block()
            } finally {
                localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
            }
        }

    @Test
    fun start_withARememberedActiveFilter_putsTheLightweightProcessorOnTheTrack() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, brightness = 10)))
            h.controller.start()
            assertEquals(CameraFilterPhase.ACTIVE, h.controller.phase)
            assertEquals(listOf(VideoProcessorKind.LIGHTWEIGHT), h.track.setCalls)
            assertTrue(h.states.last().filtering)
            assertEquals(10, h.stage.settings.brightness)
            assertTrue(h.created.isEmpty(), "no segmenting processor, no MediaPipe")
        }

    @Test
    fun start_withNothingRemembered_doesNothingToTheTrack() =
        clean {
            val h = Harness()
            h.controller.start()
            assertEquals(CameraFilterPhase.IDLE, h.controller.phase)
            assertTrue(h.track.setCalls.isEmpty())
            assertFalse(h.states.last().filtering)
        }

    @Test
    fun aSliderThatIsMoved_switchesTheFilterOnAndSaysSoOnce_theSecondMoveDoesNot() =
        clean {
            val h = Harness()
            h.controller.start()
            h.controller.onSliderMoved { it.copy(brightness = 15) }
            assertTrue(h.controller.settings.enabled)
            assertEquals(15, h.controller.settings.brightness)
            assertEquals(
                "Filter eingeblendet",
                h.states
                    .filter { it.liveMessage != null }
                    .first()
                    .liveMessage,
            )
            assertEquals(CameraFilterPhase.ACTIVE, h.controller.phase)
            assertEquals(listOf(VideoProcessorKind.LIGHTWEIGHT), h.track.setCalls)
            h.states.clear()
            h.controller.onSliderMoved { it.copy(brightness = 16) }
            assertTrue(h.states.all { it.liveMessage == null }, "no second announcement")
            assertEquals(1, h.track.setCalls.size, "the processor stays")
        }

    @Test
    fun theMainSwitch_off_takesTheProcessorOffAndKeepsTheSliderValues() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, brightness = 10)))
            h.controller.start()
            h.controller.setEnabled(false)
            assertEquals(CameraFilterPhase.IDLE, h.controller.phase)
            assertNull(h.track.current)
            assertEquals(10, h.controller.settings.brightness, "the sliders keep their values")
            assertFalse(h.controller.settings.enabled)
            h.controller.setEnabled(true)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, h.track.current?.kind)
            assertEquals(10, h.stage.settings.brightness)
        }

    @Test
    fun resetAll_setsEverythingNeutral_switchesOff_announces_andIsRemembered() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, 10, 5, 3, 20, 40)))
            h.controller.start()
            h.controller.resetAll()
            assertEquals(CameraFilterSettings(), h.controller.settings)
            assertNull(h.track.current)
            assertEquals("Alle Filter ausgeschaltet", h.states.last().liveMessage)
            assertEquals(CameraFilterSettings(), decodeCameraFilterSettings(h.saved.last()))
        }

    @Test
    fun theSliders_areRememberedAndClamped() =
        clean {
            val h = Harness()
            h.controller.start()
            h.controller.onSliderMoved { it.copy(brightness = 9_000) }
            assertEquals(40, h.controller.settings.brightness)
            assertEquals(40, decodeCameraFilterSettings(h.saved.last()).brightness)
        }

    @Test
    fun aThrowingStorage_neverBreaksAnything_andTheFilterHoldsForTheSession() =
        clean {
            val h = Harness(throwingStorage = true)
            h.controller.start()
            assertEquals(CameraFilterSettings(), h.controller.settings, "nothing readable: neutral, switched off")
            h.controller.onSliderMoved { it.copy(warmth = 20) }
            assertEquals(20, h.controller.settings.warmth)
            assertEquals(CameraFilterPhase.ACTIVE, h.controller.phase)
        }

    @Test
    fun notOffered_orUnsupported_nothingHappens() =
        clean {
            val hidden = Harness(offered = false)
            hidden.controller.start()
            hidden.controller.onSliderMoved { it.copy(brightness = 10) }
            assertTrue(hidden.stage.configured.isEmpty())
            assertTrue(hidden.track.setCalls.isEmpty())
            assertEquals(CameraFilterPhase.UNSUPPORTED, hidden.controller.phase)

            val unsupported = Harness(availability = ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER)
            unsupported.controller.start()
            unsupported.controller.setEnabled(true)
            assertTrue(unsupported.stage.configured.isEmpty())
            assertEquals(CameraFilterPhase.UNSUPPORTED, unsupported.controller.phase)
        }

    // --- the processor ladder ---------------------------------------------------------------------------------------------------------

    @Test
    fun theLadder_noneLightweightSegmentingLightweightNone_withOneProcessorAtATimeAndNoSecondSegmentation() =
        clean {
            val h = Harness()
            h.controller.start()
            assertNull(h.track.current)
            // filter on, no effect: lightweight
            h.controller.onSliderMoved { it.copy(brightness = 10) }
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, h.track.current?.kind)
            // a background effect: ONE segmenting processor, carrying the hook; it replaces the lightweight one
            h.background.select(blur, h.track)
            assertEquals(listOf(VideoProcessorKind.LIGHTWEIGHT, VideoProcessorKind.SEGMENTING), h.track.setCalls)
            assertEquals(1, h.created.size)
            assertTrue(h.created[0].hooked, "the hook (filter input side + overlays) went into the segmenting processor")
            // filter changes while an effect runs: the hook is in place, no rebuild, no stop, no new processor
            h.controller.onSliderMoved { it.copy(contrast = 5) }
            h.controller.setEnabled(false)
            h.controller.setEnabled(true)
            assertEquals(2, h.track.setCalls.size)
            assertEquals(1, h.created.size)
            assertEquals(VideoProcessorKind.SEGMENTING, h.track.current?.kind)
            // effect off, filter still on: back to lightweight
            h.background.select(CONFERENCE_BACKGROUND_OFF, h.track)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, h.track.current?.kind)
            // filter off: nothing left to do, no processor at all
            h.controller.resetAll()
            assertNull(h.track.current)
        }

    @Test
    fun aBackgroundEffectAlone_isUnchangedByAnIdleFilter() =
        clean {
            val h = Harness()
            h.controller.start()
            h.background.select(blur, h.track)
            assertEquals(listOf(VideoProcessorKind.SEGMENTING), h.track.setCalls)
            h.controller.setEnabled(true) // enabled but all sliders neutral: nothing to apply
            assertEquals(1, h.track.setCalls.size)
            assertEquals(blur, h.background.state.applied)
        }

    @Test
    fun aNewCameraTrack_putsTheProcessorBackOn_idempotently() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, brightness = 10)))
            h.controller.start()
            h.controller.onCameraTrack()
            assertEquals(1, h.track.setCalls.size, "already on the track: no second processor")
            h.track.current = null // the camera was restarted without a processor
            h.background.onLocalCameraTrack(h.track)
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, h.track.current?.kind)
        }

    // --- failures and the governor ----------------------------------------------------------------------------------------------------

    @Test
    fun aStageFailure_isReportedOnce_keepsTheSliders_andNeverTouchesTheBackgroundState() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, brightness = 10)))
            h.controller.start()
            h.background.select(blur, h.track)
            val backgroundBefore = h.background.state
            h.controller.onStageFailure()
            h.controller.onStageFailure()
            h.drain()
            assertEquals(CameraFilterPhase.FAILED, h.controller.phase)
            assertEquals(1, h.messages.size, "one message per session")
            assertTrue(h.messages.single().contains("unverändert"))
            assertEquals(10, h.controller.settings.brightness, "the slider value is never overwritten")
            assertEquals(backgroundBefore, h.background.state)
            assertTrue(h.backgroundMessages.isEmpty())
        }

    @Test
    fun aFailedProcessor_isOnlyTheFiltersBusiness_theBackgroundStateStaysClean() =
        clean {
            val h = Harness()
            h.controller.start()
            h.track.failSetFor = VideoProcessorKind.LIGHTWEIGHT
            h.controller.onSliderMoved { it.copy(brightness = 10) }
            assertEquals(listOf(VideoOverlayFailure.DRAW_FAILED), h.overlayFailures)
            assertEquals(ConferenceBackgroundPhase.OFF, h.background.state.phase)
            assertTrue(h.backgroundMessages.isEmpty())
            assertNull(h.track.current)
        }

    @Test
    fun aPermanentlyFailingProcessor_doesNotLoopTheHandOver() =
        clean {
            val h = Harness(wireProcessorFailure = true)
            h.controller.start()
            h.track.failSetFor = VideoProcessorKind.LIGHTWEIGHT
            h.controller.onSliderMoved { it.copy(brightness = 10) }
            h.drain()
            h.drain()
            assertTrue(h.track.setCalls.size <= 2, "bounded attempts, no retry loop: ${h.track.setCalls}")
            assertEquals(CameraFilterPhase.FAILED, h.controller.phase)
            assertEquals(1, h.messages.size, "the person is told once")
            assertEquals(10, h.controller.settings.brightness)
        }

    @Test
    fun theGovernor_reducedThenPaused_oneMessagePerCause_theValueStays_retryClears() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, brightness = 5, smoothing = 60)))
            h.controller.start()
            h.controller.onPerfAction(PerfAction.REDUCED)
            assertTrue(h.states.last().degraded)
            assertTrue(h.messages.isEmpty(), "reduced quality is not announced as a failure")
            h.controller.onPerfAction(PerfAction.PAUSED)
            h.controller.onPerfAction(PerfAction.PAUSED)
            h.drain()
            assertEquals(SmoothingPause.PERFORMANCE, h.states.last().smoothingPause)
            assertEquals(1, h.messages.size)
            assertTrue(h.messages.single().contains("pausiert"))
            assertEquals(60, h.controller.settings.smoothing, "the slider keeps its value")
            assertTrue(h.stage.paused, "the stage is told to stop smoothing")
            assertTrue(h.stage.active, "the light and colour part keeps running")
            h.controller.retrySmoothing()
            assertEquals(SmoothingPause.NONE, h.states.last().smoothingPause)
            assertFalse(h.states.last().degraded)
            assertFalse(h.stage.paused)
            assertEquals(1, h.stage.retries)
            // a second pause after the retry is announced again (a new episode)
            h.controller.onPerfAction(PerfAction.PAUSED)
            assertEquals(2, h.messages.size)
        }

    @Test
    fun anUnavailableDetector_pausesTheSmoothingWithItsOwnMessage_andTheRestKeepsRunning() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, brightness = 5, smoothing = 40)))
            h.controller.start()
            h.controller.onSmoothingUnavailable()
            h.drain()
            assertEquals(SmoothingPause.UNAVAILABLE, h.states.last().smoothingPause)
            assertTrue(h.messages.single().contains("nicht gestartet"))
            assertEquals(CameraFilterPhase.ACTIVE, h.controller.phase)
        }

    @Test
    fun aPausedSmoothingAlone_leavesNothingToApply_andTheProcessorGoesAway() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, smoothing = 60)))
            h.controller.start()
            assertEquals(VideoProcessorKind.LIGHTWEIGHT, h.track.current?.kind)
            h.controller.onPerfAction(PerfAction.PAUSED)
            h.drain()
            assertNull(h.track.current, "only smoothing was on and it is paused: no processor is needed")
            assertEquals(CameraFilterPhase.IDLE, h.controller.phase)
            assertNotNull(h.states.last())
        }

    @Test
    fun dispose_releasesTheStage_andIgnoresLaterEvents() =
        clean {
            val h = Harness(stored = encodeCameraFilterSettings(CameraFilterSettings(true, brightness = 5)))
            h.controller.start()
            h.controller.dispose()
            assertTrue(h.stage.released)
            val calls = h.track.setCalls.size
            h.controller.onSliderMoved { it.copy(brightness = 9) }
            h.controller.onStageFailure()
            h.drain()
            assertEquals(calls, h.track.setCalls.size)
            assertTrue(h.messages.isEmpty())
        }
}
