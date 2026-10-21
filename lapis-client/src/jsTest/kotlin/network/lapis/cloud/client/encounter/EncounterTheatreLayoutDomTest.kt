package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.EncounterTablesConfig
import network.lapis.cloud.shared.domain.allowedFor
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
 * V1.9.167 -- the theatre hall: the HALL template of a room with tables puts the tables in a parterre and half of every row of seats in a left and a right
 * loge. The seat plan itself (indices, names, tab stop, arrow keys) is unchanged. Measured in a real Chrome; the narrow-screen rules (under 768 and 420 px) are
 * media queries on the window, which a test cannot resize, so those are pinned in the stylesheet scan of `ClientEncounterDockCssTripwireTest`.
 */
class EncounterTheatreLayoutDomTest {
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

    private val clock = 1_000_000.0
    private val crowd = seatedCrowd()

    private fun hallSpace(tables: Int = 3) =
        testSpace(
            profile = EncounterProfile.ASSEMBLY,
            roomLayout = EncounterRoomLayout.HALL,
            tables = EncounterTablesConfig(enabled = true, count = tables, seats = 6),
        )

    private suspend fun withRoom(
        space: network.lapis.cloud.shared.domain.EncounterSpaceDto,
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        withEncounterRoom(entry = testEntry(), peopleOf = { crowd }, clock = { clock }, space = space) { rig, element ->
            awaitUntil("six seats occupied") {
                element.allOf(".lapis-encounter-seat[data-seat]").count { it.getAttribute("aria-disabled") == "true" } >= 6
            }
            block(rig, element)
        }
    }

