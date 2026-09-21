package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Welle V1.4.27 (W3), review finding: the sum row of the two expandable reports must have as many cells as the
 * table has columns. `theme.css` paints the sum row's background and strong top rule per `td`, so a missing last
 * cell (the "Details" column of the data rows) cuts the line off in front of it. `ReportCellOracleDomTest` cannot
 * see this on purpose (it trims trailing blank cells on both sides), so the cell COUNT is pinned here.
 */
class ReportTotalRowCellsDomTest {
    private companion object {
        // Sphere / three amounts / Details, and Jahr / five amounts / Details -- the headers are private to the screen.
        const val FOUR_SPHERE_COLUMNS = 5
        const val USE_OF_FUNDS_COLUMNS = 7
    }

    private fun cellCounts(
        host: org.w3c.dom.HTMLElement,
        rowSelector: String,
    ): List<Int> {
        val rows = host.querySelectorAll(rowSelector)
        return (0 until rows.length)
            .map { rows.item(it) as org.w3c.dom.Element }
            // the collapsed detail rows hold nested tables with their own sum rows -- not the report's
            .filter { it.closest("tr.d-none") == null }
            .map { it.children.length }
    }

    @Test
    fun fourSphere_sumRowSpansEveryColumnIncludingDetails() {
        withMountedRoot("total-cells-four-sphere") { root, element ->
            renderFourSphereIncomeStatementBody(root, fourSphereStatement)
            assertEquals(listOf(FOUR_SPHERE_COLUMNS), cellCounts(element(), "table.lapis-report-table > tbody > tr.lapis-total-row"))
        }
    }

    @Test
    fun useOfFunds_sumRowSpansEveryColumnIncludingDetails() {
        withMountedRoot("total-cells-use-of-funds") { root, element ->
            renderUseOfFundsBody(root, useOfFunds)
            assertEquals(listOf(USE_OF_FUNDS_COLUMNS), cellCounts(element(), "table.lapis-report-table > tbody > tr.lapis-total-row"))
        }
    }
}
