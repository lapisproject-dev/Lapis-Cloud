package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import network.lapis.cloud.client.livekit.DeviceEnumeration
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.272 -- the device section of the guest call view in a REAL mounted root, with a fake call and a fake permission provider. */
class EventGuestDevicePanelDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private class FakePort(
        var mics: List<ConferenceDeviceOption> = listOf(ConferenceDeviceOption("m1", "Mic One"), ConferenceDeviceOption("m2", "Mic Two")),
        var cameras: List<ConferenceDeviceOption> =
            listOf(ConferenceDeviceOption("c1", "Cam One"), ConferenceDeviceOption("c2", "Cam Two")),
        var speakers: List<ConferenceDeviceOption> = emptyList(),
        var rawOverride: Map<ConferenceDeviceKind, Int> = emptyMap(),
    ) : GuestDevicePort {
        override var publishesMedia = true
        var failure: ConferenceDeviceFailure? = null
        var throwOnList = false
        val switches = mutableListOf<Pair<ConferenceDeviceKind, String>>()
        val actives = mutableMapOf<ConferenceDeviceKind, String?>()

        override suspend fun list(kind: ConferenceDeviceKind): DeviceEnumeration {
            if (throwOnList) throw IllegalStateException("no enumerateDevices")
            val all =
                when (kind) {
                    ConferenceDeviceKind.MICROPHONE -> mics
                    ConferenceDeviceKind.CAMERA -> cameras
                    ConferenceDeviceKind.SPEAKER -> speakers
                }
            return DeviceEnumeration(all.filter { it.deviceId.isNotBlank() }, rawOverride[kind] ?: all.size)
        }

        override fun active(kind: ConferenceDeviceKind): String? = actives[kind]

        override suspend fun switch(
            kind: ConferenceDeviceKind,
            deviceId: String,
        ): ConferenceDeviceFailure? {
            switches += kind to deviceId
            return failure
        }
    }

    private class FakeProvider(
        var status: DevicePermissionStatus = DevicePermissionStatus.GRANTED,
        var result: DevicePermissionRequestResult = DevicePermissionRequestResult.Allowed(true, true),
    ) : DevicePermissionProvider {
        var requests = 0
        var unregistered = 0

        override suspend fun query(kind: ConferenceDeviceKind) = status

        override suspend fun request(
            audio: Boolean,
            video: Boolean,
        ): DevicePermissionRequestResult {
            requests++
            return result
        }

        override fun onChange(
            kind: ConferenceDeviceKind,
            listener: () -> Unit,
        ): () -> Unit = { unregistered++ }
    }

    private fun opt(
        id: String,
        label: String = "",
    ) = ConferenceDeviceOption(id, label)

    private fun HTMLElement.selects(): List<HTMLSelectElement> = allOf("select").map { it as HTMLSelectElement }

    private fun HTMLElement.selectLabelled(text: String): HTMLSelectElement {
        val label = assertNotNull(allOf("label").firstOrNull { it.textContent?.trim() == text }, "no label '$text'")
        val id = assertNotNull(label.getAttribute("for"), "label '$text' has no for")
        return assertNotNull(querySelector("select[id='$id']") as? HTMLSelectElement, "label '$text' points at no select")
    }

    private fun HTMLSelectElement.choose(value: String) {
        this.value = value
        dispatchEvent(Event("change"))
    }

    private fun HTMLSelectElement.optionValues(): List<String> =
        (0 until options.length).map { options.item(it)!!.asDynamic().value as String }

    private suspend fun settle() {
        delay(60)
    }

    private class Harness(
        val port: FakePort,
        val provider: FakeProvider,
        val notes: MutableList<String> = mutableListOf(),
        var cameraSwitched: Int = 0,
    )

    private suspend fun withPanel(
        id: String,
        port: FakePort = FakePort(),
        provider: FakeProvider = FakeProvider(),
        sinkApi: Boolean = false,
        block: suspend (EventGuestDevicePanel, Harness, () -> HTMLElement) -> Unit,
    ) {
        val h = Harness(port, provider)
        mountedForm(id) { root, element ->
            assertTrue(stylesLoaded)
            val panel =
                EventGuestDevicePanel(
                    parent = root,
                    port = port,
                    permission = provider,
                    sinkApi = sinkApi,
                    onNote = { h.notes += it },
                    onCameraSwitched = { h.cameraSwitched++ },
                    initiallyOpen = true,
                )
            try {
                panel.onConnected()
                awaitUntil("first enumeration") {
                    element().selects().isNotEmpty() &&
                        element().selectLabelled("Mikrofon").optionValues().isNotEmpty()
                }
                settle()
                block(panel, h, element)
            } finally {
                panel.dispose()
            }
        }
    }

    @Test
    fun microphoneAndCamera_haveVisibleLabels_andTheDevicesAsOptions(): Promise<Unit> =
        formTest {
            withPanel("guest-dev-render") { _, _, element ->
                val mic = element().selectLabelled("Mikrofon")
                val cam = element().selectLabelled("Kamera")
                assertEquals(listOf("m1", "m2"), mic.optionValues())
                assertEquals(listOf("c1", "c2"), cam.optionValues())
                assertEquals(null, element().allOf("label").firstOrNull { it.textContent?.trim() == "Lautsprecher" })
            }
        }

    @Test
    fun aUserChoice_switchesExactlyOnce_aProgrammaticAssignmentNever(): Promise<Unit> =
        formTest {
            withPanel("guest-dev-switch") { panel, h, element ->
                panel.syncActive(ConferenceDeviceKind.MICROPHONE, "m2")
                settle()
                assertEquals(emptyList(), h.port.switches, "a programmatic sync is no choice")
                assertEquals("m2", element().selectLabelled("Mikrofon").value)
                element().selectLabelled("Mikrofon").choose("m1")
                awaitUntil("one switch") { h.port.switches.size == 1 }
                settle()
                assertEquals(listOf(ConferenceDeviceKind.MICROPHONE to "m1"), h.port.switches)
                assertEquals(0, h.cameraSwitched, "a microphone switch does not touch the preview")
            }
        }

    @Test
    fun syncActive_ignoresBlankIdsAndIdsNotInTheList(): Promise<Unit> =
        formTest {
            withPanel("guest-dev-sync") { panel, h, element ->
                val before = element().selectLabelled("Kamera").value
                panel.syncActive(ConferenceDeviceKind.CAMERA, "")
                panel.syncActive(ConferenceDeviceKind.CAMERA, "ghost")
                settle()
                assertEquals(before, element().selectLabelled("Kamera").value)
                assertEquals(emptyList(), h.port.switches)
            }
        }

    @Test
    fun aFailedSwitch_revertsTheSelect_andSaysSoInTheNote(): Promise<Unit> =
        formTest {
            val port = FakePort()
            port.actives[ConferenceDeviceKind.MICROPHONE] = "m1"
            withPanel("guest-dev-fail", port) { _, h, element ->
                assertEquals("m1", element().selectLabelled("Mikrofon").value)
                port.failure = ConferenceDeviceFailure.DEVICE_IN_USE
                element().selectLabelled("Mikrofon").choose("m2")
                awaitUntil("note") { h.notes.isNotEmpty() }
                assertEquals(
                    conferenceDeviceErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.DEVICE_IN_USE),
                    h.notes.single(),
                )
                awaitUntil("reverted") { element().selectLabelled("Mikrofon").value == "m1" }
                assertEquals(1, port.switches.size, "the revert is no second switch")
            }
        }

    @Test
    fun aCameraSwitch_reattachesThePreview_aFailedOneDoesNot(): Promise<Unit> =
        formTest {
            withPanel("guest-dev-camera") { _, h, element ->
                element().selectLabelled("Kamera").choose("c2")
                awaitUntil("preview") { h.cameraSwitched == 1 }
                h.port.failure = ConferenceDeviceFailure.OTHER
                element().selectLabelled("Kamera").choose("c1")
                awaitUntil("second switch") { h.port.switches.size == 2 }
                settle()
                assertEquals(1, h.cameraSwitched)
            }
        }

    @Test
    fun withoutPermission_theSelectsShowThePlaceholder_andTheButtonIsThere_andNothingIsRequested(): Promise<Unit> =
        formTest {
            val port = FakePort(mics = listOf(opt(""), opt("")), cameras = listOf(opt("")))
            val provider = FakeProvider(status = DevicePermissionStatus.PROMPT)
            withPanel("guest-dev-prompt", port, provider) { _, _, element ->
                val mic = element().selectLabelled("Mikrofon")
                assertEquals("true", mic.getAttribute("aria-disabled"))
                assertEquals(
                    "Erst nach Freigabe verfügbar",
                    mic.options
                        .item(0)!!
                        .textContent
                        ?.trim(),
                )
                assertEquals(
                    "Zugriff auf Mikrofon und Kamera erlauben",
                    element().buttonNamed("Zugriff auf Mikrofon und Kamera erlauben").textContent?.trim(),
                )
                assertEquals(0, provider.requests, "nothing is requested on load")
                assertEquals(emptyList(), port.switches, "the placeholder is no choice")
            }
        }

    @Test
    fun onlyTheClick_requestsAccess(): Promise<Unit> =
        formTest {
            val port = FakePort(mics = listOf(opt("")), cameras = listOf(opt("")))
            val provider = FakeProvider(status = DevicePermissionStatus.PROMPT, result = DevicePermissionRequestResult.NotFound)
            withPanel("guest-dev-click", port, provider) { panel, _, element ->
                panel.refresh(preserveFocus = true)
                panel.refresh(preserveFocus = false)
                settle()
                assertEquals(0, provider.requests)
                element().buttonNamed("Zugriff auf Mikrofon und Kamera erlauben").click()
                awaitUntil("request") { provider.requests == 1 }
                awaitUntil(
                    "sentence",
                ) { element().textContent.orEmpty().contains("Es wurde weder ein Mikrofon noch eine Kamera gefunden.") }
                assertTrue(panel.root.visible, "the section stays")
            }
        }

    @Test
    fun aBlockedBrowser_showsTheBlockedSentence_withoutCrashing(): Promise<Unit> =
        formTest {
            val port = FakePort(mics = listOf(opt("")), cameras = listOf(opt("")))
            val provider = FakeProvider(status = DevicePermissionStatus.DENIED)
            withPanel("guest-dev-denied", port, provider) { _, _, element ->
                awaitUntil("blocked") { element().textContent.orEmpty().contains("Der Browser hat den Zugriff blockiert.") }
                assertEquals(0, provider.requests)
                assertEquals(emptyList(), element().selectLabelled("Mikrofon").optionValues().filter { it.isNotBlank() })
            }
        }

    @Test
    fun aVanishedDevice_isSaid_onAHotplugRefresh(): Promise<Unit> =
        formTest {
            val port = FakePort()
            port.actives[ConferenceDeviceKind.MICROPHONE] = "m2"
            withPanel("guest-dev-lost", port) { panel, h, element ->
                assertEquals("m2", element().selectLabelled("Mikrofon").value)
                port.mics = listOf(opt("m1", "Mic One"))
                panel.refresh(preserveFocus = true)
                settle()
                assertEquals(listOf(conferenceDeviceLostMessage(ConferenceDeviceKind.MICROPHONE)), h.notes)
                assertEquals(listOf("m1"), element().selectLabelled("Mikrofon").optionValues())
            }
        }

    @Test
    fun withoutTheSinkApi_thereIsNoSpeakerField_andNoReplacementText(): Promise<Unit> =
        formTest {
            val port = FakePort(speakers = listOf(opt("s1", "Speaker One"), opt("s2", "Speaker Two")))
            withPanel("guest-dev-nosink", port, sinkApi = false) { _, _, element ->
                assertEquals(2, element().selects().size)
                assertFalse(element().textContent.orEmpty().contains("Lautsprecher"))
            }
        }

    @Test
    fun withOneRealOutput_orOnlyTheDefaultAlias_thereIsNoSpeakerField(): Promise<Unit> =
        formTest {
            val port = FakePort(speakers = listOf(opt("default", "Default"), opt("s1", "Speaker One")))
            withPanel("guest-dev-oneout", port, sinkApi = true) { _, _, element ->
                assertEquals(2, element().selects().size)
            }
        }

    @Test
    fun withTwoRealOutputs_theSpeakerFieldSwitchesWithRealIds_neverTheSentinel(): Promise<Unit> =
        formTest {
            val port = FakePort(speakers = listOf(opt("s1", "Speaker One"), opt("s2", "Speaker Two")))
            withPanel("guest-dev-speaker", port, sinkApi = true) { _, h, element ->
                val speaker = element().selectLabelled("Lautsprecher")
                assertEquals(listOf("s1", "s2"), speaker.optionValues())
                assertEquals("s1", speaker.value, "no active output: the first entry is shown")
                speaker.choose("s2")
                awaitUntil("speaker switch") { h.port.switches.isNotEmpty() }
                assertEquals(listOf(ConferenceDeviceKind.SPEAKER to "s2"), h.port.switches)
                assertTrue(h.port.switches.none { it.second.isBlank() || it.second == "lapis-system-default" })
            }
        }

    @Test
    fun aMissingEnumerateDevices_neverBreaksThePanel(): Promise<Unit> =
        formTest {
            val port = FakePort()
            port.throwOnList = true
            val h = Harness(port, FakeProvider())
            mountedForm("guest-dev-throws") { root, element ->
                val panel = EventGuestDevicePanel(root, port, h.provider, false, { h.notes += it }, {}, true)
                try {
                    panel.onConnected()
                    delay(150)
                    assertTrue(element().selects().all { it.options.length <= 1 })
                    assertEquals(emptyList(), h.notes)
                } finally {
                    panel.dispose()
                }
            }
        }

    @Test
    fun aClosedPanel_listsNothing_untilItIsOpened(): Promise<Unit> =
        formTest {
            val port = FakePort()
            var listed = 0
            val counting =
                object : GuestDevicePort by port {
                    override suspend fun list(kind: ConferenceDeviceKind): DeviceEnumeration {
                        listed++
                        return port.list(kind)
                    }
                }
            mountedForm("guest-dev-closed") { root, element ->
                val panel = EventGuestDevicePanel(root, counting, FakeProvider(), false, {}, {}, false)
                try {
                    panel.onConnected()
                    panel.refresh(preserveFocus = true)
                    delay(100)
                    assertEquals(0, listed)
                    assertFalse(panel.isOpen)
                    assertFalse(panel.root.visible)
                    panel.open()
                    awaitUntil("listed on first opening") { listed >= 2 }
                    assertTrue(panel.isOpen)
                } finally {
                    panel.dispose()
                }
            }
        }

    @Test
    fun theSection_writesNothingToBrowserStorage(): Promise<Unit> =
        formTest {
            val before = runCatching { localStorage.length }.getOrDefault(0)
            withPanel("guest-dev-storage") { _, h, element ->
                element().selectLabelled("Mikrofon").choose("m2")
                element().selectLabelled("Kamera").choose("c2")
                awaitUntil("switches") { h.port.switches.size == 2 }
                settle()
                assertEquals(before, runCatching { localStorage.length }.getOrDefault(0))
                assertEquals(null, runCatching { localStorage.getItem("lapis-cloud-device-audioinput") }.getOrNull())
            }
        }

    @Test
    fun dispose_unregistersThePermissionListeners(): Promise<Unit> =
        formTest {
            val provider = FakeProvider()
            mountedForm("guest-dev-dispose") { root, _ ->
                val panel = EventGuestDevicePanel(root, FakePort(), provider, false, {}, {}, true)
                panel.dispose()
                panel.dispose()
                assertEquals(2, provider.unregistered, "microphone and camera, once")
            }
        }

    @Test
    fun at390px_nothingOverflows_andEveryControlIsTouchSized(): Promise<Unit> =
        formTest {
            withPanel("guest-dev-390") { panel, _, element ->
                val section = assertNotNull(element().querySelector("#${panel.panelId}") as? HTMLElement)
                val box = element()
                val original = box.style.cssText
                box.style.cssText = "$original;width:390px;"
                try {
                    assertTrue(
                        section.scrollWidth <= section.clientWidth + 1,
                        "panel overflows: ${section.scrollWidth} > ${section.clientWidth}",
                    )
                    assertTrue(section.getBoundingClientRect().width <= 390, "panel is ${section.getBoundingClientRect().width}px wide")
                    element().selects().forEach {
                        assertTrue(
                            it.getBoundingClientRect().height >= 44,
                            "select is ${it.getBoundingClientRect().height}px high",
                        )
                    }
                } finally {
                    box.style.cssText = original
                }
            }
        }
}
