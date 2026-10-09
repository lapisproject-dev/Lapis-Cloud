package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.coroutines.async
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.91 -- the speaker of the encounter room without a room: one sink id for the elements of BOTH hosts, all or nothing, a late
 * element gets the chosen device, nothing happens without the browser API, and a device that went away makes the room fall back once.
 */
class EncounterAudioOutputTest {
    private fun audio(): HTMLElement = document.createElement("audio") as HTMLElement

    private fun options(vararg ids: String) = ids.map { ConferenceDeviceOption(it, "Name $it") }

    private fun output(
        env: EncounterDeviceEnvironment,
        elements: MutableList<HTMLElement>,
        fellBack: MutableList<EncounterOutputFallback> = mutableListOf(),
    ) = EncounterAudioOutput(env = env, elements = { elements.toList() }, onFellBack = { fellBack += it })

    // ── the pure list filter ─────────────────────────────────────────────────

    @Test
    fun usableOutputs_dropBlankIdsAndTheDefaultAlias_andKeepTheOrder() {
        val raw = options("", "default", "spk-1", " ", "spk-2")
        assertEquals(listOf("spk-1", "spk-2"), encounterUsableOutputs(raw).map { it.deviceId })
        assertTrue(encounterUsableOutputs(options("", "default")).isEmpty())
    }

    // ── select ───────────────────────────────────────────────────────────────

    @Test
    fun select_setsTheSinkOnEveryElementOfBothHosts_andRemembersIt(): Promise<Unit> =
        formTest {
            val env = FakeDeviceEnvironment()
            val pulpit = audio()
            val table = audio()
            val output = output(env, mutableListOf(pulpit, table))
            assertNull(output.sinkId, "the system default until somebody chooses")
            assertEquals(EncounterOutputResult.APPLIED, output.select("spk-1"))
            assertEquals("spk-1", output.sinkId)
            assertEquals(2, env.sinkCalls.size)
            assertTrue(env.sinkCalls.all { it.second == "spk-1" })
            assertTrue(env.sinkCalls.any { it.first === pulpit } && env.sinkCalls.any { it.first === table })
            // back to the system default: an empty sink id, and the choice is null
            assertEquals(EncounterOutputResult.APPLIED, output.select(null))
            assertNull(output.sinkId)
            assertEquals("", env.sinkCalls.last().second)
        }

    @Test
    fun aSinkThatOneElementRefuses_putsEveryElementBackOnThePreviousDevice(): Promise<Unit> =
        formTest {
            val env = FakeDeviceEnvironment()
            val a = audio()
            val b = audio()
            val output = output(env, mutableListOf(a, b))
            assertEquals(EncounterOutputResult.APPLIED, output.select("spk-1"))
            env.sinkCalls.clear()
            env.failSinkFor += "spk-2"
            env.failElements += b
            assertEquals(EncounterOutputResult.REVERTED_TO_PREVIOUS, output.select("spk-2"))
            assertEquals("spk-1", output.sinkId, "the choice did not change")
            // a and b both ended on the previous device: no half state
            assertEquals("spk-1", env.sinkCalls.last { it.first === a }.second)
            assertEquals("spk-1", env.sinkCalls.last { it.first === b }.second)
        }

    @Test
    fun ifEvenGoingBackFails_everyElementFallsBackToTheSystemDefault(): Promise<Unit> =
        formTest {
            val env = FakeDeviceEnvironment()
            val a = audio()
            val b = audio()
            val output = output(env, mutableListOf(a, b))
            assertEquals(EncounterOutputResult.APPLIED, output.select("spk-1"))
            env.sinkCalls.clear()
            env.failSinkFor += setOf("spk-2", "spk-1")
            env.failElements += b
            assertEquals(EncounterOutputResult.FELL_BACK_TO_DEFAULT, output.select("spk-2"))
            assertNull(output.sinkId)
            assertEquals("", env.sinkCalls.last { it.first === a }.second)
            assertEquals("", env.sinkCalls.last { it.first === b }.second)
        }

