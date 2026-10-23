package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.192 -- the shared master-detail pattern (table with caption, real name link, back button, focus hand-back), tested once. */
class MasterDetailDomTest {
    @Test
    fun detailRoute_urlEncodesTheId() {
        assertEquals("/committees/abc-1", detailRoute("/committees", "abc-1"))
        assertEquals("/meetings/a%2Fb%20c%3Fx", detailRoute("/meetings", "a/b c?x"))
    }

    @Test
    fun masterTable_hasAHiddenCaption_andColumnHeaders(): Promise<Unit> =
        formTest {
            mountedForm("master-table") { root, element ->
                val table = root.masterTable("Gremien", listOf(TableHeader("Name"), TableHeader("Art")))
                table.row { masterRow() }
                awaitUntil("the table") { element().querySelector("table") != null }
                val caption = assertNotNull(element().querySelector("table > caption"), "a caption")
                assertEquals("Gremien", caption.textContent?.trim())
                assertTrue(element().querySelector("table")!!.classList.contains(REPORT_CAPTION_HIDDEN_CLASS))
                val headers = element().allOf("thead th")
                assertEquals(listOf("Name", "Art"), headers.map { it.textContent?.trim() })
                assertTrue(headers.all { it.getAttribute("scope") == "col" })
                assertTrue(
                    element()
                        .allOf("tbody tr")
                        .single()
                        .classList
                        .contains(MASTER_ROW_CLASS),
                )
            }
        }

    @Test
    fun recordLink_isARealAnchor_withTheIdAndSanitizedText_andTheRowHasNoClickHandler(): Promise<Unit> =
        formTest {
            mountedForm("master-link") { root, element ->
                val forged = KV_I18N_MARKER + "Vorstand"
                val table = root.masterTable("Gremien", listOf(TableHeader("Name")))
                table.row {
                    masterRow()
                    cell { recordLink(forged, "/committees", "id/1") }
                }
                awaitUntil("the link") { element().querySelector("a[data-record-id]") != null }
                val link = element().querySelector("a[data-record-id]") as HTMLElement
                assertEquals("#/committees/id%2F1", link.getAttribute("href"))
                assertEquals("id/1", link.getAttribute(RECORD_ID_ATTRIBUTE))
                assertFalse(link.textContent.orEmpty().contains(KV_I18N_MARKER), "marker must not survive")
                val before = window.location.hash
                (element().querySelector("tbody tr") as HTMLElement).click()
                assertEquals(before, window.location.hash, "a click on the row (not the link) navigates nowhere")
                MasterDetailFocus.clearAll()
            }
        }

    @Test
    fun backButton_hasTheArrowIcon_naturalWidth_andAlwaysNavigatesToTheList(): Promise<Unit> =
        formTest {
            mountedForm("master-back") { root, element ->
                val target = mutableListOf<String>()
                root.div { overviewBackButton("/committees") { target += it } }
                val button = element().buttonNamed("Zur Übersicht")
                assertNotNull(button.querySelector("i.fa-arrow-left"), "the arrow icon")
                assertTrue(button.classList.contains(BACK_BUTTON_CLASS))
                assertFalse(button.classList.contains("w-100"))
                val historyBefore = window.history.length
                button.click()
                assertEquals(listOf("/committees"), target)
                assertEquals(historyBefore, window.history.length, "no history.back()")
            }
        }

    @Test
    fun focusHandBack_goesToTheOpenedLink_once_andNotAfterAFreshVisit(): Promise<Unit> =
        formTest {
            MasterDetailFocus.clearAll()
            MasterDetailFocus.remember("/committees", "b")
            mountedForm("master-focus") { root, element ->
                val table = root.masterTable("Gremien", listOf(TableHeader("Name")))
                table.row { cell { recordLink("A", "/committees", "a") } }
                table.row { cell { recordLink("B", "/committees", "b") } }
                awaitUntil("the second link has the focus") {
                    (kotlinx.browser.document.activeElement as? HTMLElement)?.getAttribute(RECORD_ID_ATTRIBUTE) == "b"
                }
                assertNull(MasterDetailFocus.peek("/committees"), "consumed")
            }
            // a fresh visit (nothing remembered) leaves the focus alone
            mountedForm("master-focus-fresh") { root, element ->
                (kotlinx.browser.document.activeElement as? HTMLElement)?.blur()
                val table = root.masterTable("Gremien", listOf(TableHeader("Name")))
                table.row { cell { recordLink("A", "/committees", "a") } }
                awaitUntil("the link") { element().querySelector("a[data-record-id]") != null }
                kotlinx.coroutines.delay(150)
                assertNull(
                    (kotlinx.browser.document.activeElement as? HTMLElement)?.getAttribute(RECORD_ID_ATTRIBUTE),
                    "no focus without a remembered record",
                )
            }
        }
}
