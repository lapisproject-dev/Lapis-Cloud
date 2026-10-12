package network.lapis.cloud.client

import network.lapis.cloud.client.encounter.EncounterAudioContextFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A WebAudio context that records every node and every scheduled value; `state` is set by the test. */
internal fun fakeJoinLeaveContext(
    state: String,
    rejectResume: Boolean = false,
    withSinkId: Boolean = true,
    throwingSinkId: Boolean = false,
): dynamic {
    val build: dynamic =
        js(
            """(function (state, reject, withSink, throwingSink) {
                var ctx = {
                    state: state, currentTime: 1, destination: {}, resumeCalls: 0, closed: false, oscillators: [], gains: [], sinkIds: [],
                    resume: function () {
                        ctx.resumeCalls++;
                        if (reject) return Promise.reject(new Error('blocked'));
                        ctx.state = 'running';
                        return Promise.resolve();
                    },
                    close: function () { ctx.closed = true; return Promise.reject(new Error('already closed')); },
                    createGain: function () {
                        var node = { events: [] };
                        node.gain = {
                            setValueAtTime: function (v, t) { node.events.push(['set', v, t]); },
                            linearRampToValueAtTime: function (v, t) { node.events.push(['linear', v, t]); },
                            exponentialRampToValueAtTime: function (v, t) { node.events.push(['exp', v, t]); }
                        };
                        node.connect = function (target) { node.target = target; };
                        ctx.gains.push(node);
                        return node;
                    },
                    createOscillator: function () {
                        var osc = { type: null, frequency: { value: 0 }, started: null, stopped: null };
                        osc.connect = function (target) { osc.target = target; };
                        osc.start = function (t) { osc.started = t; };
                        osc.stop = function (t) { osc.stopped = t; };
                        ctx.oscillators.push(osc);
                        return osc;
                    }
                };
                if (withSink) {
                    ctx.setSinkId = function (id) {
                        ctx.sinkIds.push(id);
                        if (throwingSink) throw new Error('no sink');
                        return Promise.resolve();
                    };
                }
                return ctx;
            })""",
        )
    return build(state, rejectResume, withSinkId, throwingSinkId)
}

/**
 * V1.9.120 -- the real WebAudio implementation against a fake context: it never throws, never queues a tone for a context that is not
 * running, keeps the level under the cap, plays the mirrored tones in the specified order and follows the chosen speaker.
 */
class ConferenceJoinLeaveSoundPlayerTest {
    private fun player(ctx: dynamic) = BrowserJoinLeaveSoundPlayer(EncounterAudioContextFactory { ctx })

    private fun frequencies(ctx: dynamic): List<Double> =
        (0 until (ctx.oscillators.length as Int)).map {
            ctx.oscillators[it].frequency.value as Double
        }

    /** Highest gain value ever ramped to, summed over the oscillators that share a start time (they sound together). */
    private fun peakOfSimultaneousVoices(ctx: dynamic): Double {
        val byStart = mutableMapOf<Double, Double>()
        for (i in 0 until (ctx.gains.length as Int)) {
            val events = ctx.gains[i].events
            var level = 0.0
            var start = 0.0
            for (j in 0 until (events.length as Int)) {
                val e = events[j]
                if (e[0] == "set") start = e[2] as Double
                if (e[0] == "linear") level = maxOf(level, e[1] as Double)
            }
            byStart[start] = (byStart[start] ?: 0.0) + level
        }
        return byStart.values.max()
    }

    @Test
    fun theJoinTone_risesFromTheLowToTheHighNote_withTwoOscillatorsPerNote() {
        val ctx = fakeJoinLeaveContext("running")
        val p = player(ctx)
        p.unlock()
        p.playJoin()
        assertEquals(listOf(659.25, 1318.5, 880.0, 1760.0), frequencies(ctx))
        assertEquals("sine", ctx.oscillators[0].type)
        assertEquals(1.0 + JOIN_LEAVE_START_DELAY_S, ctx.oscillators[0].started as Double, 1e-9)
        assertEquals(1.0 + JOIN_LEAVE_START_DELAY_S + JOIN_LEAVE_FIRST_TONE_S, ctx.oscillators[2].started as Double, 1e-9)
    }

    @Test
    fun theLeaveTone_isTheMirroredPair() {
        val ctx = fakeJoinLeaveContext("running")
        val p = player(ctx)
        p.unlock()
        p.playLeave()
        assertEquals(listOf(880.0, 1760.0, 659.25, 1318.5), frequencies(ctx))
    }

