package network.lapis.cloud.client

import io.kvision.i18n.tr
import io.kvision.panel.Root
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.26 (W2): what the migrated screens must produce in a REAL, mounted [Root] -- Karma runs in a
 * real Chrome here, so the vnodes and the snabbdom hooks really exist (see `MountedRootHarness` and
 * `DataTableModeSwitchDomTest` for why a bare `SimplePanel()` cannot see any of this).
 *
 * These assertions are the DOM-level restatement of the guideline's own table rules: the migrated tables
 * must be `table-sm table-striped table-hover` inside a `.table-responsive` frame, their numeric cells and
 * headers must carry `.lapis-num`, the actions column must have an accessible name without visible text,
 * and a sortable header must be a real `button` with `aria-sort` on its `th`. A screen that goes back to a
 * hand-built `table(...)` would keep compiling and keep passing every pure test -- this is what catches it.
 */
class HotTableDomTest {
    private data class Row(
        val name: String,
        val amount: String,
    )

    private val rows = listOf(Row("Ada Lovelace", "12,00"), Row("Grace Hopper", "3,50"))

    private fun columns() =
        listOf(
            textColumn<Row>(title = tr("Mitglied"), primary = true, sortKey = "name") { it.name },
            textColumn<Row>(title = tr("Betrag"), numeric = true) { it.amount },
        )

    private fun table(element: HTMLElement) = element.getElementsByTagName("table")[0] as? HTMLElement

    @Test
    fun migratedTable_carriesTheMandatorySignatureOfTheGuideline() {
        withMountedRoot("hot-table-signature-test") { root, element ->
            root.dataTableWith(
                columns = columns(),
                rows = rows,
                sort = null,
                onSort = null,
                sortOptions = SortOptions(),
                actions = null,
                focusSortKey = null,
                viewport = FakeNarrowViewport(narrow = false),
            )
            val rendered = assertNotNull(table(element()), "a real <table> must be rendered")
            val classes = rendered.className
            assertTrue(classes.contains("table-sm"), "table-sm (the density decision) missing: $classes")
            assertTrue(classes.contains("table-striped"), "table-striped missing: $classes")
            assertTrue(classes.contains("table-hover"), "table-hover missing: $classes")
            // RESPONSIVE wraps the table in Bootstrap's own horizontally scrolling frame.
            val wrapper = rendered.parentElement as? HTMLElement
            assertTrue(wrapper?.className?.contains("table-responsive") == true, "responsive frame missing")
        }
    }

    @Test
    fun migratedTable_numericColumnIsRightAlignedInBothHeaderAndBody() {
        withMountedRoot("hot-table-num-test") { root, element ->
            root.dataTableWith(
                columns = columns(),
                rows = rows,
                sort = null,
                onSort = null,
                sortOptions = SortOptions(),
                actions = null,
                focusSortKey = null,
                viewport = FakeNarrowViewport(narrow = false),
            )
            val rendered = assertNotNull(table(element()))
            val headers = rendered.getElementsByTagName("th")
            val numericHeaders = (0 until headers.length).count { (headers[it] as HTMLElement).className.contains("lapis-num") }
            assertEquals(1, numericHeaders, "exactly the amount header carries .lapis-num")
            val cells = rendered.getElementsByTagName("td")
            val numericCells = (0 until cells.length).count { (cells[it] as HTMLElement).className.contains("lapis-num") }
            assertEquals(rows.size, numericCells, "one numeric cell per row")
        }
    }

    @Test
    fun migratedTable_actionsColumnHasAnAccessibleNameWithoutVisibleText() {
        withMountedRoot("hot-table-actions-test") { root, element ->
            root.dataTableWith(
                columns = columns(),
                rows = rows,
                sort = null,
                onSort = null,
                sortOptions = SortOptions(),
                actions = { container, row -> container.tableActionButton("fas fa-eye", "Details ${row.name}") },
                focusSortKey = null,
                viewport = FakeNarrowViewport(narrow = false),
            )
            val rendered = assertNotNull(table(element()))
            val headers = rendered.getElementsByTagName("th")
            val actionsHeader = headers[headers.length - 1] as HTMLElement
            assertEquals("", actionsHeader.textContent?.trim(), "the actions header stays visually empty")
            assertNotNull(actionsHeader.getAttribute("aria-label"), "but it must have an accessible name")
            // Every icon-only row action carries title AND aria-label (guideline 2.9 / R39).
            val buttons = rendered.getElementsByTagName("button")
            assertEquals(rows.size, buttons.length)
            (0 until buttons.length).forEach { index ->
                val button = buttons[index] as HTMLElement
                assertNotNull(button.getAttribute("title"), "row action without a tooltip")
                assertNotNull(button.getAttribute("aria-label"), "row action without an accessible name")
                assertTrue(
                    button.getAttribute("aria-label")?.contains("###") != true,
                    "the KVision i18n marker leaked into aria-label: ${button.getAttribute("aria-label")}",
                )
            }
        }
    }

    @Test
    fun sortableHeader_isARealButtonAndTheThCarriesAriaSort() {
        withMountedRoot("hot-table-sort-test") { root, element ->
            var clicked: SortState? = null
            root.dataTableWith(
                columns = columns(),
                rows = rows,
                sort = SortState(key = "name", direction = SortDirection.ASC),
                onSort = { clicked = it },
                sortOptions = SortOptions(),
                actions = null,
                focusSortKey = null,
                viewport = FakeNarrowViewport(narrow = false),
            )
            val rendered = assertNotNull(table(element()))
            val headers = rendered.getElementsByTagName("th")
            val sortHeader = headers[0] as HTMLElement
            assertEquals("ascending", sortHeader.getAttribute("aria-sort"))
            // Exactly one column is not "none".
            val notNone = (0 until headers.length).count { (headers[it] as HTMLElement).getAttribute("aria-sort") !in listOf(null, "none") }
            assertEquals(1, notNone)
            val button = assertNotNull(sortHeader.getElementsByTagName("button")[0] as? HTMLElement, "sort header must be a button")
            // A native button means Enter and Space work without a key handler.
            assertEquals("button", button.getAttribute("type"))
            button.click()
            assertEquals(SortState(key = "name", direction = SortDirection.DESC), clicked)
        }
    }

    @Test
    fun belowTheCardListBreakpoint_thereIsNoTableAtAll() {
        withMountedRoot("hot-table-card-test") { root, element ->
            root.dataTableWith(
                columns = columns(),
                rows = rows,
                sort = null,
                onSort = null,
                sortOptions = SortOptions(),
                actions = null,
                focusSortKey = null,
                viewport = FakeNarrowViewport(narrow = true),
            )
            val rendered = element()
            assertEquals(0, rendered.getElementsByTagName("table").length, "a phone gets cards, not a table")
            val cards = rendered.getElementsByClassName("lapis-data-card")
            assertEquals(rows.size, cards.length)
            // Every non-primary column becomes a dt/dd pair, so the value keeps its label.
            assertEquals(rows.size, rendered.getElementsByTagName("dt").length)
        }
    }
}
