package network.lapis.cloud.client

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.124 -- the instance default of the noise suppression through the controller. The rule: a remembered choice (on OR off) wins over the
 * default; with no choice the default decides; the automatic paths (construction, track published, the next-gesture hook) NEVER write the
 * preference, so the default does not turn into the person's own choice; only a person's click writes. A failure of the default is shown
 * but not announced; the microphone is never left behind a half-installed filter.
 */
class NoiseSuppressionInstanceDefaultTest {
    private class Rig(
        stored: Boolean?,
        instanceDefault: Boolean,
        val chain: FakeNoiseSuppressionChain? = FakeNoiseSuppressionChain(),
        capability: NoiseSuppressionUnavailable? = null,
    ) {
        val preference = FakeNoiseSuppressionPreference(stored)
        val hook = FakeGestureHook()
        val logger = RecordingNoiseSuppressionLogger()
        val seen = mutableListOf<Pair<NoiseSuppressionState, Boolean>>()
        val controller =
            ConferenceNoiseSuppressionController(
                preference = preference,
                chain = { chain },
                capability = { capability },
                loadTimeoutMs = 5_000L,
                stopTimeoutMs = 200L,
                gestureHook = hook,
                logger = logger,
                instanceDefault = instanceDefault,
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

    @Test
    fun matrix_storedChoice_x_default_x_browser_x_attach_neverWritesOnTheAutomaticPath() =
        run {
            val attachOutcomes =
                listOf<NoiseSuppressionUnavailable?>(
                    null,
                    NoiseSuppressionUnavailable.LOAD_FAILED,
                    NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING,
                )
            for (stored in listOf(true, false, null)) {
                for (default in listOf(true, false)) {
                    for (supported in listOf(true, false)) {
                        for (outcome in attachOutcomes) {
                            val label = "stored=$stored default=$default supported=$supported attach=$outcome"
                            val rig =
                                Rig(
                                    stored = stored,
                                    instanceDefault = default,
                                    capability = if (supported) null else NoiseSuppressionUnavailable.NO_AUDIO_WORKLET,
                                )
                            rig.chain!!.attachResult = outcome
                            val wantOn = stored ?: default
                            rig.controller.onMicTrackPublished()
                            when {
                                !supported -> {
                                    assertEquals(NoiseSuppressionUnavailable.NO_AUDIO_WORKLET, rig.reason(), label)
                                    assertEquals(0, rig.chain.attachCalls, label)
                                }
                                !wantOn -> {
                                    assertEquals(NoiseSuppressionState.Off, rig.controller.state, label)
                                    assertEquals(0, rig.chain.attachCalls, label)
                                }
                                outcome == null -> {
                                    assertEquals(NoiseSuppressionState.On, rig.controller.state, label)
                                    assertEquals(1, rig.chain.attachCalls, label)
                                    assertEquals(
                                        false,
                                        rig.chain.browserNoiseSuppression.last(),
                                        "the browser's own reduction is off while RNNoise works: $label",
                                    )
                                }
                                else -> {
                                    assertEquals(outcome, rig.reason(), label)
                                    assertEquals(1, rig.chain.attachCalls, label)
                                    assertTrue(rig.chain.detachCalls >= 1, "the filter is taken out again: $label")
                                    assertEquals(
                                        true,
                                        rig.chain.browserNoiseSuppression.last(),
                                        "the microphone keeps the browser's reduction: $label",
                                    )
                                    val silent = stored == null && default
                                    if (silent) assertTrue(rig.announcements().isEmpty(), "a failing default is not announced: $label")
                                    assertEquals(
                                        outcome == NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING,
                                        rig.hook.registered,
                                        "only a suspended context waits for a gesture: $label",
                                    )
                                }
                            }
                            assertTrue(rig.preference.writes.isEmpty(), "the automatic path never writes: $label")
                            assertEquals(stored, rig.preference.stored, "the stored choice is untouched: $label")
                        }
                    }
                }
            }
        }

    @Test
    fun aStoredOff_beatsTheDefaultOn_noAttach_noHint() =
        run {
            val rig = Rig(stored = false, instanceDefault = true)
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionState.Off, rig.controller.state)
            assertEquals(0, rig.chain!!.attachCalls)
            assertFalse(rig.controller.fromDefault)
        }

    @Test
    fun aStoredOn_withDefaultOff_worksAsBefore() =
        run {
            val rig = Rig(stored = true, instanceDefault = false)
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            rig.controller.onMicTrackPublished()
            assertEquals(1, rig.chain!!.attachCalls)
            assertFalse(rig.controller.fromDefault, "it is the person's own choice")
        }

    @Test
    fun theDefaultOn_withoutAMicrophone_isOn_writesNothing_andIsAppliedWhenTheTrackAppears() =
        run {
            val rig = Rig(stored = null, instanceDefault = true, chain = null)
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertTrue(rig.controller.fromDefault)
            assertTrue(rig.preference.writes.isEmpty())
            // a click while there is no microphone is a deliberate choice: remembered
            val other = Rig(stored = null, instanceDefault = false, chain = null)
            other.controller.toggle()
            assertEquals(listOf(true), other.preference.writes)
            assertEquals(NoiseSuppressionState.On, other.controller.state)
        }

    @Test
    fun aClickOff_whileTheDefaultIsOn_writesOff_endsTheHint_andTheDefaultNeverOverrulesItAgain() =
        run {
            val rig = Rig(stored = null, instanceDefault = true)
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertTrue(rig.controller.fromDefault)
            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.Off, rig.controller.state)
            assertEquals(listOf(false), rig.preference.writes)
            assertFalse(rig.controller.fromDefault)
            // a later page (same preference, default still on): the explicit off wins
            val later =
                ConferenceNoiseSuppressionController(
                    preference = rig.preference,
                    chain = { FakeNoiseSuppressionChain() },
                    capability = { null },
                    instanceDefault = true,
                )
            assertEquals(NoiseSuppressionState.Off, later.state)
            assertFalse(later.fromDefault)
        }

