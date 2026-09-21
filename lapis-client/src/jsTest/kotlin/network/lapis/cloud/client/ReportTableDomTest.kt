package network.lapis.cloud.client

import io.kvision.i18n.tr
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.27 (W3): the structure of the report grammar in a REAL, mounted `Root` (see `MountedRootHarness`).
 * The style side (caption on top, the sum row's line) is `ReportRowStyleDomTest`, which needs the CSS.
 *
 * Rams' decision made testable: a report is a document, never a list -- no sort buttons in the header and no
 * card list, whatever the viewport.
 */
class ReportTableDomTest {
    private val headers =
        listOf(
            TableHeader(title = "Konto"),
            TableHeader(title = "Kontenklasse"),
            TableHeader(title = "Betrag", numeric = true),
        )

    private fun table(element: HTMLElement) = assertNotNull(element.getElementsByTagName("table")[0] as? HTMLElement, "no <table>")

    @Test
    fun reportTable_isARealStripedHoverSmallTableInTheResponsiveFrame() {
        withMountedRoot("report-table-signature") { root, element ->
            root.reportTable(caption = "Gewinn- und Verlustrechnung (GuV)", headers = headers)
            val rendered = table(element())
            listOf("table-sm", "table-striped", "table-hover", "caption-top", "lapis-report-table").forEach { expected ->
                assertTrue(rendered.className.contains(expected), "$expected missing: ${rendered.className}")
            }
            assertTrue(
                (rendered.parentElement as? HTMLElement)?.className?.contains("table-responsive") == true,
                "responsive frame missing",
            )
        }
    }

    @Test
    fun reportTable_captionIsTheFirstChildAndTranslated() {
        withTranslations(mapOf("Gewinn- und Verlustrechnung (GuV)" to "Income statement")) {
            withMountedRoot("report-table-caption") { root, element ->
                root.reportTable(caption = tr("Gewinn- und Verlustrechnung (GuV)"), headers = headers)
                val first = assertNotNull(table(element()).firstElementChild, "empty table")
                assertEquals("CAPTION", first.tagName)
                assertEquals("Income statement", first.textContent?.trim())
            }
        }
    }

    @Test
    fun reportTable_hasNoSortButtonsAndNoCardList() {
        withMountedRoot("report-table-no-sort") { root, element ->
            val report = root.reportTable(caption = "Bericht", headers = headers)
            report.reportRows(incomeStatementRows(incomeStatement), headers)
            val head = assertNotNull(table(element()).getElementsByTagName("thead")[0])
            assertEquals(0, head.getElementsByTagName("button").length, "a report header has no sort buttons")
            assertNull(element().querySelector(".lapis-card-list"), "a report never turns into a card list")
            assertNull(element().querySelector("[aria-sort]"), "a report is not sortable")
        }
    }

    @Test
    fun sectionRow_isASpanningHeadingWithoutAScopeClaim_andSubsectionsKeepTheBaseClass() {
        withMountedRoot("report-table-section") { root, element ->
            val report = root.reportTable(caption = "Bericht", headers = headers)
            report.sectionRow("Aktiva", headers.size)
            report.sectionRow("Verbindlichkeiten", headers.size, sub = true)
            val th = assertNotNull(element().querySelector("tbody tr.lapis-section-row > th"), "no section heading")
            assertEquals("3", th.getAttribute("colspan"))
            assertEquals("Aktiva", th.textContent?.trim())
            // Audit D3: ONE <tbody> -- a `scope="rowgroup"` would let "Aktiva" claim the "Passiva" rows below it as well.
            assertNull(th.getAttribute("scope"), "a section heading in a single-tbody report must not claim a scope")
            assertEquals(0, element().querySelectorAll("[scope=rowgroup]").length)
            assertEquals(1, element().querySelectorAll("tbody").length, "a report has ONE tbody")
            val sub =
                assertNotNull(
                    element().querySelector("tbody tr.lapis-section-row.lapis-section-row-sub"),
                    "sub-section lost its base class",
                )
            assertEquals("Verbindlichkeiten", sub.textContent?.trim())
        }
    }

    @Test
    fun columnHeadersCarryScopeCol_andSumAndBalanceLabelsAreRowHeaders() {
        withMountedRoot("report-table-scopes") { root, element ->
            val report = root.reportTable(caption = "Bericht", headers = headers)
            report.reportRows(balanceSheetRows(balanceSheet), headers)
            val heads = element().querySelectorAll("thead th")
            assertEquals(headers.size, heads.length)
            (0 until heads.length).forEach { assertEquals("col", (heads[it] as HTMLElement).getAttribute("scope"), "column header $it") }

            // Audit D4: the label of every sum/balance row is that row's header (`<th scope="row">`) ...
            val totals = element().querySelectorAll("tbody tr.lapis-total-row, tbody tr.lapis-balance-row")
            assertEquals(5, totals.length)
            (0 until totals.length).forEach { index ->
                val labels = (totals[index] as HTMLElement).querySelectorAll("th[scope=row]")
                assertEquals(1, labels.length, "sum/balance row $index has exactly one row header")
                assertTrue(!(labels[0] as HTMLElement).textContent.orEmpty().isBlank())
            }
            // ... and a figure row has NO row header (its first cell is a data cell)
            val figureRows = "tbody tr:not(.lapis-total-row):not(.lapis-balance-row):not(.lapis-section-row)"
            assertEquals(0, element().querySelectorAll("$figureRows th").length)
        }
    }

    @Test
    fun ledgerBalanceLabelIsTheRowHeaderEvenBehindBlankCells() {
        withMountedRoot("report-table-ledger-row-header") { root, element ->
            val ledgerHeaders =
                listOf(
                    TableHeader("Datum"),
                    TableHeader("Beschreibung"),
                    TableHeader("Soll", numeric = true),
                    TableHeader("Haben", numeric = true),
                    TableHeader("Saldo", numeric = true),
                )
            val report = root.reportTable(caption = "Hauptbuch", headers = ledgerHeaders)
            report.reportRows(generalLedgerRows(generalLedger), ledgerHeaders)
            val opening = assertNotNull(element().querySelector("tbody tr.lapis-balance-row"))
            assertEquals(5, opening.children.length, "the row keeps all five cells")
            assertEquals("TD", opening.children[0]?.tagName, "the blank date cell stays a data cell")
            assertEquals("TH", opening.children[1]?.tagName)
            assertEquals("row", opening.children[1]?.getAttribute("scope"))
        }
    }

    @Test
    fun captionCanBeHiddenVisuallyButStaysTheTablesName() {
        withMountedRoot("report-table-caption-flag") { root, element ->
            root.reportTable(caption = "Bilanz", headers = headers, captionVisible = false)
            root.reportTable(caption = "GuV", headers = headers)
            val tables = element().getElementsByTagName("table")
            assertEquals(2, tables.length)
            assertTrue(tables[0]!!.className.contains("lapis-report-caption-hidden"))
            assertTrue(!tables[1]!!.className.contains("lapis-report-caption-hidden"))
            assertEquals("Bilanz", tables[0]!!.firstElementChild?.textContent?.trim(), "the hidden caption is still there")
        }
    }

    @Test
    fun reportRows_paintSumBalanceAndNoteRowsWithTheirOwnClass() {
        withMountedRoot("report-table-rows") { root, element ->
            val report = root.reportTable(caption = "Bericht", headers = headers)
            report.reportRows(balanceSheetRows(balanceSheet), headers)
            assertEquals(4, element().querySelectorAll("tbody tr.lapis-total-row").length)
            assertEquals(1, element().querySelectorAll("tbody tr.lapis-balance-row").length)
            assertEquals(1, element().querySelectorAll("tbody tr.lapis-note-row").length)
            assertEquals("3", element().querySelector("tbody tr.lapis-note-row > td")?.getAttribute("colspan"))
            // the amount column is right-aligned in every row that has an amount
            val numeric = element().querySelectorAll("tbody td.lapis-num").length
            assertTrue(numeric >= 8, "amount cells lost their .lapis-num: $numeric")
        }
    }
}