    @Test
    fun theLevel_staysUnderTheCap_andTheLeaveIsQuieterThanTheJoin() {
        val join = fakeJoinLeaveContext("running")
        player(join).also {
            it.unlock()
            it.playJoin()
        }
        val leave = fakeJoinLeaveContext("running")
        player(leave).also {
            it.unlock()
            it.playLeave()
        }
        assertTrue(peakOfSimultaneousVoices(join) <= JOIN_SOUND_PEAK_GAIN + 1e-9)
        assertTrue(peakOfSimultaneousVoices(leave) <= LEAVE_SOUND_PEAK_GAIN + 1e-9)
        assertTrue(peakOfSimultaneousVoices(leave) < peakOfSimultaneousVoices(join))
    }

    @Test
    fun everyToneIsStoppedAndEndsInASilentDecay() {
        val ctx = fakeJoinLeaveContext("running")
        player(ctx).also {
            it.unlock()
            it.playJoin()
        }
        for (i in 0 until (ctx.oscillators.length as Int)) assertTrue(ctx.oscillators[i].stopped != null, "oscillator $i is stopped")
        for (i in 0 until (ctx.gains.length as Int)) {
            val events = ctx.gains[i].events
            assertEquals("exp", events[(events.length as Int) - 1][0])
            assertTrue((events[(events.length as Int) - 1][1] as Double) < 0.001)
        }
    }

    @Test
    fun aToneForAContextThatIsNotRunning_isDropped_notQueued() {
        val ctx = fakeJoinLeaveContext("suspended", rejectResume = true)
        val p = player(ctx)
        p.unlock()
        p.playJoin()
        p.playLeave()
        assertEquals(0, ctx.oscillators.length as Int, "nothing is scheduled for a context the browser has not let start")
    }

    @Test
    fun withoutUnlock_nothingSounds_andNothingThrows() {
        val ctx = fakeJoinLeaveContext("running")
        val p = player(ctx)
        p.playJoin()
        p.playLeave()
        assertEquals(0, ctx.oscillators.length as Int)
    }

    @Test
    fun neverThrows_whenTheFactoryGivesNull_throws_orResumeIsRejected() {
        val nullFactory = BrowserJoinLeaveSoundPlayer(EncounterAudioContextFactory { null })
        val throwingFactory = BrowserJoinLeaveSoundPlayer(EncounterAudioContextFactory { error("no audio") })
        val rejecting = player(fakeJoinLeaveContext("suspended", rejectResume = true))
        for (p in listOf(nullFactory, throwingFactory, rejecting)) {
            p.unlock()
            p.playJoin()
            p.playLeave()
            p.playProbe()
            p.setSink("speaker-1")
            p.dispose()
            p.unlock()
            p.playJoin()
        }
    }

    @Test
    fun theProbe_playsTheJoinToneOnce_afterTheContextStarted() {
        val ctx = fakeJoinLeaveContext("suspended")
        val p = player(ctx)
        p.unlock()
        p.playProbe()
        // the fake's resume() already put it to "running" synchronously; the probe played the join tone
        assertEquals(4, ctx.oscillators.length as Int)
        assertEquals(659.25, frequencies(ctx).first())
    }

    @Test
    fun theSpeakerChoice_isAppliedToAnExistingAndToALaterContext_andAFailureIsSwallowed() {
        val early = fakeJoinLeaveContext("running")
        val p = player(early)
        p.unlock()
        p.setSink("speaker-1")
        assertEquals("speaker-1", early.sinkIds[0])

        val late = fakeJoinLeaveContext("running", throwingSinkId = true)
        val q = player(late)
        q.setSink("speaker-2")
        q.unlock()
        assertEquals("speaker-2", late.sinkIds[0])
        q.playJoin()
        assertEquals(4, late.oscillators.length as Int, "a failing setSinkId does not silence the tone")
    }

    @Test
    fun aContextWithoutSetSinkId_isFine() {
        val ctx = fakeJoinLeaveContext("running", withSinkId = false)
        val p = player(ctx)
        p.setSink("speaker-1")
        p.unlock()
        p.playJoin()
        assertEquals(4, ctx.oscillators.length as Int)
    }

    @Test
    fun dispose_closesTheContext_andLaterTonesAreSilent() {
        val ctx = fakeJoinLeaveContext("running")
        val p = player(ctx)
        p.unlock()
        p.dispose()
        assertTrue(ctx.closed as Boolean)
        p.playJoin()
        assertEquals(0, ctx.oscillators.length as Int)
        assertFalse(ctx.resumeCalls as Int > 0)
    }

    @Test
    fun theTone_soundsInAHiddenTab_thereIsNoVisibilityCheck() {
        // the player has no notion of visibility at all (unlike the bell of the encounter room): a running context always sounds
        val ctx = fakeJoinLeaveContext("running")
        val p = player(ctx)
        p.unlock()
        p.playJoin()
        assertTrue((ctx.oscillators.length as Int) > 0)
    }
}
