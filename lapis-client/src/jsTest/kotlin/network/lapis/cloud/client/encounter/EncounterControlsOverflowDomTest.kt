package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.lastOpenModal
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.74 -- the overflow of the encounter room's bar on the real cascade: at every width the bar is ONE row that is not clipped, "Verlassen"
 * stays (and is never in the sheet), "Mehr" shows exactly while something sits in the sheet, the doors go before the chat, and the sheet
 * opens, closes (Escape, outside click) and hands the focus back.
 */
class EncounterControlsOverflowDomTest {
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

    /** A pulpit person: moderation controls, microphone and camera, the reactions of a church service. */
    private suspend fun withPulpit(block: suspend (EncounterRoomRig, HTMLElement) -> Unit) =
        withEncounterRoom(
            entry = testEntry(role = EncounterPresenceRole.PULPIT),
            peopleOf = { listOf(testPerson("me", EncounterPresenceRole.PULPIT, "Ich Selbst")) + crowd },
            clock = { clock },
            session = FakeSpeakerSession(),
            space = testSpace(profile = EncounterProfile.CHURCH_SERVICE),
            block = block,
        )

    private fun HTMLElement.bar(): HTMLElement = assertNotNull(querySelector(".lapis-encounter-controls") as? HTMLElement)

    private fun HTMLElement.setWidth(width: Int) {
        (querySelector(".lapis-encounter-room")!!.parentElement as HTMLElement).style.width = "${width}px"
    }

    private fun HTMLElement.shownBar(): List<HTMLElement> = barButtons().filter { it.offsetWidth > 0 }

    private suspend fun HTMLElement.settleAt(width: Int) {
        setWidth(width)
        delay(120)
    }

    /** The sheet while it is on screen (a closed sheet is hidden, perhaps not even rendered). */
    private fun HTMLElement.sheet(): HTMLElement? =
        (querySelector(".lapis-encounter-more-sheet") as? HTMLElement)?.takeIf {
            it.offsetWidth >
                0
        }

    private fun cleanUpModals() {
        for (selector in listOf(".modal", ".modal-backdrop")) {
            val nodes = document.querySelectorAll(selector)
            for (i in 0 until nodes.length) (nodes.item(i) as? HTMLElement)?.remove()
        }
        document.body?.classList?.remove("modal-open")
    }

    @Test
    fun atEveryPhoneAndTabletWidth_theBarIsOneUnclippedRow_andLeaveStaysLast(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withPulpit { _, element ->
                for (width in 320..900 step 20) {
                    element.settleAt(width)
                    val bar = element.bar()
                    val shown = element.shownBar()
                    val tops = shown.map { it.getBoundingClientRect().top }
                    assertTrue(tops.all { abs(it - tops.first()) <= 1.0 }, "width $width: one row $tops")
                    assertTrue(bar.scrollWidth <= bar.clientWidth + 1, "width $width: clipped, ${bar.scrollWidth} > ${bar.clientWidth}")
                    assertEquals("Verlassen", shown.last().barName(), "width $width: Verlassen is last: ${shown.map { it.barName() }}")
                    for (fixed in listOf("Hand heben", "Mikrofon", "Kamera")) {
                        assertTrue(shown.any { it.barName() == fixed }, "width $width: $fixed stays")
                    }
                    val moreShown = shown.any { it.barName() == "Mehr" }
                    val somethingMoved = element.barButtons().any { it.classList.contains("lapis-encounter-control-overflowed") }
                    assertEquals(somethingMoved, moreShown, "width $width: Mehr shows exactly while something sits in the sheet")
                    val chatMoved = element.barControl("Chat").classList.contains("lapis-encounter-control-overflowed")
                    val doorsMoved = element.barControl("Türen schließen").classList.contains("lapis-encounter-control-overflowed")
                    if (chatMoved) assertTrue(doorsMoved, "width $width: the doors go before the chat")
                }
            }
        }

    @Test
    fun at360px_theChatStays_andAt320px_itIsInTheSheet_whileLeaveIsNeverThere(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withPulpit { _, element ->
                element.settleAt(360)
                assertTrue(element.shownBar().any { it.barName() == "Chat" }, "360 px: ${element.shownBar().map { it.barName() }}")
                assertTrue(element.shownBar().any { it.barName() == "Mehr" })
                element.settleAt(320)
                assertFalse(element.shownBar().any { it.barName() == "Chat" }, "320 px: ${element.shownBar().map { it.barName() }}")
                element.barControl("Mehr").click()
                awaitUntil("the sheet is open") { element.sheet() != null }
                val names = element.sheet()!!.allOf("button").map { it.textContent.orEmpty().trim() }
                assertTrue("Chat" in names, "the chat's twin: $names")
                assertFalse("Verlassen" in names, "Verlassen never sits in the sheet: $names")
            }
        }

    @Test
    fun theSheet_listsTwinsInBarOrder_withTheDoorsLast_andMirrorsPressedState(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            window.localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
            withPulpit { _, element ->
                element.settleAt(320)
                element.barControl("Szene ausblenden").click()
                awaitUntil("pressed") { element.barControl("Szene ausblenden").getAttribute("aria-pressed") == "true" }
                element.barControl("Mehr").click()
                awaitUntil("the sheet is open") { element.sheet() != null }
                val twins = element.sheet()!!.allOf("button")
                val names = twins.map { it.textContent.orEmpty().trim() }
                assertEquals(
                    listOf("Amen", "Chat", "Szene ausblenden", "Vollbild", "Übertragung", "Türen schließen"),
                    names,
                    "bar order, doors last",
                )
                assertEquals("true", twins[2].getAttribute("aria-pressed"), "the scene twin mirrors the state")
                assertTrue(twins.last().classList.contains("lapis-encounter-twin-end"))
            }
        }

