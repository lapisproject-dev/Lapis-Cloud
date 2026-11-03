package network.lapis.cloud.client

import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.225 / V1.9.256 -- the party-report page: shell, notice, tabs, the overview table with its icon-only actions (guideline R58), draft wording only (no claim of an official or finished report). */
class PartyReportScreenDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.buttons(): List<String> = allOf("button").map { it.textContent?.trim().orEmpty() }

    @Test
    fun theOverview_showsTheUnitsWithBadgesTotals_andOnlyDraftWording(): Promise<Unit> =
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
                    // six tabs in the display order (not the enum order), the overview is active
                    val tabs = page.allOf(".btn-group button").map { it.textContent?.trim() }
                    assertEquals(listOf("Übersicht", "Werte erfassen", "Ausweise", "Erläuterungen", "Positionen", "Bericht"), tabs)
                    val rows = page.allOf("tbody tr")
                    assertTrue(rows[0].shows("Bundesverband") && rows[0].shows("Nicht erfasst"))
                    assertTrue(rows[1].shows("Landesverband Nord") && rows[1].shows("Teilweise (12 von 35)"))
                    assertTrue(rows[1].shows("1.500,00"), "income total")
                    assertTrue(rows[0].shows("–"), "nothing entered shows a dash")
                    // the page offers the draft, never a finished or official report
                    assertTrue(page.shows("Entwurf"), "the page speaks of a draft")
                    assertTrue(page.buttons().contains("Bericht erzeugen"))
                    val all = page.textContent.orEmpty().lowercase()
                    val withoutNegations =
                        all
                            .replace("nicht amtlich", "")
                            .replace("ersetzt nicht den geprüften", "")
                            .replace("kein amtlicher", "")
                    listOf("amtlich", "fertig", "geprüft", "einreichen").forEach {
                        assertFalse(withoutNegations.contains(it), "no claim of '$it' without a negation")
                    }
                }
            }
        }

    @Test
    fun theOverviewLink_switchesToTheReportTab(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-link") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview rows") { element().allOf("tbody tr").size == 2 }
                    element().buttonNamed("Bericht erzeugen").click()
                    awaitUntil("the report tab") { element().allOf("[data-partg-checklist]").isNotEmpty() }
                    assertTrue(element().shows("Umfang"))
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

    @Test
    fun theTabSelect_forNarrowScreens_hasTheLabelBereich_andTheSameSixChoices(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-tab-select") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview rows") { element().allOf("tbody tr").size == 2 }
                    val select = element().controlOf("Bereich") as org.w3c.dom.HTMLSelectElement
                    val labels =
                        (0 until select.options.length).map {
                            select.options
                                .item(it)
                                ?.textContent
                                ?.trim()
                        }
                    assertEquals(listOf("Übersicht", "Werte erfassen", "Ausweise", "Erläuterungen", "Positionen", "Bericht"), labels)
                    assertEquals("OVERVIEW", select.value)
                    // the wide and the narrow variant sit in their own containers; the stylesheet shows exactly one of them
                    assertEquals(1, element().allOf(".lapis-partg-tabs-wide").size)
                    assertEquals(1, element().allOf(".lapis-partg-tabs-narrow").size)
                }
            }
        }

    @Test
    fun aRememberedTabFromBeforeR3_isStillRead(): Promise<Unit> =
        formTest {
            // the stored value is the enum NAME; the four old names stay valid, the new ones were appended
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "POSITIONS") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-remembered") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the positions") { element().shows("Nur Pflichtbeiträge") }
                    assertEquals(
                        "Positionen",
                        element()
                            .allOf(".btn-group button")
                            .first { it.getAttribute("aria-pressed") == "true" }
                            .textContent
                            ?.trim(),
                    )
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }
}
