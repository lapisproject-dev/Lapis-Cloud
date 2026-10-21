package network.lapis.cloud.client.encounter

import io.kvision.html.div
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapPositionDto
import network.lapis.cloud.shared.domain.EncounterMapTemplates
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
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

/** V1.9.166 -- the grid map as DOM: one tab stop, the keys, the figures (moved on themselves), no name on the board, no ancestor that moves, the pad. */
class EncounterMapDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private class Handler : EncounterMapHandler {
        val calls = mutableListOf<String>()

        override fun step(dir: EncounterMapFacing) {
            calls += "step:${dir.name}"
        }

        override fun walkTo(index: Int) {
            calls += "walk:$index"
        }

        override fun teleport(anchor: EncounterMapAnchor) {
            calls += "teleport:${anchor.name}"
        }

        override fun enterMap() {
            calls += "enter"
        }

        override fun leaveMap() {
            calls += "leave"
        }

        override fun describeAloud() {
            calls += "describe"
        }
    }

    private val template = EncounterMapTemplates.of(EncounterRoomLayout.HALL)!!

    private fun model(
        vararg people: EncounterMapPositionDto,
        selfOn: Boolean = true,
        canWalk: Boolean = true,
        near: List<String> = emptyList(),
        more: Int = 0,
        anchorsBusy: Boolean = false,
    ) = EncounterMapModel(
        template = template,
        people = people.toList(),
        selfId = "me",
        selfOnMap = selfOn,
        canWalk = canWalk,
        status = if (selfOn) "Reihe 8, Spalte 6" else "Sie stehen nicht auf der Karte.",
        near = near,
        nearMore = more,
        anchorsBusy = anchorsBusy,
    )

    private fun person(
        id: String,
        x: Int,
        y: Int,
        initials: String = "AB",
    ) = EncounterMapPositionDto(id, x, y, EncounterMapFacing.NORTH, initials)

    /** How often the layer around the view asked to be closed (Escape that the view did not use up). */
    private var closedByLayer = 0

    private suspend fun withMap(
        canWalk: Boolean = true,
        notice: String? = null,
        block: suspend (EncounterMapView, Handler, HTMLElement) -> Unit,
    ) {
        mountedForm("encounter-map") { root, element ->
            val wrapper = root.div(className = "lapis-encounter-room lapis-encounter")
            val handler = Handler()
            // V1.9.167: the view lives in the map layer (which owns the heading, the privacy line and the notice)
            var view: EncounterMapView? = null
            val overlay =
                EncounterMapOverlay(
                    parent = wrapper,
                    notice = notice,
                    canWalk = canWalk,
                    focusInitial = { view?.focusBoard() },
                    onOpened = {},
                    onClosed = { closedByLayer++ },
                )
            view =
                EncounterMapView(
                    host = overlay.host,
                    terms = termsFor(EncounterProfile.ASSEMBLY),
                    template = template,
                    handler = handler,
                    canWalk = canWalk,
                )
            overlay.open()
            try {
                block(view, handler, element())
            } finally {
                // the layer listens on the document while it is open: a test must not leave it behind
                overlay.dispose()
            }
        }
    }

    private fun HTMLElement.board(): HTMLElement = querySelector(".lapis-encounter-map__grid") as HTMLElement

    private fun key(
        target: HTMLElement,
        name: String,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ): KeyboardEvent {
        val event =
            KeyboardEvent(
                "keydown",
                KeyboardEventInit(key = name, ctrlKey = ctrl, altKey = alt, metaKey = meta, bubbles = true, cancelable = true),
            )
        target.dispatchEvent(event)
        return event
    }

    @Test
    fun theBoard_isOneTabStop_agroupNamedKarte_describedByTheStatus_with96HiddenTiles(): Promise<Unit> =
        formTest {
            withMap { view, _, element ->
                view.render(model(person("me", 5, 7)))
                awaitUntil("tiles") { element.allOf(".lapis-encounter-map__tile").size == 96 }
                val board = element.board()
                assertEquals("group", board.getAttribute("role"))
                assertEquals("Karte", board.getAttribute("aria-label"))
                assertEquals("0", board.getAttribute("tabindex"))
                val status = element.querySelector("#lapis-encounter-map-status")
                assertNotNull(status)
                assertEquals("lapis-encounter-map-status", board.getAttribute("aria-describedby"))
                assertEquals("Reihe 8, Spalte 6", status.textContent)
                val tiles = element.allOf(".lapis-encounter-map__tile")
                assertTrue(tiles.all { it.getAttribute("aria-hidden") == "true" })
                assertTrue(tiles.all { !it.hasAttribute("title") && !it.hasAttribute("aria-label") })
                // exactly one tab stop on the board (the buttons around it are separate controls)
                assertEquals(1, element.allOf(".lapis-encounter-map__grid[tabindex]").size)
                assertTrue(element.allOf(".lapis-encounter-map__tile[tabindex]").isEmpty())
                // the only data attribute is the tile index
                val dataAttributes =
                    element
                        .allOf("*")
                        .flatMap { el ->
                            (0 until el.attributes.length).map { el.attributes.item(it)!!.name }
                        }.filter { it.startsWith("data-") }
                        .toSet()
                assertEquals(setOf("data-cell"), dataAttributes)
            }
        }

    @Test
    fun arrowKeysAndWasd_step_aKeyWithAModifier_isLeftToTheBrowser(): Promise<Unit> =
        formTest {
            withMap { view, handler, element ->
                view.render(model(person("me", 5, 7)))
                awaitUntil("board") { element.querySelector(".lapis-encounter-map__grid") != null }
                val board = element.board()
                mapOf(
                    "ArrowUp" to "NORTH",
                    "ArrowDown" to "SOUTH",
                    "ArrowLeft" to "WEST",
                    "ArrowRight" to "EAST",
                    "w" to "NORTH",
                    "A" to "WEST",
                    "s" to "SOUTH",
                    "D" to "EAST",
                ).forEach { (name, dir) ->
                    handler.calls.clear()
                    val event = key(board, name)
                    assertEquals(listOf("step:$dir"), handler.calls, name)
                    assertTrue(event.defaultPrevented, "$name must not scroll the page")
                }
                handler.calls.clear()
                listOf(key(board, "ArrowUp", ctrl = true), key(board, "w", alt = true), key(board, "d", meta = true)).forEach {
                    assertFalse(it.defaultPrevented, "a shortcut of the browser is left alone")
                }
                assertTrue(handler.calls.isEmpty())
                key(board, "Tab")
                key(board, "x")
                assertTrue(handler.calls.isEmpty())
            }
        }

    @Test
    fun theLKey_speaksThePlace_andEscape_movesTheFocusToLeaveTheMap_onlyWhileOnTheMap(): Promise<Unit> =
        formTest {
            withMap { view, handler, element ->
                view.render(model(person("me", 5, 7)))
                awaitUntil("leave button") { element.allOf("button").any { it.textContent.orEmpty().contains("Karte verlassen") } }
                val board = element.board()
                key(board, "l")
                assertEquals(listOf("describe"), handler.calls)
                closedByLayer = 0
                val first = key(board, "Escape")
                val leave = element.allOf("button").first { it.textContent.orEmpty().contains("Karte verlassen") }
                assertEquals(leave, document.activeElement)
                assertTrue(first.defaultPrevented, "the first Escape is used up by the view: the layer stays open")
                assertEquals(0, closedByLayer)
                // a second Escape (focus on "Karte verlassen") is left alone: the layer around the view closes
                key(leave, "Escape")
                assertEquals(1, closedByLayer, "the second Escape reaches the layer")
            }
            withMap { view, _, element ->
                view.render(model(selfOn = false))
                awaitUntil(
                    "enter button",
                ) { element.allOf("button").any { it.textContent.orEmpty().contains("Auf die Karte") && isShown(it) } }
                closedByLayer = 0
                key(element.board(), "Escape")
                assertEquals(1, closedByLayer, "off the map Escape is left to the layer, which closes")
            }
        }

    @Test
    fun aClickOnATile_walksThere_anOfficeHolderCannot(): Promise<Unit> =
        formTest {
            withMap { view, handler, element ->
                view.render(model(person("me", 5, 7)))
                awaitUntil("tiles") { element.allOf(".lapis-encounter-map__tile").size == 96 }
                val tile = element.allOf(".lapis-encounter-map__tile")[5 + 12 * 4]
                tile.dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
                assertEquals(listOf("walk:${5 + 12 * 4}"), handler.calls)
            }
            withMap(canWalk = false) { view, handler, element ->
                view.render(model(person("other", 5, 7), selfOn = false, canWalk = false))
                awaitUntil("tiles") { element.allOf(".lapis-encounter-map__tile").size == 96 }
                element.allOf(".lapis-encounter-map__tile")[40].dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true)))
                key(element.board(), "ArrowUp")
                assertTrue(handler.calls.isEmpty())
                assertTrue(
                    element.allOf("button").none { it.textContent.orEmpty().contains("Auf die Karte") && isShown(it) },
                    "no entering for a viewer",
                )
            }
        }

    private fun isShown(element: HTMLElement): Boolean = window.getComputedStyle(element).display != "none"

    @Test
    fun theFigures_moveOnThemselves_withCustomProperties_andKeepTheirElement(): Promise<Unit> =
        formTest {
            withMap { view, _, element ->
                view.render(model(person("me", 5, 7), person("other", 6, 7, "CD")))
                awaitUntil("two figures") { element.allOf(".lapis-encounter-map__figure").size == 2 }
                val first = element.allOf(".lapis-encounter-map__figure").first()
                assertEquals("true", first.getAttribute("aria-hidden"))
                assertTrue(first.classList.contains("is-self"))
                assertTrue(first.classList.contains("is-n"))
                assertEquals("5", first.style.getPropertyValue("--x"))
                assertEquals("7", first.style.getPropertyValue("--y"))
                view.render(model(person("me", 4, 6).copy(facing = EncounterMapFacing.WEST), person("other", 6, 7, "CD")))
                awaitUntil("moved") { first.style.getPropertyValue("--x") == "4" }
                assertEquals("6", first.style.getPropertyValue("--y"))
                assertTrue(first.classList.contains("is-w") && !first.classList.contains("is-n"))
                assertEquals(first, element.allOf(".lapis-encounter-map__figure").first(), "the element stays, only its properties change")
                // the transform is on the figure itself and moves it by whole tiles
                val figureWidth = first.getBoundingClientRect().width
                val grid = element.board().getBoundingClientRect()
                assertTrue(
                    figureWidth > 0 && kotlin.math.abs(figureWidth - grid.width / 12) < 1.0,
                    "a figure is one tile wide: $figureWidth vs ${grid.width / 12}",
                )
                val expectedLeft = grid.left + 4 * figureWidth
                assertTrue(kotlin.math.abs(first.getBoundingClientRect().left - expectedLeft) < 1.5, "x=4 sits four tiles from the left")
                // a person who left the map loses the figure
                view.render(model(person("me", 4, 6)))
                awaitUntil("one figure") { element.allOf(".lapis-encounter-map__figure").size == 1 }
            }
        }

    @Test
    fun noAncestorOfTheBoard_hasATransformAFilterOrAContainment(): Promise<Unit> =
        formTest {
            withMap { view, _, element ->
                view.render(model(person("me", 5, 7)))
                awaitUntil("board") { element.querySelector(".lapis-encounter-map__grid") != null }
                var node: HTMLElement? = element.board().parentElement as? HTMLElement
                val seen = mutableListOf<String>()
                while (node != null && node != document.body) {
                    val style = window.getComputedStyle(node)
                    assertEquals("none", style.transform, "transform on ${node.className}")
                    assertEquals("none", style.filter, "filter on ${node.className}")
                    assertEquals(
                        "none",
                        style.getPropertyValue("backdrop-filter").ifEmpty { "none" },
                        "backdrop-filter on ${node.className}",
                    )
                    assertTrue(style.getPropertyValue("contain").let { it.isEmpty() || it == "none" }, "containment on ${node.className}")
                    assertTrue(
                        style.getPropertyValue("container-type").let { it.isEmpty() || it == "normal" },
                        "container-type on ${node.className}",
                    )
                    seen += node.className
                    node = node.parentElement as? HTMLElement
                }
                assertTrue(seen.isNotEmpty())
                // the layer around the board is the fixed one; the board keeps no scroll margin of its own any more
                assertEquals("fixed", window.getComputedStyle(element.querySelector(".lapis-encounter-map-overlay")!!).position)
            }
        }

    @Test
    fun noNameOnTheBoard_theVicinityListIsTheOnlyPlaceForNames(): Promise<Unit> =
        formTest {
            withMap { view, _, element ->
                view.render(model(person("me", 5, 7), person("other", 6, 7, "BE"), near = listOf("Berta Beispiel"), more = 3))
                awaitUntil("list") { element.querySelector(".lapis-encounter-map__near li") != null }
                val board = element.querySelector(".lapis-encounter-map__board") as HTMLElement
                assertFalse(board.outerHTML.contains("Berta"), "no name on the board")
                assertTrue(element.allOf("[title]").isEmpty())
                val items = element.allOf(".lapis-encounter-map__near li").map { it.textContent.orEmpty().trim() }
                assertEquals(listOf("Berta Beispiel", "und 3 weitere"), items)
                assertEquals("In Ihrer Nähe", element.querySelector(".lapis-encounter-map__near-heading")?.textContent?.trim())
                view.render(model(person("me", 5, 7), near = emptyList()))
                awaitUntil("list gone") { element.querySelector(".lapis-encounter-map__near li") == null }
            }
        }

    @Test
    fun theEnterAndLeaveButtons_followTheState_andTheNoticeIsShownToAMemberOnly(): Promise<Unit> =
        formTest {
            withMap(notice = "Karte und Position. In diesem Raum gibt es eine Karte.") { view, handler, element ->
                view.render(model(selfOn = false))
                awaitUntil(
                    "enter button",
                ) { element.allOf("button").any { it.textContent.orEmpty().contains("Auf die Karte") && isShown(it) } }
                assertTrue(element.allOf("button").none { it.textContent.orEmpty().contains("Karte verlassen") && isShown(it) })
                assertTrue(
                    element
                        .querySelector(".lapis-encounter-map__notice")!!
                        .textContent
                        .orEmpty()
                        .startsWith("Karte und Position."),
                )
                element.allOf("button").first { it.textContent.orEmpty().contains("Auf die Karte") }.click()
                assertEquals(listOf("enter"), handler.calls)
                view.render(model(person("me", 5, 7)))
                awaitUntil("leave button") {
                    element.allOf("button").any {
                        it.textContent.orEmpty().contains("Karte verlassen") &&
                            isShown(it)
                    }
                }
                val anchors =
                    element.allOf("button").map { it.textContent.orEmpty().trim() }.filter {
                        it.startsWith("Zum ") ||
                            it.startsWith("Zur ")
                    }
                assertEquals(listOf("Zum Eingang", "Zum Podium", "Zur Mitte"), anchors)
                element.allOf("button").first { it.textContent.orEmpty().trim() == "Zum Podium" }.click()
                assertEquals("teleport:PULPIT", handler.calls.last())
            }
            withMap(canWalk = false, notice = "darf nicht erscheinen") { view, _, element ->
                view.render(model(selfOn = false, canWalk = false))
                awaitUntil("board") { element.querySelector(".lapis-encounter-map__grid") != null }
                assertNull(element.querySelector(".lapis-encounter-map__notice"))
            }
        }

    @Test
    fun theAnchorButtons_showACooldown_withAriaDisabled_neverTheNativeDisabled_andKeepTheFocus(): Promise<Unit> =
        formTest {
            withMap { view, _, element ->
                view.render(model(person("me", 5, 7)))
                val buttons =
                    element.allOf("button").filter {
                        it.textContent.orEmpty().trim().let { t ->
                            t.startsWith("Zum ") ||
                                t.startsWith("Zur ")
                        }
                    }
                assertEquals(3, buttons.size)
                val clicked = buttons[1]
                clicked.focus()
                view.render(model(person("me", 5, 7), anchorsBusy = true))
                buttons.forEach {
                    assertEquals("true", it.getAttribute("aria-disabled"))
                    assertTrue(it.classList.contains("is-cooling"))
                    assertFalse(it.hasAttribute("disabled"))
                }
                assertTrue(document.activeElement === clicked, "the focus stays on the clicked button")
                view.render(model(person("me", 5, 7), anchorsBusy = false))
                buttons.forEach {
                    assertNull(it.getAttribute("aria-disabled"))
                    assertFalse(it.classList.contains("is-cooling"))
                }
                assertTrue(document.activeElement === clicked, "the focus is still there after the cooldown")
            }
        }

    @Test
    fun theDirectionPad_isHiddenOnADesktop_andTheStyleSheetShowsItBelowTheOneBreakpointWith44PxButtons(): Promise<Unit> =
        formTest {
            withMap { view, handler, element ->
                view.render(model(person("me", 5, 7)))
                awaitUntil("pad") { element.querySelector(".lapis-encounter-map__pad") != null }
                val pad = element.querySelector(".lapis-encounter-map__pad") as HTMLElement
                if (window.innerWidth >= 768) assertEquals("none", window.getComputedStyle(pad).display, "no pad on a wide screen")
                assertEquals(4, pad.querySelectorAll("button").length)
                (pad.querySelector(".lapis-encounter-map__pad-button--up") as HTMLElement).click()
                (pad.querySelector(".lapis-encounter-map__pad-button--left") as HTMLElement).click()
                assertEquals(listOf("step:NORTH", "step:WEST"), handler.calls)
                assertEquals(
                    listOf("Nach oben", "Nach links", "Nach rechts", "Nach unten"),
                    pad
                        .allOf("button")
                        .map {
                            it.getAttribute("aria-label")
                        }.sortedBy { listOf("Nach oben", "Nach links", "Nach rechts", "Nach unten").indexOf(it) },
                )
                // the media rule of theme.css
                var rule = ""
                for (i in 0 until document.styleSheets.length) {
                    val rules =
                        document.styleSheets
                            .item(i)!!
                            .asDynamic()
                            .cssRules
                    for (r in 0 until (rules.length as Int)) {
                        val text = rules[r].cssText as String
                        if (text.startsWith("@media") && text.contains("767.98px") && text.contains("lapis-encounter-map__pad")) rule = text
                    }
                }
                assertTrue(rule.contains("display: grid"), "the pad appears in the one mobile breakpoint: $rule")
                assertTrue(rule.contains("44px"), "buttons of at least 44 px")
            }
        }
}
