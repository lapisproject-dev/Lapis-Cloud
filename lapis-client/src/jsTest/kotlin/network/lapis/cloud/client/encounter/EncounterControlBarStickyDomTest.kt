package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.165 -- the control bar of the encounter room is ALWAYS on screen: the bar, the "Mehr" sheet and the device panel live in one
 * `position: sticky; bottom: 0` dock. Measured in a real Chrome in three ways: the page scrolls (window), the room is the scroll root
 * (pseudo fullscreen: `position: fixed`, `overflow-y: auto`), and both again inside a phone-sized viewport box (320 x 568 portrait,
 * 844 x 390 landscape). A real `:fullscreen` is replaced by the pseudo fullscreen rule (`requestFullscreen` is not reliable in headless).
 */
class EncounterControlBarStickyDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                // A ResizeObserver notice ("loop completed with undelivered notifications") is benign layout feedback of the bar's overflow
                // measurement, but Karma counts it as a failure of whatever test is running -- and it can arrive a task after the test body.
                window.addEventListener(
                    "error",
                    { event ->
                        if ((event.asDynamic().message as? String)?.startsWith("ResizeObserver loop") == true) {
                            event.preventDefault()
                            event.stopImmediatePropagation()
                        }
                    },
                    true,
                )
                // Karma's own `window.onerror` is what turns it into a test failure: let it see everything but that notice.
                val w: dynamic = window
                val previous: dynamic = w.onerror
                w.onerror = { message: dynamic, source: dynamic, line: dynamic, column: dynamic, error: dynamic ->
                    if (message is String && message.startsWith("ResizeObserver loop")) {
                        true
                    } else if (previous != null) {
                        previous(message, source, line, column, error)
                    } else {
                        false
                    }
                }
                true
            }
        const val EPS = 0.75
    }

    private var clock = 1_000_000.0
    private val crowd = seatedCrowd()

    private enum class Mode { WINDOW, PSEUDO, BOX, BOX_PSEUDO }

    /** Where the user looks (the viewport rectangle) and what scrolls. */
    private class Frame(
        val top: () -> Double,
        val bottom: () -> Double,
        val left: () -> Double,
        val right: () -> Double,
        val scroller: () -> HTMLElement,
        val cleanup: () -> Unit,
    )

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun HTMLElement.shown(): Boolean = getBoundingClientRect().let { it.width > 0 && it.height > 0 }

    private fun buildFrame(
        mode: Mode,
        room: HTMLElement,
        width: Int,
        height: Int,
    ): Frame {
        val host = room.parentElement as HTMLElement
        val hostParent = host.parentElement
        val hostNext = host.nextSibling

        // The mounted root is looked up by id when the test ends: the host goes back to where it was before the box is removed.
        fun restoreHost(box: HTMLElement) {
            hostParent?.insertBefore(host, hostNext)
            box.remove()
        }

        fun box(transformed: Boolean): HTMLElement {
            val box = document.createElement("div") as HTMLElement
            box.style.position = "fixed"
            box.style.left = "0"
            box.style.top = "0"
            box.style.width = "${width}px"
            box.style.height = "${height}px"
            box.style.zIndex = "5000"
            box.style.background = "white"
            if (transformed) {
                // A transformed ancestor is the containing block of `position: fixed`: the pseudo-fullscreen room fills the box, not the window.
                box.style.setProperty("transform", "translateZ(0)")
                box.style.setProperty("overflow", "hidden")
            } else {
                box.style.overflowY = "auto"
            }
            document.body!!.appendChild(box)
            box.appendChild(host)
            return box
        }
        return when (mode) {
            Mode.WINDOW ->
                Frame(
                    top = { 0.0 },
                    bottom = { window.innerHeight.toDouble() },
                    left = { 0.0 },
                    right = { window.innerWidth.toDouble() },
                    scroller = { document.scrollingElement as HTMLElement },
                    cleanup = { (document.scrollingElement as HTMLElement).scrollTop = 0.0 },
                )
            Mode.PSEUDO -> {
                room.classList.add("is-pseudo-fullscreen")
                Frame(
                    top = { 0.0 },
                    bottom = { window.innerHeight.toDouble() },
                    left = { 0.0 },
                    right = { window.innerWidth.toDouble() },
                    scroller = { room },
                    cleanup = {},
                )
            }
            Mode.BOX -> {
                val b = box(transformed = false)
                Frame(
                    top = { b.getBoundingClientRect().top },
                    bottom = { b.getBoundingClientRect().bottom },
                    left = { b.getBoundingClientRect().left },
                    right = { b.getBoundingClientRect().right },
                    scroller = { b },
                    cleanup = { restoreHost(b) },
                )
            }
            Mode.BOX_PSEUDO -> {
                val b = box(transformed = true)
                room.classList.add("is-pseudo-fullscreen")
                Frame(
                    top = { b.getBoundingClientRect().top },
                    bottom = { b.getBoundingClientRect().bottom },
                    left = { b.getBoundingClientRect().left },
                    right = { b.getBoundingClientRect().right },
                    scroller = { room },
                    cleanup = { restoreHost(b) },
                )
            }
        }
    }

    private suspend fun scrollTo(
        frame: Frame,
        fraction: Double,
    ) {
        val s = frame.scroller()
        val top = (s.scrollHeight - s.clientHeight).coerceAtLeast(0) * fraction
        // `html { scroll-behavior: smooth }` would animate a plain `scrollTop = ...`: jump.
        val options: dynamic = js("({})")
        options.top = top
        options.behavior = "instant"
        s.asDynamic().scrollTo(options)
        delay(80)
    }

    private suspend fun runCheck(
        mode: Mode,
        width: Int,
        height: Int,
    ): Pair<Boolean, Boolean> {
        var sheetChecked = false
        var panelChecked = false
        withEncounterRoom(
            entry = testEntry(),
            peopleOf = { crowd },
            clock = { clock },
            space = testSpace(),
        ) { _, element ->
            assertTrue(stylesLoaded)
            awaitUntil("six seats") { element.occupied() == 6 }
            val room = element.q(".lapis-encounter-room")
            // Tall content above the benches: the room is several viewports high, the benches and the bar are at its end.
            val filler = document.createElement("div") as HTMLElement
            filler.style.height = "${maxOf(window.innerHeight, height) * 3}px"
            room.insertBefore(filler, room.firstChild)
            val frame = buildFrame(mode, room, width, height)
            try {
                delay(150)
                val dock = room.q(".lapis-encounter-dock")
                val tag = "$mode ${width}x$height"

                // The dock is a direct child of the room, carries bar + both sheets, and nothing visible follows it.
                assertEquals(room, dock.parentElement, "$tag: the dock hangs from the room")
                assertEquals(dock, room.q(".lapis-encounter-controls").parentElement, "$tag: the bar is in the dock")
                var next = dock.nextElementSibling as? HTMLElement
                while (next != null) {
                    assertTrue(next.getBoundingClientRect().height < 1.0, "$tag: nothing visible follows the dock (${next.className})")
                    next = next.nextElementSibling as? HTMLElement
                }

                // Ancestors: nothing between the dock and <html> clips or scrolls except what this mode makes the scroll root.
                var node: HTMLElement? = dock.parentElement as? HTMLElement
                while (node != null) {
                    // Only the ancestors up to the nearest scroll root matter: a box around the scroll root does not turn sticky off.
                    if (node === frame.scroller() && node !== document.documentElement) break
                    val overflow = window.getComputedStyle(node).overflowY
                    val scrollRoot = node === frame.scroller() || node === document.body || node === document.documentElement
                    assertTrue(
                        overflow == "visible" ||
                            overflow == "clip" ||
                            scrollRoot ||
                            node.parentElement === document.body &&
                            mode == Mode.BOX,
                        "$tag: ancestor ${node.tagName}.${node.className} has overflow-y $overflow (sticky would break)",
                    )
                    node = node.parentElement as? HTMLElement
                }

                val style = window.getComputedStyle(dock)
                assertEquals("sticky", style.position, tag)
                assertEquals("0px", style.bottom, tag)
                assertEquals("6", style.zIndex, tag)

                val before = room.q(".lapis-encounter-controls").allOfShown().size
                listOf(0.0, 0.5, 1.0).forEach { fraction ->
                    scrollTo(frame, fraction)
                    val r = dock.getBoundingClientRect()
                    val at = "$tag @${(fraction * 100).toInt()}%"
                    assertTrue(r.bottom <= frame.bottom() + EPS, "$at: dock bottom ${r.bottom} <= viewport ${frame.bottom()}")
                    assertTrue(
                        r.bottom >= frame.bottom() - EPS,
                        "$at: dock flush with the viewport bottom ${r.bottom} vs ${frame.bottom()}",
                    )
                    assertTrue(r.top >= frame.top() - EPS, "$at: dock top ${r.top} inside the viewport")
                    assertTrue(r.height > 40, "$at: the bar has a height ${r.height}")
                    if (fraction == 1.0) {
                        val last = room.q(".lapis-encounter-main").getBoundingClientRect()
                        assertTrue(last.bottom <= r.top + EPS, "$at: the last content ${last.bottom} ends above the dock ${r.top}")
                    }
                }
                assertEquals(
                    before,
                    room.q(".lapis-encounter-controls").allOfShown().size,
                    "$tag: the overflow did not change by scrolling",
                )

                // "Mehr" (narrow viewports): the sheet hangs above the dock, inside the viewport.
                scrollTo(frame, 0.5)
                val more =
                    room
                        .querySelectorAll(".lapis-encounter-controls button")
                        .let { list -> (0 until list.length).map { list.item(it) as HTMLElement } }
                        .firstOrNull { it.getAttribute("aria-label") == "Mehr" && it.shown() }
                if (more != null) {
                    more.click()
                    awaitUntil(
                        "$tag: the sheet is open",
                    ) { (room.querySelector(".lapis-encounter-more-sheet") as? HTMLElement)?.shown() == true }
                    val sheet = room.q(".lapis-encounter-more-sheet")
                    assertEquals(dock, sheet.parentElement, "$tag: the sheet is a sibling of the bar")
                    val s = sheet.getBoundingClientRect()
                    val d = dock.getBoundingClientRect()
                    assertTrue(s.bottom <= d.top + EPS, "$tag: sheet ${s.bottom} above dock ${d.top}")
                    assertTrue(s.top >= frame.top() - EPS, "$tag: sheet top ${s.top} inside the viewport")
                    assertTrue(s.left >= frame.left() - EPS && s.right <= frame.right() + EPS, "$tag: sheet width inside the viewport")
                    sheetChecked = true
                    more.click()
                    awaitUntil("$tag: the sheet is closed") {
                        (room.querySelector(".lapis-encounter-more-sheet") as? HTMLElement)?.shown() !=
                            true
                    }
                }

                // The device panel: same rules.
                val gear = room.querySelector("button[aria-controls=lapis-encounter-device-panel]") as? HTMLElement
                if (gear != null && gear.shown()) {
                    gear.click()
                    awaitUntil("$tag: the panel is open") {
                        (room.querySelector("#lapis-encounter-device-panel") as? HTMLElement)?.shown() ==
                            true
                    }
                    val panel = room.q("#lapis-encounter-device-panel")
                    val p = panel.getBoundingClientRect()
                    val d = dock.getBoundingClientRect()
                    assertEquals(dock, panel.parentElement, "$tag: the panel hangs from the dock")
                    assertTrue(p.bottom <= d.top + EPS, "$tag: panel ${p.bottom} above dock ${d.top}")
                    assertTrue(p.top >= frame.top() - EPS, "$tag: panel top ${p.top} inside the viewport")
                    assertTrue(p.left >= frame.left() - EPS && p.right <= frame.right() + EPS, "$tag: panel width inside the viewport")
                    panelChecked = true
                    gear.click()
                }

                // Focus: the scroll root keeps a scroll-padding-bottom of at least the dock height (WCAG 2.4.11), so a focused seat is
                // not scrolled in under the dock. The browser's own focus scrolling is exercised in the window case.
                if (mode == Mode.WINDOW || mode == Mode.PSEUDO || mode == Mode.BOX_PSEUDO) {
                    val root = if (mode == Mode.WINDOW) document.documentElement as HTMLElement else room
                    val padding =
                        window
                            .getComputedStyle(root)
                            .getPropertyValue("scroll-padding-bottom")
                            .removeSuffix("px")
                            .toDoubleOrNull() ?: 0.0
                    assertTrue(padding >= dock.getBoundingClientRect().height - 1.0, "$tag: scroll-padding-bottom $padding covers the dock")
                }
                if (mode == Mode.WINDOW) {
                    scrollTo(frame, 0.0)
                    val seat = room.seats().last()
                    seat.focus()
                    assertTrue(document.activeElement === seat, "$tag: the seat takes focus")
                    // `html { scroll-behavior: smooth }`: the browser scrolls the focused seat into view over a few hundred milliseconds.
                    awaitUntil("$tag: the focused seat is above the dock") {
                        seat.getBoundingClientRect().bottom <= dock.getBoundingClientRect().top + EPS
                    }
                }
            } finally {
                frame.cleanup()
            }
        }
        return sheetChecked to panelChecked
    }

    private fun HTMLElement.allOfShown(): List<HTMLElement> =
        querySelectorAll("button").let { list -> (0 until list.length).map { list.item(it) as HTMLElement } }.filter { it.shown() }

    private fun windowCheck(
        mode: Mode,
        width: Int,
        height: Int,
        expectSheets: Boolean = false,
    ): Promise<Unit> =
        formTest {
            val (sheet, panel) = runCheck(mode, width, height)
            if (expectSheets) {
                assertTrue(sheet, "the \"Mehr\" sheet was opened and measured")
                assertTrue(panel, "the device panel was opened and measured")
            }
        }

    @Test
    fun window_thePageScrolls_theBarStaysAtTheBottom(): Promise<Unit> = windowCheck(Mode.WINDOW, window.innerWidth, window.innerHeight)

    @Test
    fun pseudoFullscreen_theRoomScrolls_theBarStaysAtTheBottom(): Promise<Unit> =
        windowCheck(Mode.PSEUDO, window.innerWidth, window.innerHeight)

    @Test
    fun phonePortrait_320x568_inTheWindowFlow(): Promise<Unit> = windowCheck(Mode.BOX, 320, 568, expectSheets = true)

    @Test
    fun phoneLandscape_844x390_inTheWindowFlow(): Promise<Unit> = windowCheck(Mode.BOX, 844, 390)

    @Test
    fun phonePortrait_320x568_pseudoFullscreen(): Promise<Unit> = windowCheck(Mode.BOX_PSEUDO, 320, 568, expectSheets = true)

    @Test
    fun phoneLandscape_844x390_pseudoFullscreen(): Promise<Unit> = windowCheck(Mode.BOX_PSEUDO, 844, 390)
}
