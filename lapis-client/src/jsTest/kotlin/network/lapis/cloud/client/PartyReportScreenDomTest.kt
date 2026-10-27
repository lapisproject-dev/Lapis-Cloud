package network.lapis.cloud.client

import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.225 -- the party-report page: shell, notice, tabs, the overview table with its icon-only actions (guideline R58), no promise of a finished report. */
class PartyReportScreenDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    @Test
    fun theOverview_showsTheUnitsWithBadgesTotals_andNoPromiseOfAReport(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview rows") { element().allOf("tbody tr").size == 2 }
                    val page = element()
                    assertTrue(page.shows("Rechenschaftsbericht nach Parteiengesetz"))
                    // the notice says plainly that this is a working aid
                    assertTrue(page.allOf(".alert-info").single().shows("keine Rechtsberatung"))
                    // three tabs, the overview is active
                    val tabs = page.allOf(".btn-group button").map { it.textContent?.trim() }
                    assertEquals(listOf("Übersicht", "Werte erfassen", "Positionen"), tabs)
                    val rows = page.allOf("tbody tr")
                    assertTrue(rows[0].shows("Bundesverband") && rows[0].shows("Nicht erfasst"))
                    assertTrue(rows[1].shows("Landesverband Nord") && rows[1].shows("Teilweise (12 von 35)"))
                    assertTrue(rows[1].shows("1.500,00"), "income total")
                    assertTrue(rows[0].shows("–"), "nothing entered shows a dash")
                    // nothing on the page offers to create the report (R1 stores values only)
                    val all = page.textContent.orEmpty().lowercase()
                    assertFalse(
                        all.contains("bericht erstellen") || all.contains("bericht erzeugen") || all.contains("pdf"),
                        "no promise of a report",
                    )
                }
            }
        }

    @Test
    fun theRowActions_areIconsOnly_withNamesAsTooltipAndAccessibleName(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-actions") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview rows") { element().allOf("tbody tr").size == 2 }
                    val expected = listOf("Werte erfassen", "Vorlage herunterladen", "Excel-Datei importieren")
                    element().allOf("tbody tr").forEach { row ->
                        val buttons = row.allOf("button")
                        assertEquals(expected, buttons.map { it.getAttribute("aria-label") })
                        assertEquals(expected, buttons.map { it.getAttribute("title") })
                        assertTrue(buttons.all { it.textContent?.trim().isNullOrEmpty() }, "icon only")
                    }
                }
            }
        }

    @Test
    fun aNonParty_getsTheUnavailableNote_andNoTabs(): Promise<Unit> =
        formTest {
            AppState.setSession(PartyReportDomSupport.session(role = AccountRole.ADMIN, party = false))
            mountedForm("party-report-off") { root, element ->
                renderPartyReportScreen(root)
                assertTrue(element().shows("nur für politische Parteien"))
                assertEquals(0, element().allOf(".btn-group button").size)
            }
        }
}
