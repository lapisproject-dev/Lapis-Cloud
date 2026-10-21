package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapPositionDto
import network.lapis.cloud.shared.domain.EncounterMapSelfDto
import network.lapis.cloud.shared.domain.EncounterMapSnapshotDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.167 -- the map layer in the real room: closed by default, opened by the dock button (and its twin in "Mehr"), a keyboard focus trap, Escape that
 * closes in the right order, closing never leaves the map, the dock stays reachable, the button names the state, and a guest has nothing of it.
 */
class EncounterMapOverlayDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
        const val NOTICE = "Hinweis <b>fett</b> zur Karte."
    }

    private val mapSpace = testSpace(profile = EncounterProfile.ASSEMBLY, roomLayout = EncounterRoomLayout.HALL, mapEnabled = true)
    private val clock = 1_000_000.0
    private val me = testPerson("me", name = "Ich Selbst")
    private val berta = testPerson("c2", name = "Berta Beispiel")

    private fun pos(
        id: String,
        x: Int,
        y: Int,
    ) = EncounterMapPositionDto(id, x, y, EncounterMapFacing.NORTH, "AB")

    private suspend fun withMapRoom(
        onMap: Boolean = false,
        notice: String? = NOTICE,
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        val snapshot = routeOf { rpcService<IEncounterSpaceService>().mapSnapshot("space-1") }
        val step = routeOf { rpcService<IEncounterSpaceService>().mapStep("space-1", EncounterMapFacing.NORTH) }
        val teleport = routeOf { rpcService<IEncounterSpaceService>().mapTeleport("space-1", EncounterMapAnchor.PULPIT) }
        val leave = routeOf { rpcService<IEncounterSpaceService>().mapLeave("space-1") }
        withEncounterRoom(
            entry = testEntry(),
            peopleOf = { listOf(me, berta) },
            clock = { clock },
            space = mapSpace,
            mapRoomNotice = notice,
            extraRespond = { request ->
                when (request.rpcRoute) {
                    snapshot ->
                        request.answerWith(
                            jsonOf(
                                EncounterMapSnapshotDto.serializer(),
                                EncounterMapSnapshotDto(
                                    "aa",
                                    1,
                                    if (onMap) listOf(pos("me", 5, 7), pos("c2", 3, 3)) else listOf(pos("c2", 3, 3)),
                                    if (onMap) EncounterMapSelfDto(onMap = true, x = 5, y = 7) else EncounterMapSelfDto(onMap = false),
                                ),
                            ),
                        )
                    step, teleport ->
                        request.answerWith(
                            jsonOf(EncounterMapSelfDto.serializer(), EncounterMapSelfDto(onMap = true, x = 5, y = 6)),
                        )
                    leave -> request.answerWith("null")
                    else -> null
                }
            },
            block = block,
        )
    }

    private fun HTMLElement.q(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun HTMLElement.layer(): HTMLElement = q(".lapis-encounter-map-overlay")

    private fun HTMLElement.openButton(): HTMLElement = q(".lapis-encounter-map-open")

    private fun HTMLElement.isLayerOpen(): Boolean = !layer().hasAttribute("hidden")

    private fun key(
        target: HTMLElement,
        name: String,
        shift: Boolean = false,
    ): KeyboardEvent {
        val event = KeyboardEvent("keydown", KeyboardEventInit(key = name, shiftKey = shift, bubbles = true, cancelable = true))
        target.dispatchEvent(event)
        return event
    }

    /** The sheet's width, 0 while it is closed (a closed sheet is not rendered at all). */
    private fun HTMLElement.sheetWidth(): Double =
        (querySelector(".lapis-encounter-more-sheet") as? HTMLElement)?.getBoundingClientRect()?.width ?: 0.0

    private suspend fun HTMLElement.awaitButton() = awaitUntil("the map button") { querySelector(".lapis-encounter-map-open") != null }

    private suspend fun HTMLElement.openLayer() {
        awaitButton()
        openButton().click()
        awaitUntil("the layer is open") { isLayerOpen() && layer().getBoundingClientRect().width > 0 }
    }

    @Test
    fun theLayerIsClosedByDefault_andTheDockButtonPointsAtIt(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.awaitButton()
                val button = element.openButton()
                assertEquals("Karte öffnen", button.getAttribute("aria-label"))
                assertEquals("false", button.getAttribute("aria-expanded"))
                assertEquals("lapis-encounter-map-overlay", button.getAttribute("aria-controls"))
                assertEquals("dialog", button.getAttribute("aria-haspopup"))
                val layer = element.layer()
                assertTrue(layer.hasAttribute("hidden"))
                assertEquals("none", window.getComputedStyle(layer).display)
                val panel = layer.q(".lapis-encounter-map-overlay__panel")
                assertEquals("dialog", panel.getAttribute("role"))
                assertEquals("true", panel.getAttribute("aria-modal"))
                assertEquals("lapis-encounter-map-overlay", panel.id)
                assertEquals("Karte", document.getElementById(panel.getAttribute("aria-labelledby")!!)!!.textContent)
                // the button stands in the panels group directly after the chat ("Mehr" is not rendered while nothing sits in the sheet)
                val names = element.allOf(".lapis-encounter-control-group--panels > button").map { it.getAttribute("aria-label") }
                assertEquals(listOf("Chat", "Karte öffnen"), names)
            }
        }

    @Test
    fun aClickOpensTheLayer_andTheFocusLandsInsideIt(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.openLayer()
                assertEquals("true", element.openButton().getAttribute("aria-expanded"))
                val panel = element.layer().q(".lapis-encounter-map-overlay__panel")
                awaitUntil("the focus is in the panel") { panel.contains(document.activeElement) }
                // not on the map: the offer to step onto it comes first
                assertTrue(
                    document.activeElement!!
                        .textContent
                        .orEmpty()
                        .contains("Auf die Karte"),
                )
            }
            withMapRoom(onMap = true) { _, element ->
                awaitUntil("on the map") { element.allOf(".lapis-encounter-map__figure.is-self").size == 1 }
                element.openLayer()
                val grid = element.q(".lapis-encounter-map__grid")
                awaitUntil("the focus is on the board") { document.activeElement === grid }
            }
        }

    @Test
    fun theLayerSitsAboveTheDock_andTheDockStaysReachable(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.awaitButton()
                // the test page has no header: make the room taller than the window, as a real room is, so the sticky dock rests on the window's bottom edge
                element.q(".lapis-encounter-room").style.minHeight = "${window.innerHeight + 400}px"
                element.openLayer()
                val dock = element.q(".lapis-encounter-dock").getBoundingClientRect()
                val layer = element.layer()
                assertEquals("fixed", window.getComputedStyle(layer).position)
                assertTrue(
                    layer.getBoundingClientRect().bottom <= dock.top + 0.75,
                    "layer ${layer.getBoundingClientRect().bottom} dock ${dock.top}",
                )
                assertTrue(layer.parentElement!!.classList.contains("lapis-encounter-room"), "a sibling of the dock, not inside it")
                assertTrue(element.q(".lapis-encounter-dock").parentElement === layer.parentElement)
                // the dock button is not covered: the element at its centre is the button
                val rect = element.openButton().getBoundingClientRect()
                val top = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
                assertTrue(element.openButton().contains(top), "the dock button stays on top: ${top?.className}")
                // no ancestor of the layer or of the dock moves
                var node: HTMLElement? = layer.parentElement as? HTMLElement
                while (node != null && node != document.body) {
                    val style = window.getComputedStyle(node)
                    assertEquals("none", style.transform, "transform on ${node.className}")
                    assertEquals("none", style.filter, "filter on ${node.className}")
                    node = node.parentElement as? HTMLElement
                }
            }
        }

    @Test
    fun tabWrapsInsideThePanel_inBothDirections(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.openLayer()
                val panel = element.layer().q(".lapis-encounter-map-overlay__panel")
                awaitUntil("the focus is in the panel") { panel.contains(document.activeElement) }
                val items =
                    element.layer().let { l ->
                        l.allOf("button, summary, [tabindex=\"0\"]").filter { it.getClientRects().length > 0 }
                    }
                assertTrue(items.size >= 3, "close button, the disclosure, the board: ${items.map { it.tagName }}")
                val first = items.first()
                val last = items.last()
                assertTrue(first.classList.contains("btn-close"), "the close button comes first")
                last.focus()
                val forward = key(last, "Tab")
                assertTrue(forward.defaultPrevented)
                assertEquals(first, document.activeElement)
                val backward = key(first, "Tab", shift = true)
                assertTrue(backward.defaultPrevented)
                assertEquals(last, document.activeElement)
                // inside the panel Tab is left to the browser
                items[1].focus()
                val middle = key(items[1], "Tab")
                assertFalse(middle.defaultPrevented)
            }
        }

    @Test
    fun escape_theCloseButton_andTheScrim_closeTheLayer_andNeverLeaveTheMap(): Promise<Unit> =
        formTest {
            val leave = routeOf { rpcService<IEncounterSpaceService>().mapLeave("space-1") }
            withMapRoom(onMap = true) { rig, element ->
                awaitUntil("on the map") { element.allOf(".lapis-encounter-map__figure.is-self").size == 1 }
                // Escape from the dock (the focus went there by mouse): a document-level Escape closes the layer
                element.openLayer()
                key(element.openButton(), "Escape")
                awaitUntil("closed by Escape") { !element.isLayerOpen() }
                awaitUntil("the focus is back on the dock button") { document.activeElement === element.openButton() }
                assertEquals("false", element.openButton().getAttribute("aria-expanded"))
                // the close button
                element.openLayer()
                element.layer().q(".btn-close").click()
                awaitUntil("closed by the close button") { !element.isLayerOpen() }
                awaitUntil("focus back") { document.activeElement === element.openButton() }
                // the scrim
                element.openLayer()
                element.layer().q(".lapis-encounter-map-overlay__scrim").dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true)))
                awaitUntil("closed by the scrim") { !element.isLayerOpen() }
                delay(100)
                assertFalse(rig.requests.any { it.isRpc && it.rpcRoute == leave }, "closing the layer never leaves the map")
                assertEquals(1, element.allOf(".lapis-encounter-map__figure.is-self").size, "still on the map")
            }
        }

    @Test
    fun escapeOnTheMap_firstMovesToLeaveTheMap_thenCloses_andOffTheMapClosesAtOnce(): Promise<Unit> =
        formTest {
            withMapRoom(onMap = true) { _, element ->
                awaitUntil("on the map") { element.allOf(".lapis-encounter-map__figure.is-self").size == 1 }
                element.openLayer()
                val grid = element.q(".lapis-encounter-map__grid")
                awaitUntil("focus on the board") { document.activeElement === grid }
                key(grid, "Escape")
                val leaveButton =
                    element
                        .allOf(
                            ".lapis-encounter-map button",
                        ).first { it.textContent.orEmpty().contains("Karte verlassen") }
                assertEquals(leaveButton, document.activeElement)
                assertTrue(element.isLayerOpen(), "the layer stays open after the first Escape")
                key(leaveButton, "Escape")
                awaitUntil("closed by the second Escape") { !element.isLayerOpen() }
            }
            withMapRoom(onMap = false) { _, element ->
                element.openLayer()
                val grid = element.q(".lapis-encounter-map__grid")
                grid.focus()
                key(grid, "Escape")
                awaitUntil("closed at once") { !element.isLayerOpen() }
            }
        }

    @Test
    fun theButtonNamesTheState_andShowsADotOnlyWhileOnTheMap(): Promise<Unit> =
        formTest {
            withMapRoom(onMap = false) { _, element ->
                element.awaitButton()
                assertEquals("Karte öffnen", element.openButton().getAttribute("aria-label"))
                assertFalse(element.q(".lapis-encounter-map-open__dot").classList.contains("is-on"))
                assertEquals("none", window.getComputedStyle(element.q(".lapis-encounter-map-open__dot")).display)
            }
            withMapRoom(onMap = true) { _, element ->
                awaitUntil("named as on the map") {
                    element.querySelector(".lapis-encounter-map-open")?.getAttribute("aria-label") == "Karte öffnen, Sie sind auf der Karte"
                }
                assertTrue(element.q(".lapis-encounter-map-open__dot").classList.contains("is-on"))
                assertEquals("block", window.getComputedStyle(element.q(".lapis-encounter-map-open__dot")).display)
                assertEquals("true", element.q(".lapis-encounter-map-open__dot").getAttribute("aria-hidden"))
            }
        }

    @Test
    fun thePrivacyLineIsAlwaysThere_theNoticeSitsBehindMehr_asText(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.awaitButton()
                val line = element.q(".lapis-encounter-map__privacy-line")
                assertEquals("Ihre Position sehen nur anwesende Mitglieder. Nichts wird gespeichert.", line.textContent)
                val details = element.q(".lapis-encounter-map-overlay details")
                assertEquals("Mehr", details.q("summary").textContent)
                assertFalse(details.hasAttribute("open"), "closed by default")
                val notice = details.q(".lapis-encounter-map__notice")
                assertEquals(NOTICE, notice.textContent, "the notice is text: a tag in it stays literal")
                assertNull(notice.querySelector("b"))
            }
            withMapRoom(notice = null) { _, element ->
                element.awaitButton()
                assertEquals(null, element.querySelector(".lapis-encounter-map-overlay details"))
                assertTrue(element.querySelector(".lapis-encounter-map__privacy-line") != null)
            }
        }

    @Test
    fun theKeysAndTheAnchorsStillWorkInsideTheLayer(): Promise<Unit> =
        formTest {
            val step = routeOf { rpcService<IEncounterSpaceService>().mapStep("space-1", EncounterMapFacing.NORTH) }
            val teleport = routeOf { rpcService<IEncounterSpaceService>().mapTeleport("space-1", EncounterMapAnchor.PULPIT) }
            withMapRoom(onMap = true) { rig, element ->
                awaitUntil("on the map") { element.allOf(".lapis-encounter-map__figure.is-self").size == 1 }
                element.openLayer()
                val grid = element.q(".lapis-encounter-map__grid")
                awaitUntil("focus on the board") { document.activeElement === grid }
                key(grid, "ArrowUp")
                awaitUntil("a step was asked of the server") { rig.requests.any { it.isRpc && it.rpcRoute == step } }
                element.allOf(".lapis-encounter-map button").first { it.textContent.orEmpty().trim() == "Zum Podium" }.click()
                awaitUntil("a teleport was asked of the server") { rig.requests.any { it.isRpc && it.rpcRoute == teleport } }
            }
        }

    @Test
    fun threeQuickAnchorClicks_sendOneJump_andTheButtonsCoolDown(): Promise<Unit> =
        formTest {
            val teleport = routeOf { rpcService<IEncounterSpaceService>().mapTeleport("space-1", EncounterMapAnchor.PULPIT) }
            withMapRoom(onMap = true) { rig, element ->
                awaitUntil("on the map") { element.allOf(".lapis-encounter-map__figure.is-self").size == 1 }
                element.openLayer()
                val anchors =
                    element.allOf(".lapis-encounter-map button").filter {
                        it.textContent.orEmpty().trim().let { t ->
                            t.startsWith("Zum ") ||
                                t.startsWith("Zur ")
                        }
                    }
                assertEquals(3, anchors.size)
                anchors.forEach { it.click() }
                awaitUntil("a teleport was asked of the server") { rig.requests.any { it.isRpc && it.rpcRoute == teleport } }
                delay(100)
                assertEquals(1, rig.requests.count { it.isRpc && it.rpcRoute == teleport })
                assertTrue(anchors.all { it.getAttribute("aria-disabled") == "true" && !it.hasAttribute("disabled") })
                awaitUntil("the buttons are free again") { anchors.none { it.hasAttribute("aria-disabled") } }
            }
        }

    @Test
    fun theLayerSurvivesAFullscreenToggle(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.openLayer()
                val room = element.q(".lapis-encounter-room")
                room.classList.add("is-pseudo-fullscreen")
                delay(50)
                assertTrue(element.isLayerOpen())
                assertEquals("fixed", window.getComputedStyle(element.layer()).position)
                assertTrue(element.layer().getBoundingClientRect().height > 0)
                room.classList.remove("is-pseudo-fullscreen")
            }
        }

    @Test
    fun openingChatOrTheSheetClosesTheLayer(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.openLayer()
                element.barControl("Chat").click()
                awaitUntil("the layer closed when the chat opened") { !element.isLayerOpen() }
                assertEquals("false", element.openButton().getAttribute("aria-expanded"))
            }
        }

    @Test
    fun onANarrowBar_theMapButtonMovesIntoMehrBeforeTheChat_andItsTwinOpensTheLayer(): Promise<Unit> =
        formTest {
            withMapRoom { _, element ->
                element.awaitButton()

                fun width(px: Int) {
                    (element.q(".lapis-encounter-room").parentElement as HTMLElement).style.width = "${px}px"
                }
                // at every width: when the chat sits in the sheet the map button does too (it goes first)
                for (px in 200..900 step 20) {
                    width(px)
                    delay(100)
                    val chatMoved = element.barControl("Chat").classList.contains("lapis-encounter-control-overflowed")
                    val mapMoved = element.openButton().classList.contains("lapis-encounter-control-overflowed")
                    if (chatMoved) assertTrue(mapMoved, "width $px: the map button goes into the sheet before the chat")
                }
                width(200)
                awaitUntil(
                    "the map button sits in the sheet",
                ) { element.openButton().classList.contains("lapis-encounter-control-overflowed") }
                element.barControl("Mehr").click()
                awaitUntil("the sheet is open") { element.sheetWidth() > 0 }
                val twin =
                    element.allOf(".lapis-encounter-more-sheet button").first { it.textContent.orEmpty().trim() == "Karte öffnen" }
                assertTrue(twin.getBoundingClientRect().width > 0, "the twin is shown")
                twin.click()
                awaitUntil("the layer is open") { element.isLayerOpen() }
                assertEquals(0.0, element.sheetWidth(), "the sheet closed")
                // Escape closes the layer; the opener is in the sheet, so the focus goes to "Mehr"
                key(element.layer().q(".lapis-encounter-map-overlay__panel"), "Escape")
                awaitUntil("closed") { !element.isLayerOpen() }
                awaitUntil("the focus is on Mehr") { document.activeElement === element.barControl("Mehr") }
            }
        }

    @Test
    fun aGuest_hasNoLayer_noButton_andNoTwin(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { listOf(testPerson("me", name = "Ich Selbst", isGuest = true), berta) },
                clock = { clock },
                space = mapSpace,
                mapRoomNotice = NOTICE,
            ) { rig, element ->
                delay(300)
                (element.q(".lapis-encounter-room").parentElement as HTMLElement).style.width = "320px"
                delay(150)
                assertNull(element.querySelector(".lapis-encounter-map-overlay"))
                assertNull(element.querySelector(".lapis-encounter-map-open"))
                assertNull(element.querySelector("[aria-controls='lapis-encounter-map-overlay']"))
                assertTrue(element.allOf("button").none { it.textContent.orEmpty().contains("Karte öffnen") })
                assertTrue(element.allOf("button").none { it.getAttribute("aria-label").orEmpty().contains("Karte") })
                assertNull(rig.room.mapOpenButton)
            }
        }
}
