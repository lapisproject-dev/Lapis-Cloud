package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.html.Span
import io.kvision.html.Tag
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.table.ResponsiveType
import io.kvision.table.Table
import io.kvision.table.TableType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Welle V1.4.25 -- `dataTable` in both modes, on an in-memory widget tree (no DOM mounting, same posture
 * as [DataScreenLayoutTest]). The viewport is a fake, so the mode switch needs no real resize.
 */
class DataTableWidgetTest {
    private data class Person(
        val name: String,
        val amount: String,
        val note: String,
    )

    private val people =
        listOf(
            Person(name = "Ada", amount = "12,00", note = "eins"),
            Person(name = "<img src=x onerror=alert(1)>", amount = "3,50", note = ""),
        )

    private fun columns(sortable: Boolean = true) =
        listOf(
            textColumn<Person>(title = tr("Name"), primary = true, sortKey = if (sortable) "name" else null) { it.name },
            textColumn<Person>(title = tr("Betrag"), numeric = true, sortKey = if (sortable) "amount" else null) { it.amount },
            textColumn<Person>(title = tr("Notiz")) { it.note },
        )

    private fun SimplePanel.build(
        viewport: FakeNarrowViewport,
        sort: SortState? = SortState("name", SortDirection.ASC),
        onSort: ((SortState?) -> Unit)? = {},
        actions: ((io.kvision.core.Container, Person) -> Unit)? = null,
        columns: List<DataColumn<Person>> = columns(),
    ): DataTablePanel =
        dataTableWith(
            columns = columns,
            rows = people,
            sort = sort,
            onSort = onSort,
            sortOptions = SortOptions(),
            actions = actions,
            focusSortKey = null,
            viewport = viewport,
        ) as DataTablePanel

    private fun DataTablePanel.table(): Table = getChildren().filterIsInstance<Table>().single()

    private fun Widget.texts(): List<String> =
        (this as? SimplePanel)?.getChildren().orEmpty().filterIsInstance<Span>().mapNotNull {
            it.content
        }

    // ── table mode ──

    @Test
    fun tableMode_isStripedHoverSmallAndResponsive() {
        val panel = SimplePanel().build(FakeNarrowViewport(narrow = false))
        val table = panel.table()
        assertEquals(setOf(TableType.STRIPED, TableType.HOVER, TableType.SMALL), table.types)
        assertEquals(ResponsiveType.RESPONSIVE, table.responsiveType)
        assertFalse(panel.cardMode)
    }

    @Test
    fun tableMode_exactlyOneHeaderCarriesAnActiveAriaSort() {
        val panel = SimplePanel().build(FakeNarrowViewport(narrow = false), sort = SortState("amount", SortDirection.DESC))
        val ariaSorts = panel.headerCells.map { it.getAttribute("aria-sort") }
        assertEquals(listOf("none", "descending", null), ariaSorts)
        assertEquals(1, ariaSorts.count { it != null && it != "none" })
    }

    @Test
    fun tableMode_sortHeader_hasANativeButtonWithTooltipAndStateLabel_withoutTheMarker() {
        val panel = SimplePanel().build(FakeNarrowViewport(narrow = false), sort = SortState("name", SortDirection.ASC))
        val nameHeader = panel.headerCells.first()
        val button = nameHeader.getChildren().filterIsInstance<Tag>().single()
        assertEquals("button", button.getAttribute("type")) // a native <button type="button">
        assertEquals("Nach Name absteigend sortieren", button.title)
        assertEquals("Aufsteigend sortiert nach Name", button.getAttribute("aria-label"))
        assertFalse(button.getAttribute("aria-label").orEmpty().contains("###KvI18nS###"))
        assertFalse(button.title.orEmpty().contains("###KvI18nS###"))
    }

    @Test
    fun tableMode_sortHeader_tooltipAndAriaLabelUseTheTranslatedColumnName() {
        // Audit V1.4.25 M2: the visible header is translated, the button texts must not mix in the German key.
        withTranslations(
            mapOf(
                "Name" to "Full name",
                "Nach %1 absteigend sortieren" to "Sort by %1 descending",
                "Aufsteigend sortiert nach %1" to "Sorted ascending by %1",
            ),
        ) {
            val panel = SimplePanel().build(FakeNarrowViewport(narrow = false), sort = SortState("name", SortDirection.ASC))
            val button =
                panel.headerCells
                    .first()
                    .getChildren()
                    .filterIsInstance<Tag>()
                    .single()
            assertEquals("Sort by Full name descending", button.title)
            assertEquals("Sorted ascending by Full name", button.getAttribute("aria-label"))
        }
    }

    @Test
    fun tableMode_sortHeader_onlyForColumnsWithASortKey_andWhenOnSortIsGiven() {
        val noSortKeys = SimplePanel().build(FakeNarrowViewport(narrow = false), columns = columns(sortable = false))
        assertTrue(noSortKeys.headerCells.all { it.getAttribute("aria-sort") == null })
        val noHandler = SimplePanel().build(FakeNarrowViewport(narrow = false), onSort = null)
        assertTrue(noHandler.headerCells.all { it.getAttribute("aria-sort") == null })
    }

    @Test
    fun tableMode_numericColumn_hasLapisNumOnHeaderAndCells() {
        val panel = SimplePanel().build(FakeNarrowViewport(narrow = false))
        assertTrue(panel.headerCells[1].hasCssClass("lapis-num"))
        assertFalse(panel.headerCells[0].hasCssClass("lapis-num"))
        val firstRow = panel.table().getChildren().first() as SimplePanel
        val cells = firstRow.getChildren().filterIsInstance<Widget>()
        assertFalse(cells[0].hasCssClass("lapis-num"))
        assertTrue(cells[1].hasCssClass("lapis-num"))
    }

