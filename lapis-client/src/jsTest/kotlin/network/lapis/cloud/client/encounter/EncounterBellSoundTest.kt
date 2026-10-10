package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.formTest
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.96 -- the real WebAudio implementation against a fake context: counts, gestures, rejected promises, failing factories, closing. */
class EncounterBellSoundTest {
    private fun fakeContext(rejectResume: Boolean): dynamic {
        val build: dynamic =
            js(
                """(function (reject) {
                    var ctx = {
                        state: 'suspended', currentTime: 0, destination: {},
                        resumeCalls: 0, oscillators: 0, started: 0, stopped: 0, closed: false, gains: 0, masterNode: null,
                        resume: function () { ctx.resumeCalls++; return reject ? Promise.reject(new Error('blocked')) : Promise.resolve(); },
                        close: function () { ctx.closed = true; return Promise.reject(new Error('already closed')); },
                        createGain: function () {
                            ctx.gains++;
                            var node = { gain: { value: 1, setValueAtTime: function () {}, linearRampToValueAtTime: function () {}, exponentialRampToValueAtTime: function () {} } };
                            node.connect = function (target) { if (target === ctx.destination) ctx.masterNode = node; };
                            return node;
                        },
                        createOscillator: function () {
                            ctx.oscillators++;
                            return { type: '', frequency: { value: 0 }, connect: function () {}, start: function () { ctx.started++; }, stop: function () { ctx.stopped++; } };
                        }
                    };
                    return ctx;
                })""",
            )
        return build(rejectResume)
    }

    private fun sound(
        ctx: dynamic,
        active: Boolean = true,
        created: IntArray = IntArray(1),
        sink: (dynamic) -> Unit = {},
    ) = BrowserEncounterBellSound(
        factory =
            EncounterAudioContextFactory {
                created[0] = created[0] + 1
                ctx
            },
        applySink = sink,
        hasBeenActive = { active },
    )

    @Test
    fun aRing_isThreeStrikesOfSevenPartials_aProbeIsOne_andTheMasterStaysQuiet(): Promise<Unit> =
        formTest {
            val ctx = fakeContext(rejectResume = false)
            val bell = sound(ctx)
            bell.probe()
            assertEquals(ENCOUNTER_BELL_PARTIAL_RATIOS.size, ctx.oscillators as Int, "a probe is one strike")
            bell.ring()
            assertEquals(ENCOUNTER_BELL_PARTIAL_RATIOS.size * (1 + ENCOUNTER_BELL_STRIKES), ctx.oscillators as Int)
            assertEquals(ctx.oscillators as Int, ctx.started as Int, "every oscillator is started ...")
            assertEquals(ctx.oscillators as Int, ctx.stopped as Int, "... and stopped")
            assertTrue((ctx.masterNode.gain.value as Double) <= 1.0)
            assertEquals(ENCOUNTER_BELL_PEAK_GAIN, ctx.masterNode.gain.value as Double)
        }

    @Test
    fun aRefusedResume_isNeverAnUnhandledRejection(): Promise<Unit> =
        formTest {
            var unhandled = 0
            val listener: (Event) -> Unit = { unhandled++ }
            window.addEventListener("unhandledrejection", listener)
            try {
                val ctx = fakeContext(rejectResume = true)
                val bell = sound(ctx)
                bell.prime()
                bell.ring()
                bell.dispose() // close() rejects too
                delay(100)
                assertTrue((ctx.resumeCalls as Int) >= 1)
                assertEquals(0, unhandled, "every promise has a handler")
            } finally {
                window.removeEventListener("unhandledrejection", listener)
            }
        }

    @Test
    fun aFactoryThatThrowsOrAnswersNothing_isSilentAndNeverAnException(): Promise<Unit> =
        formTest {
            val throwing = BrowserEncounterBellSound(factory = { throw IllegalStateException("no WebAudio") }, hasBeenActive = { true })
            throwing.prime()
            throwing.ring()
            throwing.probe()
            throwing.dispose()
            val empty = BrowserEncounterBellSound(factory = { null }, hasBeenActive = { true })
            empty.ring()
            empty.dispose()
        }

    @Test
    fun withoutAUserActivation_noContextIsCreated(): Promise<Unit> =
        formTest {
            val created = IntArray(1)
            val bell = sound(fakeContext(false), active = false, created = created)
            bell.prime()
            bell.ring()
            bell.probe()
            assertEquals(0, created[0], "the browser would otherwise warn in the console")
        }

    @Test
    fun oneContextPerRoom_itIsPointedAtTheChosenSpeakerOnce_andClosedOnDispose(): Promise<Unit> =
        formTest {
            val ctx = fakeContext(false)
            val created = IntArray(1)
            var sinks = 0
            val bell = sound(ctx, created = created, sink = { sinks++ })
            bell.ring()
            bell.ring()
            bell.probe()
            assertEquals(1, created[0], "one context")
            assertEquals(1, sinks, "the speaker is applied when the context is made")
            bell.dispose()
            assertTrue(ctx.closed as Boolean)
            val before = ctx.oscillators as Int
            bell.ring()
            assertEquals(before, ctx.oscillators as Int, "nothing plays after dispose")
        }
}
