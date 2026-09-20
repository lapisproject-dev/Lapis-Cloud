package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Review finding "Testabdeckung": the failure path of [ConferenceBackgroundController] -- the KDoc's "most
 * important place of the wave" -- through the [BackgroundTrack]/[BackgroundProcessorHandle] seams, without a
 * camera, WASM or WebGL. Covers: mutex serialisation, the timeout classification, switchTo-vs-setProcessor,
 * LOAD_FAILED vs APPLY_FAILED, `stopProcessor` after every failure, exactly one message per cause, and the
 * `localStorage` persistence.
 *
 * `@Test` functions cannot be `suspend` in Kotlin/JS; returning a `Promise` from `GlobalScope.promise` is the
 * established bridge (see `LiveKitRoomSessionDeviceFailureTest`).
 */
class ConferenceBackgroundControllerTest {
    private class FakeProcessor : BackgroundProcessorHandle {
        val switched = mutableListOf<ConferenceBackgroundEffect>()
        var behavior: suspend (ConferenceBackgroundEffect) -> Unit = {}

        /**
         * What `BackgroundTransformer.backgroundImageAndPath?.path` would report. Audit finding M1: the
         * library SWALLOWS a failing image inside `init` and resolves anyway, which shows up exactly here as
         * `null` (or a stale path) while everything else looks successful.
         */
        var appliedImagePath: String? = null

        /** `true` makes a resolving `switchTo` leave [appliedImagePath] untouched -- the swallowed-error shape. */
        var silentSwitchFailure = false

        override suspend fun switchTo(effect: ConferenceBackgroundEffect) {
            switched += effect
            behavior(effect)
            if (!silentSwitchFailure) conferenceBackgroundImagePath(effect)?.let { appliedImagePath = it }
        }

        override fun appliedBackgroundImagePath(): String? = appliedImagePath
    }

    private class FakeTrack : BackgroundTrack {
        var current: BackgroundProcessorHandle? = null
        var setBehavior: suspend (BackgroundProcessorHandle) -> Unit = {}
        var stopBehavior: suspend () -> Unit = {}
        var setCalls = 0
        var stopCalls = 0
        var running = 0
        var maxRunning = 0

        /** Completes the moment `setProcessor` is entered -- lets a test wait for "the apply is in flight"
         * without sleeping for a guessed duration. */
        val setProcessorEntered = CompletableDeferred<Unit>()

        /**
         * Models the ONE property that makes the zombie-processor bug (audit finding M4) possible: a Kotlin
         * timeout cancels the `await`, but the underlying JS promise keeps running and sets the processor
         * anyway. When set, `setProcessor` never returns to its caller, yet [current] is assigned as soon as
         * the test completes this gate -- exactly what `LocalTrack.setProcessor` does behind livekit's
         * `trackChangeLock`, but under the test's control instead of a wall-clock delay.
         */
        var lateSetGate: CompletableDeferred<Unit>? = null

        /** Completed once a [lateSetGate]-driven late assignment has really happened. */
        val lateSetApplied = CompletableDeferred<Unit>()

        override suspend fun setProcessor(handle: BackgroundProcessorHandle) {
            setCalls++
            setProcessorEntered.complete(Unit)
            val gate = lateSetGate
            if (gate != null) {
                GlobalScope.launch {
                    gate.await()
                    current = handle
                    lateSetApplied.complete(Unit)
                }
                awaitCancellation()
            }
            running++
            if (running > maxRunning) maxRunning = running
            try {
                setBehavior(handle)
                current = handle
            } finally {
                running--
            }
        }

        override suspend fun stopProcessor() {
            stopCalls++
            stopBehavior()
            current = null
        }

        override fun currentProcessor(): BackgroundProcessorHandle? = current
    }

