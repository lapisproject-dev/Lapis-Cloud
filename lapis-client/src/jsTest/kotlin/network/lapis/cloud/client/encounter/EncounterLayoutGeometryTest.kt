package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.allowedFor
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.ParentNode
import org.w3c.dom.Window
import org.w3c.dom.asList
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.153 -- the room's picture MEASURED against its seats, in a real viewport. Why this test exists: the benches of V1.9.152 hung on a frame
 * behind the rows (a 29 px offset by the hint line above, a stretched tile on the phone), the scene objects lay under the opaque pulpit card and the hint
 * lines lay on drawings, and nothing noticed, because the earlier DOM tests only compared variables.
 *
 * The method: the real `EncounterRoom` is mounted, its HTML and every loaded style rule are copied into an `<iframe>` of the width under test, so the
 * media queries of `theme.css` really apply at that width (a test that sets the phone variables by hand does not test the media query). There the boxes
 * are measured: the seat centres against the block drawing, the pitch of the rows, the aisle, no overlap of any hint line or drawing strip with a seat,
 * the front drawings not hidden by the pulpit card, and "Szene aus". No CSS is rebuilt here; the drawing itself (its coordinates) is pinned by the JVM
 * test `EncounterObjectAssetsTest`, so a mask URL that does not load (404 in the test run) does not matter for the boxes measured here.
 */
class EncounterLayoutGeometryTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
        const val TOLERANCE = 2.0
        val widths = listOf(360, 600, 768, 1170)
    }

    private var clock = 1_000_000.0

    // The viewer ("me") has no seat: the hint "choose a seat" and the unseated row are really on screen.
    private val crowd = listOf(testPerson("me", name = "Ich Selbst")) + seatedCrowd(14)

    private class Metrics(
        val seat: Double,
        val gap: Double,
        val aisle: Double,
        val frontStrip: Double,
        val rearStrip: Double,
    ) {
        val pitch get() = seat + gap
    }

    private fun metricsAt(width: Int): Metrics =
        when {
            width >= 768 -> Metrics(seat = 48.0, gap = 8.0, aisle = 32.0, frontStrip = 72.0, rearStrip = 48.0)
            width >= 480 -> Metrics(seat = 44.0, gap = 4.0, aisle = 16.0, frontStrip = 56.0, rearStrip = 40.0)
            else -> Metrics(seat = 44.0, gap = 4.0, aisle = 16.0, frontStrip = 48.0, rearStrip = 40.0)
        }

    /** The CSS of the running page as ONE style text (works for `<style>` text as well as for rules inserted by script). */
    private fun pageCss(): String {
        val css = StringBuilder()
        val sheets = document.styleSheets
        for (i in 0 until sheets.length) {
            val rules: dynamic =
                try {
                    sheets.item(i).asDynamic().cssRules
                } catch (e: Throwable) {
                    null
                }
            if (rules == null || rules == undefined) continue
            for (j in 0 until (rules.length as Int)) css.append(rules[j].cssText as String).append('\n')
        }
        return css.toString()
    }

    private suspend fun inFrame(
        html: String,
        width: Int,
        rootFontSize: String? = null,
        block: suspend (Window, org.w3c.dom.Document) -> Unit,
    ) {
        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.setAttribute("style", "position:fixed;left:0;top:0;border:0;visibility:hidden;width:${width}px;height:1200px")
        val fontRule = if (rootFontSize != null) "html{font-size:$rootFontSize}" else ""
        val head = "<meta charset=\"utf-8\"><style>${pageCss()}\n$fontRule</style>"
        val page = "<!doctype html><html><head>$head</head><body>$html</body></html>"
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                frame.addEventListener("load", { if (cont.isActive) cont.resume(Unit) })
                frame.srcdoc = page
                document.body!!.appendChild(frame)
            }
            // The choice "Szene aus" is stored in the browser and other tests toggle it: a copied room must start with the scene ON.
            frame.contentDocument!!
                .querySelector(".lapis-encounter")
                ?.classList
                ?.remove("lapis-encounter--scene-off")
            delay(50)
            block(frame.contentWindow!!, frame.contentDocument!!)
        } finally {
            frame.remove()
        }
    }

    private suspend fun withRoomHtml(
        profile: EncounterProfile,
        layout: EncounterRoomLayout,
        block: suspend (String) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        window.localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
        withEncounterRoom(
            entry = testEntry(),
            peopleOf = { crowd },
            clock = { clock },
            space = testSpace(profile = profile, roomLayout = layout),
        ) { _, element ->
            awaitUntil("fourteen seats occupied, and the viewer told to choose") {
                element.occupied() >= 14 && element.querySelector(".lapis-encounter-seat-hint") != null
            }
            block(element.outerHTML)
        }
    }

    private fun Element.rect(): DOMRect = getBoundingClientRect()

    private fun DOMRect.centerX() = left + width / 2

    private fun DOMRect.centerY() = top + height / 2

    private fun DOMRect.intersects(other: DOMRect): Boolean =
        min(right, other.right) - max(left, other.left) > 0.5 && min(bottom, other.bottom) - max(top, other.top) > 0.5

    private fun intersectionArea(
        a: DOMRect,
        b: DOMRect,
    ): Double = max(0.0, min(a.right, b.right) - max(a.left, b.left)) * max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))

    private fun near(
        expected: Double,
        actual: Double,
        tolerance: Double,
        message: String,
    ) = assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected, was $actual")

    // Elements of the frame are of the frame's own realm: a checked `as HTMLElement` would fail, so the cast is unchecked.
    private fun org.w3c.dom.Document.q(selector: String): HTMLElement = querySelector(selector).unsafeCast<HTMLElement>()

    private fun ParentNode.all(selector: String): List<HTMLElement> =
        querySelectorAll(selector).asList().map { it.unsafeCast<HTMLElement>() }

    private fun px(value: String): Double = value.trim().removeSuffix("px").toDouble()

    /** Makes the quiet lines visible that a plain room hides (the hint for somebody without a seat, "Platz freigeben", the unseated row). */
    private fun showEverything(doc: org.w3c.dom.Document) {
        doc.querySelectorAll(".lapis-encounter-seat-hint, .lapis-encounter-seat-release, .lapis-encounter-unseated").asList().forEach {
            it.unsafeCast<HTMLElement>().style.setProperty("display", "block", "important")
        }
    }

    private val combinations =
        EncounterProfile.entries.flatMap { profile -> EncounterRoomLayout.entries.filter { it.allowedFor(profile) }.map { profile to it } }

    @Test
    fun theDrawingOfEachBlock_sitsOnItsSeats_atEveryTemplateAndWidth(): Promise<Unit> =
        formTest {
            combinations.forEach { (profile, layout) ->
                withRoomHtml(profile, layout) { html ->
                    widths.forEach { width ->
                        val m = metricsAt(width)
                        inFrame(html, width) { win, doc ->
                            showEverything(doc)
                            val label = "$layout @${width}px"
                            val firstSeat = doc.q(".lapis-encounter-seat")
                            // Sanity: the styles are really there (a bare DOM would pass every box test below by accident).
                            assertEquals(
                                "${m.seat.toInt()}px",
                                win.getComputedStyle(firstSeat).width,
                                "$label: theme.css applies in the frame",
                            )
                            val benches = doc.q(".lapis-encounter-benches")
                            val stretch = benches.classList.contains("lapis-encounter-benches--stretch")
                            val blocks = doc.all(".lapis-encounter-block").filter { it.rect().height > 0 }
                            assertTrue(blocks.size >= 4, "$label: at least two visible rows of two blocks")
                            blocks.forEach { block ->
                                val r = block.rect()
                                val before = win.getComputedStyle(block, "::before")
                                near(r.width, px(before.width), 0.6, "$label: the drawing is as wide as its block")
                                near(r.height, px(before.height), 0.6, "$label: the drawing is as high as its block")
                                near(m.seat, r.height, 0.6, "$label: a block is one seat high")
                                val maskSize = (before.asDynamic().maskSize ?: before.asDynamic().webkitMaskSize) as String
                                if (stretch) {
                                    assertEquals("100% 100%", maskSize, "$label: the bench is stretched over the block")
                                } else {
                                    assertEquals("${px(before.width)}px 100%", maskSize, "$label: the tile is as wide as the block")
                                    val seats = block.all(".lapis-encounter-seat")
                                    assertEquals(3, seats.size)
                                    listOf(24.0, 80.0, 136.0).forEachIndexed { index, cx ->
                                        val seat = seats[index].rect()
                                        near(
                                            r.left + cx * r.width / 160.0,
                                            seat.centerX(),
                                            TOLERANCE,
                                            "$label: chair $index, horizontal centre",
                                        )
                                        near(
                                            r.top + 24.0 * r.height / 48.0,
                                            seat.centerY(),
                                            TOLERANCE,
                                            "$label: chair $index, vertical centre",
                                        )
                                    }
                                }
                                val seats = block.all(".lapis-encounter-seat")
                                near(
                                    r.centerX(),
                                    seats.map { it.rect().centerX() }.average(),
                                    TOLERANCE,
                                    "$label: the seats are centred in the block",
                                )
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun theRowsKeepTheirPitch_andTheAisleKeepsItsWidth_atEveryWidth(): Promise<Unit> =
        formTest {
            withRoomHtml(EncounterProfile.CHURCH_SERVICE, EncounterRoomLayout.NAVE) { html ->
                widths.forEach { width ->
                    val m = metricsAt(width)
                    inFrame(html, width) { _, doc ->
                        val rows = doc.all(".lapis-encounter-row").filter { it.rect().height > 0 }
                        assertTrue(rows.size >= 3, "@${width}px: three visible rows")
                        rows.zipWithNext().forEach { (a, b) ->
                            near(m.pitch, b.rect().top - a.rect().top, 0.5, "@${width}px: pitch of the rows")
                        }
                        rows.forEach { row ->
                            val blocks = row.all(".lapis-encounter-block")
                            near(m.aisle, blocks[1].rect().left - blocks[0].rect().right, 0.5, "@${width}px: the aisle")
                        }
                    }
                }
            }
        }

    @Test
    fun noHintLineAndNoDrawingStripLiesOnASeat_evenWithBigText(): Promise<Unit> =
        formTest {
            combinations.forEach { (profile, layout) ->
                withRoomHtml(profile, layout) { html ->
                    listOf(360 to null, 360 to "200%", 768 to null, 1170 to null).forEach { (width, font) ->
                        inFrame(html, width, rootFontSize = font) { _, doc ->
                            showEverything(doc)
                            val label = "$layout @${width}px font ${font ?: "100%"}"
                            val seats = doc.all(".lapis-encounter-seat").map { it.rect() }.filter { it.width > 0 }
                            assertTrue(seats.isNotEmpty(), "$label: seats")
                            listOf(
                                ".lapis-encounter-seat-hint",
                                ".lapis-encounter-seat-note",
                                ".lapis-encounter-scene-zone--rear",
                                ".lapis-encounter-seat-release",
                                ".lapis-encounter-unseated",
                            ).forEach { selector ->
                                // The release button exists only for a viewer who sits; everything else is on screen for this viewer.
                                if (doc.querySelector(selector) == null && selector.endsWith("release")) return@forEach
                                val box = doc.q(selector).rect()
                                assertTrue(box.width > 0 && box.height > 0, "$label: $selector is shown")
                                seats.forEach { seat -> assertTrue(!box.intersects(seat), "$label: $selector lies on a seat") }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun theFrontDrawings_standAbovePulpit_andNothingOfThemIsCovered(): Promise<Unit> =
        formTest {
            combinations.forEach { (profile, layout) ->
                withRoomHtml(profile, layout) { html ->
                    widths.forEach { width ->
                        val m = metricsAt(width)
                        inFrame(html, width) { _, doc ->
                            val label = "$layout @${width}px"
                            val pulpit = doc.q(".lapis-encounter-pulpit").rect()
                            val front = doc.q(".lapis-encounter-scene-zone--front").rect()
                            near(m.frontStrip, front.height, 0.6, "$label: the front strip")
                            assertTrue(
                                front.bottom <= pulpit.top + 0.5,
                                "$label: the strip ends above the pulpit card (${front.bottom} vs ${pulpit.top})",
                            )
                            val rear = doc.q(".lapis-encounter-scene-zone--rear").rect()
                            near(m.rearStrip, rear.height, 0.6, "$label: the rear strip")
                            val drawings =
                                doc
                                    .all(
                                        ".lapis-encounter-scene-zone .lapis-encounter-scene-object",
                                    ).filter { it.rect().width > 0 }
                            assertTrue(drawings.isNotEmpty(), "$label: drawings are shown")
                            drawings.forEach { drawing ->
                                val box = drawing.rect()
                                val covered = intersectionArea(box, pulpit)
                                assertTrue(
                                    (box.width * box.height - covered) / (box.width * box.height) >= 0.95,
                                    "$label: ${drawing.className} is covered",
                                )
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun szeneAus_takesBothStripsAndEveryBlockDrawing(): Promise<Unit> =
        formTest {
            withRoomHtml(EncounterProfile.ASSEMBLY, EncounterRoomLayout.HALL) { html ->
                widths.forEach { width ->
                    inFrame(html, width) { win, doc ->
                        doc.q(".lapis-encounter").classList.add("lapis-encounter--scene-off")
                        doc
                            .all(
                                ".lapis-encounter-scene-zone",
                            ).forEach { assertEquals(0.0, it.rect().height, "@${width}px: the strip is gone, no gap") }
                        val block = doc.q(".lapis-encounter-block")
                        assertEquals("none", win.getComputedStyle(block, "::before").content, "@${width}px: no block drawing")
                    }
                }
            }
        }
}
