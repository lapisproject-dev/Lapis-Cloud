package network.lapis.cloud.client.encounter

import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRecordingSignal
import network.lapis.cloud.shared.domain.PulpitRecordingStateDto
import network.lapis.cloud.shared.rpc.IPulpitRecordingService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.143 -- the pulpit recording in the real room: the banner everybody sees (symbol AND words, no animation, announced once per change), the toggle for
 * PULPIT/STEWARD of a room whose option is on (and for nobody else), and the toggle's state through `aria-pressed`.
 */
class EncounterRecordingDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private var stateRoute = ""
    private var startRoute = ""
    private var stopRoute = ""

    private fun state(active: Boolean) =
        PulpitRecordingStateDto(
            active = active,
            startable = !active,
            confirmationRequired = false,
            confirmedByMe = false,
            pendingConfirmation = false,
            maxReached = false,
        )

    private var current = false

    private fun respond(request: RecordedRequest): StubResponse? =
        when {
            !request.isRpc -> null
            request.rpcRoute == stateRoute -> request.answerWith(jsonOf(PulpitRecordingStateDto.serializer(), state(current)))
            request.rpcRoute == startRoute -> {
                current = true
                request.answerWith(jsonOf(PulpitRecordingStateDto.serializer(), state(true)))
            }
            request.rpcRoute == stopRoute -> {
                current = false
                request.answerWith(jsonOf(PulpitRecordingStateDto.serializer(), state(false)))
            }
            else -> null
        }

    private suspend fun withRoom(
        role: EncounterPresenceRole,
        optionOn: Boolean = true,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
        notice: String? = null,
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        stateRoute = routeOf { rpcService<IPulpitRecordingService>().pulpitRecordingState("s") }
        startRoute = routeOf { rpcService<IPulpitRecordingService>().startPulpitRecording("s") }
        stopRoute = routeOf { rpcService<IPulpitRecordingService>().stopPulpitRecording("s") }
        current = false
        val entry = testEntry(role = role)
        withEncounterRoom(
            entry = entry,
            peopleOf = { listOf<EncounterPresentDto>(testPerson("me", entry.presenceRole, "Ich Selbst")) },
            clock = { 1_000_000.0 },
            session = if (role == EncounterPresenceRole.CONGREGATION) FakeListenerSession() else FakeSpeakerSession(),
            space =
                testSpace(profile = profile).copy(
                    pulpitRecordingAllowed = optionOn,
                    pulpitRecordingStartable =
                        optionOn && role != EncounterPresenceRole.CONGREGATION,
                ),
            extraRespond = ::respond,
            block = block,
        )
    }

    private fun HTMLElement.banner(): HTMLElement = querySelector(".lapis-encounter-recording") as HTMLElement

    private fun HTMLElement.bannerVisible(): Boolean = !banner().hasAttribute("hidden")

    @Test
    fun theBanner_isHiddenUntilARecordingRuns_thenShowsSymbolAndWords_andIsQuiet(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.CONGREGATION, notice = null) { rig, element ->
                assertFalse(element.bannerVisible())
                rig.room.callbacks.onRecording(EncounterRecordingSignal(active = true))
                awaitUntil("the banner is shown") { element.bannerVisible() }
                val text =
                    element
                        .banner()
                        .textContent
                        .orEmpty()
                        .replace(Regex("\\s+"), " ")
                assertTrue(text.contains("Aufzeichnung läuft · Nur die Kanzel wird aufgezeichnet"), text)
                assertTrue(text.contains("Aufnahme"), "the short form is in the DOM for a narrow room")
                assertNull(
                    element.banner().getAttribute("role"),
                    "the room's persistent polite region announces; the band is no second live region",
                )
                assertNotNull(element.banner().querySelector("i[aria-hidden=true]"), "a symbol, not colour alone")
                val style = kotlinx.browser.window.getComputedStyle(element.banner())
                assertEquals("none", style.getPropertyValue("animation-name"), "no animation")
                assertEquals("0s", style.getPropertyValue("transition-duration"), "no transition")
                rig.room.callbacks.onRecording(EncounterRecordingSignal(active = false))
                awaitUntil("the banner is gone") { !element.bannerVisible() }
            }
        }

    @Test
    fun theBanner_saysPodiumInAnAssembly_andTheChangeIsAnnouncedInThePoliteRegion(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.CONGREGATION, profile = EncounterProfile.ASSEMBLY) { rig, element ->
                rig.room.callbacks.onRecording(EncounterRecordingSignal(active = true))
                awaitUntil("the banner is shown") { element.bannerVisible() }
                assertTrue(
                    element
                        .banner()
                        .textContent
                        .orEmpty()
                        .contains("Nur das Podium wird aufgezeichnet"),
                )
                awaitUntil("announced") {
                    element.allOf("[role=status][aria-live=polite]").any { it.textContent.orEmpty().trim() == "Aufzeichnung gestartet" }
                }
                rig.room.callbacks.onRecording(EncounterRecordingSignal(active = false))
                awaitUntil("the end is announced") {
                    element.allOf("[role=status][aria-live=polite]").any { it.textContent.orEmpty().trim() == "Aufzeichnung beendet" }
                }
            }
        }

    @Test
    fun theToggle_existsForThePulpitAndTheSteward_notForTheCongregation_andNotWithoutTheRoomOption(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.PULPIT) { rig, element ->
                assertNotNull(rig.room.recordingToggle)
                assertTrue(element.barControlNames().contains("Aufzeichnung starten"))
            }
            withRoom(EncounterPresenceRole.STEWARD) { rig, _ -> assertNotNull(rig.room.recordingToggle) }
            withRoom(EncounterPresenceRole.CONGREGATION) { rig, element ->
                assertNull(rig.room.recordingToggle)
                assertFalse(element.barControlNames().any { it.startsWith("Aufzeichnung") })
            }
            withRoom(EncounterPresenceRole.PULPIT, optionOn = false) { rig, element ->
                assertNull(rig.room.recordingToggle)
                assertFalse(element.barControlNames().any { it.startsWith("Aufzeichnung") })
            }
        }

    @Test
    fun theToggle_startsAndStopsThroughTheServer_andWritesItsStateThroughAriaPressed(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.PULPIT) { rig, element ->
                val toggle = element.barControl("Aufzeichnung starten")
                assertEquals("false", toggle.getAttribute("aria-pressed"))
                toggle.click()
                awaitUntil("the recording runs") { rig.room.recordingBannerView.visible }
                awaitUntil("the toggle shows the state") { rig.room.recordingToggle?.getAttribute("aria-pressed") == "true" }
                assertEquals("Aufzeichnung beenden", rig.room.recordingToggle?.getAttribute("aria-label"))
                rig.room.recordingToggle
                    ?.getElement()
                    ?.click()
                awaitUntil("the recording stopped") { !rig.room.recordingBannerView.visible }
                assertEquals("false", rig.room.recordingToggle?.getAttribute("aria-pressed"))
            }
        }
}
