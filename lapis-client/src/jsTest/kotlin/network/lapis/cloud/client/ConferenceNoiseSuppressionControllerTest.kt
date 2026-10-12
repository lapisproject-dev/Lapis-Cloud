package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.114 -- the state machine of the optional noise suppression ([ConferenceNoiseSuppressionController]) through the [NoiseSuppressionChain]
 * seam, without a microphone, a worklet or WebAssembly: off -> on -> off, the remembered choice, the track change, every fallback (no
 * AudioWorklet, no WebAssembly, a load error, a timeout, a suspended context -- which is a chain failure), exactly one announcement per cause,
 * no error dialog, a quick double click that must not stack two filters. Timeouts are injected (a few milliseconds), nothing depends on the date.
 */
class ConferenceNoiseSuppressionControllerTest {
    private class FakePreference(
        var stored: Boolean = false,
    ) : NoiseSuppressionPreference {
        var writes = mutableListOf<Boolean>()

        override fun read(): Boolean = stored

        override fun write(on: Boolean) {
            writes += on
            stored = on
        }
    }

    /** The track is `muted` and stays so: the chain never touches it (no unpublish, no mute call exist on the seam at all). */
    private class FakeChain : NoiseSuppressionChain {
        var attachResult: NoiseSuppressionUnavailable? = null
        var attachBehavior: suspend () -> Unit = {}
        var attachCalls = 0
        var detachCalls = 0
        var prepareCalls = 0
        var attached = false
        val browserNoiseSuppression = mutableListOf<Boolean>()
        var throwOnBrowserNoiseSuppression = false

        override fun prepare() {
            prepareCalls++
        }

        override suspend fun attach(): NoiseSuppressionFailure? {
            attachCalls++
            attachBehavior()
            if (attachResult == null) attached = true
            return attachResult?.let { NoiseSuppressionFailure(it) }
        }

        override suspend fun detach() {
            detachCalls++
            attached = false
        }

        override suspend fun setBrowserNoiseSuppression(enabled: Boolean) {
            browserNoiseSuppression += enabled
            if (throwOnBrowserNoiseSuppression) error("refused by the browser")
        }
    }

    private class Rig(
        stored: Boolean = false,
        val chain: FakeChain? = FakeChain(),
        capability: NoiseSuppressionUnavailable? = null,
        loadTimeoutMs: Long = 5_000L,
    ) {
        val preference = FakePreference(stored)
        var currentChain: FakeChain? = chain
        val seen = mutableListOf<Pair<NoiseSuppressionState, Boolean>>()
        val controller =
            ConferenceNoiseSuppressionController(
                preference = preference,
                chain = { currentChain },
                capability = { capability },
                loadTimeoutMs = loadTimeoutMs,
                stopTimeoutMs = 200L,
            ).also { it.observe { state, announce -> seen += state to announce } }

        fun announcements() = seen.filter { it.second }.map { it.first }
    }

    private fun run(block: suspend () -> Unit): Promise<Unit> = GlobalScope.promise { block() }

