package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.212 -- the row-action `td` of [dataTable] carries the no-wrap class; the table shows no raw temporal text. */
class DataTableActionsCellDomTest {
    private data class Row(
        val name: String,
    )

    @Test
    fun actionCell_carriesTheActionsCellClass_andAllActionsRender() {
        withMountedRoot("data-table-actions-cell-test") { root, element ->
            root.dataTableWith(
                columns = listOf(textColumn<Row>(title = tr("Name"), primary = true) { it.name }),
                rows = listOf(Row("Ada"), Row("Grace")),
                sort = null,
                onSort = null,
                sortOptions = SortOptions(),
                actions = { container, _ ->
                    container.tableActionGroup().apply { repeat(7) { tableActionButton("fas fa-pen", "Aktion $it") } }
                },
                focusSortKey = null,
                viewport = FakeNarrowViewport(false),
            )
            val cells = element().querySelectorAll("td.$TABLE_ACTIONS_CELL_CLASS")
            assertEquals(2, cells.length)
            assertTrue(element().querySelectorAll("td.$TABLE_ACTIONS_CELL_CLASS .btn").length == 14)
            assertNoRawTemporalInCells(element())
        }
    }
}
