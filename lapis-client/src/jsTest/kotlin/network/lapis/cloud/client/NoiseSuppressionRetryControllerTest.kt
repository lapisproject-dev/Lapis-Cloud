package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.119 -- the diagnosis and the retry of the noise suppression through the [NoiseSuppressionChain] seam: every one of the 12 stages ends
 * as `Unavailable(stage)` with the browser's own reduction back on and the remembered choice untouched, writes EXACTLY ONE console line per
 * (stage, trigger), a failure that cannot change locks the switch while every other one is retried by a click or a new microphone track, a
 * suspended context is started by the person's next gesture (once, `prepare()` inside the gesture, quietly), and a failed restart falls back.
 * Timeouts are injected, nothing depends on the date.
 */
class NoiseSuppressionRetryControllerTest {
    private class Rig(
        stored: Boolean = true,
        val chain: FakeNoiseSuppressionChain? = FakeNoiseSuppressionChain(),
        capability: NoiseSuppressionUnavailable? = null,
        loadTimeoutMs: Long = 5_000L,
    ) {
        val preference = FakeNoiseSuppressionPreference(stored)
        val logger = RecordingNoiseSuppressionLogger()
        val hook = FakeGestureHook()
        val seen = mutableListOf<Pair<NoiseSuppressionState, Boolean>>()
        val controller =
            ConferenceNoiseSuppressionController(
                preference = preference,
                chain = { chain },
                capability = { capability },
                loadTimeoutMs = loadTimeoutMs,
                stopTimeoutMs = 200L,
                gestureHook = hook,
                logger = logger,
            ).also { it.observe { state, announce -> seen += state to announce } }

        fun announcements() = seen.filter { it.second }.map { it.first }

        fun reason() = (controller.state as? NoiseSuppressionState.Unavailable)?.failure?.reason
    }

    private fun run(block: suspend () -> Unit): Promise<Unit> = GlobalScope.promise { block() }

    private suspend fun awaitIdle(rig: Rig) {
        repeat(2_000) {
            if (!rig.controller.busy) return
            delay(1)
        }
        error("still busy")
    }

    private val attachStages =
        listOf(
            NoiseSuppressionUnavailable.SAMPLE_RATE,
            NoiseSuppressionUnavailable.NO_AUDIO_CONTEXT,
            NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING,
            NoiseSuppressionUnavailable.WASM_FETCH,
            NoiseSuppressionUnavailable.WORKLET_MODULE,
            NoiseSuppressionUnavailable.NODE_CREATE,
            NoiseSuppressionUnavailable.SET_PROCESSOR,
            NoiseSuppressionUnavailable.RESTART,
            NoiseSuppressionUnavailable.LOAD_FAILED,
        )

    @Test
    fun everyStageOfAnAttach_endsUnavailableWithItsCode_logsOneLine_andKeepsTheChoice() =
        run {
            for (code in attachStages) {
                val rig = Rig()
                rig.chain!!.attachFailures = listOf(NoiseSuppressionFailure(code, "SomeError", "detail"))
                rig.chain.diagnosticsAnswer = NoiseSuppressionDiagnostics("x", "suspended", 48000, 44100, 2)
                rig.controller.onMicTrackPublished()
                assertEquals(code, rig.reason(), "the stage $code")
                assertEquals(1, rig.logger.lines.size, "one line for $code")
                val line = rig.logger.lines.single()
                assertTrue(line.startsWith("[noise-suppression] stage=${code.name} trigger=auto error=SomeError message=detail "), line)
                assertTrue(line.endsWith("ctxState=suspended ctxRate=48000 trackRate=44100 trackChannels=2"), line)
                assertEquals(listOf(true), rig.chain.browserNoiseSuppression.takeLast(1), "the browser's own reduction is back on")
                assertTrue(rig.chain.detachCalls >= 1)
                assertTrue(rig.preference.stored, "the choice stays on for $code")
                assertTrue(rig.preference.writes.isEmpty())
                rig.controller.onMicTrackPublished()
                if (code.retryable) assertEquals(1, rig.logger.lines.size, "the same stage and trigger is logged once only")
            }
        }

    @Test
    fun theTwoMissingBrowserFeatures_areLoggedOnceAtConstruction() =
        run {
            for (code in listOf(NoiseSuppressionUnavailable.NO_AUDIO_WORKLET, NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY)) {
                val rig = Rig(capability = code)
                assertEquals(code, rig.reason())
                assertEquals(1, rig.logger.lines.size)
                assertTrue(
                    rig.logger.lines
                        .single()
                        .contains("stage=${code.name} trigger=init"),
                )
                assertEquals(0, rig.chain!!.attachCalls)
            }
        }

