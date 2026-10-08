package network.lapis.cloud.client.encounter

import io.kvision.html.div
import kotlinx.browser.document
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.79 -- the seat plan as a widget, on its own (no room, no network): the seat buttons, their names (initials, never a person's name),
 * the roving tab stop, the arrow-key model, the sizes and that the cells are updated in place.
 */
class EncounterSeatGridDomTest {
    private companion object {
        /** The real stylesheets: sizes, the aisle and the 44-pixel minimum are CSS, and the test measures them. */
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private val church = termsFor(EncounterProfile.CHURCH_SERVICE)

    private fun slots(
        size: Int = 24,
        block: (Int) -> SeatSlot?,
    ) = SeatGridModel((0 until size).map { block(it) ?: SeatSlot(initials = null) })

    /** Mounts a plan in the same wrapper the room uses (`.lapis-encounter` carries the sizing variables). */
    private suspend fun withGrid(
        width: String? = null,
        block: suspend (EncounterSeatGrid, HTMLElement, MutableList<Int>) -> Unit,
    ) {
        mountedForm("encounter-seat-grid") { root, element ->
            val wrapper = root.div(className = "lapis-encounter")
            val host = wrapper.div(className = "lapis-encounter-benches")
            val grid = EncounterSeatGrid(host, church)
            val chosen = mutableListOf<Int>()
            grid.onChoose = { chosen += it }
            if (width != null) {
                // The phone values of theme.css (`@media (max-width: 767.98px)`), applied by hand: the Karma window is wider than a phone.
                wrapper.setStyle("--lapis-enc-seat", "44px")
                wrapper.setStyle("--lapis-enc-seat-gap", "4px")
                wrapper.setStyle("--lapis-enc-aisle", "16px")
                wrapper.setStyle("width", width)
            }
            block(grid, element(), chosen)
        }
    }

    private fun HTMLElement.cells(): List<HTMLElement> = allOf(".lapis-encounter-seat")

    private fun key(
        target: HTMLElement,
        name: String,
    ) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = name, bubbles = true, cancelable = true)))
    }

    private fun focusedSeat(): String? = (document.activeElement as? HTMLElement)?.getAttribute("data-seat")

    @Test
    fun aSeat_isARealButton_namedByRowPositionStateAndSpelledInitials_neverByNameOrTitle(): Promise<Unit> =
        formTest {
            withGrid { grid, element, _ ->
                grid.render(
                    slots { seat ->
                        when (seat) {
                            2 -> SeatSlot(initials = "MS")
                            9 -> SeatSlot(initials = "AK", own = true)
                            else -> null
                        }
                    },
                )
                awaitUntil("24 seats") { element.cells().size == 24 }
                val cells = element.cells()
                assertTrue(cells.all { it.tagName == "BUTTON" && it.getAttribute("type") == "button" })
                assertEquals("Reihe 1, Platz 1, frei. Diesen Platz wählen", cells[0].getAttribute("aria-label"))
                assertEquals("Reihe 1, Platz 3, besetzt, M S", cells[2].getAttribute("aria-label"))
                assertEquals("Reihe 2, Platz 4, Ihr Platz", cells[9].getAttribute("aria-label"))
                assertTrue(cells.none { it.hasAttribute("title") }, "no tooltip with a name")
                assertEquals("group", element.querySelector(".lapis-encounter-benches")?.getAttribute("role"))
                assertEquals("Sitzplan", element.querySelector(".lapis-encounter-benches")?.getAttribute("aria-label"))
            }
        }

    @Test
    fun anAssemblyRoom_saysChair_notSeat(): Promise<Unit> =
        formTest {
            mountedForm("encounter-seat-grid-assembly") { root, element ->
                val grid = EncounterSeatGrid(root.div(), termsFor(EncounterProfile.ASSEMBLY))
                grid.render(slots { null })
                awaitUntil("seats") { element().cells().isNotEmpty() }
                assertEquals("Reihe 1, Stuhl 1, frei. Diesen Stuhl wählen", element().cells()[0].getAttribute("aria-label"))
            }
        }

    @Test
    fun aHandAndAReaction_areGlyphsAndPartOfTheName(): Promise<Unit> =
        formTest {
            withGrid { grid, element, _ ->
                grid.render(slots { if (it == 4) SeatSlot(initials = "BE", handUp = true) else null })
                awaitUntil("seats") { element.cells().size == 24 }
                grid.showEvent(4, EncounterReactionOption.AMEN)
                val cell = element.cells()[4]
                assertEquals("Reihe 1, Platz 5, besetzt, B E, Hand erhoben, Reaktion: Amen", cell.getAttribute("aria-label"))
                assertNotNull(cell.querySelector(".lapis-encounter-seat-hand.is-on"))
                assertNotNull(cell.querySelector(".lapis-encounter-seat-event.is-on"))
                assertEquals("true", cell.querySelector(".lapis-encounter-seat-event")?.getAttribute("aria-hidden"))
            }
        }

    @Test
    fun aFreeSeatCanBeChosen_aTakenOnePendingOneAndOneOfAnOfficeHolderCannot(): Promise<Unit> =
        formTest {
            withGrid { grid, element, chosen ->
                grid.render(
                    slots {
                        when (it) {
                            1 -> SeatSlot(initials = "AB")
                            2 -> SeatSlot(initials = null, pending = true)
                            3 -> SeatSlot(initials = "CD", own = true)
                            else -> null
                        }
                    },
                )
                awaitUntil("seats") { element.cells().size == 24 }
                val cells = element.cells()
                cells[0].click()
                cells[1].click()
                cells[2].click()
                cells[3].click()
                assertEquals(listOf(0), chosen, "only the free seat reacts")
                assertEquals("true", cells[1].getAttribute("aria-disabled"))
                assertEquals("true", cells[2].getAttribute("aria-disabled"))
                assertEquals("true", cells[2].getAttribute("aria-busy"))
                assertEquals("true", cells[3].getAttribute("aria-disabled"), "the own seat is chosen already")
                assertNull(cells[0].getAttribute("aria-disabled"))
                // a viewer who cannot sit: nothing can be chosen, and the label makes no invitation
                grid.render(SeatGridModel(slots { null }.slots, choosable = false))
                element.cells()[5].click()
                assertEquals(listOf(0), chosen)
                assertEquals("Reihe 1, Platz 6, frei", element.cells()[5].getAttribute("aria-label"))
                assertEquals("true", element.cells()[5].getAttribute("aria-disabled"))
            }
        }

    @Test
    fun exactlyOneSeatIsATabStop_theOwnSeatFirst_thenTheFirstFreeOne(): Promise<Unit> =
        formTest {
            withGrid { grid, element, _ ->
                grid.render(slots { if (it < 2) SeatSlot(initials = "XY") else null })
                awaitUntil("seats") { element.cells().size == 24 }
                assertEquals(1, element.cells().count { it.getAttribute("tabindex") == "0" })
                assertEquals("0", element.cells()[2].getAttribute("tabindex"), "no own seat: the first free seat")
                grid.render(slots { if (it == 7) SeatSlot(initials = "XY", own = true) else null })
                assertEquals(1, element.cells().count { it.getAttribute("tabindex") == "0" })
                assertEquals("0", element.cells()[7].getAttribute("tabindex"), "the own seat is the tab stop")
            }
        }

    @Test
    fun theArrowKeys_moveOverTheAisle_betweenRows_andHomeEndJump_withoutWrappingIntoTheNextRow(): Promise<Unit> =
        formTest {
            withGrid { grid, element, _ ->
                grid.render(slots { null })
                awaitUntil("seats") { element.cells().size == 24 }
                val cells = element.cells()
                cells[2].focus()
                key(cells[2], "ArrowRight")
                awaitUntil("focus crossed the aisle") { focusedSeat() == "3" }
                key(element.cells()[3], "ArrowLeft")
                awaitUntil("and back") { focusedSeat() == "2" }
                key(element.cells()[2], "ArrowDown")
                awaitUntil("same position, next row") { focusedSeat() == "8" }
                key(element.cells()[8], "ArrowUp")
                awaitUntil("and up") { focusedSeat() == "2" }
                key(element.cells()[2], "End")
                awaitUntil("the end of the row") { focusedSeat() == "5" }
                key(element.cells()[5], "ArrowRight")
                delay(60)
                assertEquals("5", focusedSeat(), "no wrap into the next row")
                key(element.cells()[5], "Home")
                awaitUntil("the start of the row") { focusedSeat() == "0" }
                key(element.cells()[0], "ArrowLeft")
                delay(60)
                assertEquals("0", focusedSeat())
                key(element.cells()[0], "ArrowUp")
                delay(60)
                assertEquals("0", focusedSeat(), "no row above the first")
                // exactly one tab stop follows the focus
                assertEquals(1, element.cells().count { it.getAttribute("tabindex") == "0" })
                assertEquals("0", element.cells()[0].getAttribute("tabindex"))
            }
        }

    @Test
    fun anUpdate_changesTheCellsInPlace_theFocusedSeatKeepsItsFocus(): Promise<Unit> =
        formTest {
            withGrid { grid, element, _ ->
                grid.render(slots { null })
                awaitUntil("seats") { element.cells().size == 24 }
                val before = element.cells()
                before[4].focus()
                grid.render(slots { if (it == 9) SeatSlot(initials = "ZZ") else null })
                val after = element.cells()
                assertTrue(before.indices.all { before[it] === after[it] }, "the same elements are reused")
                assertEquals("4", focusedSeat(), "the focus did not move")
                assertTrue(after[9].classList.contains("lapis-encounter-seat--taken"))
            }
        }

    @Test
    fun theOwnSeatShowsTheWordSie_andOnlyTheOwnSeat(): Promise<Unit> =
        formTest {
            withGrid { grid, element, _ ->
                grid.render(
                    slots {
                        if (it ==
                            3
                        ) {
                            SeatSlot(initials = "AB", own = true)
                        } else if (it == 4) {
                            SeatSlot(initials = "CD")
                        } else {
                            null
                        }
                    },
                )
                awaitUntil("seats") { element.cells().size == 24 }
                val withWord = element.cells().filter { it.querySelector(".lapis-encounter-seat-self")?.isVisibleText() == true }
                assertEquals(listOf("3"), withWord.map { it.getAttribute("data-seat") })
                assertEquals(
                    "Sie",
                    withWord
                        .single()
                        .querySelector(".lapis-encounter-seat-self")
                        ?.textContent
                        ?.trim(),
                )
            }
        }

    private fun org.w3c.dom.Element.isVisibleText(): Boolean = (this as HTMLElement).getBoundingClientRect().let { it.width > 0 }

    @Test
    fun everySeatIsAtLeast44Pixels_atDesktopAndAtPhoneWidth_andTheRowNeverOverflows360Pixels(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withGrid { grid, element, _ ->
                grid.render(slots { null })
                awaitUntil("seats") { element.cells().size == 24 }
                element.cells().forEach { cell ->
                    val box = cell.getBoundingClientRect()
                    assertTrue(
                        box.width >= 44.0 && box.height >= 44.0,
                        "seat ${cell.getAttribute("data-seat")} is ${box.width} x ${box.height}",
                    )
                }
            }
            withGrid(width = "360px") { grid, element, _ ->
                grid.render(slots { null })
                awaitUntil("seats") { element.cells().size == 24 }
                element.cells().forEach { cell ->
                    val box = cell.getBoundingClientRect()
                    assertTrue(box.width >= 44.0 && box.height >= 44.0, "phone: seat is ${box.width} x ${box.height}")
                }
                val wrapper = assertNotNull(element.querySelector(".lapis-encounter") as? HTMLElement)
                assertTrue(
                    wrapper.scrollWidth <= wrapper.clientWidth,
                    "no horizontal overflow at 360 px: ${wrapper.scrollWidth} > ${wrapper.clientWidth}",
                )
            }
        }
}
