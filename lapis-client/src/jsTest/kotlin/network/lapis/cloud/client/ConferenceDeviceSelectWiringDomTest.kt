package network.lapis.cloud.client

import io.kvision.form.select.select
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.272 -- the wiring shared by the member screen and the guest view, driven without LiveKit. */
class ConferenceDeviceSelectWiringDomTest {
    private class Rig {
        var programmatic = false
        var failure: ConferenceDeviceFailure? = null
        val switches = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val applied = mutableListOf<String>()
    }

    private suspend fun withSelect(
        id: String,
        rig: Rig,
        block: suspend (HTMLSelectElement) -> Unit,
    ) {
        mountedForm(id) { root, element ->
            val select = root.select(options = listOf("a" to "A", "b" to "B"), value = "a", label = "Gerät")
            // KVision calls a new subscriber back at once with the current value; that is not a choice (the screens guard it the same way).
            rig.programmatic = true
            wireConferenceDeviceSelect(
                select = select,
                kind = ConferenceDeviceKind.MICROPHONE,
                switch = { _, deviceId ->
                    rig.switches += deviceId
                    rig.failure
                },
                activeId = { "a" },
                isProgrammatic = { rig.programmatic },
                setProgrammatic = { rig.programmatic = it },
                guarded = { it() },
                onFailure = { rig.failures += it },
                onApplied = { rig.applied += it },
            )
            kotlinx.coroutines.delay(60)
            rig.programmatic = false
            block(element().querySelector("select") as HTMLSelectElement)
        }
    }

    private fun HTMLSelectElement.choose(value: String) {
        this.value = value
        dispatchEvent(Event("change"))
    }

    @Test
    fun aUserChoice_switches_andReportsTheApplication(): Promise<Unit> =
        formTest {
            val rig = Rig()
            withSelect("wiring-ok", rig) { el ->
                el.choose("b")
                awaitUntil("applied") { rig.applied.isNotEmpty() }
                assertEquals(listOf("b"), rig.switches)
                assertEquals(listOf("b"), rig.applied)
                assertEquals(emptyList(), rig.failures)
            }
        }

    @Test
    fun aFailure_isReported_andTheSelectReverts_withoutASecondSwitch(): Promise<Unit> =
        formTest {
            val rig = Rig()
            rig.failure = ConferenceDeviceFailure.NOT_FOUND
            withSelect("wiring-fail", rig) { el ->
                el.choose("b")
                awaitUntil("failure") { rig.failures.isNotEmpty() }
                assertEquals(
                    listOf(conferenceDeviceErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.NOT_FOUND)),
                    rig.failures,
                )
                awaitUntil("reverted") { el.value == "a" }
                assertEquals(listOf("b"), rig.switches)
                assertEquals(emptyList(), rig.applied)
                assertEquals(false, rig.programmatic)
            }
        }

    @Test
    fun aProgrammaticAssignment_isNoChoice(): Promise<Unit> =
        formTest {
            val rig = Rig()
            withSelect("wiring-prog", rig) { el ->
                rig.programmatic = true
                el.choose("b")
                kotlinx.coroutines.delay(80)
                assertEquals(emptyList(), rig.switches)
            }
        }
}
