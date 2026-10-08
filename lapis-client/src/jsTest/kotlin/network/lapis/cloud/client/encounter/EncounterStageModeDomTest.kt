package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterReactionOption
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.67 -- the room of the two profiles in a real mounted root: the buttons and the filter follow the room's reaction set, the stage
 * mode controls (side panel toggle, full screen with its fallback), the scenes and the geometry of the seat grid.
 */
class EncounterStageModeDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private var clock = 1_000_000.0
    private val sixPeople = seatedCrowd()

    private suspend fun withRoom(
        profile: EncounterProfile,
        reactions: List<EncounterReactionOption> = EncounterReactionOption.defaultsFor(profile),
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) = withEncounterRoom(
        entry = testEntry(),
        peopleOf = { sixPeople },
        clock = { clock },
        space = testSpace(profile = profile, reactions = reactions),
        block = block,
    )

    private fun HTMLElement.buttonLabels(): List<String> = allOf("button").map { it.textContent.orEmpty().trim() }

    // ── the reaction set drives the bar ───────────────────────────────────────────

    @Test
    fun anAssemblyRoom_offersHandAndApplause_noAmen(): Promise<Unit> =
        formTest {
            withRoom(EncounterProfile.ASSEMBLY) { _, element ->
                val names = element.buttonLabels()
                assertTrue("Hand heben" in names && "Applaus" in names, "$names")
                assertFalse("Amen" in names, "no amen button in an assembly: $names")
                assertFalse("Herz" in names, "$names")
            }
        }

    @Test
    fun aChurchRoom_offersHandAndAmen_noApplause(): Promise<Unit> =
        formTest {
            withRoom(EncounterProfile.CHURCH_SERVICE) { _, element ->
                val names = element.buttonLabels()
                assertTrue("Hand heben" in names && "Amen" in names, "$names")
                assertFalse("Applaus" in names || "Herz" in names, "$names")
            }
        }

    @Test
    fun theButtons_followTheConfiguredSetInCanonicalOrder(): Promise<Unit> =
        formTest {
            withRoom(
                EncounterProfile.ASSEMBLY,
                reactions = listOf(EncounterReactionOption.HEART, EncounterReactionOption.APPLAUSE, EncounterReactionOption.HAND),
            ) { _, element ->
                val group = assertNotNull(element.querySelector(".lapis-encounter-control-group--reactions") as? HTMLElement)
                val labels = group.allOf("button").map { it.textContent.orEmpty().trim() }
                assertEquals(listOf("Hand heben", "Applaus", "Herz"), labels)
            }
        }

    // ── the receive filter ────────────────────────────────────────────────────────

    @Test
    fun aReactionTheRoomDoesNotAllow_isDroppedSilently_anAllowedOneShowsASymbolOfItsOwn(): Promise<Unit> =
        formTest {
            withRoom(EncounterProfile.ASSEMBLY) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                rig.room.callbacks.onReaction("c1", EncounterReaction.AMEN)
                delay(50)
                assertEquals(0, element.querySelectorAll(".lapis-encounter-seat-event.is-on").length, "amen is not part of this room")
                rig.room.callbacks.onReaction("c2", EncounterReaction.APPLAUSE)
                awaitUntil("the applause is shown") { element.querySelectorAll(".lapis-encounter-seat-event.is-on").length == 1 }
                assertEquals("APPLAUSE", element.querySelector(".lapis-encounter-seat-event.is-on")!!.getAttribute("data-reaction"))
                assertEquals(1, rig.room.seatGrid.let { grid -> (0 until grid.seatCount).count { grid.eventVisible(it) } })
            }
        }

    @Test
    fun aRaisedHand_canAlwaysBeLowered_evenInTheSmallestSet(): Promise<Unit> =
        formTest {
            withRoom(EncounterProfile.ASSEMBLY, reactions = listOf(EncounterReactionOption.HAND)) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                rig.room.callbacks.onReaction("c3", EncounterReaction.HAND)
                awaitUntil("the hand is up") { rig.room.raisedHandIds == listOf("c3") }
                rig.room.callbacks.onReaction("c3", EncounterReaction.HAND_LOWERED)
                awaitUntil("the hand is down") { rig.room.raisedHandIds.isEmpty() }
            }
        }

    @Test
    fun aNewerEvent_replacesTheOneAtTheSameSeat(): Promise<Unit> =
        formTest {
            withRoom(
                EncounterProfile.ASSEMBLY,
                reactions = listOf(EncounterReactionOption.HAND, EncounterReactionOption.APPLAUSE, EncounterReactionOption.HEART),
            ) { rig, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                rig.room.callbacks.onReaction("c1", EncounterReaction.APPLAUSE)
                clock += 1_000.0
                rig.room.callbacks.onReaction("c1", EncounterReaction.HEART)
                awaitUntil("the heart replaced the applause") {
                    rig.room.seatGrid.eventShown(0) == EncounterReactionOption.HEART
                }
                assertEquals(1, element.querySelectorAll(".lapis-encounter-seat-event.is-on").length)
            }
        }

    // ── sending ───────────────────────────────────────────────────────────────────

    @Test
    fun anEventReaction_isSentOnce_allEventButtonsWaitTogether_theHandStaysFree(): Promise<Unit> =
        formTest {
            withRoom(
                EncounterProfile.ASSEMBLY,
                reactions = listOf(EncounterReactionOption.HAND, EncounterReactionOption.APPLAUSE, EncounterReactionOption.HEART),
            ) { rig, element ->
                element.buttonNamed("Applaus").click()
                awaitUntil("the applause was sent") { rig.session.reactions == listOf(EncounterReaction.APPLAUSE) }
                assertTrue(element.buttonNamed("Applaus").hasAttribute("disabled"))
                assertTrue(element.buttonNamed("Herz").hasAttribute("disabled"), "the heart waits with the applause")
                assertFalse(element.buttonNamed("Hand heben").hasAttribute("disabled"), "asking for the floor is never blocked")
                element.buttonNamed("Hand heben").click()
                awaitUntil("the hand was sent") {
                    rig.session.reactions == listOf(EncounterReaction.APPLAUSE, EncounterReaction.HAND)
                }
            }
        }

    // ── stage mode: panel, full screen ────────────────────────────────────────────

    @Test
    fun thePanelToggle_tellsItsStateAndWhatItControls(): Promise<Unit> =
        formTest {
            withRoom(EncounterProfile.CHURCH_SERVICE) { _, element ->
                val toggle = element.barControl("Chat")
                assertEquals(ENCOUNTER_SIDE_PANEL_ID, toggle.getAttribute("aria-controls"))
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                toggle.click()
                awaitUntil("the panel is open") { element.querySelector("#$ENCOUNTER_SIDE_PANEL_ID") != null }
                assertEquals("true", toggle.getAttribute("aria-expanded"))
            }
        }

    @Test
    fun theFullscreenButton_fallsBackToACssFullscreen_andEscapeLeavesIt(): Promise<Unit> =
        formTest {
            val realDescriptor = js("Object.getOwnPropertyDescriptor(Document.prototype, 'fullscreenEnabled')")
            js("Object.defineProperty(document, 'fullscreenEnabled', {value: false, configurable: true})")
            try {
                withRoom(EncounterProfile.CHURCH_SERVICE) { rig, element ->
                    val button = element.barControl("Vollbild")
                    assertEquals("false", button.getAttribute("aria-pressed"))
                    button.click()
                    val room = element.querySelector(".lapis-encounter-room") as HTMLElement
                    awaitUntil("the css fullscreen is on") { room.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS) }
                    assertEquals("true", button.getAttribute("aria-pressed"))
                    // V1.9.74: the label stays "Vollbild"; the state is aria-pressed and the symbol
                    assertEquals("Vollbild", button.getAttribute("aria-label"))
                    awaitUntil("the symbol shows the way back") { button.querySelector("i")?.classList?.contains("fa-compress") == true }
                    document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape")))
                    awaitUntil("Escape left it") { !room.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS) }
                    assertEquals("false", button.getAttribute("aria-pressed"))
                    // and the toggle works again, then dispose cleans up
                    button.click()
                    awaitUntil("on again") { room.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS) }
                    rig.room.dispose()
                    assertFalse(room.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS), "dispose leaves the full screen")
                    assertFalse(rig.room.fullscreenControl.isActive)
                }
            } finally {
                js("delete document.fullscreenEnabled")
                if (realDescriptor != null) js("Object.defineProperty(Document.prototype, 'fullscreenEnabled', realDescriptor)")
            }
        }

    @Test
    fun aConfirmationDialog_leavesTheFullscreenFirst_soItIsNotHiddenBehindTheRoom(): Promise<Unit> =
        formTest {
            val realDescriptor = js("Object.getOwnPropertyDescriptor(Document.prototype, 'fullscreenEnabled')")
            js("Object.defineProperty(document, 'fullscreenEnabled', {value: false, configurable: true})")
            try {
                val entry = testEntry(role = EncounterPresenceRole.STEWARD)
                withEncounterRoom(
                    entry = entry,
                    peopleOf = { sixPeople },
                    clock = { clock },
                    space = testSpace(profile = EncounterProfile.CHURCH_SERVICE),
                    session = FakeSpeakerSession(),
                ) { _, element ->
                    val room = element.querySelector(".lapis-encounter-room") as HTMLElement
                    element.barControl("Vollbild").click()
                    awaitUntil("the css fullscreen is on") { room.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS) }
                    element.barControl("Türen schließen").click()
                    awaitUntil("the room left the fullscreen") { !room.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS) }
                    awaitUntil("the dialog is open") { document.querySelector(".modal") != null }
                    for (selector in listOf(".modal", ".modal-backdrop")) {
                        val nodes = document.querySelectorAll(selector)
                        for (i in 0 until nodes.length) (nodes.item(i) as? HTMLElement)?.remove()
                    }
                }
            } finally {
                js("delete document.fullscreenEnabled")
                if (realDescriptor != null) js("Object.defineProperty(Document.prototype, 'fullscreenEnabled', realDescriptor)")
            }
        }

    @Test
    fun aRejectedRequestFullscreen_fallsBackToTheCssFullscreen(): Promise<Unit> =
        formTest {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            host.asDynamic().requestFullscreen = { Promise.reject(IllegalStateException("denied")) }
            var last: Boolean? = null
            val fullscreen = EncounterFullscreen(element = { host }, onChange = { last = it }, nativeSupported = { true })
            try {
                fullscreen.toggle()
                awaitUntil("the fallback took over") { host.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS) }
                assertEquals(true, last)
                fullscreen.toggle()
                assertFalse(host.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS))
                assertEquals(false, last)
            } finally {
                fullscreen.dispose()
                host.remove()
            }
        }

    @Test
    fun aDisposedFullscreen_ignoresEverything(): Promise<Unit> =
        formTest {
            val host = document.createElement("div") as HTMLElement
            var calls = 0
            val fullscreen = EncounterFullscreen(element = { host }, onChange = { calls++ }, nativeSupported = { false })
            fullscreen.toggle()
            fullscreen.dispose()
            fullscreen.toggle()
            document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape")))
            assertEquals(1, calls, "only the entering was reported")
            assertFalse(host.classList.contains(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS))
        }

    // ── scene and geometry ────────────────────────────────────────────────────────

    @Test
    fun theSceneOfEachProfile_isItsOwnFloorPlan_andSwitchedOffByTheToggle(): Promise<Unit> =
        formTest {
            listOf(
                EncounterProfile.CHURCH_SERVICE to "church",
                EncounterProfile.ASSEMBLY to "hall",
            ).forEach { (profile, folder) ->
                withRoom(profile) { _, element ->
                    val front = assertNotNull(element.querySelector(".lapis-encounter-scene-front") as? HTMLElement)
                    val rows = assertNotNull(element.querySelector(".lapis-encounter-scene-rows") as? HTMLElement)
                    assertEquals("true", front.getAttribute("aria-hidden"))
                    assertEquals("true", rows.getAttribute("aria-hidden"))
                    assertTrue(front.style.getPropertyValue("--lapis-enc-scene-front").contains("/encounter-themes/$folder/front.svg"))
                    assertTrue(rows.style.getPropertyValue("--lapis-enc-scene-row").contains("/encounter-themes/$folder/row.svg"))
                    element.barControl("Szene ausblenden").click()
                    awaitUntil("the scene is gone") { element.querySelector(".lapis-encounter--scene-off") != null }
                    assertFalse(front.isShown() || rows.isShown(), "both layers leave the picture")
                    element.barControl("Szene ausblenden").click()
                    awaitUntil("the scene is back") { element.querySelector(".lapis-encounter--scene-off") == null }
                }
            }
        }

    @Test
    fun theSeatGridCss_matchesTheRowTileOfTheScene(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterProfile.CHURCH_SERVICE) { _, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                val stage = element.querySelector(".lapis-encounter") as HTMLElement
                val style = window.getComputedStyle(stage)

                fun px(name: String) =
                    style
                        .getPropertyValue(name)
                        .trim()
                        .removeSuffix("px")
                        .toDouble()
                val blocks = 2
                val perBlock = 3
                val width =
                    blocks * perBlock * px("--lapis-enc-seat") + blocks * (perBlock - 1) * px("--lapis-enc-seat-gap") +
                        (blocks - 1) * px("--lapis-enc-aisle")
                assertEquals(width, px("--lapis-enc-row-w"), "row tile width = footprint of one row of seats")
                assertEquals(px("--lapis-enc-seat") + px("--lapis-enc-seat-gap"), px("--lapis-enc-row-h"), "row tile height = seat + gap")
                // the real boxes agree with the variables
                val firstRow = element.querySelector(".lapis-encounter-row") as HTMLElement
                assertEquals(width, firstRow.getBoundingClientRect().width, 1.0)
            }
        }

    @Test
    fun theBar_hasTargetsOfAtLeast44px_andTheRoomDoesNotScrollSideways_at360px(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterProfile.ASSEMBLY) { _, element ->
                awaitUntil("six seats occupied") { element.occupied() == 6 }
                element.allOf(".lapis-encounter-controls .btn").forEach {
                    val box = it.getBoundingClientRect()
                    assertTrue(box.height >= 43.5 && box.width >= 43.5, "target ${it.textContent}: ${box.width} x ${box.height}")
                }
                val room = element.querySelector(".lapis-encounter-room") as HTMLElement
                val host = room.parentElement as HTMLElement
                host.style.width = "360px"
                delay(100)
                assertTrue(room.scrollWidth <= room.clientWidth + 1, "no horizontal page scroll: ${room.scrollWidth} > ${room.clientWidth}")
            }
        }

    @Test
    fun theStageRegions_areNotCappedAt720px(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterProfile.ASSEMBLY) { _, element ->
                val pulpit = element.querySelector(".lapis-encounter-pulpit") as HTMLElement
                val maxWidth = window.getComputedStyle(pulpit).maxWidth
                assertFalse(maxWidth == "720px", "the pulpit is no longer capped at 720 px (was: $maxWidth)")
            }
        }

    @Test
    fun anOfficeHoldersDevices_sitInTheirOwnGroup_apartFromTheReactions(): Promise<Unit> =
        formTest {
            val entry = testEntry(role = EncounterPresenceRole.PULPIT)
            val session = FakeSpeakerSession()
            withEncounterRoom(
                entry = entry,
                peopleOf = { listOf(testPerson("me", EncounterPresenceRole.PULPIT, "Ich Selbst")) + sixPeople },
                clock = { clock },
                session = session,
                space = testSpace(profile = EncounterProfile.ASSEMBLY),
            ) { _, element ->
                val devices = assertNotNull(element.querySelector(".lapis-encounter-control-group--devices") as? HTMLElement)
                assertEquals(listOf("Mikrofon", "Kamera"), devices.allOf("button").map { it.barName() })
                val reactions = assertNotNull(element.querySelector(".lapis-encounter-control-group--reactions") as? HTMLElement)
                assertFalse(reactions.allOf("button").any { it.barName() in setOf("Mikrofon", "Kamera") })
            }
        }
}
