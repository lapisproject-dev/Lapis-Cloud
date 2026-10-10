package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.coroutines.delay
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.95 -- the blessing on the real DOM: the button exists for the pulpit of a church-service room and for nobody else (not in the bar,
 * not in the sheet, not as a group wrapper), it is a 44 px icon-only control that fires one call per press, and the display is the same
 * quiet cross for everybody, extended (not doubled) by a second packet, announced once, and hidden again.
 */
class EncounterBlessingDomTest {
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

    private var clock = 1_000_000.0
    private val crowd = seatedCrowd()

    private fun peopleFor(entry: EncounterEntryDto): List<EncounterPresentDto> =
        listOf(testPerson("me", entry.presenceRole, "Ich Selbst")) + crowd

    private suspend fun withRoom(
        role: EncounterPresenceRole,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
        privileged: Boolean = false,
        blessingScheduler: EncounterBlessingScheduler = browserBlessingScheduler,
        extraRespond: (network.lapis.cloud.client.RecordedRequest) -> StubResponse? = { null },
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        val entry = testEntry(role = role)
        withEncounterRoom(
            entry = entry,
            peopleOf = { peopleFor(entry) },
            clock = { clock },
            privileged = privileged,
            session = if (role == EncounterPresenceRole.CONGREGATION) FakeListenerSession() else FakeSpeakerSession(),
            space = testSpace(profile = profile),
            blessingScheduler = blessingScheduler,
            extraRespond = extraRespond,
            block = block,
        )
    }

    private fun HTMLElement.blessingButtons(): List<HTMLElement> = allOf("[data-label=\"Segen\"], button[aria-label=\"Segen\"], .fa-cross")

    private fun HTMLElement.display(): HTMLElement? = querySelector(".lapis-encounter-blessing") as? HTMLElement