    @Test
    fun withoutTheBrowserApi_nothingIsCalled(): Promise<Unit> =
        formTest {
            val env = FakeDeviceEnvironment(sinkApi = false, outputs = options("spk-1", "spk-2"))
            val element = audio()
            val output = output(env, mutableListOf(element))
            assertFalse(output.available)
            assertEquals(EncounterOutputResult.UNSUPPORTED, output.select("spk-1"))
            output.apply(element)
            assertTrue(output.listOutputs().isEmpty(), "no list without the API")
            assertTrue(env.sinkCalls.isEmpty())
            assertNull(output.sinkId)
        }

    @Test
    fun anElementThatArrivesDuringTheSwitch_getsTheChosenDevice(): Promise<Unit> =
        formTest {
            val fake = FakeDeviceEnvironment()
            val elements = mutableListOf(audio())
            val late = audio()
            var added = false
            val env =
                object : EncounterDeviceEnvironment by fake {
                    override suspend fun setSink(
                        element: HTMLElement,
                        sinkId: String,
                    ): Boolean {
                        if (!added) {
                            added = true
                            elements += late
                        }
                        return fake.setSink(element, sinkId)
                    }
                }
            val output = output(env, elements)
            assertEquals(EncounterOutputResult.APPLIED, output.select("spk-1"))
            assertTrue(fake.sinkCalls.any { it.first === late && it.second == "spk-1" }, "the late element was not left on the old device")
        }

    @Test
    fun anElementAddedWhileTheSwitchRuns_endsOnTheNewDevice_notOnTheOldOne(): Promise<Unit> =
        formTest {
            val fake = FakeDeviceEnvironment()
            val first = audio()
            val elements = mutableListOf(first)
            val env =
                object : EncounterDeviceEnvironment by fake {
                    override suspend fun setSink(
                        element: HTMLElement,
                        sinkId: String,
                    ): Boolean {
                        if (element === first && sinkId == "spk-2") kotlinx.coroutines.delay(100)
                        return fake.setSink(element, sinkId)
                    }
                }
            val output = output(env, elements)
            assertEquals(EncounterOutputResult.APPLIED, output.select("spk-1"))
            val switching = AppScope.async { output.select("spk-2") }
            kotlinx.coroutines.delay(30)
            // the switch is running (stuck on the first element); a new element arrives, sinkId still says spk-1
            val late = audio()
            elements += late
            output.apply(late)
            assertEquals(EncounterOutputResult.APPLIED, switching.await())
            kotlinx.coroutines.delay(50)
            assertEquals("spk-2", fake.sinkCalls.last { it.first === late }.second, "the late element ends on the room's device")
        }

    // ── apply (a new element) ────────────────────────────────────────────────

    @Test
    fun aNewElement_getsTheChosenSink_butNothingWithoutAChoice(): Promise<Unit> =
        formTest {
            val env = FakeDeviceEnvironment()
            val elements = mutableListOf<HTMLElement>()
            val output = output(env, elements)
            val first = audio()
            elements += first
            output.apply(first)
            kotlinx.coroutines.delay(50)
            assertTrue(env.sinkCalls.isEmpty(), "no choice, nothing to set")
            assertEquals(EncounterOutputResult.APPLIED, output.select("spk-1"))
            env.sinkCalls.clear()
            val second = audio()
            elements += second
            output.apply(second)
            awaitUntil("the new element got the sink") { env.sinkCalls.any { it.first === second && it.second == "spk-1" } }
        }

    @Test
    fun aDeviceThatWentAway_makesTheRoomFallBackToTheDefaultOnce(): Promise<Unit> =
        formTest {
            val env = FakeDeviceEnvironment()
            val elements = mutableListOf(audio())
            val fellBack = mutableListOf<EncounterOutputFallback>()
            val output = output(env, elements, fellBack)
            assertEquals(EncounterOutputResult.APPLIED, output.select("spk-1"))
            env.failSinkFor += "spk-1"
            val fresh = audio()
            elements += fresh
            output.apply(fresh)
            awaitUntil("the room fell back") { fellBack.isNotEmpty() }
            assertEquals(listOf(EncounterOutputFallback.DEVICE_GONE), fellBack)
            assertNull(output.sinkId)
        }
}
