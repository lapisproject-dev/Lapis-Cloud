package network.lapis.cloud.client.encounter

import io.kvision.html.div
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.mountedForm
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.80 (stage 2b) -- the tables as a widget on its own (no room, no network): the cards, the seat buttons and their names (initials,
 * never a person's name), the tab stop per table, the arrow keys, the sizes at 360 px, that the buttons are updated in place and that
 * a state is never only a colour.
 */
class EncounterTableGridDomTest {
    private companion object {
        /** The real stylesheets: sizes and the 44-pixel minimum are CSS, and the test measures them. */
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private fun card(
        table: Int,
        seats: Int = 6,
        quieted: Boolean = false,
        own: Boolean = false,
        block: (Int) -> TableSeatSlot? = { null },
    ) = TableCardModel(
        table = table,
        seats = (0 until seats).map { block(it) ?: TableSeatSlot(initials = null) },
        quieted = quieted,
        own = own,
        micStatus = if (own) "Mikrofon aus" else null,
    )

    private fun model(
        choosable: Boolean = true,
        canModerate: Boolean = false,
        cards: List<TableCardModel>,
    ) = TablesModel(cards = cards, choosable = choosable, canModerate = canModerate)

    private suspend fun withView(
        width: String? = null,
        block: suspend (EncounterTablesView, HTMLElement) -> Unit,
    ) {
        mountedForm("encounter-table-grid") { root, element ->
            val wrapper = root.div(className = "lapis-encounter")
            if (width != null) wrapper.setStyle("width", width)
            val view = EncounterTablesView(wrapper)
            block(view, element())
        }
    }

    private fun HTMLElement.seatsOf(table: Int): List<HTMLElement> = allOf(".lapis-encounter-table-seat[data-table=\"$table\"]")

    private fun key(
        target: HTMLElement,
        name: String,
    ) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = name, bubbles = true, cancelable = true)))
    }

    private fun focusedSeat(): Pair<String?, String?> {
        val active = document.activeElement as? HTMLElement
        return active?.getAttribute("data-table") to active?.getAttribute("data-table-seat")
    }

    private fun threeTables() =
        listOf(
            card(table = 0) {
                if (it == 1) {
                    TableSeatSlot(initials = "MS")
                } else if (it == 2) {
                    TableSeatSlot(initials = "AK")
                } else {
                    null
                }
            },
            card(table = 1, own = true) { if (it == 4) TableSeatSlot(initials = "IS", own = true) else null },
            card(table = 2),
        )

    @Test
    fun aCard_isANamedGroup_withTheFreeSeatCount_andEachSeatIsA44PixelButtonNamedByInitialsNotByName(): Promise<Unit> =
        formTest {
            withView { view, element ->
                view.render(model(cards = threeTables()))
                awaitUntil("18 seats") { element.allOf(".lapis-encounter-table-seat").size == 18 }
                val cards = element.allOf(".lapis-encounter-table")
                assertEquals(3, cards.size)
                assertEquals("group", cards[0].getAttribute("role"))
                assertEquals("Tisch 1 · 4 von 6 Plätzen frei", cards[0].getAttribute("aria-label"))
                assertEquals("Tisch 1 · 4 von 6 Plätzen frei", cards[0].querySelector(".lapis-encounter-table-title")?.textContent)
                val seats = element.seatsOf(0)
                assertTrue(seats.all { it.tagName == "BUTTON" && it.getAttribute("type") == "button" })
                assertEquals("Tisch 1, Platz 1, frei, hier setzen", seats[0].getAttribute("aria-label"))
                assertEquals("Tisch 1, Platz 2, besetzt, M S", seats[1].getAttribute("aria-label"))
                assertEquals("Tisch 2, Platz 5, Ihr Platz", element.seatsOf(1)[4].getAttribute("aria-label"))
                assertTrue(element.allOf(".lapis-encounter-table-seat").none { it.hasAttribute("title") }, "no tooltip with a name")
                awaitUntil("the word 'Sie' marks the own seat only") {
                    element.allOf(".lapis-encounter-table-seat-self").count { it.getBoundingClientRect().width > 0 } == 1 &&
                        (
                            element
                                .seatsOf(
                                    1,
                                )[4]
                                .querySelector(".lapis-encounter-table-seat-self") as HTMLElement
                        ).getBoundingClientRect().width >
                        0
                }
                element.allOf(".lapis-encounter-table-seat").forEach { seat ->
                    val box = seat.getBoundingClientRect()
                    assertTrue(box.width >= 44 && box.height >= 44, "seat ${seat.getAttribute("aria-label")}: ${box.width} x ${box.height}")
                }
            }
        }

    @Test
    fun clickingAFreeSeat_choosesIt_aTakenOrOwnSeat_andAnOfficeHolder_doNothing(): Promise<Unit> =
        formTest {
            withView { view, element ->
                val chosen = mutableListOf<Pair<Int, Int>>()
                view.onChoose = { table, seat -> chosen += table to seat }
                view.render(model(cards = threeTables()))
                awaitUntil("seats") { element.allOf(".lapis-encounter-table-seat").size == 18 }
                element.seatsOf(0)[0].click()
                element.seatsOf(0)[1].click() // taken
                element.seatsOf(1)[4].click() // own
                assertEquals(listOf(0 to 0), chosen)
                assertEquals("true", element.seatsOf(0)[1].getAttribute("aria-disabled"))
                // somebody who cannot sit (an office holder): free seats are shown, but offer no choice
                view.render(model(choosable = false, cards = threeTables()))
                element.seatsOf(2)[0].click()
                assertEquals(listOf(0 to 0), chosen)
                assertEquals("true", element.seatsOf(2)[0].getAttribute("aria-disabled"))
                assertEquals("Tisch 3, Platz 1, frei", element.seatsOf(2)[0].getAttribute("aria-label"))
            }
        }

    @Test
    fun oneTabStopPerTable_andTheArrowKeysWalkAlongTheSeatsOfOneTable(): Promise<Unit> =
        formTest {
            withView { view, element ->
                view.render(model(cards = threeTables()))
                awaitUntil("seats") { element.allOf(".lapis-encounter-table-seat").size == 18 }
                (0..2).forEach { table ->
                    assertEquals(
                        1,
                        element.seatsOf(table).count { it.getAttribute("tabindex") == "0" },
                        "table $table has exactly one tab stop",
                    )
                }
                assertEquals("0", element.seatsOf(0)[0].getAttribute("tabindex"), "no own seat: the first free seat")
                assertEquals("0", element.seatsOf(1)[4].getAttribute("tabindex"), "the own seat is the stop of its table")
                view.focusSeat(2, 0)
                awaitUntil("focus on table 3 seat 1") { focusedSeat() == ("2" to "0") }
                key(element.seatsOf(2)[0], "ArrowRight")
                awaitUntil("focus moved right") { focusedSeat() == ("2" to "1") }
                key(element.seatsOf(2)[1], "End")
                awaitUntil("focus at the end") { focusedSeat() == ("2" to "5") }
                key(element.seatsOf(2)[5], "ArrowRight")
                assertEquals("2" to "5", focusedSeat(), "no wrap into another table")
                key(element.seatsOf(2)[5], "Home")
                awaitUntil("focus at the start") { focusedSeat() == ("2" to "0") }
                key(element.seatsOf(2)[0], "ArrowDown")
                assertEquals("2" to "0", focusedSeat(), "up/down are not arrows of a table")
            }
        }

    @Test
    fun cardsAreUpdatedInPlace_theFocusedSeatSurvivesARefresh(): Promise<Unit> =
        formTest {
            withView { view, element ->
                view.render(model(cards = threeTables()))
                awaitUntil("seats") { element.allOf(".lapis-encounter-table-seat").size == 18 }
                val before = element.seatsOf(0)[3]
                view.focusSeat(0, 3)
                awaitUntil("focus") { focusedSeat() == ("0" to "3") }
                view.render(
                    model(
                        cards =
                            listOf(
                                card(table = 0) {
                                    if (it ==
                                        0
                                    ) {
                                        TableSeatSlot(initials = "ZZ")
                                    } else {
                                        null
                                    }
                                },
                                card(table = 1),
                                card(table = 2),
                            ),
                    ),
                )
                assertSame(before, element.seatsOf(0)[3], "the same button, not a rebuilt one")
                assertEquals("0" to "3", focusedSeat())
                assertEquals("Tisch 1 · 5 von 6 Plätzen frei", element.allOf(".lapis-encounter-table-title")[0].textContent)
            }
        }

    @Test
    fun aQuietedTable_saysSoInWordsAndSymbol_aSpeakingSeatCarriesTheWord_theOwnMicrophoneHasAStatusLine(): Promise<Unit> =
        formTest {
            withView { view, element ->
                view.render(
                    model(
                        cards =
                            listOf(
                                card(table = 0, quieted = true) { if (it == 0) TableSeatSlot(initials = "AB", speaking = true) else null },
                                card(table = 1, own = true),
                            ),
                    ),
                )
                awaitUntil("cards") { element.allOf(".lapis-encounter-table").size == 2 }
                val quietNote = assertNotNull(element.allOf(".lapis-encounter-table-quiet").first())
                awaitUntil("the symbol is there") { quietNote.querySelector("[class*=\"fa-volume-xmark\"]") != null }
                assertTrue(quietNote.getBoundingClientRect().width > 0, "the note is shown: " + quietNote.outerHTML)
                assertEquals("Der Tisch ist beruhigt", quietNote.textContent.orEmpty().trim())
                assertNotNull(quietNote.querySelector("[class*=\"fa-volume-xmark\"]"), "a symbol, not only a colour")
                assertTrue(
                    element
                        .allOf(".lapis-encounter-table")[0]
                        .getAttribute("aria-label")
                        .orEmpty()
                        .endsWith("beruhigt"),
                )
                val speaking = element.seatsOf(0)[0]
                assertTrue(speaking.classList.contains("is-speaking"))
                // the words really are shown at the right seat and nowhere else (show/hide of the marker spans)
                awaitUntil("the word 'spricht' is shown at the speaking seat only") {
                    fun HTMLElement.wordShown(selector: String) =
                        (querySelector(selector) as? HTMLElement)?.getBoundingClientRect()?.width.let {
                            it !=
                                null &&
                                it > 0
                        }
                    speaking.wordShown(".lapis-encounter-table-seat-speaking") &&
                        !element.seatsOf(0)[1].wordShown(".lapis-encounter-table-seat-speaking")
                }
                assertEquals("spricht", speaking.querySelector(".lapis-encounter-table-seat-speaking")?.textContent)
                assertTrue(speaking.getAttribute("aria-label").orEmpty().endsWith("spricht"))
                assertEquals("Mikrofon aus", element.allOf(".lapis-encounter-table-mic").last().textContent)
                kotlinx.coroutines.delay(300)
                val firstMic = element.allOf(".lapis-encounter-table-mic").first()
                assertTrue(
                    firstMic.textContent.orEmpty().isEmpty() && firstMic.getBoundingClientRect().width == 0.0,
                    "only the own card has the line: " + firstMic.outerHTML,
                )
            }
        }

    @Test
    fun theButtonsOfACard_followTheRole_leaveOnTheOwnTable_quietForModerators(): Promise<Unit> =
        formTest {
            withView { view, element ->
                val quiets = mutableListOf<Pair<Int, Boolean>>()
                var leaves = 0
                view.onQuiet = { table, quiet -> quiets += table to quiet }
                view.onLeave = { leaves++ }
                view.render(model(canModerate = true, cards = listOf(card(table = 0, own = true), card(table = 1, quieted = true))))
                awaitUntil("cards") { element.allOf(".lapis-encounter-table").size == 2 }
                val first = element.allOf(".lapis-encounter-table")[0]
                val second = element.allOf(".lapis-encounter-table")[1]

                fun HTMLElement.shown(name: String) =
                    allOf("button").firstOrNull {
                        it.textContent?.trim() == name &&
                            it.getBoundingClientRect().width > 0
                    }
                assertNotNull(first.shown("Tisch verlassen")).click()
                assertEquals(1, leaves)
                assertNotNull(first.shown("Tisch beruhigen")).click()
                assertNotNull(second.shown("Beruhigung aufheben")).click()
                assertEquals(listOf(0 to true, 1 to false), quiets)
                assertTrue(second.shown("Tisch verlassen") == null, "only the own table has the leave button")
                view.render(model(canModerate = false, cards = listOf(card(table = 0, own = true), card(table = 1, quieted = true))))
                assertTrue(
                    first.shown("Tisch beruhigen") == null && second.shown("Beruhigung aufheben") == null,
                    "no quiet control for the congregation",
                )
            }
        }

    @Test
    fun onAWidePhone_twoRingCardsFitSideBySide_onASmallPhoneOnePerRow_neverASidewaysScroll(): Promise<Unit> =
        formTest {
            withView(width = "400px") { view, element ->
                view.render(model(cards = (0 until 4).map { card(table = it) }))
                awaitUntil("cards") { element.allOf(".lapis-encounter-table").size == 4 }
                val grid = assertNotNull(element.querySelector(".lapis-encounter-tables-grid") as? HTMLElement)
                assertTrue(grid.scrollWidth <= grid.clientWidth + 1, "no sideways scroll: ${grid.scrollWidth} > ${grid.clientWidth}")
                element.allOf(".lapis-encounter-table").forEach { card ->
                    assertTrue(card.scrollWidth <= card.clientWidth + 1, "a card does not overflow itself")
                }
                val lefts = element.allOf(".lapis-encounter-table").map { it.getBoundingClientRect().left }.toSet()
                assertEquals(2, lefts.size, "two columns")
            }
            // V1.9.159: a ring of up to 8 seats needs a card of 172 px; at 320 px there is one table per row, still without a sideways scroll.
            withView(width = "320px") { view, element ->
                view.render(model(cards = (0 until 2).map { card(table = it) }))
                awaitUntil("cards") { element.allOf(".lapis-encounter-table").size == 2 }
                val grid = assertNotNull(element.querySelector(".lapis-encounter-tables-grid") as? HTMLElement)
                assertTrue(grid.scrollWidth <= grid.clientWidth + 1, "no sideways scroll: ${grid.scrollWidth} > ${grid.clientWidth}")
                assertEquals(
                    1,
                    element
                        .allOf(".lapis-encounter-table")
                        .map { it.getBoundingClientRect().left }
                        .toSet()
                        .size,
                    "one column",
                )
            }
        }

    @Test
    fun seatsStandOnARing_aroundAVisibleTableTop(): Promise<Unit> =
        formTest {
            withView(width = "400px") { view, element ->
                view.render(model(cards = listOf(card(table = 0))))
                awaitUntil("seats") { element.seatsOf(0).size == 6 }
                val ring = assertNotNull(element.querySelector(".lapis-encounter-table-seats") as? HTMLElement)
                val box = ring.getBoundingClientRect()
                val cx = box.left + box.width / 2
                val cy = box.top + box.height / 2
                val first = element.seatsOf(0)[0].getBoundingClientRect()
                assertTrue(abs((first.left + first.width / 2) - cx) < 2, "seat 1 at 12 o'clock (horizontally centred)")
                assertTrue((first.top + first.height / 2) < cy - box.height / 4, "seat 1 above the centre")
                element.seatsOf(0).forEach { seat ->
                    val r = seat.getBoundingClientRect()
                    val d = kotlin.math.hypot(r.left + r.width / 2 - cx, r.top + r.height / 2 - cy)
                    assertTrue(abs(d - (box.width / 2 - 22)) < 2, "every seat on the same ring, radius $d")
                }
                val top = window.getComputedStyle(ring, "::before")
                assertTrue(top.getPropertyValue("content") != "none", "the table top is drawn")
            }
        }

    @Test
    fun noTables_noSection(): Promise<Unit> =
        formTest {
            withView { view, element ->
                view.render(model(cards = emptyList()))
                assertTrue(element.allOf(".lapis-encounter-tables").all { it.getBoundingClientRect().width == 0.0 })
            }
        }
}
