package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.formTest
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private suspend fun settle() = delay(20)

internal fun fakeSoundContext(rejectResume: Boolean): dynamic {
    val build: dynamic =
        js(
            """(function (reject) {
                var ctx = {
                    state: 'suspended', currentTime: 0, destination: {},
                    resumeCalls: 0, closed: false, sources: [], gains: [],
                    resume: function () { ctx.resumeCalls++; return reject ? Promise.reject(new Error('blocked')) : Promise.resolve(); },
                    close: function () { ctx.closed = true; return Promise.reject(new Error('already closed')); },
                    createGain: function () {
                        var node = { connectedTo: null, events: [] };
                        node.gain = {
                            value: 1,
                            setValueAtTime: function (v, t) { node.events.push(['set', v, t]); },
                            linearRampToValueAtTime: function (v, t) { node.events.push(['ramp', v, t]); },
                            cancelScheduledValues: function (t) { node.events.push(['cancel', 0, t]); }
                        };
                        node.connect = function (target) { node.connectedTo = target; };
                        ctx.gains.push(node);
                        return node;
                    },
                    createBufferSource: function () {
                        var source = { buffer: null, started: false, startedAt: null, stops: [], onended: null, target: null };
                        source.connect = function (target) { source.target = target; };
                        source.start = function (t) { source.started = true; source.startedAt = t; };
                        source.stop = function (t) {
                            if (!source.started) throw new Error('InvalidStateError');
                            source.stops.push(t === undefined ? 'now' : t);
                        };
                        ctx.sources.push(source);
                        return source;
                    }
                };
                return ctx;
            })""",
        )
    return build(rejectResume)
}

/**
 * V1.9.97 -- the real WebAudio implementation against a fake context and a fake loader: what is loaded and when, which buffer sounds at which
 * gain, one voice at a time, refused loads, late buffers, hidden tabs, silencing, disposing, gestures and refused promises.
 */
class EncounterBellSoundTest {
    /** A loader whose promises the test settles by hand; it records every url it was asked for. */
    private class FakeSoundLoader {
        val urls = mutableListOf<String>()
        private val resolvers = mutableMapOf<String, (dynamic) -> Unit>()
        private val rejecters = mutableMapOf<String, (Throwable) -> Unit>()
        var throwing = false

        val loader =
            EncounterSoundLoader { _, url ->
                urls.add(url)
                if (throwing) throw IllegalStateException("no loader")
                Promise<dynamic> { resolve, reject ->
                    resolvers[url] = { value -> resolve(value) }
                    rejecters[url] = { error -> reject(error) }
                }
            }

        fun resolve(
            url: String,
            buffer: dynamic,
        ) = resolvers.getValue(url)(buffer)

        fun reject(url: String) = rejecters.getValue(url)(IllegalStateException("404"))
    }

    private class Rig(
        rejectResume: Boolean = false,
    ) {
        val ctx: dynamic = fakeSoundContext(rejectResume)
        val loader = FakeSoundLoader()
        var time = 0.0
        var visible = true
        var active = true
        var created = 0
        var sinks = 0
        val callBuffer: dynamic = js("({ id: 'call' })")
        val blessingBuffer: dynamic = js("({ id: 'blessing' })")
        val sound =
            BrowserEncounterBellSound(
                factory =
                    EncounterAudioContextFactory {
                        created += 1
                        ctx
                    },
                applySink = { sinks += 1 },
                hasBeenActive = { active },
                loader = loader.loader,
                now = { time },
                pageVisible = { visible },
            )

        fun sources(): Int = ctx.sources.length as Int

        fun source(index: Int): dynamic = ctx.sources[index]

        fun gain(index: Int): dynamic = ctx.gains[index]

        suspend fun loaded() {
            loader.resolve(ENCOUNTER_CALL_BELL_URL, callBuffer)
            loader.resolve(ENCOUNTER_BLESSING_BELL_URL, blessingBuffer)
            settle()
        }
    }

