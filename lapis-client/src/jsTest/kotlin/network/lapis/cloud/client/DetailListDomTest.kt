package network.lapis.cloud.client

import io.kvision.html.span
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.27 (W3): the label/value list ([detailList] / [detailEntry]) -- the `<dl>` that replaced the
 * `width = 220.px` label cells of the audit-log detail view. Structure (a `dt` and its `dd` are DIRECT children
 * of the `dl`, in pairs -- otherwise the grid and the screen-reader association break) and the grid of
 * `theme.css` (real Bootstrap + theme loaded, otherwise a style assertion would test nothing).
 */
class DetailListDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('./theme.css')")
                true
            }
    }

    private fun HTMLElement.dl() = assertNotNull(querySelector("dl.lapis-detail-list") as? HTMLElement, "no <dl class=lapis-detail-list>")

    @Test
    fun detailEntry_createsOneDtAndOneDdAsDirectChildrenOfTheDl() {
        withMountedRoot("detail-list-structure") { root, element ->
            val list = root.detailList()
            list.detailEntry("Datum") { span("2026-03-01") }
            list.detailEntry("Status") { span("Gebucht") }

            val dl = element().dl()
            val children = (0 until dl.children.length).map { dl.children.item(it)!! }
            assertEquals(listOf("DT", "DD", "DT", "DD"), children.map { it.tagName })
            assertEquals(listOf("Datum", "2026-03-01", "Status", "Gebucht"), children.map { it.textContent.orEmpty().trim() })
            assertEquals(1, element().querySelectorAll("dl").length, "entries must not open further lists")
        }
    }

    @Test
    fun emptyDetailList_hasNoEntries() {
        withMountedRoot("detail-list-empty") { root, element ->
            root.detailList()
            assertEquals(0, element().dl().children.length)
        }
    }

    @Test
    fun theGridPutsTheValueNextToItsLabel() {
        assertTrue(stylesLoaded)
        withMountedRoot("detail-list-grid") { root, element ->
            val list = root.detailList()
            list.detailEntry("Beschreibung") { span("Beitrag") }
            val dl = element().dl()
            assertEquals("grid", window.getComputedStyle(dl).display)
            val dt = dl.querySelector("dt") as HTMLElement
            val dd = dl.querySelector("dd") as HTMLElement
            assertTrue(dd.offsetLeft > dt.offsetLeft, "the value must sit in the second grid column, right of its label")
            assertEquals(dt.offsetTop, dd.offsetTop, "label and value share one grid row")
            assertEquals("0px", window.getComputedStyle(dd).marginLeft, "Bootstrap's dd margin would break the grid alignment")
        }
    }
}
