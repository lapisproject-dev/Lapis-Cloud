package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.client.livekit.TrackPublication
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterTableTokenAnswer
import network.lapis.cloud.shared.domain.EncounterTableTokenDto
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.FocusEvent
import org.w3c.dom.events.FocusEventInit
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.91 -- the device picker in a real mounted room: who gets which fields, where the button stands, what a choice does (call, one
 * storage key with the id only), what never writes (opening, listing, a plug event, a failed switch), the quiet application of a
 * remembered device, a device that vanishes, focus and keyboard, the table microphone across a rotation, and that no device id or name
 * ever goes over the wire.
 */
class EncounterDevicePickerDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
        const val KEY_PREFIX = "lapis-cloud-device-"
        val DEVICE_KEYS = listOf("lapis-cloud-device-audioinput", "lapis-cloud-device-videoinput", "lapis-cloud-device-audiooutput")
    }

    private var clock = 1_000_000.0
    private val crowd = seatedCrowd()

    private fun options(
        prefix: String,
        vararg ids: String,
    ) = ids.map { ConferenceDeviceOption(it, "Name $prefix $it") }

    private fun clearKeys() = DEVICE_KEYS.forEach { localStorage.removeItem(it) }

    // ── a spy on the two writers of the browser storage (device keys only) ───────────────────────────────────

    private class StorageSpy {
        val log: dynamic = js("[]")
        private var restore: dynamic = null

        fun install() {
            val installer: dynamic =
                js(
                    """(function (log) {
                        var s = Storage.prototype.setItem, r = Storage.prototype.removeItem;
                        Storage.prototype.setItem = function (k, v) {
                            if (String(k).indexOf('lapis-cloud-device-') === 0) log.push('set:' + k + '=' + v);
                            return s.call(this, k, v);
                        };
                        Storage.prototype.removeItem = function (k) {
                            if (String(k).indexOf('lapis-cloud-device-') === 0) log.push('remove:' + k);
                            return r.call(this, k);
                        };
                        return function () { Storage.prototype.setItem = s; Storage.prototype.removeItem = r; };
                    })""",
                )
            restore = installer(log)
        }

        fun uninstall() {
            val undo = restore
            restore = null
            if (undo != null) undo()
        }

        val writes: List<String> get() = (0 until (log.length as Int)).map { log[it] as String }
    }

    private suspend fun spying(block: suspend (StorageSpy) -> Unit) {
        clearKeys()
        val spy = StorageSpy()
        spy.install()
        try {
            block(spy)
        } finally {
            spy.uninstall()
            clearKeys()
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun steward(
        micIds: List<String> = listOf("m1", "m2"),
        camIds: List<String> = listOf("c1", "c2"),
    ) = FakeSpeakerSession().also { session ->
        session.devices[ConferenceDeviceKind.MICROPHONE] = micIds.map { ConferenceDeviceOption(it, "Name mic $it") }
        session.devices[ConferenceDeviceKind.CAMERA] = camIds.map { ConferenceDeviceOption(it, "Name cam $it") }
        session.activeDevices[ConferenceDeviceKind.MICROPHONE] = micIds.firstOrNull()
        session.activeDevices[ConferenceDeviceKind.CAMERA] = camIds.firstOrNull()
    }

    private suspend fun withSteward(
        session: FakeSpeakerSession,
        env: FakeDeviceEnvironment,
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) = withEncounterRoom(
        entry = testEntry(role = EncounterPresenceRole.STEWARD),
        peopleOf = { listOf(testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst")) + crowd },
        clock = { clock },
        session = session,
        deviceEnv = env,
        block = block,
    )

    private suspend fun withMember(
        env: FakeDeviceEnvironment,
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) = withEncounterRoom(
        entry = testEntry(),
        peopleOf = { listOf(testPerson("me", name = "Ich Selbst")) + crowd },
        clock = { clock },
        deviceEnv = env,
        block = block,
    )

    private fun fakeAudioTrack(element: HTMLElement): Track {
        val track: dynamic = js("({})")
        track.kind = "audio"
        track.attach = { element }
        track.detach = { arrayOf<HTMLElement>(element) }
        return track.unsafeCast<Track>()
    }

    private fun fakePublication(): TrackPublication {
        val publication: dynamic = js("({})")
        publication.source = "microphone"
        publication.trackSid = "sid"
        publication.isSubscribed = true
        return publication.unsafeCast<TrackPublication>()
    }

    // ── DOM helpers ─────────────────────────────────────────────────────────────

    private fun HTMLElement.shown(): Boolean = getBoundingClientRect().let { it.width > 0 && it.height > 0 }

    private fun HTMLElement.pickerButton(): HTMLElement? =
        (querySelector("button[aria-controls=lapis-encounter-device-panel]") as? HTMLElement)?.takeIf { it.shown() }

    private fun HTMLElement.panel(): HTMLElement? = (querySelector("#lapis-encounter-device-panel") as? HTMLElement)?.takeIf { it.shown() }

    private fun HTMLElement.rows(): List<HTMLElement> =
        allOf("#lapis-encounter-device-panel .lapis-encounter-device-row").filter { it.shown() }

    private fun HTMLElement.rowLabels(): List<String> =
        rows().map {
            it
                .querySelector("label")
                ?.textContent
                .orEmpty()
                .trim()
        }

    private fun HTMLElement.selectOf(label: String): HTMLSelectElement =
        assertNotNull(
            rows()
                .firstOrNull {
                    it
                        .querySelector(
                            "label",
                        )?.textContent
                        .orEmpty()
                        .trim() == label
                }?.querySelector("select") as? HTMLSelectElement,
            "no shown field '$label' in ${rowLabels()}",
        )

    private fun HTMLSelectElement.optionTexts(): List<String> =
        (0 until options.length).map {
            (options.item(it) as HTMLOptionElement).text.trim()
        }

    private fun HTMLSelectElement.pick(id: String) {
        value = id
        dispatchEvent(Event("change"))
    }

    private fun HTMLElement.status(): String = allOf("[role=status]").joinToString(" | ") { it.textContent.orEmpty() }

    private suspend fun HTMLElement.openPicker(): HTMLElement {
        awaitUntil("the device button is shown") { pickerButton() != null }
        pickerButton()!!.click()
        awaitUntil("the device panel is open") { panel() != null && rows().isNotEmpty() }
        return panel()!!
    }

    // ── who gets which fields ──────────────────────────────────────────────────

    @Test
    fun anOfficeHolder_getsThreeLabelledFields_andTheButtonIsTheLastOfTheDevicesGroup(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            clearKeys()
            val env = FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))
            withSteward(steward(), env) { _, element ->
                awaitUntil("the device button is shown") { element.pickerButton() != null }
                val button = element.pickerButton()!!
                assertEquals("Geräte wählen", button.getAttribute("aria-label"))
                assertEquals("false", button.getAttribute("aria-expanded"))
                assertEquals("lapis-encounter-device-panel", button.getAttribute("aria-controls"))
                val group = assertNotNull(element.querySelector(".lapis-encounter-control-group--devices") as? HTMLElement)
                assertEquals(
                    listOf("Mikrofon", "Kamera", "Geräte wählen"),
                    group.allOf("button").map { it.getAttribute("aria-label").orEmpty() },
                    "microphone, camera, then the picker",
                )
                assertNull(element.panel(), "closed at first")
                button.click()
                awaitUntil("open") { element.panel() != null && element.rows().size == 3 }
                assertEquals("true", button.getAttribute("aria-expanded"))
                assertEquals(listOf("Mikrofon", "Kamera", "Lautsprecher"), element.rowLabels())
                assertEquals(listOf("Name mic m1", "Name mic m2"), element.selectOf("Mikrofon").optionTexts())
                assertEquals(
                    listOf("Standard des Systems", "Name spk s1", "Name spk s2").size,
                    element.selectOf("Lautsprecher").optionTexts().size,
                    "the system default and the two usable outputs (the alias is not offered)",
                )
                assertEquals("Standard des Systems", element.selectOf("Lautsprecher").optionTexts().first())
                assertTrue(
                    element
                        .panel()!!
                        .textContent
                        .orEmpty()
                        .contains("Aktiv: Name mic m1"),
                    element.panel()!!.textContent,
                )
                awaitUntil("the focus moved to the first field") { document.activeElement === element.selectOf("Mikrofon") }
            }
        }

    @Test
    fun withoutTheSinkApi_thereAreTwoFields_andAPlainSentenceAboutTheSpeaker(): Promise<Unit> =
        formTest {
            clearKeys()
            val env = FakeDeviceEnvironment(sinkApi = false, outputs = options("spk", "s1", "s2"))
            withSteward(steward(), env) { _, element ->
                element.openPicker()
                assertEquals(listOf("Mikrofon", "Kamera"), element.rowLabels())
                assertTrue(
                    element.panel()!!.textContent.orEmpty().contains(
                        "Den Lautsprecher wählen Sie in diesem Browser über die Einstellungen Ihres Geräts.",
                    ),
                )
            }
        }

    @Test
    fun aCongregationMemberWithoutATable_getsTheSpeakerFieldOnly_andOnlyWithTwoUsableOutputs(): Promise<Unit> =
        formTest {
            clearKeys()
            withMember(FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))) { _, element ->
                element.openPicker()
                assertEquals("Lautsprecher wählen", element.pickerButton()!!.getAttribute("aria-label"))
                assertEquals(listOf("Lautsprecher"), element.rowLabels())
            }
            withMember(FakeDeviceEnvironment(outputs = options("spk", "s1"))) { _, element ->
                delay(150)
                assertNull(element.pickerButton(), "the alias plus one device is no choice")
            }
            withMember(FakeDeviceEnvironment(sinkApi = false, outputs = options("spk", "s1", "s2"))) { _, element ->
                delay(150)
                assertNull(element.pickerButton(), "no sink API (Safari): no dead control")
            }
            withMember(FakeDeviceEnvironment()) { _, element ->
                delay(150)
                assertNull(element.pickerButton(), "no devices at all: no control")
            }
        }

    @Test
    fun anOfficeHolderWithoutDeviceNames_hasNoButton_untilADeviceIsKnown(): Promise<Unit> =
        formTest {
            clearKeys()
            val session = steward(micIds = emptyList(), camIds = emptyList())
            withSteward(session, FakeDeviceEnvironment()) { _, element ->
                delay(150)
                assertNull(element.pickerButton(), "a hint alone is no reason for a control in the bar")
            }
        }

    // ── a choice ──────────────────────────────────────────────────────────────

    @Test
    fun aChoice_callsTheDevice_andStoresExactlyThatIdUnderTheSharedKey(): Promise<Unit> =
        formTest {
            spying { spy ->
                val session = steward()
                val env = FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))
                withSteward(session, env) { rig, element ->
                    element.openPicker()
                    assertTrue(spy.writes.isEmpty(), "opening writes nothing: ${spy.writes}")
                    element.selectOf("Mikrofon").pick("m2")
                    awaitUntil("the microphone was switched") { session.deviceSwitches == listOf(ConferenceDeviceKind.MICROPHONE to "m2") }
                    awaitUntil("remembered") { spy.writes == listOf("set:lapis-cloud-device-audioinput=m2") }
                    element.selectOf("Kamera").pick("c2")
                    awaitUntil("camera remembered") { spy.writes.size == 2 }
                    assertEquals(
                        listOf("set:lapis-cloud-device-audioinput=m2", "set:lapis-cloud-device-videoinput=c2"),
                        spy.writes,
                    )
                    // the speaker: both hosts
                    val pulpit = document.createElement("audio") as HTMLElement
                    rig.room.callbacks.onRemoteTrack("p1", "Pfarrer Paul", fakeAudioTrack(pulpit), fakePublication())
                    awaitUntil("the pulpit audio is in the room") { element.querySelector(".lapis-encounter-audio-sink audio") === pulpit }
                    element.selectOf("Lautsprecher").pick("s2")
                    awaitUntil("the sink was set on the element") { env.sinkCalls.any { it.first === pulpit && it.second == "s2" } }
                    awaitUntil("speaker remembered") { spy.writes.contains("set:lapis-cloud-device-audiooutput=s2") }
                    // the system default removes the key (nothing remembered = default)
                    element.selectOf("Lautsprecher").pick(ENCOUNTER_SYSTEM_DEFAULT_OUTPUT)
                    awaitUntil("default: the key is removed") { spy.writes.contains("remove:lapis-cloud-device-audiooutput") }
                    assertEquals("", env.sinkCalls.last { it.first === pulpit }.second)
                    assertEquals(
                        listOf(
                            "set:lapis-cloud-device-audioinput=m2",
                            "set:lapis-cloud-device-videoinput=c2",
                            "set:lapis-cloud-device-audiooutput=s2",
                            "remove:lapis-cloud-device-audiooutput",
                        ),
                        spy.writes,
                        "exactly one key per kind, only ids, nothing else",
                    )
                }
            }
        }

    @Test
    fun openingListingAPlugEventAndAFailedSwitch_neverWriteToTheStorage(): Promise<Unit> =
        formTest {
            spying { spy ->
                val session = steward()
                val env = FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))
                withSteward(session, env) { _, element ->
                    element.openPicker()
                    env.fireDeviceChange()
                    session.devices[ConferenceDeviceKind.CAMERA] = options("cam", "c1", "c2", "c3")
                    env.fireDeviceChange()
                    awaitUntil("the new camera is listed") { element.selectOf("Kamera").optionTexts().size == 3 }
                    // a switch that fails: toast, the field goes back, nothing remembered
                    session.switchFailure = ConferenceDeviceFailure.PERMISSION_DENIED
                    element.selectOf("Mikrofon").pick("m2")
                    awaitUntil("the field went back to the active device") { element.selectOf("Mikrofon").value == "m1" }
                    assertTrue(session.deviceSwitches.isEmpty())
                    delay(100)
                    assertTrue(spy.writes.isEmpty(), "no write at all: ${spy.writes}")
                }
            }
        }

    // ── the remembered device is applied quietly ──────────────────────────────────

    @Test
    fun aRememberedDevice_isAppliedWithoutAWord_whenTheMicrophoneIsSwitchedOn(): Promise<Unit> =
        formTest {
            spying { spy ->
                localStorage.setItem("lapis-cloud-device-audioinput", "m2")
                spy.log.length = 0
                val session = steward()
                withSteward(session, FakeDeviceEnvironment()) { _, element ->
                    element.barControl("Mikrofon").click()
                    awaitUntil("the remembered microphone was applied") {
                        session.deviceSwitches.contains(ConferenceDeviceKind.MICROPHONE to "m2")
                    }
                    delay(100)
                    assertTrue(spy.writes.isEmpty(), "applying a remembered device does not write: ${spy.writes}")
                    assertEquals("m2", localStorage.getItem("lapis-cloud-device-audioinput"), "and does not delete")
                    assertFalse(element.status().contains("Gerät"), "no sentence for a quiet restore: ${element.status()}")
                }
            }
        }

    @Test
    fun aRememberedDeviceThatIsNotThere_isNotApplied_notDeleted_andTheOpenPanelSaysSoQuietly(): Promise<Unit> =
        formTest {
            spying { spy ->
                localStorage.setItem("lapis-cloud-device-audioinput", "gone")
                spy.log.length = 0
                val session = steward()
                withSteward(session, FakeDeviceEnvironment()) { _, element ->
                    element.barControl("Mikrofon").click()
                    awaitUntil("the microphone is on") { session.microphoneCalls == listOf(true) }
                    delay(150)
                    assertTrue(session.deviceSwitches.isEmpty(), "nothing is switched")
                    assertEquals("gone", localStorage.getItem("lapis-cloud-device-audioinput"), "the memory stays")
                    element.openPicker()
                    assertTrue(
                        element.panel()!!.textContent.orEmpty().contains(
                            "Ihr gemerktes Gerät ist gerade nicht angeschlossen. Es wird das Standardgerät verwendet.",
                        ),
                    )
                    assertTrue(spy.writes.isEmpty(), "${spy.writes}")
                }
            }
        }

    // ── a device that vanishes ──────────────────────────────────────────────────

    @Test
    fun anActiveDeviceThatVanishes_isReplacedAndAnnouncedByKind_neverByName_andNeverRemembered(): Promise<Unit> =
        formTest {
            spying { spy ->
                val session = steward()
                val env = FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))
                withSteward(session, env) { _, element ->
                    element.openPicker()
                    // the speaker s2 is chosen, then unplugged
                    element.selectOf("Lautsprecher").pick("s2")
                    awaitUntil("s2 is the speaker") { spy.writes.contains("set:lapis-cloud-device-audiooutput=s2") }
                    spy.log.length = 0
                    env.outputs = options("spk", "s1", "s3")
                    // the active microphone m1 is unplugged too
                    session.devices[ConferenceDeviceKind.MICROPHONE] = options("mic", "m2")
                    env.fireDeviceChange()
                    awaitUntil("the microphone was replaced") { session.deviceSwitches.contains(ConferenceDeviceKind.MICROPHONE to "m2") }
                    awaitUntil("the speaker fell back to the default") {
                        element.status().contains("Der Lautsprecher ist nicht mehr verfügbar. Es wird das Standardgerät verwendet.")
                    }
                    assertTrue(element.status().contains("Das Mikrofon ist nicht mehr verfügbar. Es wird das Standardgerät verwendet."))
                    assertFalse(element.status().contains("Name"), "no device name in a live region: ${element.status()}")
                    delay(100)
                    assertTrue(spy.writes.isEmpty(), "a replacement is not a choice: ${spy.writes}")
                }
            }
        }

    @Test
    fun aPlugEventDuringARunningRefresh_stillReplacesTheVanishedActiveDevice(): Promise<Unit> =
        formTest {
            val session = steward()
            val fake = FakeDeviceEnvironment(outputs = options("spk", "s1"))
            val env = fake
            withSteward(session, env) { _, element ->
                awaitUntil("the device button is shown") { element.pickerButton() != null }
                fake.holdOutputs = true
                // the opening refresh is stuck on the output list ...
                element.pickerButton()!!.click()
                delay(50)
                // ... and the headset is unplugged right now
                session.devices[ConferenceDeviceKind.MICROPHONE] = options("mic", "m2")
                fake.fireDeviceChange()
                delay(50)
                fake.holdOutputs = false
                awaitUntil("the microphone was replaced") { session.deviceSwitches.contains(ConferenceDeviceKind.MICROPHONE to "m2") }
                awaitUntil("the person was told") {
                    element.status().contains("Das Mikrofon ist nicht mehr verfügbar. Es wird das Standardgerät verwendet.")
                }
            }
        }

    // ── focus and keyboard ───────────────────────────────────────────────────────

    @Test
    fun aPlugEvent_neverMovesTheFocus_andRebuildsAFocusedFieldOnlyAfterTheBlur(): Promise<Unit> =
        formTest {
            clearKeys()
            val session = steward()
            val env = FakeDeviceEnvironment()
            withSteward(session, env) { _, element ->
                element.openPicker()
                val mic = element.selectOf("Mikrofon")
                awaitUntil("the first field has the focus") { document.activeElement === mic }
                assertEquals(2, mic.optionTexts().size)
                session.devices[ConferenceDeviceKind.MICROPHONE] = options("mic", "m1", "m2", "m3")
                session.devices[ConferenceDeviceKind.CAMERA] = options("cam", "c1", "c2", "c3")
                env.fireDeviceChange()
                awaitUntil("the field that does NOT have the focus was rebuilt") { element.selectOf("Kamera").optionTexts().size == 3 }
                assertTrue(document.activeElement === mic, "the focus stayed on the field")
                assertEquals(2, mic.optionTexts().size, "the focused field is not rebuilt under the person's hands")
                mic.blur()
                awaitUntil("after the blur the field catches up") { element.selectOf("Mikrofon").optionTexts().size == 3 }
            }
        }

    @Test
    fun escape_closesAndReturnsTheFocus_aClickOutsideClosesWithoutMovingIt_tabOutCloses(): Promise<Unit> =
        formTest {
            clearKeys()
            withSteward(steward(), FakeDeviceEnvironment()) { _, element ->
                val button = element.openPicker().let { element.pickerButton()!! }
                document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape")))
                awaitUntil("closed by Escape") { element.panel() == null }
                assertEquals("false", button.getAttribute("aria-expanded"))
                assertTrue(document.activeElement === button, "the focus is back on the button")
                // a click outside
                element.openPicker()
                val other = element.barControl("Kamera")
                other.focus()
                document.body!!.click()
                awaitUntil("closed by a click outside") { element.panel() == null }
                assertTrue(document.activeElement === other, "a click elsewhere keeps the focus where the person put it")
                // Tab out of the panel
                element.openPicker()
                val mic = element.selectOf("Mikrofon")
                mic.dispatchEvent(FocusEvent("focusout", FocusEventInit(relatedTarget = other, bubbles = true)))
                awaitUntil("closed by tabbing out") { element.panel() == null }
                // tabbing from the first field back to the button keeps it open
                element.openPicker()
                element.selectOf("Mikrofon").dispatchEvent(FocusEvent("focusout", FocusEventInit(relatedTarget = button, bubbles = true)))
                delay(100)
                assertNotNull(element.panel(), "the button belongs to the panel")
            }
        }

    @Test
    fun theDevicePanelAndTheMoreSheet_areNeverOpenTogether(): Promise<Unit> =
        formTest {
            clearKeys()
            withEncounterRoom(
                entry = testEntry(role = EncounterPresenceRole.STEWARD),
                peopleOf = { listOf(testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst")) + crowd },
                clock = { clock },
                session = steward(),
                deviceEnv = FakeDeviceEnvironment(),
            ) { _, element ->
                (element.querySelector(".lapis-encounter-room")!!.parentElement as HTMLElement).style.width = "340px"
                delay(150)
                val more =
                    assertNotNull(
                        element.allOf(".lapis-encounter-controls button").firstOrNull {
                            it.getAttribute("aria-label") ==
                                "Mehr" &&
                                it.shown()
                        },
                    )
                more.click()
                awaitUntil(
                    "the sheet is open",
                ) { ((element.querySelector(".lapis-encounter-more-sheet") as? HTMLElement)?.shown() == true) }
                element.openPicker()
                assertFalse(
                    ((element.querySelector(".lapis-encounter-more-sheet") as? HTMLElement)?.shown() == true),
                    "the sheet closed when the panel opened",
                )
                more.click()
                awaitUntil("the sheet is open again") {
                    (
                        (element.querySelector(".lapis-encounter-more-sheet") as? HTMLElement)?.shown() ==
                            true
                    )
                }
                assertNull(element.panel(), "the panel closed when the sheet opened")
            }
        }

    // ── the table ─────────────────────────────────────────────────────────────

    private class TableServer {
        var token: EncounterTableTokenDto? = null
        var next: EncounterTableTokenDto? = null
    }

    @Test
    fun atATable_theTableMicrophoneAndTheSpeakerAreOffered_theRememberedMicrophoneIsAppliedAndSurvivesARotation(): Promise<Unit> =
        formTest {
            spying { spy ->
                localStorage.setItem("lapis-cloud-device-audioinput", "tm2")
                spy.log.length = 0
                val server = TableServer()
                val opener =
                    FakeTableOpener { token ->
                        FakeTableSession(canPublish = token.canPublish).also {
                            it.microphones = options("tmic", "tm1", "tm2")
                            it.activeMicrophone = "tm1"
                        }
                    }
                val joinRoute = routeOf { rpcService<IEncounterSpaceService>().joinTable("space-1", 0, 0) }
                val tokenRoute = routeOf { rpcService<IEncounterSpaceService>().tableToken("space-1") }
                val people = listOf(testPerson("me", name = "Ich Selbst")) + crowd
                val env = FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))
                withEncounterRoom(
                    entry = testEntry(),
                    peopleOf = { people },
                    clock = { clock },
                    space = tablesSpace(),
                    tableSessionOpener = opener.opener,
                    deviceEnv = env,
                    extraRespond = { request: RecordedRequest ->
                        when (request.rpcRoute) {
                            joinRoute ->
                                request.answerWith(
                                    jsonOf(EncounterTableTokenDto.serializer(), testTableToken(table = 0, seat = 0)),
                                )
                            tokenRoute ->
                                request.answerWith(
                                    jsonOf(EncounterTableTokenAnswer.serializer(), EncounterTableTokenAnswer(token = server.next)),
                                )
                            else -> StubResponse()
                        }
                    },
                ) { _, element ->
                    awaitUntil("the tables are drawn") { element.allOf(".lapis-encounter-table").size == 3 }
                    // the speaker is offered at once; the table microphone only once the person sits and switches it on
                    awaitUntil("the speaker button") { element.pickerButton()?.getAttribute("aria-label") == "Lautsprecher wählen" }
                    element
                        .querySelector(
                            ".lapis-encounter-table-seat[data-table=\"0\"][data-table-seat=\"0\"]",
                        )!!
                        .let { (it as HTMLElement).click() }
                    awaitUntil("seated") { opener.opened.size == 1 && element.barControl("Mikrofon am Tisch").shown() }
                    element.barControl("Mikrofon am Tisch").click()
                    val first = opener.opened.single().second
                    awaitUntil("the table microphone is on") { first.microphoneCalls == listOf(true) }
                    awaitUntil("the remembered table microphone was applied, quietly") { first.microphoneSwitches == listOf("tm2") }
                    assertTrue(spy.writes.isEmpty(), "${spy.writes}")
                    awaitUntil("the button names both") {
                        element.pickerButton()?.getAttribute("aria-label") ==
                            "Mikrofon und Lautsprecher wählen"
                    }
                    // an own choice at the table
                    element.openPicker()
                    assertEquals(listOf("Mikrofon", "Lautsprecher"), element.rowLabels())
                    element.selectOf("Mikrofon").pick("tm1")
                    awaitUntil("switched at the table") { first.microphoneSwitches == listOf("tm2", "tm1") }
                    awaitUntil("remembered") { spy.writes == listOf("set:lapis-cloud-device-audioinput=tm1") }
                    // the room rotates: a new session, the microphone comes back and the CHOICE with it
                    server.next = testTableToken(room = "lc-et-2", table = 0, seat = 0)
                    opener.lastCallbacks!!.onDisconnected(DisconnectCause.Other)
                    awaitUntil("a second session") { opener.opened.size == 2 }
                    val second = opener.opened[1].second
                    awaitUntil("the microphone came back") { second.microphoneCalls == listOf(true) }
                    // tm1 is the remembered device now; the new session starts on tm1 as well (active), so nothing is left to switch
                    delay(150)
                    assertTrue(
                        second.microphoneSwitches.isEmpty() || second.microphoneSwitches == listOf("tm1"),
                        "${second.microphoneSwitches}",
                    )
                    assertEquals("tm1", second.activeMicrophone)
                }
            }
        }

    @Test
    fun aQuietedTable_disablesTheMicrophoneField_andSaysSo(): Promise<Unit> =
        formTest {
            clearKeys()
            val opener =
                FakeTableOpener { token ->
                    FakeTableSession(canPublish = token.canPublish).also { it.microphones = options("tmic", "tm1", "tm2") }
                }
            val joinRoute = routeOf { rpcService<IEncounterSpaceService>().joinTable("space-1", 0, 0) }
            val people = listOf(testPerson("me", name = "Ich Selbst")) + crowd
            val env = FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { people },
                clock = { clock },
                space = tablesSpace(),
                tableSessionOpener = opener.opener,
                deviceEnv = env,
                extraRespond = { request: RecordedRequest ->
                    if (request.rpcRoute == joinRoute) {
                        request.answerWith(
                            jsonOf(EncounterTableTokenDto.serializer(), testTableToken(table = 0, seat = 0).copy(canPublish = false)),
                        )
                    } else {
                        StubResponse()
                    }
                },
            ) { _, element ->
                awaitUntil("the tables are drawn") { element.allOf(".lapis-encounter-table").size == 3 }
                element
                    .querySelector(
                        ".lapis-encounter-table-seat[data-table=\"0\"][data-table-seat=\"0\"]",
                    )!!
                    .let { (it as HTMLElement).click() }
                awaitUntil("seated at a quieted table") { opener.opened.size == 1 }
                element.openPicker()
                awaitUntil(
                    "the quiet sentence is shown",
                ) {
                    element
                        .panel()!!
                        .textContent
                        .orEmpty()
                        .contains("Ihr Tisch ist gerade beruhigt.")
                }
                assertEquals(2, element.rows().size, "the microphone row (with the sentence) and the speaker row")
                assertEquals("Lautsprecher", element.rowLabels().last())
            }
        }

    // ── nothing goes over the wire ───────────────────────────────────────────────

    @Test
    fun noDeviceIdOrNameEverLeavesTheBrowser(): Promise<Unit> =
        formTest {
            spying { _ ->
                val session = steward()
                val env = FakeDeviceEnvironment(outputs = options("spk", "s1", "s2"))
                withSteward(session, env) { rig, element ->
                    element.openPicker()
                    element.selectOf("Mikrofon").pick("m2")
                    element.selectOf("Kamera").pick("c2")
                    element.selectOf("Lautsprecher").pick("s2")
                    awaitUntil("all three choices were made") { session.deviceSwitches.size == 2 && env.outputs.isNotEmpty() }
                    delay(200)
                    env.fireDeviceChange()
                    delay(100)
                    val wire = rig.requests.joinToString("\n") { it.url + " " + it.body }
                    for (secret in listOf("\"m1\"", "\"m2\"", "\"c1\"", "\"c2\"", "\"s1\"", "\"s2\"", "Name mic", "Name cam", "Name spk")) {
                        assertFalse(wire.contains(secret), "$secret went over the wire: $wire")
                    }
                }
            }
        }
}