    @Test
    fun tableMode_actionsColumn_hasAnEmptyVisibleHeaderWithAnAccessibleName() {
        val panel =
            SimplePanel().build(FakeNarrowViewport(narrow = false), actions = {
                container,
                person,
                ->
                io.kvision.html
                    .Span(person.name)
                    .also { container.add(it) }
            })
        val actionsHeader = panel.headerCells.last()
        assertNull(actionsHeader.content)
        assertEquals("Aktionen", actionsHeader.getAttribute("aria-label"))
        assertEquals(4, panel.headerCells.size)
    }

    @Test
    fun cellContent_isPlainTextNeverRichHtml() {
        val hostile = "<img src=x onerror=alert(1)>"
        val table = SimplePanel().build(FakeNarrowViewport(narrow = false)).table()
        val secondRow = table.getChildren()[1] as SimplePanel
        val nameCell = secondRow.getChildren().first() as SimplePanel
        val span = nameCell.getChildren().filterIsInstance<Span>().single()
        assertEquals(hostile, span.content)
        assertFalse(span.rich)
    }

    // ── card mode ──

    @Test
    fun cardMode_rendersNoTable_oneCardPerRow_withThePrimaryColumnAsTitle() {
        val panel = SimplePanel().build(FakeNarrowViewport(narrow = true))
        assertTrue(panel.cardMode)
        assertTrue(panel.getChildren().filterIsInstance<Table>().isEmpty())
        val list = panel.getChildren().single() as SimplePanel
        assertEquals("list", list.getAttribute("role"))
        val cards = list.getChildren().filterIsInstance<SimplePanel>()
        assertEquals(people.size, cards.size)
        assertTrue(cards.all { it.hasCssClass("lapis-data-card") && it.getAttribute("role") == "listitem" })
        val firstTitle = cards[0].getChildren().first() as SimplePanel
        assertTrue(firstTitle.hasCssClass("lapis-data-card-title"))
        assertEquals(listOf("Ada"), firstTitle.texts())
    }

    @Test
    fun cardMode_dropsDetailPairsWhoseValueIsEmpty() {
        val panel = SimplePanel().build(FakeNarrowViewport(narrow = true))
        val cards = (panel.getChildren().single() as SimplePanel).getChildren().filterIsInstance<SimplePanel>()

        fun pairCount(card: SimplePanel) = (card.getChildren()[1] as SimplePanel).getChildren().size
        // Ada: amount + note = 2 pairs (4 widgets); the second person has an empty note -> only amount (2 widgets).
        assertEquals(4, pairCount(cards[0]))
        assertEquals(2, pairCount(cards[1]))
    }

    @Test
    fun cardMode_actionsGroup_isLabelled_andDroppedWhenEmpty() {
        val withActions =
            SimplePanel().build(
                FakeNarrowViewport(narrow = true),
                actions = { container, person -> if (person.name == "Ada") container.add(Span("Bearbeiten")) },
            )
        val cards = (withActions.getChildren().single() as SimplePanel).getChildren().filterIsInstance<SimplePanel>()
        val groupOfAda = cards[0].getChildren().last() as SimplePanel
        assertEquals("group", groupOfAda.getAttribute("role"))
        assertEquals("Aktionen", groupOfAda.getAttribute("aria-label"))
        // The second card's group stayed empty -> removed, so its last child is the dl.
        assertFalse((cards[1].getChildren().last() as SimplePanel).hasCssClass("lapis-data-card-actions"))
    }

    // ── mode switch ──

    /**
     * Counts renders in an UNMOUNTED tree, and that is all it can do: `SimplePanel()` has no `Root` above
     * it, so `getRoot()` is `null`, every `refresh()` returns without patching and no snabbdom hook ever
     * runs. This test therefore stayed green while the mode switch was broken in the browser (V1.4.25) --
     * the defect lived entirely in the patch cycle (see `DataTable.kt`'s KDoc on the hook order). What
     * happens in a real, mounted `Root` is covered by [DataTableModeSwitchDomTest]; do not "strengthen"
     * this test with DOM assertions, it has no DOM.
     */
    @Test
    fun modeSwitch_rendersAgainOnlyOnARealFlip() {
        val viewport = FakeNarrowViewport(narrow = false)
        val panel = SimplePanel().build(viewport)
        assertEquals(1, panel.renderCount)
        viewport.change(false) // no flip
        assertEquals(1, panel.renderCount)
        viewport.change(true)
        assertEquals(2, panel.renderCount)
        assertTrue(panel.cardMode)
        viewport.change(true) // no flip
        assertEquals(2, panel.renderCount)
        viewport.change(false)
        assertEquals(3, panel.renderCount)
        assertFalse(panel.cardMode)
    }

    @Test
    fun modeSwitch_decidedSynchronouslyAtConstruction() {
        assertTrue(SimplePanel().build(FakeNarrowViewport(narrow = true)).cardMode)
        assertFalse(SimplePanel().build(FakeNarrowViewport(narrow = false)).cardMode)
    }

    @Test
    fun returnsThePanelItWasAddedTo() {
        val parent = SimplePanel()
        val panel = parent.build(FakeNarrowViewport(narrow = false))
        assertSame(panel, parent.getChildren().single())
        assertNotNull(panel.table())
    }
}
