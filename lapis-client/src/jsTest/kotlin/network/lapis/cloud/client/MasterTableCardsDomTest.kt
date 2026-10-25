package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.link
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.Table
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import org.w3c.dom.Document
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** R62 / V1.9.211 -- master tables are card lists below 768 px, real tables above; labels are set by the hook and survive a re-render. */
class MasterTableCardsDomTest {
    private val headers =
        listOf(
            TableHeader("Name"),
            TableHeader("Art"),
            TableHeader("Mitglieder", numeric = true),
            TableHeader("Beschreibung", mobileHidden = true),
            TableHeader("Aktionen"),
        )

    private fun Table.fill(count: Int) {
        repeat(count) { index ->
            row {
                masterRow()
                val name = HeaderCell(scope = Scope.ROW)
                add(name)
                name.link("Gremium Nummer $index mit einem recht langen Namen", "#/committees/$index")
                cell("Vorstand")
                cell("$index")
                cell("Eine lange Beschreibung, die auf dem Telefon nicht stehen soll")
                cell {
                    tableActionGroup().add(Button("Bearbeiten"))
                }
            }
        }
    }

    private fun mountedTable(
        id: String,
        count: Int = 3,
        block: suspend (Table, Container, () -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            mountedForm(id) { root, element ->
                val table = root.masterTable("Gremien", headers)
                table.fill(count)
                awaitUntil("the labels") {
                    val cells = element().queryAll("tbody td")
                    cells.size == count * 4 && cells.all { it.getAttribute("role") == "cell" }
                }
                block(table, root, element)
            }
        }

    @Test
    fun labels_areSetOnEveryCell_andRolesEverywhere(): Promise<Unit> =
        mountedTable("cards-labels") { _, _, element ->
            val cells = element().queryAll("tbody td")
            assertTrue(cells.isNotEmpty())
            cells.forEach { cell ->
                val isActions = cell.getAttribute("data-cell") == "actions"
                assertTrue(isActions || !cell.getAttribute("data-label").isNullOrEmpty(), "label on ${cell.outerHTML}")
                assertEquals("cell", cell.getAttribute("role"))
            }
            assertEquals(
                listOf("Art", "Mitglieder", "Beschreibung"),
                element().queryAll("tbody tr")[0].queryAll("td[data-label]").map { it.getAttribute("data-label") },
            )
            assertEquals(null, element().queryOne("tbody th").getAttribute("data-label"))
            assertEquals("rowheader", element().queryOne("tbody th").getAttribute("role"))
            assertEquals("table", element().queryOne("table").getAttribute("role"))
            assertEquals(1, element().queryAll("tbody tr")[0].queryAll("td[data-mobile-hidden]").size)
        }

    @Test
    fun colspanDetailRow_getsNoLabel(): Promise<Unit> =
        mountedTable("cards-colspan") { table, _, element ->
            table.row {
                cell("Eine aufgeklappte Nachricht") { setAttribute("colspan", "5") }
            }
            awaitUntil("the detail cell is processed") {
                element().queryAll("tbody td").any { it.getAttribute("colspan") == "5" && it.getAttribute("role") == "cell" }
            }
            val detail = element().queryAll("tbody td").first { it.getAttribute("colspan") == "5" }
            assertEquals(null, detail.getAttribute("data-label"))
            assertEquals(null, detail.getAttribute("data-cell"))
        }

    @Test
    fun labels_survive_aRerender(): Promise<Unit> =
        mountedTable("cards-rerender") { table, _, element ->
            table.removeAll()
            table.fill(2)
            awaitUntil("two relabelled rows") {
                val rows = element().queryAll("tbody tr")
                rows.size == 2 && rows.all { row -> row.queryAll("td[data-label]").size == 3 && row.getAttribute("role") == "row" }
            }
        }

    @Test
    fun dispose_stopsTheObserver_withoutError(): Promise<Unit> =
        mountedTable("cards-dispose") { table, _, element ->
            val html = element().queryOne("table") as org.w3c.dom.HTMLTableElement
            val observer = TableLabelObserver(html, emptySet())
            observer.start()
            observer.stop()
            observer.stop()
            table.dispose()
        }

    @Test
    fun mobileHidden_isValidated() {
        assertFailsWith<IllegalStateException> { requireValidMobileHidden(listOf(TableHeader("A", mobileHidden = true), TableHeader("B"))) }
        assertFailsWith<IllegalStateException> {
            requireValidMobileHidden(listOf(TableHeader("A"), TableHeader("B", mobileHidden = true), TableHeader("C", mobileHidden = true)))
        }
        requireValidMobileHidden(listOf(TableHeader("A"), TableHeader("B", mobileHidden = true)))
    }

    private fun style(
        doc: Document,
        element: HTMLElement,
        pseudo: String? = null,
    ) = window.getComputedStyle(element, pseudo)

    @Test
    fun at360px_rowsAreCards_noOverflow_labelsShown_touchTargets(): Promise<Unit> =
        mountedTable("cards-360") { _, _, element ->
            measureAtWidth(element(), 360) { doc, host ->
                assertTrue(doc.documentElement!!.scrollWidth <= doc.documentElement!!.clientWidth, "no horizontal page overflow")
                val tr = host.queryOne("tbody tr")
                assertEquals("block", style(doc, tr).display)
                val cell = host.queryOne("tbody td[data-label]")
                assertEquals("flex", style(doc, cell).display)
                assertEquals("\"Art\"", style(doc, cell, "::before").content)
                val head = host.queryOne("thead")
                assertTrue(head.getBoundingClientRect().height <= 1.5, "thead is visually hidden but in the DOM")
                assertEquals("none", style(doc, host.queryOne("td[data-mobile-hidden]")).display)
                assertTrue(host.queryOne("tbody th a").getBoundingClientRect().height >= 44.0, "name link 44px")
                assertTrue(host.queryOne("td[data-cell=actions] button").getBoundingClientRect().height >= 44.0, "action 44px")
            }
        }

    @Test
    fun at1200px_isARealTable_withStripes(): Promise<Unit> =
        mountedTable("cards-1200") { _, _, element ->
            measureAtWidth(element(), 1200) { doc, host ->
                val rows = host.queryAll("tbody tr")
                assertEquals("table-row", style(doc, rows[0]).display)
                assertTrue(host.queryOne("thead").getBoundingClientRect().height > 1.5)
                assertNotEquals(style(doc, rows[0].queryOne("td")).backgroundColor, style(doc, rows[1].queryOne("td")).backgroundColor)
            }
        }

    @Test
    fun darkMode_cardAndStickyCellsAreOpaque(): Promise<Unit> =
        mountedTable("cards-dark") { _, _, element ->
            listOf(360, 1200).forEach { width ->
                measureAtWidth(element(), width) { doc, host ->
                    doc.documentElement!!.setAttribute("data-theme", "dark")
                    val surface = style(doc, doc.documentElement.unsafeCast<HTMLElement>()).getPropertyValue("--lapis-surface").trim()
                    assertTrue(surface.isNotEmpty())
                    if (width == 360) {
                        assertTrue(style(doc, host.queryOne("tbody tr")).backgroundColor.startsWith("rgb("), "opaque card")
                    }
                }
            }
        }

    @Test
    fun stickyFirstColumn_isStickyAndOpaque_withoutPageOverflow(): Promise<Unit> =
        formTest {
            mountedForm("cards-sticky") { root, element ->
                val table = root.standardTable(listOf(TableHeader("A"), TableHeader("B")), stickyFirstColumn = true)
                table.row {
                    cell("a")
                    cell("b")
                }
                awaitUntil("the table") { element().querySelector("tbody td") != null }
                measureAtWidth(element(), 360) { doc, host ->
                    val first = host.queryOne("tbody td")
                    assertEquals("sticky", style(doc, first).position)
                    assertTrue(style(doc, first).backgroundColor.startsWith("rgb("), "opaque: ${style(doc, first).backgroundColor}")
                    assertTrue(doc.documentElement!!.scrollWidth <= doc.documentElement!!.clientWidth)
                }
            }
        }
}