    private class Harness(
        supported: Boolean = true,
        timeoutMs: Long = 5_000L,
        disposeStopTimeoutMs: Long = 50L,
    ) {
        val created = mutableListOf<FakeProcessor>()
        val messages = mutableListOf<String>()
        val states = mutableListOf<ConferenceBackgroundState>()
        var swapped = 0
        val factoryEffects = mutableListOf<ConferenceBackgroundEffect>()
        var processorBehavior: suspend (ConferenceBackgroundEffect) -> Unit = {}

        /** M1: `true` makes a freshly built processor report "no background image" despite a resolved apply. */
        var simulateSilentImageFailure = false
        val probedImages = mutableListOf<String>()
        var imageProbeBehavior: suspend (String) -> Unit = {}

        /** M4/B1: the unbounded, detached cleanups the controller scheduled -- collected instead of launched. */
        val detachedCleanups = mutableListOf<suspend () -> Unit>()

        val controller =
            ConferenceBackgroundController(
                notifyFailure = { messages += it },
                onProcessedStreamSwapped = { swapped++ },
                onStateChanged = { states += it },
                supported = supported,
                processorFactory = { effect ->
                    factoryEffects += effect
                    FakeProcessor().also {
                        it.behavior = processorBehavior
                        if (!simulateSilentImageFailure) it.appliedImagePath = conferenceBackgroundImagePath(effect)
                        created += it
                    }
                },
                applyTimeoutMs = timeoutMs,
                imageProbe = { path ->
                    probedImages += path
                    imageProbeBehavior(path)
                },
                launchDetachedCleanup = { block -> detachedCleanups += block },
                disposeStopTimeoutMs = disposeStopTimeoutMs,
            )
    }

    private fun stored(): String? = localStorage[CONFERENCE_BACKGROUND_STORAGE_KEY]

    private fun withCleanStorage(block: suspend () -> Unit) =
        GlobalScope.promise {
            localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
            try {
                block()
            } finally {
                localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
            }
        }

    // --- Happy path ---------------------------------------------------------------------------