    @Test
    fun offToOnToOff_putsTheFilterInAndOut_andAnnouncesOnlyTheSwitchOn() =
        run {
            val rig = Rig()
            val chain = rig.chain!!
            assertEquals(NoiseSuppressionState.Off, rig.controller.state)

            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(1, chain.attachCalls)
            assertTrue(chain.attached)
            assertEquals(listOf(false), chain.browserNoiseSuppression, "the browser's own reduction is off while RNNoise works")
            assertEquals(listOf(true), rig.preference.writes)
            assertEquals(listOf<NoiseSuppressionState>(NoiseSuppressionState.On), rig.announcements())
            assertFalse(rig.controller.busy)

            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.Off, rig.controller.state)
            assertEquals(1, chain.detachCalls)
            assertFalse(chain.attached)
            assertEquals(listOf(false, true), chain.browserNoiseSuppression, "the browser's own reduction is back on")
            assertEquals(listOf(true, false), rig.preference.writes)
            assertEquals(1, rig.announcements().size, "switching off announces nothing")
        }

    @Test
    fun theFirstStateIsNeverAnnounced_notEvenWithARememberedOn() =
        run {
            val rig = Rig(stored = true)
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(listOf<Pair<NoiseSuppressionState, Boolean>>(NoiseSuppressionState.On to false), rig.seen)
            assertEquals(0, rig.chain!!.attachCalls, "nothing is attached before a microphone track exists")
        }

    @Test
    fun aRememberedChoice_isAppliedWhenTheMicrophoneTrackAppears_withoutAnAnnouncement() =
        run {
            val rig = Rig(stored = true)
            rig.controller.onMicTrackPublished()
            assertEquals(1, rig.chain!!.attachCalls)
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(listOf(false), rig.chain.browserNoiseSuppression)
            assertTrue(rig.announcements().isEmpty())
        }

    @Test
    fun aRememberedOff_isNeverApplied_andATrackChangeDoesNotTouchTheChain() =
        run {
            val rig = Rig(stored = false)
            rig.controller.onMicTrackPublished()
            assertEquals(0, rig.chain!!.attachCalls)
            assertEquals(0, rig.chain.detachCalls)
            assertTrue(rig.chain.browserNoiseSuppression.isEmpty())
        }

    @Test
    fun aTrackChangeWhileOn_appliesAgain_stateStaysOn_andNothingIsAnnounced() =
        run {
            val rig = Rig()
            rig.controller.toggle()
            val before = rig.announcements().size
            rig.controller.onMicTrackPublished()
            rig.controller.onMicTrackPublished()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(3, rig.chain!!.attachCalls, "the chain is asked each time (the session makes it idempotent)")
            assertEquals(before, rig.announcements().size)
        }

    @Test
    fun withoutAMicrophoneTrack_theSwitchIsRemembered_andAppliedOnPublish() =
        run {
            val rig = Rig(chain = null)
            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(listOf(true), rig.preference.writes)
            val chain = FakeChain()
            rig.currentChain = chain
            rig.controller.onMicTrackPublished()
            assertEquals(1, chain.attachCalls)
            assertTrue(chain.attached)
        }

    // ── the fallback: never a dialog, always the browser's own reduction ──────────────────────────────────────

    @Test
    fun noAudioWorklet_isUnavailableAtOnce_withNoAttach_andAClickChangesNothing() =
        run {
            val rig = Rig(capability = NoiseSuppressionUnavailable.NO_AUDIO_WORKLET)
            assertEquals(
                NoiseSuppressionState.Unavailable(NoiseSuppressionFailure(NoiseSuppressionUnavailable.NO_AUDIO_WORKLET)),
                rig.controller.state,
            )
            rig.controller.toggle()
            rig.controller.onMicTrackPublished()
            assertEquals(0, rig.chain!!.attachCalls)
            assertTrue(rig.preference.writes.isEmpty())
            assertEquals(
                NoiseSuppressionState.Unavailable(NoiseSuppressionFailure(NoiseSuppressionUnavailable.NO_AUDIO_WORKLET)),
                rig.controller.state,
            )
        }

    @Test
    fun noWebAssembly_isUnavailable() =
        run {
            val rig = Rig(capability = NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY)
            assertEquals(
                NoiseSuppressionState.Unavailable(NoiseSuppressionFailure(NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY)),
                rig.controller.state,
            )
        }

    @Test
    fun aCapabilityLostLater_failsTheSwitchOn_andRestoresTheBrowserReduction() =
        run {
            var capability: NoiseSuppressionUnavailable? = null
            val chain = FakeChain()
            val controller =
                ConferenceNoiseSuppressionController(
                    preference = FakePreference(),
                    chain = { chain },
                    capability = { capability },
                    stopTimeoutMs = 200L,
                )
            capability = NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY
            controller.toggle()
            assertEquals(0, chain.attachCalls)
            assertEquals(
                NoiseSuppressionState.Unavailable(NoiseSuppressionFailure(NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY)),
                controller.state,
            )
            assertEquals(listOf(true), chain.browserNoiseSuppression)
        }

    @Test
    fun aLoadFailureReportedByTheChain_endsUnavailable_removesTheFilter_keepsTheChoice_andAnnouncesOnce() =
        run {
            val rig = Rig()
            rig.chain!!.attachResult = NoiseSuppressionUnavailable.LOAD_FAILED
            rig.controller.toggle()
            assertEquals(
                NoiseSuppressionState.Unavailable(NoiseSuppressionFailure(NoiseSuppressionUnavailable.LOAD_FAILED)),
                rig.controller.state,
            )
            assertEquals(1, rig.chain.detachCalls, "the filter is taken out again")
            assertEquals(listOf(true), rig.chain.browserNoiseSuppression, "the browser's own reduction is on")
            assertTrue(rig.preference.writes.isEmpty(), "the remembered choice is not changed by a failure")
            assertEquals(1, rig.announcements().size)
            assertFalse(rig.controller.busy)
        }

    @Test
    fun anExceptionFromTheChain_isLoadFailed_notAnErrorOutside() =
        run {
            val rig = Rig()
            rig.chain!!.attachBehavior = { error("addModule failed") }
            rig.controller.toggle()
            val failed = rig.controller.state as NoiseSuppressionState.Unavailable
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, failed.failure.reason)
            assertEquals("addModule failed", failed.failure.errorMessage)
            assertEquals(listOf(true), rig.chain.browserNoiseSuppression)
        }

    @Test
    fun aLoadThatNeverEnds_isATimeoutFailure() =
        run {
            val rig = Rig(loadTimeoutMs = 30L)
            rig.chain!!.attachBehavior = { awaitCancellation() }
            rig.controller.toggle()
            assertEquals(
                NoiseSuppressionState.Unavailable(NoiseSuppressionFailure(NoiseSuppressionUnavailable.TIMEOUT)),
                rig.controller.state,
            )
            assertEquals(1, rig.chain.detachCalls)
            assertFalse(rig.controller.busy)
        }

    @Test
    fun aBrowserThatRefusesTheConstraint_isIgnored_theStateStaysOn() =
        run {
            val rig = Rig()
            rig.chain!!.throwOnBrowserNoiseSuppression = true
            rig.controller.toggle()
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
        }

    @Test
    fun afterAFailureThatCannotChange_aTrackChangeDoesNotRetryTheFilter() =
        run {
            val rig = Rig(stored = true)
            rig.chain!!.attachResult = NoiseSuppressionUnavailable.SAMPLE_RATE
            rig.controller.onMicTrackPublished()
            rig.controller.onMicTrackPublished()
            rig.controller.onMicTrackPublished()
            assertEquals(1, rig.chain.attachCalls)
            assertEquals(1, rig.announcements().size)
        }

    // ── the lock ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aQuickDoubleClick_doesNotStackTwoFilters() =
        run {
            val rig = Rig()
            val gate = CompletableDeferred<Unit>()
            rig.chain!!.attachBehavior = { gate.await() }
            val first = GlobalScope.launch { rig.controller.toggle() }
            yield()
            assertTrue(rig.controller.busy)
            rig.controller.toggle() // dropped
            rig.controller.toggle() // dropped
            gate.complete(Unit)
            first.join()
            assertEquals(1, rig.chain.attachCalls)
            assertEquals(NoiseSuppressionState.On, rig.controller.state)
            assertEquals(0, rig.chain.detachCalls)
        }

    @Test
    fun aStuckDetach_neverFreezesTheSwitch() =
        run {
            val stuck = CompletableDeferred<Unit>()
            val stuckChain =
                object : NoiseSuppressionChain {
                    override fun prepare() {}

                    override suspend fun attach(): NoiseSuppressionFailure? = null

                    override suspend fun detach() = stuck.await()

                    override suspend fun setBrowserNoiseSuppression(enabled: Boolean) {}
                }
            val controller =
                ConferenceNoiseSuppressionController(
                    preference = FakePreference(true),
                    chain = { stuckChain },
                    capability = { null },
                    stopTimeoutMs = 20L,
                )
            controller.toggle() // off: detach hangs, is cut after 20 ms
            assertEquals(NoiseSuppressionState.Off, controller.state)
            assertFalse(controller.busy)
        }

    @Test
    fun prepareFromGesture_asksTheChainToStartItsContext_onlyWhileUsable() =
        run {
            val rig = Rig()
            rig.controller.prepareFromGesture()
            assertEquals(1, rig.chain!!.prepareCalls)
            val unavailable = Rig(capability = NoiseSuppressionUnavailable.NO_AUDIO_WORKLET)
            unavailable.controller.prepareFromGesture()
            assertEquals(0, unavailable.chain!!.prepareCalls)
            val noChain = Rig(chain = null)
            noChain.controller.prepareFromGesture() // must not throw
            assertNull(noChain.currentChain)
        }

    @Test
    fun supportProbe_namesTheFirstMissingPiece() {
        assertNull(noiseSuppressionSupport(hasAudioWorklet = true, hasWebAssembly = true))
        assertEquals(NoiseSuppressionUnavailable.NO_AUDIO_WORKLET, noiseSuppressionSupport(hasAudioWorklet = false, hasWebAssembly = true))
        assertEquals(NoiseSuppressionUnavailable.NO_AUDIO_WORKLET, noiseSuppressionSupport(hasAudioWorklet = false, hasWebAssembly = false))
        assertEquals(NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY, noiseSuppressionSupport(hasAudioWorklet = true, hasWebAssembly = false))
    }
}