    @Test
    fun thePulpitOfAChurchRoom_hasTheBlessing_asA44pxIconOnlyButtonInItsOwnGroup(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.PULPIT) { _, element ->
                val button = element.barControl("Segen")
                assertEquals("BUTTON", button.tagName)
                assertEquals("Segen", button.getAttribute("title"))
                assertEquals("Segen", button.getAttribute("data-label"))
                assertEquals("", button.textContent.orEmpty().trim(), "no visible word")
                assertNotNull(button.querySelector("i.fa-cross"), "the cross symbol")
                val box = button.getBoundingClientRect()
                assertTrue(box.width >= 44.0 && box.height >= 44.0, "at least 44 x 44 px: ${box.width} x ${box.height}")
                assertTrue(button.tabIndex >= 0, "reachable by keyboard")
                assertFalse(button.hasAttribute("disabled"))
                val group = assertNotNull(button.closest(".lapis-encounter-control-group--liturgy") as? HTMLElement)
                assertEquals(null, group.getAttribute("aria-label"), "a group of one needs no name of its own")
                val names = element.barControlNames()
                assertTrue(names.indexOf("Segen") > names.indexOf("Amen"), "after the reactions: $names")
                assertTrue(names.indexOf("Segen") < names.indexOf("Chat"), "before the panels: $names")
            }
        }

    @Test
    fun everybodyElse_hasNoBlessing_neitherInTheBar_norInTheSheet_norAsAGroup(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val cases =
                listOf(
                    "steward" to Triple(EncounterPresenceRole.STEWARD, EncounterProfile.CHURCH_SERVICE, false),
                    "congregation" to Triple(EncounterPresenceRole.CONGREGATION, EncounterProfile.CHURCH_SERVICE, false),
                    "board without an office" to Triple(EncounterPresenceRole.CONGREGATION, EncounterProfile.CHURCH_SERVICE, true),
                    "pulpit of an assembly" to Triple(EncounterPresenceRole.PULPIT, EncounterProfile.ASSEMBLY, false),
                )
            for ((name, case) in cases) {
                withRoom(role = case.first, profile = case.second, privileged = case.third) { _, element ->
                    assertTrue(element.blessingButtons().isEmpty(), "$name: no blessing control: ${element.barControlNames()}")
                    assertNull(element.querySelector(".lapis-encounter-control-group--liturgy"), "$name: no liturgy group in the DOM")
                    assertTrue(element.allOf(".lapis-encounter-more-sheet button").none { it.textContent.orEmpty().contains("Segen") })
                    assertFalse("Segen" in element.barControlNames())
                }
            }
        }

    @Test
    fun atPhoneWidths_thePulpitReachesTheBlessing_inTheBarOrAsATwinInTheSheet(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            for (width in listOf(360, 320)) {
                withRoom(EncounterPresenceRole.PULPIT) { _, element ->
                    (element.querySelector(".lapis-encounter-room")!!.parentElement as HTMLElement).style.width = "${width}px"
                    delay(150)
                    val inBar = element.barButtons().any { it.barName() == "Segen" && it.offsetWidth > 0 }
                    if (!inBar) {
                        // a closed sheet is not even rendered: open it, then the labelled twin is there
                        element.barControl("Mehr").click()
                        awaitUntil("$width px: the twin is shown in the open sheet") {
                            element.allOf(".lapis-encounter-more-sheet button").any {
                                it.textContent.orEmpty().trim() == "Segen" && it.offsetWidth > 0
                            }
                        }
                    }
                    assertEquals(
                        "Verlassen",
                        element
                            .barButtons()
                            .filter { it.offsetWidth > 0 }
                            .last()
                            .barName(),
                    )
                }
            }
        }

    @Test
    fun aPress_callsBlessSpaceOnce_andADoublePressWhileTheCallIsOut_isOnlyOneCall(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val route = routeOf { rpcService<IEncounterSpaceService>().blessSpace("space-1") }
            withRoom(
                EncounterPresenceRole.PULPIT,
                extraRespond = { request ->
                    if (request.isRpc &&
                        request.rpcRoute == route
                    ) {
                        StubResponse(text = "null", delayMs = 300)
                    } else {
                        null
                    }
                },
            ) { rig, element ->
                val button = element.barControl("Segen")
                button.click()
                button.click()
                awaitUntil("the call went out") { rig.requests.any { it.isRpc && it.rpcRoute == route } }
                delay(500)
                assertEquals(1, rig.requests.count { it.isRpc && it.rpcRoute == route }, "one call for two presses")
                // after the answer a new press is a new call
                button.click()
                awaitUntil("a second call after the first one is done") { rig.requests.count { it.isRpc && it.rpcRoute == route } == 2 }
            }
        }

    @Test
    fun aRefusedBlessing_isNeitherShownNorAnnounced(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val route = routeOf { rpcService<IEncounterSpaceService>().blessSpace("space-1") }
            withRoom(
                EncounterPresenceRole.PULPIT,
                extraRespond = { request ->
                    if (request.isRpc && request.rpcRoute == route) {
                        request.refusedWith("network.lapis.cloud.shared.rpc.ForbiddenException")
                    } else {
                        null
                    }
                },
            ) { rig, element ->
                element.barControl("Segen").click()
                awaitUntil("the call went out") { rig.requests.any { it.isRpc && it.rpcRoute == route } }
                delay(200)
                assertTrue(element.display()?.hasAttribute("hidden") ?: true, "nothing is shown by a refusal")
                assertEquals(
                    "",
                    element.allOf("[role=status]").joinToString("") { it.textContent.orEmpty() }.trim(),
                    "and nothing is announced",
                )
            }
        }

    @Test
    fun aPacket_showsTheSameQuietCross_toEveryRole_inThePulpitArea(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            for (role in listOf(EncounterPresenceRole.CONGREGATION, EncounterPresenceRole.STEWARD, EncounterPresenceRole.PULPIT)) {
                val scheduler = ManualBlessingScheduler()
                withRoom(role, blessingScheduler = scheduler) { rig, element ->
                    assertTrue(element.display()?.hasAttribute("hidden") == true, "$role: hidden until a blessing arrives")
                    rig.room.callbacks.onBlessing()
                    awaitUntil("$role: the display shows") { element.display()?.hasAttribute("hidden") == false }
                    val display = assertNotNull(element.display())
                    assertEquals("true", display.getAttribute("aria-hidden"), "the picture is decoration, the live region speaks")
                    assertNotNull(display.querySelector("svg"), "the cross")
                    assertEquals("Segen", display.textContent.orEmpty().trim(), "the word under the cross, nothing else")
                    assertNotNull(display.closest(".lapis-encounter-pulpit"), "inside the pulpit area")
                    assertEquals("none", window_pointerEvents(display), "never takes a click")
                    assertEquals(1, element.allOf(".lapis-encounter-blessing").size)
                }
            }
        }

    private fun window_pointerEvents(element: HTMLElement): String =
        kotlinx.browser.window
            .getComputedStyle(element)
            .getPropertyValue("pointer-events")

    @Test
    fun aSecondPacketDuringTheDisplay_extendsTheWindow_opensNoSecondDisplay_andIsNotAnnouncedAgain(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val scheduler = ManualBlessingScheduler()
            withRoom(EncounterPresenceRole.CONGREGATION, blessingScheduler = scheduler) { rig, element ->
                fun liveText() = element.allOf("[role=status]").map { it.textContent.orEmpty().trim() }.filter { it.isNotEmpty() }
                rig.room.callbacks.onBlessing()
                awaitUntil("announced once") { liveText() == listOf("Der Segen wird gesprochen") }
                scheduler.fire(ENCOUNTER_BLESSING_ANNOUNCE_CLEAR_MS)
                awaitUntil("the region is empty again") { liveText().isEmpty() }
                // 3 s later a second packet: the window restarts, nothing is announced, the first hide timer is gone
                clock += 3_000.0
                rig.room.callbacks.onBlessing()
                delay(100)
                assertTrue(liveText().isEmpty(), "no second announcement while the display is on: ${liveText()}")
                assertEquals(1, scheduler.pending(ENCOUNTER_BLESSING_DISPLAY_MS), "only the restarted hide timer is pending")
                assertEquals(1, element.allOf(".lapis-encounter-blessing").size, "never a second display")
                assertTrue(element.display()?.hasAttribute("hidden") == false)
                // the window ends: fade, then hidden
                scheduler.fire(ENCOUNTER_BLESSING_DISPLAY_MS)
                scheduler.fire(ENCOUNTER_BLESSING_FADE_MS)
                awaitUntil("hidden again") { element.display()?.hasAttribute("hidden") == true }
                // a LATER blessing is announced again
                clock += 20_000.0
                rig.room.callbacks.onBlessing()
                awaitUntil("announced again") { liveText() == listOf("Der Segen wird gesprochen") }
            }
        }

    @Test
    fun theAnnouncement_namesNoPerson_andTheDisplayStoresNothing(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val scheduler = ManualBlessingScheduler()
            withRoom(EncounterPresenceRole.CONGREGATION, blessingScheduler = scheduler) { rig, element ->
                rig.room.callbacks.onBlessing()
                awaitUntil("announced") { element.allOf("[role=status]").any { it.textContent.orEmpty().contains("Segen") } }
                val spoken = element.allOf("[role=status]").joinToString(" ") { it.textContent.orEmpty() }
                crowd.forEach { assertFalse(it.displayName in spoken, "no name in the announcement") }
                assertFalse("Ich Selbst" in spoken)
                assertTrue(document.cookie.isEmpty() || !document.cookie.contains("Segen"))
            }
        }

    @Test
    fun disposingTheRoom_cancelsTheBlessingTimers(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val scheduler = ManualBlessingScheduler()
            withRoom(EncounterPresenceRole.CONGREGATION, blessingScheduler = scheduler) { rig, _ ->
                rig.room.callbacks.onBlessing()
                assertTrue(scheduler.tasks.any { !it.cancelled })
                rig.room.dispose()
                assertTrue(scheduler.tasks.all { it.cancelled }, "every timer is cancelled by dispose")
            }
        }
}