    @Test
    fun select_blur_buildsOneProcessor_setsItOnTheTrack_persists_andNotifiesSwap() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            assertEquals(listOf(ConferenceBackgroundEffect.BLUR_LIGHT), h.factoryEffects)
            assertEquals(1, track.setCalls)
            assertSame(h.created[0], track.current)
            assertEquals(ConferenceBackgroundEffect.BLUR_LIGHT, h.controller.state.applied)
            assertEquals(ConferenceBackgroundPhase.ACTIVE, h.controller.state.phase)
            assertEquals(1, h.swapped)
            assertEquals("blur-light", stored())
            assertTrue(h.messages.isEmpty())
        }

    @Test
    fun select_off_stopsTheProcessor_removesTheStoredValue() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track)
            assertEquals("bg-sage", stored())
            h.controller.select(ConferenceBackgroundEffect.OFF, track)
            assertEquals(1, track.stopCalls)
            assertNull(track.current)
            assertNull(stored())
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            assertEquals(ConferenceBackgroundPhase.OFF, h.controller.state.phase)
        }

    @Test
    fun select_secondEffect_inSameEpisode_usesSwitchTo_noRebuild_noStop() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            h.controller.select(ConferenceBackgroundEffect.BG_STUDIO, track)
            assertEquals(1, h.created.size)
            assertEquals(1, track.setCalls)
            assertEquals(0, track.stopCalls)
            assertEquals(listOf(ConferenceBackgroundEffect.BG_STUDIO), h.created[0].switched)
            assertEquals(ConferenceBackgroundEffect.BG_STUDIO, h.controller.state.applied)
        }

    @Test
    fun select_sameEffectAgain_isANoOpOnTheLibrary() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_STRONG, track)
            h.controller.select(ConferenceBackgroundEffect.BLUR_STRONG, track)
            assertEquals(1, h.created.size)
            assertEquals(1, track.setCalls)
            assertTrue(h.created[0].switched.isEmpty())
            assertEquals(ConferenceBackgroundPhase.ACTIVE, h.controller.state.phase)
        }

    @Test
    fun select_withoutTrack_persistsIntent_butAppliesNothing() =
        withCleanStorage {
            val h = Harness()
            h.controller.select(ConferenceBackgroundEffect.BG_MIDNIGHT, null)
            assertTrue(h.created.isEmpty())
            assertEquals("bg-midnight", stored())
            assertEquals(ConferenceBackgroundEffect.BG_MIDNIGHT, h.controller.state.desired)
            assertEquals(ConferenceBackgroundPhase.OFF, h.controller.state.phase)
        }

    // --- Failure path -------------------------------------------------------------------------

    @Test
    fun setProcessorRejects_isLoadFailed_stopsTheProcessor_keepsIntent_andNotifiesOnce() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            track.setBehavior = { throw IllegalStateException("boom") }
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            assertEquals(1, track.stopCalls, "stopProcessor must run after a failed setProcessor")
            assertNull(track.current)
            val state = h.controller.state
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, state.phase)
            assertEquals(ConferenceBackgroundEffect.OFF, state.applied)
            assertEquals(ConferenceBackgroundEffect.BLUR_LIGHT, state.desired, "intent survives a failure")
            assertEquals("blur-light", stored(), "stored intent survives a failure")
            assertEquals(ConferenceBackgroundEffect.OFF, conferenceBackgroundDisplayedEffect(state))
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.LOAD_FAILED)), h.messages)
            assertEquals(1, h.swapped)
        }

    @Test
    fun secondFailureOfTheSameCause_isNotAnnouncedAgain() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            track.setBehavior = { throw IllegalStateException("boom") }
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            h.controller.select(ConferenceBackgroundEffect.BLUR_STRONG, track)
            assertEquals(2, track.stopCalls)
            assertEquals(1, h.messages.size, "one message per cause per session")
            assertEquals(2, h.created.size, "a failed processor is never reused")
        }

    @Test
    fun switchToRejects_withImage_isLoadFailed_andFallsBackToOff() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            h.created[0].behavior = { throw IllegalStateException("image") }
            h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track)
            assertEquals(1, track.stopCalls)
            assertNull(track.current)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.LOAD_FAILED)), h.messages)
        }

    @Test
    fun switchToRejects_withBlur_isApplyFailed_andStopsTheProcessor() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track)
            h.created[0].behavior = { throw IllegalStateException("blur") }
            h.controller.select(ConferenceBackgroundEffect.BLUR_STRONG, track)
            assertEquals(1, track.stopCalls)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.APPLY_FAILED)), h.messages)
            assertEquals("blur-strong", stored())
        }

    @Test
    fun setProcessorNeverFinishes_isTimeout_notLoadFailed_andStopsTheProcessor() =
        withCleanStorage {
            val h = Harness(timeoutMs = 30L)
            val track = FakeTrack()
            track.setBehavior = { awaitCancellation() }
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            assertEquals(1, track.stopCalls)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.TIMEOUT)), h.messages)
            assertEquals("blur-light", stored())
        }

    @Test
    fun switchToNeverFinishes_isTimeout_notApplyFailed() =
        withCleanStorage {
            val h = Harness(timeoutMs = 30L)
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track)
            h.created[0].behavior = { awaitCancellation() }
            h.controller.select(ConferenceBackgroundEffect.BG_STUDIO, track)
            assertEquals(1, track.stopCalls)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.TIMEOUT)), h.messages)
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
        }

    @Test
    fun afterAFailure_aNewTrack_isNotRetriedAutomatically_butAUserClickIs() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            track.setBehavior = { throw IllegalStateException("boom") }
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            val setCallsAfterFailure = track.setCalls
            h.controller.onLocalCameraTrack(track)
            assertEquals(setCallsAfterFailure, track.setCalls, "the one automatic attempt is used up")
            track.setBehavior = {}
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            assertEquals(ConferenceBackgroundPhase.ACTIVE, h.controller.state.phase)
            assertSame(h.created.last(), track.current)
        }

    // --- Serialisation ------------------------------------------------------------------------

    /**
     * Gated instead of timed (follow-up audit, point 7): the first apply is held open until the second click
     * has been launched, so the overlap window is real rather than hoped for. None of the assertions is
     * time-based -- `maxRunning > 1` or a second built processor can only come from a missing mutex, never
     * from scheduling jitter, so this test can under-detect but never false-fail under CI load.
     */
    @Test
    fun concurrentSelects_neverRunTwoApplicationsAtOnce() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            val gate = CompletableDeferred<Unit>()
            track.setBehavior = { gate.await() }
            val first = GlobalScope.launch { h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track) }
            track.setProcessorEntered.await()
            val second = GlobalScope.launch { h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track) }
            repeat(5) { yield() } // dem zweiten Klick die Gelegenheit geben, sich am Mutex anzustellen
            gate.complete(Unit)
            first.join()
            second.join()
            assertEquals(1, track.maxRunning)
            assertEquals(1, h.created.size, "the second click reuses the episode's processor via switchTo")
            assertEquals(ConferenceBackgroundEffect.BG_SAGE, h.controller.state.applied)
        }

    // --- New track / dispose / unsupported / storage -----------------------------------------

    @Test
    fun onLocalCameraTrack_appliesTheStoredIntent_andIsIdempotent() =
        withCleanStorage {
            localStorage[CONFERENCE_BACKGROUND_STORAGE_KEY] = "bg-studio"
            val h = Harness()
            h.controller.restoreDesiredFromStorage()
            assertEquals(ConferenceBackgroundEffect.BG_STUDIO, h.controller.state.desired)
            val track = FakeTrack()
            h.controller.onLocalCameraTrack(track)
            h.controller.onLocalCameraTrack(track)
            assertEquals(1, track.setCalls)
            assertEquals(ConferenceBackgroundEffect.BG_STUDIO, h.controller.state.applied)
        }

    @Test
    fun dispose_stopsTheProcessor_andResetsApplied_butKeepsIntent() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            h.controller.dispose(track)
            assertEquals(1, track.stopCalls)
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            assertEquals(ConferenceBackgroundEffect.BLUR_LIGHT, h.controller.state.desired)
            assertEquals("blur-light", stored())
        }

    @Test
    fun unsupportedBrowser_selectAndNewTrackDoNothing_andRestoreDoesNotSurfaceAStoredEffect() =
        withCleanStorage {
            localStorage[CONFERENCE_BACKGROUND_STORAGE_KEY] = "bg-sage"
            val h = Harness(supported = false)
            h.controller.restoreDesiredFromStorage()
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.desired)
            assertTrue(h.states.isEmpty(), "no visible state change on an unsupported browser")
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            h.controller.onLocalCameraTrack(track)
            assertEquals(0, track.setCalls)
            assertTrue(h.created.isEmpty())
            assertEquals("bg-sage", stored(), "an unsupported select must not touch storage")
            assertFalse(h.messages.isNotEmpty())
        }

    @Test
    fun restoreDesiredFromStorage_rejectsValuesOutsideTheWhitelist() =
        withCleanStorage {
            localStorage[CONFERENCE_BACKGROUND_STORAGE_KEY] = "https://evil.example/x.png"
            val h = Harness()
            h.controller.restoreDesiredFromStorage()
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.desired)
        }

    // --- Audit-Befund B1: Verlassen darf nie hinter einem haengenden Effektladen warten -----------

    /**
     * The blocking finding itself. Before the fix `dispose` took the same mutex a running `applyLocked`
     * holds for up to `applyTimeoutMs` (10 s in production) -- and `ConferenceScreen` awaited it BEFORE
     * `session.disconnect()`, so camera and microphone kept sending for that whole time while the UI already
     * said "left". `dispose` must therefore return immediately, no matter what is in flight.
     *
     * Deliberately NOT a wall-clock assertion (follow-up audit, point 7): the in-flight apply hangs on a gate
     * this test alone opens, and it opens it only AFTER `dispose` has returned. A `dispose` that queued behind
     * the mutex could therefore never return at all, and the test fails by timing out instead of by a
     * millisecond comparison that flutters under CI load.
     */
    @Test
    fun dispose_whileAnApplyIsInFlight_isNotSerialisedBehindIt() =
        withCleanStorage {
            val h = Harness(timeoutMs = 60_000L)
            val track = FakeTrack()
            val gate = CompletableDeferred<Unit>()
            track.setBehavior = { gate.await() }
            val applying = GlobalScope.launch { h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track) }
            track.setProcessorEntered.await() // der Versuch laeuft jetzt und haelt den Mutex
            h.controller.dispose(track)
            assertEquals(ConferenceBackgroundPhase.OFF, h.controller.state.phase)
            gate.complete(Unit) // erst JETZT darf die Anwendung fertig werden
            applying.join()
            // Der ueberholte Versuch meldet weder Erfolg noch Fehler und haelt nichts fest (M4).
            assertEquals(ConferenceBackgroundPhase.OFF, h.controller.state.phase)
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            assertTrue(h.messages.isEmpty(), "a superseded attempt must not notify")
            assertEquals(1, h.detachedCleanups.size, "a superseded attempt schedules an unbounded cleanup")
            h.detachedCleanups[0].invoke()
            assertNull(track.current, "nothing may stay on the track after dispose")
        }

    /**
     * Follow-up audit, points 1 and 2: loading the background image is a suspension point, so `dispose` can
     * land in the middle of it. After that, NO processor may be built or attached any more -- pre-fix the
     * generation was only re-checked after `setProcessor`, so a processor was built, WASM initialised and the
     * camera track swapped, only to be torn down again right afterwards.
     */
    @Test
    fun disposeDuringTheImageLoad_stopsTheApplyBeforeAnyProcessorIsBuilt() =
        withCleanStorage {
            val h = Harness(timeoutMs = 60_000L)
            val track = FakeTrack()
            val probeGate = CompletableDeferred<Unit>()
            val probeEntered = CompletableDeferred<Unit>()
            h.imageProbeBehavior = {
                probeEntered.complete(Unit)
                probeGate.await()
            }
            val applying = GlobalScope.launch { h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track) }
            probeEntered.await()
            h.controller.dispose(track)
            probeGate.complete(Unit)
            applying.join()
            assertTrue(h.created.isEmpty(), "no processor may be built after dispose")
            assertEquals(0, track.setCalls, "nothing may be attached to the camera track after dispose")
            assertTrue(h.messages.isEmpty())
            assertEquals(ConferenceBackgroundPhase.OFF, h.controller.state.phase, "the phase must not stay APPLYING")
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            assertEquals(ConferenceBackgroundEffect.BG_SAGE, h.controller.state.desired, "the intent survives")
        }

    /** The same promise for the "Aus" path, whose `removeProcessor` also suspends. */
    @Test
    fun disposeDuringAnOffApply_doesNotReportSuccessAfterwards() =
        withCleanStorage {
            val h = Harness(timeoutMs = 60_000L, disposeStopTimeoutMs = 60_000L)
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            val statesBefore = h.states.size
            val stopGate = CompletableDeferred<Unit>()
            val stopEntered = CompletableDeferred<Unit>()
            track.stopBehavior = {
                stopEntered.complete(Unit)
                stopGate.await()
            }
            val applying = GlobalScope.launch { h.controller.select(ConferenceBackgroundEffect.OFF, track) }
            stopEntered.await()
            track.stopBehavior = {} // dispose' eigenes stopProcessor darf nicht am selben Gate haengen
            h.controller.dispose(track)
            stopGate.complete(Unit)
            applying.join()
            assertEquals(ConferenceBackgroundPhase.OFF, h.controller.state.phase)
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            // Nach dispose kein weiterer sichtbarer Zustandswechsel aus dem ueberholten Versuch: genau EINER
            // (der von dispose selbst) kam noch hinzu.
            assertEquals(statesBefore + 2, h.states.size, "only UserSelected(OFF) and dispose' own transition")
        }

    @Test
    fun dispose_whoseCappedStopHangs_schedulesAnUnboundedCleanup() =
        withCleanStorage {
            val h = Harness(disposeStopTimeoutMs = 30L)
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            // stopProcessor haengt hinter livekits trackChangeLock -- der Deckel greift.
            track.stopBehavior = { awaitCancellation() }
            h.controller.dispose(track)
            assertEquals(1, h.detachedCleanups.size)
            track.stopBehavior = {}
            val stopsBefore = track.stopCalls
            h.detachedCleanups[0].invoke()
            assertEquals(stopsBefore + 1, track.stopCalls)
            assertNull(track.current)
        }

    @Test
    fun afterDispose_selectAndNewTrackDoNothing() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.dispose(track)
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            h.controller.onLocalCameraTrack(track)
            assertEquals(0, track.setCalls)
            assertTrue(h.created.isEmpty())
        }

    // --- Audit-Befund M4: kein Zombie-Prozessor, keine luegende Zustandsmeldung ------------------

    @Test
    fun timeout_schedulesAnUnboundedCleanupBesidesTheCappedOne() =
        withCleanStorage {
            val h = Harness(timeoutMs = 30L)
            val track = FakeTrack()
            track.setBehavior = { awaitCancellation() }
            h.controller.select(ConferenceBackgroundEffect.BLUR_LIGHT, track)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.TIMEOUT)), h.messages)
            assertEquals(1, track.stopCalls, "the capped cleanup still runs")
            assertEquals(1, h.detachedCleanups.size, "plus an unbounded one, because stopProcessor shares the lock")
        }

    /**
     * The zombie itself: the JS promise behind `setProcessor` is NOT cancelled by the Kotlin timeout, so the
     * processor really does end up on the track after the controller already reported FAILED_FALLBACK. The
     * unbounded cleanup is what tears it down again.
     *
     * The "late" completion is gated by this test, not timed (follow-up audit, point 7): the only wall-clock
     * element left is the 30 ms apply timeout itself, and that one cannot flutter -- `setProcessor` awaits
     * cancellation, so the timeout is guaranteed to be what ends the attempt.
     */
    @Test
    fun aSetProcessorThatFinishesAfterTheTimeout_isTornDown_andNeverReportedAsApplied() =
        withCleanStorage {
            val h = Harness(timeoutMs = 30L)
            val track = FakeTrack()
            val lateGate = CompletableDeferred<Unit>()
            track.lateSetGate = lateGate
            h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.TIMEOUT)), h.messages)
            assertEquals(1, h.detachedCleanups.size)
            // Das "unkuendbare" Promise wird JETZT fertig und setzt den Prozessor doch noch.
            lateGate.complete(Unit)
            track.lateSetApplied.await()
            assertSame(h.created[0], track.current, "precondition: the late promise really does set it")
            track.lateSetGate = null
            h.detachedCleanups[0].invoke()
            assertNull(track.current, "the unbounded cleanup removes the zombie")
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied, "the reported state stays honest")
        }

    // --- Audit-Befund M1: ein nicht ladbares Hintergrundbild ist ein Fehler, kein schwarzes Bild --

    @Test
    fun anImageThatCannotBeLoaded_isLoadFailed_andNoProcessorIsEverBuilt() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.imageProbeBehavior = { throw IllegalStateException("404") }
            h.controller.select(ConferenceBackgroundEffect.BG_STUDIO, track)
            assertEquals(listOf("/assets/video-backgrounds/bg-studio.webp"), h.probedImages)
            assertTrue(h.created.isEmpty(), "the camera is never touched for a doomed image")
            assertEquals(0, track.setCalls)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.LOAD_FAILED)), h.messages)
            assertEquals("bg-studio", stored(), "the intent survives")
        }

    @Test
    fun blurEffectsAreNeverProbedForAnImage() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BLUR_STRONG, track)
            h.controller.select(ConferenceBackgroundEffect.OFF, track)
            assertTrue(h.probedImages.isEmpty())
        }

    /**
     * The exact shape of the swallowed library error: `setProcessor` resolves, but the transformer holds no
     * background image -- pre-fix that was reported as success and rendered the person over black.
     */
    @Test
    fun aSilentlySwallowedImageFailure_isStillLoadFailed_andFallsBackToOff() =
        withCleanStorage {
            val h = Harness()
            h.simulateSilentImageFailure = true
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BG_MIDNIGHT, track)
            assertEquals(1, track.setCalls)
            assertEquals(1, track.stopCalls, "the half-applied processor is torn down")
            assertNull(track.current)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertEquals(ConferenceBackgroundEffect.OFF, h.controller.state.applied)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.LOAD_FAILED)), h.messages)
        }

    @Test
    fun aSwitchToThatSilentlyKeepsTheOldImage_isLoadFailed() =
        withCleanStorage {
            val h = Harness()
            val track = FakeTrack()
            h.controller.select(ConferenceBackgroundEffect.BG_SAGE, track)
            assertEquals(ConferenceBackgroundPhase.ACTIVE, h.controller.state.phase)
            // switchTo erfuellt sich, laesst aber den alten Bildpfad stehen.
            h.created[0].silentSwitchFailure = true
            h.controller.select(ConferenceBackgroundEffect.BG_STUDIO, track)
            assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, h.controller.state.phase)
            assertEquals(listOf(conferenceBackgroundFailureMessage(ConferenceBackgroundFailure.LOAD_FAILED)), h.messages)
            assertEquals(1, track.stopCalls)
        }
}
