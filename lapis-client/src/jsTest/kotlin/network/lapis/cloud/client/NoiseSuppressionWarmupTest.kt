package network.lapis.cloud.client

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.124 -- the holder that starts the filter's audio context INSIDE the join click: it creates a context only when the filter would be
 * on anyway (a remembered choice wins over the instance default), at most one, hands it over exactly once, never hands over a closed one,
 * and stays quiet when the browser refuses (a throwing factory, a rejected `resume()`).
 */
class NoiseSuppressionWarmupTest {
    private var created = 0
    private var lastContext: dynamic = null

    private fun fakeContext(state: String = "suspended"): dynamic {
        val ctx: dynamic = js("({})")
        ctx.state = state
        ctx.resumeCalls = 0
        ctx.resume = {
            ctx.resumeCalls = ctx.resumeCalls + 1
            js("Promise").resolve(null)
        }
        return ctx
    }

    @BeforeTest
    fun setUp() {
        created = 0
        lastContext = null
        NoiseSuppressionWarmup.take() // empty the holder
        NoiseSuppressionWarmup.supportProbe = { null }
        NoiseSuppressionWarmup.contextFactory = {
            created++
            lastContext = fakeContext()
            lastContext
        }
    }

    @AfterTest
    fun tearDown() {
        NoiseSuppressionWarmup.take()
        NoiseSuppressionWarmup.supportProbe = ::noiseSuppressionSupportInThisBrowser
        NoiseSuppressionWarmup.contextFactory = ::browserNoiseAudioContextForTest
        NoiseSuppressionWarmup.remember(false)
    }

    @Test
    fun aContextIsCreated_onlyWhenTheFilterWouldBeOn_acrossTheWholeMatrix() {
        for (stored in listOf(true, false, null)) {
            for (default in listOf(true, false)) {
                NoiseSuppressionWarmup.take()
                created = 0
                val effective = stored ?: default
                NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = effective)
                assertEquals(if (effective) 1 else 0, created, "stored=$stored default=$default")
            }
        }
    }

    @Test
    fun aBrowserWithoutTheFeatures_createsNothing() {
        NoiseSuppressionWarmup.supportProbe = { NoiseSuppressionUnavailable.NO_AUDIO_WORKLET }
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true)
        assertEquals(0, created)
        assertNull(NoiseSuppressionWarmup.take())
    }

    @Test
    fun theContextIsResumedInsideTheGesture_reusedByTheNextClick_andHandedOverExactlyOnce() {
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true)
        val first = lastContext
        assertEquals(1, first.resumeCalls)
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true)
        assertEquals(1, created, "at most one context per page")
        assertEquals(2, first.resumeCalls, "the second click resumes the same one")
        assertSame(first, NoiseSuppressionWarmup.take())
        assertNull(NoiseSuppressionWarmup.take(), "handed over once; the holder is empty")
    }

    @Test
    fun aClosedContext_isNeverHandedOver_andIsReplacedByTheNextClick() {
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true)
        lastContext.state = "closed"
        assertNull(NoiseSuppressionWarmup.take())
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true)
        assertEquals(2, created)
        lastContext.state = "closed"
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true)
        assertEquals(3, created, "a context closed meanwhile is replaced, not reused")
        assertNotNull(NoiseSuppressionWarmup.take())
    }

    @Test
    fun aThrowingFactory_andARejectedResume_stayQuiet() {
        NoiseSuppressionWarmup.contextFactory = { throw IllegalStateException("no audio") }
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true) // must not throw
        assertNull(NoiseSuppressionWarmup.take())
        NoiseSuppressionWarmup.contextFactory = {
            val ctx = fakeContext()
            ctx.resume = { js("Promise").reject(js("new Error('refused')")) }
            ctx
        }
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true) // must not throw, no unhandled rejection
        assertTrue(NoiseSuppressionWarmup.take() != null)
    }

    @Test
    fun aFactoryThatAnswersNull_leavesTheHolderEmpty() {
        NoiseSuppressionWarmup.contextFactory = { null }
        NoiseSuppressionWarmup.unlockFromGesture(effectiveOn = true)
        assertNull(NoiseSuppressionWarmup.take())
    }
}

private fun browserNoiseAudioContextForTest(): dynamic =
    network.lapis.cloud.client.livekit
        .browserNoiseAudioContext()