    @Test
    fun aRing_neverStartsALoad_beforeTheSoundWasSwitchedOnOrPrimed(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.ringCall()
            rig.sound.ringBlessing()
            settle()
            assertEquals(emptyList(), rig.loader.urls, "nothing is loaded by a ring")
            assertEquals(0, rig.sources())
        }

    @Test
    fun theProbe_loadsBothRecordingsInParallel_andPlaysTheBlessingSampleWithAFadeOut(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.probe()
            assertEquals(listOf(ENCOUNTER_CALL_BELL_URL, ENCOUNTER_BLESSING_BELL_URL), rig.loader.urls)
            assertEquals(1, rig.created, "the context is made synchronously inside the click")
            rig.loaded()
            assertEquals(1, rig.sources())
            assertSame(rig.blessingBuffer, rig.source(0).buffer)
            assertEquals(ENCOUNTER_BELL_PEAK_GAIN, rig.gain(0).gain.value as Double)
            assertSame(rig.ctx.destination, rig.gain(0).connectedTo)
            val start = rig.source(0).startedAt as Double
            val events = rig.gain(0).events
            assertEquals("ramp", events[1][0])
            assertEquals(0.0, events[1][1] as Double)
            assertEquals(start + ENCOUNTER_PROBE_LENGTH_S + ENCOUNTER_PROBE_FADE_S, events[1][2] as Double, 1e-9)
            assertEquals(start + ENCOUNTER_PROBE_LENGTH_S + ENCOUNTER_PROBE_FADE_S, rig.source(0).stops[0] as Double, 1e-9)
        }

    @Test
    fun theCall_playsItsBufferAtTheBellGain_theBlessingItsOwnAtALowerGain(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.loaded()
            rig.sound.ringCall()
            assertEquals(1, rig.sources())
            assertSame(rig.callBuffer, rig.source(0).buffer)
            assertEquals(ENCOUNTER_BELL_PEAK_GAIN, rig.gain(0).gain.value as Double)
            rig.source(0).onended()
            rig.sound.ringBlessing()
            assertEquals(2, rig.sources())
            assertSame(rig.blessingBuffer, rig.source(1).buffer)
            assertEquals(ENCOUNTER_BLESSING_GAIN, rig.gain(1).gain.value as Double)
            assertTrue(ENCOUNTER_BLESSING_GAIN < ENCOUNTER_BELL_PEAK_GAIN)
        }

    @Test
    fun aSecondCallOfTheSameKind_whileItSounds_isIgnored_andIsPossibleAgainWhenItEnded(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.loaded()
            rig.sound.ringCall()
            rig.sound.ringCall()
            assertEquals(1, rig.sources(), "no second source")
            rig.source(0).onended()
            rig.sound.ringCall()
            assertEquals(2, rig.sources(), "after the end a new call sounds again")
        }

    @Test
    fun aBlessingDuringTheCall_fadesTheCallOutFirst_andStartsAfterIt_neverTwoVoicesAtOnce(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.loaded()
            rig.time = 100.0
            rig.ctx.currentTime = 2.0
            rig.sound.ringCall()
            rig.ctx.currentTime = 3.0
            rig.sound.ringBlessing()
            assertEquals(2, rig.sources())
            // the call: a ramp to silence over the fade time and a stop at its end
            val ramp = rig.gain(0).events[rig.gain(0).events.length as Int - 1]
            assertEquals("ramp", ramp[0])
            assertEquals(0.0, ramp[1] as Double)
            assertEquals(3.0 + ENCOUNTER_SOUND_FADE_S, ramp[2] as Double, 1e-9)
            assertEquals(3.0 + ENCOUNTER_SOUND_FADE_S, rig.source(0).stops[0] as Double, 1e-9)
            // the blessing starts only after the call is gone
            assertTrue((rig.source(1).startedAt as Double) >= 3.0 + ENCOUNTER_SOUND_FADE_S)
            // the ended event of the faded call does not clear the running blessing: a second blessing is still ignored
            rig.source(0).onended()
            rig.sound.ringBlessing()
            assertEquals(2, rig.sources())
        }