    private fun HTMLElement.q(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun HTMLElement.centreX(): Double = getBoundingClientRect().let { it.left + it.width / 2 }

    private fun key(
        target: HTMLElement,
        name: String,
    ): KeyboardEvent {
        val event = KeyboardEvent("keydown", KeyboardEventInit(key = name, bubbles = true, cancelable = true))
        target.dispatchEvent(event)
        return event
    }

    @Test
    fun theTheatreClassIsOnlyOnTheHallWithTables(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val rooms = mutableListOf<Triple<String, Boolean, Boolean>>() // label, theatre expected, theatre seen
            for (profile in EncounterProfile.entries) {
                for (layout in EncounterRoomLayout.entries.filter { it.allowedFor(profile) }) {
                    // tables exist only in an assembly (the server reports them for no other profile)
                    for (tablesOn in listOf(false, true)) {
                        if (tablesOn && profile != EncounterProfile.ASSEMBLY) continue
                        val space =
                            testSpace(
                                profile = profile,
                                roomLayout = layout,
                                tables = EncounterTablesConfig(enabled = tablesOn, count = 3, seats = 6),
                            )
                        withEncounterRoom(entry = testEntry(), peopleOf = { crowd }, clock = { clock }, space = space) { _, element ->
                            val root = element.q(".lapis-encounter--layout-${layoutCssKey(layout)}")
                            val seen = root.classList.contains("lapis-encounter--theatre")
                            rooms += Triple("$profile/$layout/tables=$tablesOn", layout == EncounterRoomLayout.HALL && tablesOn, seen)
                            assertEquals(
                                seen,
                                element.querySelector(".lapis-encounter-hall") != null,
                                "$profile/$layout: hall element follows the class",
                            )
                            assertEquals(
                                !seen,
                                element.querySelector(".lapis-encounter-benches-frame") != null,
                                "$profile/$layout: the pews frame is the opposite",
                            )
                        }
                    }
                }
            }
            assertTrue(rooms.any { it.second }, "the hall with tables is among the cases")
            rooms.forEach { (label, expected, seen) -> assertEquals(expected, seen, label) }
        }

    @Test
    fun theStageOrder_isFrontPulpitStewardsHall_andTheHallHoldsParterreThenTheTwoLoges(): Promise<Unit> =
        formTest {
            withRoom(hallSpace()) { _, element ->
                val stage = element.q(".lapis-encounter-stage")
                val order = stage.children.let { list -> (0 until list.length).map { list.item(it)!!.className } }

                fun index(part: String) = order.indexOfFirst { it.contains(part) }
                assertTrue(
                    index("lapis-encounter-scene-zone--front") in 0 until index("lapis-encounter-pulpit"),
                    "front strip, then the pulpit: $order",
                )
                // the stewards' strip stands between the pulpit and the hall while it has people (an empty strip is not rendered)
                assertTrue(index("lapis-encounter-pulpit") < index("lapis-encounter-hall"), order.toString())
                assertTrue(index("lapis-encounter-hall") < index("lapis-encounter-scene-zone--rear"), order.toString())
                val hall = element.q(".lapis-encounter-hall")
                val inner = hall.children.let { list -> (0 until list.length).map { list.item(it)!!.className } }
                assertEquals(3, inner.size, inner.toString())
                assertTrue(inner[0].contains("lapis-encounter-parterre"))
                assertTrue(inner[1].contains("lapis-encounter-loge--left"))
                assertTrue(inner[2].contains("lapis-encounter-loge--right"))
                assertNotNull(
                    hall.q(".lapis-encounter-parterre").querySelector(".lapis-encounter-tables-host"),
                    "the tables stand in the parterre",
                )
                assertNull(element.querySelector(".lapis-encounter-benches-frame"))
                assertNull(element.querySelector(".lapis-encounter-row"), "no row element: the blocks of a row stand in the loges")
                // the loges are named groups
                assertEquals("Linke Loge", hall.q(".lapis-encounter-loge--left").getAttribute("aria-label"))
                assertEquals("Rechte Loge", hall.q(".lapis-encounter-loge--right").getAttribute("aria-label"))
            }
        }

    @Test
    fun theHallIsTheSitzplanGroup_andSeatsAreSplitIntoTheLoges(): Promise<Unit> =
        formTest {
            withRoom(hallSpace()) { _, element ->
                val hall = element.q(".lapis-encounter-hall")
                assertEquals("group", hall.getAttribute("role"))
                assertEquals("Sitzplan", hall.getAttribute("aria-label"))
                assertEquals("lapis-encounter-seat-note", hall.getAttribute("aria-describedby"))
                val left = hall.q(".lapis-encounter-loge--left")
                val right = hall.q(".lapis-encounter-loge--right")
                val seats = element.allOf(".lapis-encounter-seat[data-seat]")
                assertTrue(seats.size >= 12, "several rows: ${seats.size}")
                seats.forEach { seat ->
                    val index = seat.getAttribute("data-seat")!!.toInt()
                    val loge = if (index % 6 < 3) left else right
                    assertTrue(loge.contains(seat), "seat $index stands in the ${if (index % 6 < 3) "left" else "right"} loge")
                }
                assertTrue(left.allOf(".lapis-encounter-block").size >= 2, "the rows are stacked inside the loge")
                assertEquals(left.allOf(".lapis-encounter-block").size, right.allOf(".lapis-encounter-block").size)
                // the names are the ones of every other template: row and position of the seat, not of its loge
                val label = seats.first { it.getAttribute("data-seat") == "3" }.getAttribute("aria-label").orEmpty()
                assertTrue(label.isNotBlank() && !label.contains("Loge"), "the name tells row and position, not the loge: $label")
                assertEquals(1, seats.count { it.getAttribute("tabindex") == "0" }, "still exactly one tab stop")
            }
        }

    @Test
    fun arrowRight_fromSeat2_goesToSeat3_acrossTheParterre_andBack(): Promise<Unit> =
        formTest {
            withRoom(hallSpace()) { _, element ->
                val seat2 = element.q(".lapis-encounter-seat[data-seat=\"2\"]")
                seat2.focus()
                key(seat2, "ArrowRight")
                awaitUntil("seat 3 has the focus") { (document.activeElement as? HTMLElement)?.getAttribute("data-seat") == "3" }
                val seat3 = document.activeElement as HTMLElement
                key(seat3, "ArrowLeft")
                awaitUntil("seat 2 has the focus") { (document.activeElement as? HTMLElement)?.getAttribute("data-seat") == "2" }
                // down stays in the loge: the same position of the next row
                key(document.activeElement as HTMLElement, "ArrowDown")
                awaitUntil("seat 8 has the focus") { (document.activeElement as? HTMLElement)?.getAttribute("data-seat") == "8" }
            }
        }

    @Test
    fun aKeyOnATableSeat_doesNotMoveTheFocusInTheSeatPlan(): Promise<Unit> =
        formTest {
            withRoom(hallSpace()) { _, element ->
                val tableSeat = element.q("[data-table-seat]")
                tableSeat.focus()
                val event = key(tableSeat, "ArrowRight")
                // the seat plan's own handler ignores the event (it listens for `data-seat` only)
                assertFalse(
                    (document.activeElement as? HTMLElement)?.hasAttribute("data-seat") == true,
                    "the focus did not jump into the seat plan: ${document.activeElement?.className} (defaultPrevented ${event.defaultPrevented})",
                )
            }
        }

    @Test
    fun theParterreIsCentred_thelogesStandLeftAndRight_andTheTablesCentreInTheParterre(): Promise<Unit> =
        formTest {
            if (window.innerWidth < 768) return@formTest // the wide-screen grid only; the narrow rules are pinned by the stylesheet scan
            for (tables in listOf(2, 3)) {
                withRoom(hallSpace(tables)) { _, element ->
                    awaitUntil("$tables table cards") { element.allOf(".lapis-encounter-table").size == tables }
                    val stage = element.q(".lapis-encounter-stage")
                    val parterre = element.q(".lapis-encounter-parterre")
                    assertTrue(
                        abs(parterre.centreX() - stage.centreX()) <= 1.0,
                        "$tables tables: parterre ${parterre.centreX()} stage ${stage.centreX()}",
                    )
                    val left = element.q(".lapis-encounter-loge--left").getBoundingClientRect()
                    val right = element.q(".lapis-encounter-loge--right").getBoundingClientRect()
                    val middle = parterre.getBoundingClientRect()
                    assertTrue(left.right <= middle.left + 0.5, "the left loge is left of the parterre")
                    assertTrue(right.left >= middle.right - 0.5, "the right loge is right of the parterre")
                    assertTrue(abs(left.width - right.width) <= 1.0, "the loges are equally wide")
                    element.allOf(".lapis-encounter-table").forEach { card ->
                        // a row of cards is centred; a lone card in its row too
                        val row = card.getBoundingClientRect().top
                        val sameRow = element.allOf(".lapis-encounter-table").filter { abs(it.getBoundingClientRect().top - row) < 1.0 }
                        val first = sameRow.minOf { it.getBoundingClientRect().left }
                        val last = sameRow.maxOf { it.getBoundingClientRect().right }
                        assertTrue(
                            abs(
                                (first + last) / 2 -
                                    middle.let {
                                        it.left + it.width / 2
                                    },
                            ) <= 1.5,
                            "$tables tables: a row of cards is centred in the parterre",
                        )
                    }
                    val grid = window.getComputedStyle(element.q(".lapis-encounter-tables-grid"))
                    assertEquals("flex", grid.display)
                    assertEquals("center", grid.justifyContent)
                }
            }
        }

    @Test
    fun theTablesHeadingStaysForScreenReaders_butIsNotSeen(): Promise<Unit> =
        formTest {
            withRoom(hallSpace()) { _, element ->
                val heading = element.q(".lapis-encounter-tables-heading")
                assertEquals("lapis-encounter-tables-heading", heading.id)
                assertEquals("Tische", heading.textContent)
                val rect = heading.getBoundingClientRect()
                assertTrue(rect.width <= 2.0 && rect.height <= 2.0, "visually hidden: ${rect.width} x ${rect.height}")
            }
            withRoom(
                testSpace(
                    profile = EncounterProfile.ASSEMBLY,
                    roomLayout = EncounterRoomLayout.FOYER,
                    tables = EncounterTablesConfig(true, 3, 6),
                ),
            ) { _, element ->
                val rect = element.q(".lapis-encounter-tables-heading").getBoundingClientRect()
                assertTrue(rect.height > 4.0, "outside the theatre the heading is visible")
            }
        }

    @Test
    fun noAncestorOfTheDockOrTheHallMoves(): Promise<Unit> =
        formTest {
            withRoom(hallSpace()) { _, element ->
                listOf(element.q(".lapis-encounter-dock"), element.q(".lapis-encounter-hall")).forEach { start ->
                    var node: HTMLElement? = start
                    while (node != null && node != document.body) {
                        val style = window.getComputedStyle(node)
                        assertEquals("none", style.transform, "transform on ${node.className}")
                        assertEquals("none", style.filter, "filter on ${node.className}")
                        assertTrue(
                            style.getPropertyValue("container-type").let { it.isEmpty() || it == "normal" },
                            "container-type on ${node.className}",
                        )
                        node = node.parentElement as? HTMLElement
                    }
                }
            }
        }
}
