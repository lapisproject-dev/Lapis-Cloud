package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.lastOpenModal
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
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
 * V1.9.74 -- the icon bar of the encounter room in a real mounted root: the exit group (doors, leave), symbols only, state in
 * `aria-pressed` with a stable label, the confirmation of "Türen schließen" (focus on "Abbrechen", fires once), "Verlassen" locks itself,
 * and the groups keep their order.
 */
class EncounterControlBarDomTest {
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
    private val sixPeople = seatedCrowd()

    private fun steward(): Pair<EncounterEntryDto, List<EncounterPresentDto>> =
        testEntry(role = EncounterPresenceRole.STEWARD) to
            (listOf(testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst")) + sixPeople)

    private fun cleanUpModals() {
        for (selector in listOf(".modal", ".modal-backdrop")) {
            val nodes = document.querySelectorAll(selector)
            for (i in 0 until nodes.length) (nodes.item(i) as? HTMLElement)?.remove()
        }
        document.body?.classList?.remove("modal-open")
    }

    private fun HTMLElement.groupOf(button: HTMLElement): String =
        button
            .closest(".lapis-encounter-control-group")!!
            .className
            .substringAfter("control-group--")
            .substringBefore(" ")

    @Test
    fun leave_isTheLastControl_doorsStandRightBeforeIt_andTheHeaderHasNoLeaveButton(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val (entry, people) = steward()
            withEncounterRoom(entry = entry, peopleOf = { people }, clock = { clock }, session = FakeSpeakerSession()) { _, element ->
                val shown = element.barButtons().filter { it.offsetWidth > 0 }.map { it.barName() }
                assertEquals("Verlassen", shown.last(), "Verlassen is last: $shown")
                assertEquals("Türen schließen", shown[shown.size - 2], "the doors stand directly before it: $shown")
                val exit = assertNotNull(element.querySelector(".lapis-encounter-control-group--exit") as? HTMLElement)
                assertEquals(listOf("Türen schließen", "Verlassen"), exit.allOf("button").map { it.barName() })
                assertEquals("group", exit.getAttribute("role"))
            }
            withEncounterRoom(entry = testEntry(), peopleOf = { sixPeople }, clock = { clock }) { _, element ->
                val names = element.barControlNames()
                assertEquals("Verlassen", names.last())
                assertFalse("Türen schließen" in names, "a listener never closes the doors: $names")
                assertFalse(element.allOf(".lapis-encounter-more-sheet button").any { it.barName() == "Verlassen" }, "never in the sheet")
            }
        }

    @Test
    fun everyControl_theReactionsIncluded_isIconOnly_withANameAndATooltip(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val (entry, people) = steward()
            withEncounterRoom(
                entry = entry,
                peopleOf = { people },
                clock = { clock },
                session = FakeSpeakerSession(),
                space = testSpace(profile = EncounterProfile.CHURCH_SERVICE, reactions = EncounterReactionOption.entries),
            ) { _, element ->
                val all = element.barButtons()
                assertTrue(all.size >= 11, "all controls incl. the reactions: ${all.size}")
                all.forEach { button ->
                    val name = button.getAttribute("aria-label").orEmpty()
                    // The text node is missing from the DOM, so this holds in every width (no media query involved).
                    assertEquals("", button.textContent.orEmpty().trim(), "$name shows no word")
                    assertEquals(0, button.querySelectorAll("span").length, "$name has no span with a word")
                    assertTrue(name.isNotBlank(), "an accessible name")
                    assertEquals(name, button.getAttribute("title"), "and the same tooltip")
                    assertNotNull(button.querySelector("i"), "$name has a symbol")
                }
                val reactions = element.allOf(".lapis-encounter-control-group--reactions button")
                assertEquals(listOf("Hand heben", "Amen", "Applaus", "Herz"), reactions.map { it.getAttribute("aria-label") })
                reactions.forEach { button ->
                    assertEquals(button.getAttribute("aria-label"), button.getAttribute("data-label"))
                    val box = button.getBoundingClientRect()
                    assertTrue(box.width >= 44.0 && box.height >= 44.0, "${button.barName()} >= 44 px: ${box.width} x ${box.height}")
                }
            }
        }

    @Test
    fun theReactionGroup_isLabelled_theOtherSymbolGroupsAreNot(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val (entry, people) = steward()
            withEncounterRoom(entry = entry, peopleOf = { people }, clock = { clock }, session = FakeSpeakerSession()) { _, element ->
                fun group(name: String) = assertNotNull(element.querySelector(".lapis-encounter-control-group--$name") as? HTMLElement)
                assertEquals("group", group("reactions").getAttribute("role"))
                assertEquals("Reaktionen", group("reactions").getAttribute("aria-label"))
                assertEquals("Geräte", group("devices").getAttribute("aria-label"))
                assertEquals("group", group("devices").getAttribute("role"))
                listOf("panels", "view").forEach { name ->
                    assertNull(group(name).getAttribute("aria-label"), "$name stays unlabelled")
                }
            }
        }

    @Test
    fun aRoomWithoutEventReactions_showsOnlyTheHand(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { sixPeople },
                clock = { clock },
                space = testSpace(profile = EncounterProfile.ASSEMBLY, reactions = listOf(EncounterReactionOption.HAND)),
            ) { _, element ->
                val reactions = element.allOf(".lapis-encounter-control-group--reactions button")
                assertEquals(listOf("Hand heben"), reactions.map { it.getAttribute("aria-label") })
            }
        }

    @Test
    fun sceneFullscreenAndHand_changeTheirState_notTheirLabel(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            window.localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
            withEncounterRoom(entry = testEntry(), peopleOf = { sixPeople }, clock = { clock }) { _, element ->
                val scene = element.barControl("Szene ausblenden")
                assertEquals("false", scene.getAttribute("aria-pressed"))
                scene.click()
                awaitUntil("pressed") { scene.getAttribute("aria-pressed") == "true" }
                assertEquals("Szene ausblenden", scene.getAttribute("aria-label"), "the label never flips")
                scene.click()
                awaitUntil("released") { scene.getAttribute("aria-pressed") == "false" }

                val hand = element.barControl("Hand heben")
                assertEquals("false", hand.getAttribute("aria-pressed"))
                hand.click()
                awaitUntil("hand up") { hand.getAttribute("aria-pressed") == "true" }
                assertEquals("Hand heben", hand.getAttribute("aria-label"), "the name does not change either")
                assertEquals("Hand heben", hand.getAttribute("title"))
                assertTrue(hand.classList.contains("btn-primary"), "the raised hand is the filled button")
            }
        }

    @Test
    fun microphoneAndCamera_keepTheirLabel_andShowTheSlashSymbolWhenOff(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val session = FakeSpeakerSession()
            val entry = testEntry(role = EncounterPresenceRole.PULPIT)
            withEncounterRoom(
                entry = entry,
                peopleOf = { listOf(testPerson("me", EncounterPresenceRole.PULPIT, "Ich Selbst")) + sixPeople },
                clock = { clock },
                session = session,
            ) { _, element ->
                val mic = element.barControl("Mikrofon")
                awaitUntil("slash symbol while off") { mic.querySelector("i")?.classList?.contains("fa-microphone-slash") == true }
                assertEquals("false", mic.getAttribute("aria-pressed"))
                mic.click()
                awaitUntil("on") { mic.getAttribute("aria-pressed") == "true" }
                awaitUntil("plain symbol while on") { mic.querySelector("i")?.classList?.contains("fa-microphone-slash") == false }
                assertEquals("Mikrofon", mic.getAttribute("aria-label"), "stable label")
                val camera = element.barControl("Kamera")
                assertEquals("Kamera", camera.getAttribute("aria-label"))
            }
        }

    @Test
    fun leave_locksItself_andCallsTheOwnerOnce(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            var leaves = 0
            withEncounterRoom(entry = testEntry(), peopleOf = { sixPeople }, clock = { clock }, onLeave = { leaves++ }) { _, element ->
                val leave = element.barControl("Verlassen")
                leave.click()
                assertTrue(leave.hasAttribute("disabled"), "disabled at once")
                awaitUntil("busy") { leave.getAttribute("aria-busy") == "true" }
                leave.click()
                assertEquals(1, leaves, "a second click does nothing")
            }
        }

    @Test
    fun closingTheDoors_asksFirst_focusesCancel_andFiresOnce(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val (entry, people) = steward()
            val closeRoute = routeOf { rpcService<IEncounterSpaceService>().closeSpace("space-1") }
            withEncounterRoom(
                entry = entry,
                peopleOf = { people },
                clock = { clock },
                session = FakeSpeakerSession(),
                space = testSpace(profile = EncounterProfile.CHURCH_SERVICE),
            ) { rig, element ->
                element.barControl("Türen schließen").click()
                awaitUntil("the dialog is open") { document.querySelector(".modal.show") != null }
                assertEquals(0, rig.requests.count { it.isRpc && it.rpcRoute == closeRoute }, "nothing before the confirmation")
                awaitUntil("focus on Abbrechen") { (document.activeElement as? HTMLElement)?.textContent.orEmpty().trim() == "Abbrechen" }
                val confirm = lastOpenModal().buttonNamed("Türen schließen")
                confirm.click()
                confirm.click()
                awaitUntil("closeSpace was asked") { rig.requests.count { it.isRpc && it.rpcRoute == closeRoute } >= 1 }
                kotlinx.coroutines.delay(100)
                assertEquals(1, rig.requests.count { it.isRpc && it.rpcRoute == closeRoute }, "two clicks, one request")
                cleanUpModals()
            }
        }

    @Test
    fun theWaitNote_isAStatusInTheBands_notAControlInTheBar(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { sixPeople },
                clock = { clock },
                space = testSpace(profile = EncounterProfile.CHURCH_SERVICE),
            ) { _, element ->
                element.barControl("Amen").click()
                val note =
                    assertNotNull(
                        element.allOf(".lapis-encounter-bands [role=status]").firstOrNull {
                            "Bitte einen Moment warten." in
                                it.textContent.orEmpty()
                        },
                        "the note stands in the bands",
                    )
                assertTrue(note.offsetWidth > 0)
                assertFalse(
                    element
                        .querySelector(".lapis-encounter-controls")!!
                        .textContent
                        .orEmpty()
                        .contains("Bitte einen Moment"),
                )
            }
        }

