package network.lapis.cloud.client

import io.kvision.table.Row
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.27 (W3): the accessibility contract of the expandable detail row -- what a screen reader user gets
 * from the four sphere rows of the Vier-Sphaeren report: a button per row that says WHICH sphere it opens
 * (four different names), `aria-expanded` that follows the state, and `aria-controls` that points at a row that
 * exists. Built from the blocks directly; `ReportScreensDomTest` repeats the checks on the real screen.
 */
class ExpandRowA11yDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private val headers = listOf(TableHeader("Sphäre"), TableHeader("Einnahmen", numeric = true), TableHeader(title = "", numeric = false))

    private suspend fun awaitAttribute(
        button: () -> HTMLElement,
        name: String,
        expected: String,
    ) {
        repeat(100) {
            if (button().getAttribute(name) == expected) return
            delay(20)
        }
        assertEquals(expected, button().getAttribute(name), "$name never became $expected")
    }

    @Test
    fun toggleButtons_nameTheirObject_andControlAnExistingRow(): Promise<Unit> =
        test {
            withMountedRoot("expand-a11y") { root, element ->
                val report = root.reportTable(caption = "Vier-Sphären", headers = headers)
                GemeinnuetzigkeitSphere.entries.forEach { sphere ->
                    val rowId = "lapis-sphere-${sphere.name}"
                    report.row {
                        cell(sphereLabel(sphere))
                        cell("0")
                        cell { expandToggleButton(sphereLabel(sphere), rowId) {} }
                    }
                    report.detailRow(rowId, headers.size) { }
                }
                val buttons = element().querySelectorAll("button[aria-controls]")
                assertEquals(4, buttons.length)
                val names = (0 until buttons.length).map { (buttons[it] as HTMLElement).getAttribute("aria-label").orEmpty() }
                assertEquals(4, names.toSet().size, "the four buttons must have four different names: $names")
                names.forEach { assertTrue(!it.contains("###"), "i18n marker leaked into the accessible name: $it") }
                names.forEach { assertTrue(it.startsWith("Details ") && it.endsWith(" ein-/ausblenden"), "unexpected name: $it") }
                (0 until buttons.length).forEach { index ->
                    val button = buttons[index] as HTMLElement
                    val target =
                        assertNotNull(
                            document.getElementById(button.getAttribute("aria-controls").orEmpty()),
                            "aria-controls points nowhere",
                        )
                    assertEquals("TR", target.tagName)
                    assertEquals("false", button.getAttribute("aria-expanded"))
                }
            }
        }

    @Test
    fun clickingTheToggle_flipsAriaExpanded_andReportsTheState(): Promise<Unit> =
        test {
            withMountedRoot("expand-a11y-toggle") { root, element ->
                val report = root.reportTable(caption = "Bericht", headers = headers)
                val states = mutableListOf<Boolean>()
                lateinit var detail: Row
                report.row {
                    cell("x")
                    cell("0")
                    cell {
                        expandToggleButton("Test", "lapis-detail-1") {
                            states += it
                            detail.setExpanded(it)
                        }
                    }
                }
                detail = report.detailRow("lapis-detail-1", headers.size) { }

                fun button() = assertNotNull(element().querySelector("button[aria-controls]") as? HTMLElement)

                fun collapsed() =
                    assertNotNull(
                        document.getElementById("lapis-detail-1"),
                        "the detail row left the document",
                    ).classList.contains("d-none")
                assertTrue(collapsed(), "the detail row starts collapsed")
                button().click()
                awaitAttribute(::button, "aria-expanded", "true")
                assertTrue(!collapsed(), "expanding must show the row")
                button().click()
                awaitAttribute(::button, "aria-expanded", "false")
                assertTrue(collapsed(), "collapsing must hide the row again, but keep it in the document")
                assertEquals(listOf(true, false), states)
            }
        }
}