    @Test
    fun aRefusedLoad_isSilent_neverRetriedByARing_andRetriedExactlyOnceByTheNextProbe(): Promise<Unit> =
        formTest {
            var unhandled = 0
            val listener: (Event) -> Unit = { unhandled += 1 }
            window.addEventListener("unhandledrejection", listener)
            try {
                val rig = Rig()
                rig.sound.probe()
                rig.loader.reject(ENCOUNTER_CALL_BELL_URL)
                rig.loader.reject(ENCOUNTER_BLESSING_BELL_URL)
                settle()
                rig.sound.ringCall()
                rig.sound.ringBlessing()
                settle()
                assertEquals(0, rig.sources(), "silent after a refusal")
                assertEquals(2, rig.loader.urls.size, "a ring never loads again")
                rig.sound.probe()
                assertEquals(4, rig.loader.urls.size, "one new attempt for each recording")
                rig.loaded()
                assertEquals(1, rig.sources(), "the retry worked, the sample sounds")
                delay(50)
                assertEquals(0, unhandled, "no unhandled rejection")
            } finally {
                window.removeEventListener("unhandledrejection", listener)
            }
        }

    @Test
    fun oneFailingRecording_leavesTheOtherPlayable(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.loader.reject(ENCOUNTER_CALL_BELL_URL)
            rig.loader.resolve(ENCOUNTER_BLESSING_BELL_URL, rig.blessingBuffer)
            settle()
            rig.sound.ringCall()
            assertEquals(0, rig.sources())
            rig.sound.ringBlessing()
            assertEquals(1, rig.sources())
        }

    @Test
    fun aLoaderThatThrowsOrAnswersNoBuffer_isSilentAndNeverAnException(): Promise<Unit> =
        formTest {
            val throwing = Rig()
            throwing.loader.throwing = true
            throwing.sound.prime()
            throwing.sound.probe()
            throwing.sound.ringCall()
            assertEquals(0, throwing.sources())
            val empty = Rig()
            empty.sound.prime()
            empty.loader.resolve(ENCOUNTER_CALL_BELL_URL, null)
            empty.loader.resolve(ENCOUNTER_BLESSING_BELL_URL, undefined)
            settle()
            empty.sound.ringCall()
            empty.sound.ringBlessing()
            assertEquals(0, empty.sources())
        }

    @Test
    fun aBufferThatArrivesTooLate_isDropped_oneInTimeIsPlayed(): Promise<Unit> =
        formTest {
            val late = Rig()
            late.sound.prime()
            late.sound.ringCall()
            late.time = ENCOUNTER_SOUND_MAX_LATENESS_MS + 100.0
            late.loaded()
            assertEquals(0, late.sources(), "later than the limit: the sign is long gone")
            val inTime = Rig()
            inTime.sound.prime()
            inTime.sound.ringCall()
            inTime.time = ENCOUNTER_SOUND_MAX_LATENESS_MS.toDouble()
            inTime.loaded()
            assertEquals(1, inTime.sources(), "exactly at the limit still plays")
            // a ready recording sounds at once whatever the clock says
            inTime.source(0).onended()
            inTime.time = 1_000_000.0
            inTime.sound.ringCall()
            assertEquals(2, inTime.sources())
        }

    @Test
    fun theNewestWaitingRequest_replacesTheOlder(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.sound.ringCall()
            rig.sound.ringBlessing()
            rig.loaded()
            assertEquals(1, rig.sources(), "only the newest waiting request sounds")
            assertSame(rig.blessingBuffer, rig.source(0).buffer)
        }

    @Test
    fun inAHiddenTab_nothingStarts_neitherNowNorWhenABufferArrivesLater(): Promise<Unit> =
        formTest {
            val hiddenNow = Rig()
            hiddenNow.sound.prime()
            hiddenNow.loaded()
            hiddenNow.visible = false
            hiddenNow.sound.ringCall()
            hiddenNow.sound.ringBlessing()
            assertEquals(0, hiddenNow.sources())
            val hiddenLater = Rig()
            hiddenLater.sound.prime()
            hiddenLater.sound.ringCall()
            hiddenLater.visible = false
            hiddenLater.loaded()
            assertEquals(0, hiddenLater.sources(), "the waiting request does not start in a hidden tab")
        }