    @Test
    fun theModerationGroup_neverBordersTheReactions(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val (entry, people) = steward()
            withEncounterRoom(entry = entry, peopleOf = { people }, clock = { clock }, session = FakeSpeakerSession()) { _, element ->
                val shown = element.barButtons().filter { it.offsetWidth > 0 }
                val groups = shown.map { element.groupOf(it) }
                assertTrue(
                    groups.zipWithNext().none { (a, b) ->
                        setOf(a, b) == setOf("reactions", "moderation")
                    },
                    "order of groups: $groups",
                )
                assertEquals(
                    listOf("devices", "reactions", "panels", "view", "moderation", "exit"),
                    groups.distinct(),
                    "the groups keep their order",
                )
                assertEquals("Moderation", element.querySelector(".lapis-encounter-control-group--moderation")!!.getAttribute("aria-label"))
            }
        }

    @Test
    fun aStateRing_marksEveryPressedToggle_ontopOfTheSymbol(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            window.localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
            withEncounterRoom(entry = testEntry(), peopleOf = { sixPeople }, clock = { clock }) { _, element ->
                val scene = element.barControl("Szene ausblenden")
                val before = window.getComputedStyle(scene).boxShadow
                scene.click()
                awaitUntil("pressed") { scene.getAttribute("aria-pressed") == "true" }
                val after = window.getComputedStyle(scene).boxShadow
                assertTrue(after != before && after.contains("inset"), "a ring appears: '$before' -> '$after'")
            }
        }
}
