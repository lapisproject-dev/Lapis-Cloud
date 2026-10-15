package network.lapis.cloud.client

import network.lapis.cloud.client.encounter.EncounterAudioContextFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.133 -- the gong synthesis against a fake WebAudio context: quiet, three sine partials, a short attack and a long silent decay, nothing for a context that is not running. */
class ConferenceTimerGongPlayerTest {
    private fun player(ctx: dynamic) = BrowserJoinLeaveSoundPlayer(EncounterAudioContextFactory { ctx })

    private fun frequencies(ctx: dynamic): List<Double> =
        (0 until (ctx.oscillators.length as Int)).map {
            ctx.oscillators[it].frequency.value as Double
        }

    @Test
    fun theGong_hasThreeSinePartials_atTheSpecifiedFrequencies() {
        val ctx = fakeJoinLeaveContext("running")
        player(ctx).also {
            it.unlock()
            it.playTimerGong()
        }
        assertEquals(listOf(392.0, 392.0 * 2.01, 392.0 * 2.76), frequencies(ctx))
        for (i in 0 until (ctx.oscillators.length as Int)) {
            assertEquals("sine", ctx.oscillators[i].type)
            assertTrue(ctx.oscillators[i].stopped != null, "oscillator $i is stopped")
        }
    }

    @Test
    fun theSumOfThePartials_neverExceedsTheCap_andTheWeightsAreOneToPointFourToPointTwo() {
        val levels = timerGongPartials().map { it.second }
        assertEquals(TIMER_GONG_PEAK_GAIN, levels.sum(), 1e-9)
        assertTrue(levels.sum() <= 0.12 + 1e-9)
        assertEquals(1.0, levels[0] / levels[0], 1e-9)
        assertEquals(0.4, levels[1] / levels[0], 1e-9)
        assertEquals(0.2, levels[2] / levels[0], 1e-9)
        val ctx = fakeJoinLeaveContext("running")
        player(ctx).also {
            it.unlock()
            it.playTimerGong()
        }
        var sum = 0.0
        for (i in 0 until (ctx.gains.length as Int)) {
            val events = ctx.gains[i].events
            for (j in 0 until (events.length as Int)) if (events[j][0] == "linear") sum += events[j][1] as Double
        }
        assertTrue(sum <= TIMER_GONG_PEAK_GAIN + 1e-9, "the simultaneous sum is $sum")
    }

    @Test
    fun theEnvelope_isAFiveMillisecondAttack_thenAnExponentialDecayOfAboutTwoSeconds() {
        val ctx = fakeJoinLeaveContext("running")
        player(ctx).also {
            it.unlock()
            it.playTimerGong()
        }
        for (i in 0 until (ctx.gains.length as Int)) {
            val events = ctx.gains[i].events
            assertEquals("set", events[0][0])
            assertEquals("linear", events[1][0])
            assertEquals(TIMER_GONG_ATTACK_S, (events[1][2] as Double) - (events[0][2] as Double), 1e-9)
            assertEquals("exp", events[2][0])
            assertEquals(TIMER_GONG_DECAY_S, (events[2][2] as Double) - (events[0][2] as Double), 1e-9)
            assertTrue((events[2][1] as Double) < 0.001, "ends in silence")
        }
        assertEquals(2.2, TIMER_GONG_DECAY_S, 1e-9)
    }

    @Test
    fun aContextThatIsNotRunning_dropsTheGong_neverQueuesIt() {
        val ctx = fakeJoinLeaveContext("suspended", rejectResume = true)
        player(ctx).also {
            it.unlock()
            it.playTimerGong()
        }
        assertEquals(0, ctx.oscillators.length as Int, "no tone for a context that does not run")
    }

    @Test
    fun theConfirmation_playsOnceTheContextHasResumed(): kotlin.js.Promise<Unit> {
        val ctx = fakeJoinLeaveContext("suspended")
        val p = player(ctx)
        p.playTimerGong(confirmation = true)
        // the resume is a promise: the gong follows right after it resolved
        return kotlin.js.Promise.resolve(Unit).then {
            assertEquals(3, ctx.oscillators.length as Int)
        }
    }

    @Test
    fun aFailingContext_neverThrows() {
        val build: dynamic =
            js(
                """(function () {
                    var ctx = { state: 'running' };
                    Object.defineProperty(ctx, 'currentTime', { get: function () { throw new Error('boom'); } });
                    return ctx;
                })""",
            )
        val broken: dynamic = build()
        val p = player(broken)
        p.unlock()
        p.playTimerGong()
        p.playTimerGong(confirmation = true)
    }
}