    @Test
    fun silence_fadesTheVoiceOut_dropsAWaitingRequest_andKeepsTheLoadedRecordings(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.sound.ringCall()
            rig.sound.silence()
            rig.loaded()
            assertEquals(0, rig.sources(), "the waiting request was forgotten")
            rig.ctx.currentTime = 5.0
            rig.sound.ringCall()
            assertEquals(1, rig.sources(), "the recordings stayed loaded")
            rig.sound.silence()
            val events = rig.gain(0).events
            val ramp = events[events.length as Int - 1]
            assertEquals(0.0, ramp[1] as Double)
            assertEquals(5.0 + ENCOUNTER_SOUND_FADE_S, ramp[2] as Double, 1e-9)
            assertEquals(5.0 + ENCOUNTER_SOUND_FADE_S, rig.source(0).stops[0] as Double, 1e-9)
            rig.sound.ringCall()
            assertEquals(2, rig.sources(), "after a silence a new call sounds again")
        }

    @Test
    fun dispose_stopsAtOnce_closesTheContext_andALateBufferNeverPlays(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.loader.resolve(ENCOUNTER_CALL_BELL_URL, rig.callBuffer)
            settle()
            rig.sound.ringCall()
            rig.sound.ringBlessing() // waits for the blessing recording
            rig.sound.dispose()
            assertEquals("now", rig.source(0).stops[0], "stopped at once")
            assertTrue(rig.ctx.closed as Boolean)
            rig.loader.resolve(ENCOUNTER_BLESSING_BELL_URL, rig.blessingBuffer)
            settle()
            assertEquals(1, rig.sources(), "a late buffer plays nothing")
            rig.sound.ringCall()
            rig.sound.probe()
            assertEquals(1, rig.sources(), "nothing plays after dispose")
            assertEquals(2, rig.loader.urls.size, "and nothing is loaded after dispose")
        }

    @Test
    fun theSpeakerIsAppliedOnce_whenTheContextIsMade_andThereIsOneContext(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.sound.prime()
            rig.sound.probe()
            rig.sound.ringCall()
            assertEquals(1, rig.created)
            assertEquals(1, rig.sinks)
        }

    @Test
    fun withoutAUserActivation_noContextIsMade_andNothingIsLoaded(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.active = false
            rig.sound.prime()
            rig.sound.probe()
            rig.sound.ringCall()
            rig.sound.ringBlessing()
            settle()
            assertEquals(0, rig.created, "the browser would otherwise warn in the console")
            assertEquals(emptyList(), rig.loader.urls)
        }

    @Test
    fun aRefusedResume_isNeverAnUnhandledRejection(): Promise<Unit> =
        formTest {
            var unhandled = 0
            val listener: (Event) -> Unit = { unhandled += 1 }
            window.addEventListener("unhandledrejection", listener)
            try {
                val rig = Rig(rejectResume = true)
                rig.sound.prime()
                rig.loaded()
                rig.sound.ringCall()
                rig.sound.dispose() // close() rejects too
                delay(100)
                assertTrue((rig.ctx.resumeCalls as Int) >= 1)
                assertFalse(unhandled > 0, "every promise has a handler")
            } finally {
                window.removeEventListener("unhandledrejection", listener)
            }
        }

    @Test
    fun aFactoryThatThrowsOrAnswersNothing_isSilentAndNeverAnException(): Promise<Unit> =
        formTest {
            val throwing = BrowserEncounterBellSound(factory = { throw IllegalStateException("no WebAudio") }, hasBeenActive = { true })
            throwing.prime()
            throwing.ringCall()
            throwing.ringBlessing()
            throwing.probe()
            throwing.silence()
            throwing.dispose()
            val empty = BrowserEncounterBellSound(factory = { null }, hasBeenActive = { true })
            empty.ringCall()
            empty.dispose()
        }
}