    @Test
    fun aLoadThatNeverEnds_isATimeout_withOneLine() =
        run {
            val rig = Rig(loadTimeoutMs = 30L)
            rig.chain!!.attachBehavior = { awaitCancellation() }
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionUnavailable.TIMEOUT, rig.reason())
            assertEquals(1, rig.logger.lines.size)
            assertTrue(
                rig.logger.lines
                    .single()
                    .contains("stage=TIMEOUT"),
            )
        }

    @Test
    fun anExceptionWithoutAStage_isLoadFailedWithTheBrowsersName_andAStageExceptionKeepsItsStage() =
        run {
            val rig = Rig()
            rig.chain!!.attachBehavior = { error("addModule failed") }
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, rig.reason())
            val other = Rig()
            other.chain!!.attachBehavior = {
                throw NoiseSuppressionStageException(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH, null, "status=404"))
            }
            other.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionUnavailable.WASM_FETCH, other.reason())
            assertTrue(
                other.logger.lines
                    .single()
                    .contains("message=status=404"),
            )
        }

    // ── retry ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aFailureThatCannotChange_locksTheSwitch_aClickChangesNothing() =
        run {
            val rig = Rig(stored = false)
            rig.chain!!.attachFailures = listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.SAMPLE_RATE, null, "rate=44100"))
            rig.controller.toggle()
            assertEquals(NoiseSuppressionUnavailable.SAMPLE_RATE, rig.reason())
            assertEquals(1, rig.chain.attachCalls)
            val preparesBefore = rig.chain.prepareCalls
            rig.controller.prepareFromGesture()
            rig.controller.toggle()
            rig.controller.onMicTrackPublished()
            assertEquals(1, rig.chain.attachCalls, "no retry of a stage that cannot change")
            assertEquals(preparesBefore, rig.chain.prepareCalls)
        }

    @Test
    fun aTemporaryFailure_isRetriedByAClick_andAnnouncedWhenItWorks() =
        run {
            val rig = Rig(stored = false)
            rig.chain!!.attachFailures = listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH, null, "status=503"), null)
            rig.controller.toggle()
            assertEquals(NoiseSuppressionUnavailable.WASM_FETCH, rig.reason())
            rig.controller.prepareFromGesture()
            assertEquals(1, rig.chain.prepareCalls, "a click on a retryable failure prepares the context inside the gesture")
            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(2, rig.chain.attachCalls)
            assertEquals(NoiseSuppressionState.On, rig.announcements().last(), "the switch-on by the person is announced")
            assertEquals(listOf(true), rig.preference.writes)
        }

    @Test
    fun aNewMicrophoneTrack_retriesATemporaryFailure_quietlyAndWithoutANewLogLine() =
        run {
            val rig = Rig()
            rig.chain!!.attachFailures =
                listOf(
                    NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH),
                    NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH),
                    null,
                )
            rig.controller.onMicTrackPublished()
            val announced = rig.announcements().size
            rig.controller.onMicTrackPublished()
            assertEquals(2, rig.chain.attachCalls)
            assertEquals(announced, rig.announcements().size, "the same failure again is not announced again")
            assertEquals(1, rig.logger.lines.size)
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(announced, rig.announcements().size, "the quiet success announces nothing")
        }

    // ── the next gesture ────────────────────────────────────────────────────────────────────────────────────────────

    private fun suspendedFirst(
        rig: Rig,
        then: NoiseSuppressionFailure?,
    ): MutableList<Int> {
        val preparesAtAttach = mutableListOf<Int>()
        rig.chain!!.attachFailures =
            listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, null, "state=suspended"), then)
        rig.chain.attachBehavior = { preparesAtAttach += rig.chain.prepareCalls }
        return preparesAtAttach
    }

    @Test
    fun aSuspendedContext_registersOneHook_andTheGestureStartsItQuietly_afterPrepare() =
        run {
            val rig = Rig()
            val preparesAtAttach = suspendedFirst(rig, null)
            rig.chain!!.attachFailures = listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING))
            rig.controller.onMicTrackPublished()
            assertEquals(1, rig.hook.registrations)
            rig.controller.onMicTrackPublished() // a second automatic attempt: the same hook, no second one
            assertEquals(1, rig.hook.registrations)
            val announced = rig.announcements().size
            val suspended = NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, null, "state=suspended")
            rig.chain!!.attachFailures = listOf(suspended, suspended, null) // the 3rd attempt (the gesture's) works
            rig.hook.fire()
            awaitIdle(rig)
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(1, rig.chain.prepareCalls, "prepare() ran inside the gesture")
            assertEquals(1, preparesAtAttach.last(), "...before the attempt")
            assertEquals(announced, rig.announcements().size, "quiet success")
            assertFalse(rig.hook.registered)
        }

    @Test
    fun aGestureThatFailsAgain_doesNotRegisterASecondHook() =
        run {
            val rig = Rig()
            suspendedFirst(rig, NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, null, "state=suspended"))
            rig.controller.onMicTrackPublished()
            rig.hook.fire()
            awaitIdle(rig)
            assertEquals(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, rig.reason())
            assertEquals(1, rig.hook.registrations)
            assertEquals(2, rig.logger.lines.size, "auto and gesture are two different triggers")
            assertTrue(
                rig.logger.lines
                    .last()
                    .contains("trigger=gesture"),
            )
            assertEquals(2, rig.announcements().size, "both failures are announced (the second came from a different trigger)")
        }

    @Test
    fun aClickOnTheSwitch_removesTheHook() =
        run {
            val rig = Rig()
            suspendedFirst(rig, null)
            rig.controller.onMicTrackPublished()
            assertTrue(rig.hook.registered)
            rig.controller.prepareFromGesture()
            assertEquals(1, rig.hook.removals)
            assertFalse(rig.hook.registered)
        }

    @Test
    fun dispose_removesTheHook_andNothingIsTriedAfterwards() =
        run {
            val rig = Rig()
            suspendedFirst(rig, null)
            rig.controller.onMicTrackPublished()
            rig.controller.dispose()
            assertEquals(1, rig.hook.removals)
            rig.controller.onMicTrackPublished()
            assertEquals(1, rig.chain!!.attachCalls)
            assertEquals(1, rig.hook.registrations)
        }

    @Test
    fun noOtherStageAndNoClick_everRegistersAHook() =
        run {
            val other = Rig()
            other.chain!!.attachFailures = listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH))
            other.controller.onMicTrackPublished()
            assertEquals(0, other.hook.registrations)
            val clicked = Rig(stored = false)
            clicked.chain!!.attachFailures = listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING))
            clicked.controller.toggle()
            assertEquals(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, clicked.reason())
            assertEquals(0, clicked.hook.registrations)
        }

    @Test
    fun theGestureAndTheClickOfTheSamePress_runExactlyOneAttempt() =
        run {
            val rig = Rig()
            val gate = CompletableDeferred<Unit>()
            suspendedFirst(rig, null)
            rig.controller.onMicTrackPublished()
            rig.chain!!.attachBehavior = { gate.await() }
            rig.chain.attachFailures = listOf(null)
            rig.hook.fire() // pointerdown (capture)
            assertTrue(rig.controller.busy)
            rig.controller.prepareFromGesture() // the click event of the same press
            rig.controller.toggle() // dropped: an attempt is running
            gate.complete(Unit)
            awaitIdle(rig)
            assertEquals(2, rig.chain.attachCalls, "the automatic one and ONE gesture attempt")
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
        }

    // ── a failed restart ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aRuntimeFailure_fallsBack_withTheRestartStage_andOneLine() =
        run {
            val rig = Rig(stored = false)
            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            val listener = rig.chain!!.runtimeListener ?: error("the controller listens for runtime failures of the chain")
            val detachBefore = rig.chain.detachCalls
            listener(NoiseSuppressionFailure(NoiseSuppressionUnavailable.RESTART, "Error", "device lost"))
            repeat(500) { if (rig.controller.state is NoiseSuppressionState.On) delay(1) }
            assertEquals(NoiseSuppressionUnavailable.RESTART, rig.reason())
            assertTrue(rig.chain.detachCalls > detachBefore)
            assertEquals(true, rig.chain.browserNoiseSuppression.last())
            assertTrue(rig.preference.stored, "the choice stays")
            assertEquals(1, rig.logger.lines.size)
            assertTrue(
                rig.logger.lines
                    .single()
                    .contains("stage=RESTART trigger=restart"),
            )
        }
}