    @Test
    fun aClickOn_whileTheDefaultIsOff_writesOn() =
        run {
            val rig = Rig(stored = null, instanceDefault = false)
            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(listOf(true), rig.preference.writes)
            assertFalse(rig.controller.fromDefault)
        }

    @Test
    fun aFailedClick_writesNothing_andKeepsTheDefaultHint_andIsAnnounced() =
        run {
            val rig = Rig(stored = null, instanceDefault = true)
            rig.chain!!.attachResult = NoiseSuppressionUnavailable.LOAD_FAILED
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, rig.reason())
            assertTrue(rig.announcements().isEmpty(), "the failure of the default itself is silent")
            rig.controller.toggle()
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, rig.reason())
            assertTrue(rig.preference.writes.isEmpty(), "a failed click is not remembered")
            assertTrue(rig.controller.fromDefault)
            assertTrue(rig.announcements().isNotEmpty(), "a failure after the person's own click is announced")
        }

    @Test
    fun aSuspendedContext_withTheDefault_waitsForOneGesture_quietly_andRetriesWithoutWriting() =
        run {
            val rig = Rig(stored = null, instanceDefault = true)
            rig.chain!!.attachFailures =
                listOf(
                    NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING),
                    null,
                )
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, rig.reason())
            assertEquals(1, rig.hook.registrations)
            assertTrue(rig.announcements().isEmpty())
            rig.hook.fire()
            awaitIdle(rig)
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertTrue(rig.preference.writes.isEmpty(), "the gesture retry is automatic, not a choice")
            assertTrue(rig.controller.fromDefault)
        }

    @Test
    fun aSecondSuspendedFailure_doesNotRegisterTheHookAgain() =
        run {
            val rig = Rig(stored = null, instanceDefault = true)
            rig.chain!!.attachFailures = listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING))
            rig.controller.onMicTrackPublished()
            rig.hook.fire()
            awaitIdle(rig)
            assertEquals(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, rig.reason())
            assertEquals(1, rig.hook.registrations, "one retry per page")
            assertNull(rig.preference.writes.firstOrNull())
        }
}
