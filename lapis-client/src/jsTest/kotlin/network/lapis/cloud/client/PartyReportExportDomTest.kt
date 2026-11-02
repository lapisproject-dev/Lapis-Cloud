package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.256 -- the "Bericht" tab: scope, checklist, the draft notice and the two download buttons (always enabled, no dialog). */
class PartyReportExportDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    /** The second select of the page: the first one is the report year. */
    private fun HTMLElement.scopeSelect(): HTMLSelectElement = querySelectorAll("select").item(1) as HTMLSelectElement

    /** Records (and cancels) every download anchor that is clicked while [block] runs. */
    private suspend fun <T> recordingDownloads(block: suspend (() -> List<String>) -> T): T {
        val hrefs = mutableListOf<String>()
        val listener: (Event) -> Unit = { event ->
            val anchor = event.target as? HTMLAnchorElement
            if (anchor != null && anchor.hasAttribute("download")) {
                hrefs += anchor.getAttribute("href").orEmpty()
                event.preventDefault()
            }
        }
        document.addEventListener("click", listener, true)
        try {
            return block { hrefs.toList() }
        } finally {
            document.removeEventListener("click", listener, true)
        }
    }

    @Test
    fun theTab_offersTheScope_theChecklist_theNotice_andBothDownloads(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-export") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the checklist") { element().allOf("[data-partg-checklist]").isNotEmpty() }
                    val page = element()
                    val select = page.scopeSelect()
                    val options =
                        (0 until select.options.length).map {
                            select.options
                                .item(it)
                                ?.textContent
                                ?.trim()
                        }
                    assertEquals(listOf("Gesamtpartei (alle Einheiten)", "Bundesverband", "Landesverband Nord"), options)
                    assertEquals("ALL", select.value)
                    val checklist = page.allOf("[data-partg-checklist]").single()
                    assertTrue(checklist.shows("Einheiten vollständig: 1"), "the complete unit is one summary line")
                    val missing = checklist.allOf("details[data-missing]").single()
                    assertTrue(missing.shows("Bundesverband: 2 von 35 Positionen nicht erfasst"))
                    assertTrue(missing.shows("Mitgliedsbeiträge und ähnliche regelmäßige Beiträge"))
                    assertTrue(missing.allOf("button").any { it.textContent?.trim() == "Werte erfassen" })
                    // the prior year is a neutral note, not a warning
                    val prior = checklist.allOf("[data-prior]")
                    assertEquals(listOf("none", "partial"), prior.map { it.getAttribute("data-prior") })
                    assertTrue(prior.all { it.classList.contains("text-muted") && !it.classList.contains("text-warning-emphasis") })
                    assertTrue(prior[0].shows("Vorjahr nicht erfasst – Spalte bleibt leer"))
                    assertTrue(prior[1].shows("Vorjahr: 5 von 35 Positionen nicht erfasst"))
                    // the fixed notice
                    val notice = page.allOf(".alert-warning").single { it.shows("kein amtlicher Bericht") }
                    assertEquals("note", notice.getAttribute("role"))
                    assertTrue(notice.shows("„n. e.“"))
                    // both downloads are enabled although there are gaps; no confirmation dialog exists
                    listOf("PDF herunterladen", "Excel herunterladen").forEach {
                        assertFalse(page.buttonNamed(it).hasAttribute("disabled"))
                    }
                    assertEquals(0, document.querySelectorAll(".modal.show").length)
                }
            }
        }

    @Test
    fun aClickOnADownload_startsTheExpectedUrl_withoutDialog(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-export-click") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the checklist") { element().allOf("[data-partg-checklist]").isNotEmpty() }
                    recordingDownloads { downloads ->
                        element().buttonNamed("PDF herunterladen").click()
                        element().buttonNamed("Excel herunterladen").click()
                        assertEquals(
                            listOf(
                                "/api/partg-report/export/pdf?year=2025&scope=ALL",
                                "/api/partg-report/export/xlsx?year=2025&scope=ALL",
                            ),
                            downloads(),
                        )
                    }
                    assertEquals(0, document.querySelectorAll(".modal.show").length)
                }
            }
        }

    @Test
    fun aSingleUnitScope_filtersTheChecklist_andEntersTheScopeInTheUrl(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-export-scope") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the checklist") { element().allOf("[data-partg-checklist]").isNotEmpty() }
                    val select = element().scopeSelect()
                    select.value = PartyReportDomSupport.state.unitKey
                    select.dispatchEvent(Event("change"))
                    awaitUntil("the filtered checklist") { element().allOf("details[data-missing]").isEmpty() }
                    assertTrue(element().shows("Alle Positionen sind erfasst."))
                    recordingDownloads { downloads ->
                        element().buttonNamed("PDF herunterladen").click()
                        assertEquals(
                            listOf(
                                "/api/partg-report/export/pdf?year=2025&scope=" + PartyReportDomSupport.state.unitKey.replace(":", "%3A"),
                            ),
                            downloads(),
                        )
                    }
                }
            }
        }

    @Test
    fun theMissingPositionLink_jumpsToTheEntryTabOfThatUnit(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-export-jump") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the checklist") { element().allOf("details[data-missing]").isNotEmpty() }
                    element().allOf("details[data-missing] button").first { it.textContent?.trim() == "Werte erfassen" }.click()
                    awaitUntil("the entry table") { element().allOf("input[id^='partg-current-']").size == 35 }
                    val select = element().scopeSelect()
                    assertEquals("FEDERAL", select.value)
                }
            }
        }

    @Test
    fun aNonParty_hasNoTabs(): Promise<Unit> =
        formTest {
            AppState.setSession(PartyReportDomSupport.session(role = AccountRole.ADMIN, party = false))
            mountedForm("party-report-export-off") { root, element ->
                renderPartyReportScreen(root)
                assertEquals(0, element().allOf(".btn-group button").size)
                assertEquals(0, element().allOf("[data-partg-checklist]").size)
            }
        }
}