    @Test
    fun theHand_neverOverflows_andHasNoTwin_whileTheEventReactionsMoveWithTheirWord(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withPulpit { _, element ->
                for (width in listOf(320, 360, 1280)) {
                    element.settleAt(width)
                    val hand = element.barControl("Hand heben")
                    assertFalse(hand.classList.contains("lapis-encounter-control-overflowed"), "width $width: the hand stays in the bar")
                    assertTrue(hand.offsetWidth > 0, "width $width: the hand is shown")
                }
                element.settleAt(320)
                val amen = element.barControl("Amen")
                assertTrue(amen.classList.contains("lapis-encounter-control-overflowed"), "320 px: the event reaction sits in the sheet")
                element.barControl("Mehr").click()
                awaitUntil("the sheet is open") { element.sheet() != null }
                val names = element.sheet()!!.allOf("button").map { it.textContent.orEmpty().trim() }
                assertTrue("Amen" in names, "the twin keeps the word: $names")
                assertFalse("Hand heben" in names, "the hand has no twin: $names")
            }
        }

    @Test
    fun theSheet_closesOnEscape_andGivesTheFocusBackToMehr(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withPulpit { _, element ->
                element.settleAt(320)
                val more = element.barControl("Mehr")
                assertEquals("false", more.getAttribute("aria-expanded"))
                more.click()
                awaitUntil("open") { element.sheet() != null }
                assertEquals("true", more.getAttribute("aria-expanded"))
                awaitUntil("the focus went into the sheet") { element.sheet()?.contains(document.activeElement) == true }
                document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape")))
                awaitUntil("closed") { element.sheet() == null }
                assertEquals("false", more.getAttribute("aria-expanded"))
                awaitUntil("the focus is back on Mehr") { document.activeElement === more }
            }
        }

    @Test
    fun theSheet_closesOnAClickOutside_butNotOnAClickInside(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withPulpit { _, element ->
                element.settleAt(320)
                element.barControl("Mehr").click()
                awaitUntil("open") { element.sheet() != null }
                element.sheet()!!.click()
                delay(50)
                assertNotNull(element.sheet(), "a click on the sheet itself keeps it open")
                element.querySelector(".lapis-encounter-info")!!.let { (it as HTMLElement).click() }
                awaitUntil("closed by the outside click") { element.sheet() == null }
            }
        }

    @Test
    fun aTwin_closesTheSheet_andRunsTheAction(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withPulpit { rig, element ->
                element.settleAt(320)
                element.barControl("Mehr").click()
                awaitUntil("open") { element.sheet() != null }
                element.sheet()!!.buttonNamed("Chat").click()
                awaitUntil("the sheet is closed") { element.sheet() == null }
                assertTrue(rig.room.sidePanel.isOpen, "and the chat panel opened")
            }
        }

    @Test
    fun theDoorsTwin_leadsToTheSameConfirmation_andFiresOnce(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val closeRoute = routeOf { rpcService<IEncounterSpaceService>().closeSpace("space-1") }
            withPulpit { rig, element ->
                // the widest bar where the doors already sit in the sheet but the chat is still in the bar
                var found = false
                for (width in 900 downTo 320 step 10) {
                    element.settleAt(width)
                    val doorsMoved = element.barControl("Türen schließen").classList.contains("lapis-encounter-control-overflowed")
                    if (doorsMoved) {
                        found = true
                        break
                    }
                }
                assertTrue(found, "some width moves the doors into the sheet")
                assertTrue(element.shownBar().any { it.barName() == "Chat" }, "while the chat is still in the bar")
                element.barControl("Mehr").click()
                awaitUntil("open") { element.sheet() != null }
                element.sheet()!!.buttonNamed("Türen schließen").click()
                awaitUntil("the dialog is open") { document.querySelector(".modal.show") != null }
                assertNull(element.sheet(), "the sheet closed before the dialog")
                assertEquals(0, rig.requests.count { it.isRpc && it.rpcRoute == closeRoute }, "nothing before the confirmation")
                awaitUntil("focus on Abbrechen") { (document.activeElement as? HTMLElement)?.textContent.orEmpty().trim() == "Abbrechen" }
                val confirm = lastOpenModal().buttonNamed("Türen schließen")
                confirm.click()
                confirm.click()
                awaitUntil("closeSpace was asked") { rig.requests.count { it.isRpc && it.rpcRoute == closeRoute } >= 1 }
                delay(100)
                assertEquals(1, rig.requests.count { it.isRpc && it.rpcRoute == closeRoute })
                cleanUpModals()
            }
        }

    @Test
    fun aWideBar_needsNoSheet_andAListenerGetsNoModerationControls(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withEncounterRoom(entry = testEntry(), peopleOf = { crowd }, clock = { clock }) { _, element ->
                element.settleAt(1200)
                assertFalse(element.barButtons().any { it.classList.contains("lapis-encounter-control-overflowed") })
                assertFalse(element.shownBar().any { it.barName() == "Mehr" })
                val names = element.barControlNames()
                assertFalse("Übertragung" in names)
                assertFalse("Türen schließen" in names)
                assertNull(element.sheet(), "the sheet does not exist while closed")
            }
        }

    @Test
    fun disposingTheRoom_removesTheDocumentListenersOfAnOpenSheet(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withPulpit { rig, element ->
                element.settleAt(320)
                element.barControl("Mehr").click()
                awaitUntil("open") { element.sheet() != null }
                rig.room.dispose()
                // a later Escape must not reach a dead room (no exception, nothing to close)
                document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape")))
                window.document.body?.click()
                delay(50)
            }
        }
}
