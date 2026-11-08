package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgUnitStatementDto
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.292 (R4b) -- the tab "Bericht" of a chapter treasurer: the unit part of their own unit and nothing of the whole party. */
class PartyReportChapterReportDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun treasurerSession() =
        PartyReportDomSupport.session(
            role = AccountRole.MEMBER,
            chapterTreasurerScopes = listOf(RegionalChapterRefDto("0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90", "Nord")),
        )

    private fun statement(
        unit: PartgUnitDto = PartyReportDomSupport.state,
        posted: Int = 0,
    ) = PartgUnitStatementDto(
        year = 2025,
        unit = unit,
        mode = PartgBookkeepingMode.A,
        current = mapOf("E01" to PartgCell.Value(150_000), "E02" to PartgCell.Partial(2_500, listOf("E03"))),
        prior = emptyMap(),
        missingCurrent = listOf("E03"),
        findings = emptyList(),
        postedEntryCount = posted,
    )

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
    fun theTreasurerSeesTheUnitPart_thePreview_threeDownloads_andNothingOfTheWholeParty(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
            AppState.setSession(treasurerSession())
            PartyReportDomSupport.unitStatement = statement()
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-chapter-report") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the preview") { element().allOf("[data-partg-chapter-preview]").isNotEmpty() }
                    val page = element()
                    assertTrue(page.shows("Teilbericht Ihrer Gliederung – Entwurf"))
                    assertTrue(page.shows("kein amtlicher Bericht"))
                    val preview = page.allOf("[data-partg-chapter-preview]").single()
                    assertTrue(preview.shows("A – Zentral gebucht"))
                    assertTrue(preview.shows("1.500,00"), "the entered value")
                    assertTrue(preview.shows("(unvollst.)"), "a partial sum is marked")
                    assertTrue(preview.shows("n. e."), "a missing value is not 0")
                    // the empty state: nothing is booked for this chapter
                    assertTrue(page.allOf("[data-partg-chapter-empty]").single().shows("Bundesschatzmeister"))
                    val buttons = page.allOf("button").map { it.textContent?.trim().orEmpty() }
                    listOf("PDF herunterladen", "Excel herunterladen", "Buchungsjournal (CSV)").forEach { assertTrue(it in buttons, it) }
                    // nothing of the federal view
                    val all = page.textContent.orEmpty()
                    assertFalse(all.contains("Gesamtpartei"))
                    assertFalse(all.contains("Konsolidierung"))
                    assertFalse(all.contains("Versionen und Freigabe"))
                    assertEquals(0, page.allOf("[data-partg-checklist]").size, "the whole-party checklist is not rendered")
                    val tabs = page.allOf(".btn-group button").map { it.textContent?.trim() }
                    assertEquals(listOf("Übersicht", "Werte erfassen", "Kontenzuordnung", "Bericht"), tabs)
                    // the downloads go to the treasurer's own unit
                    recordingDownloads { downloads ->
                        element().buttonNamed("PDF herunterladen").click()
                        element().buttonNamed("Excel herunterladen").click()
                        element().buttonNamed("Buchungsjournal (CSV)").click()
                        val key = PartyReportDomSupport.state.unitKey.replace(":", "%3A")
                        assertEquals(
                            listOf(
                                "/api/partg-report/export/pdf?year=2025&scope=$key",
                                "/api/partg-report/export/xlsx?year=2025&scope=$key",
                                "/api/partg-report/export/journal.csv?year=2025&unit=$key",
                            ),
                            downloads(),
                        )
                    }
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }

    @Test
    fun aSubordinateSummary_hasNoJournal_andTheEmptyHintIsNotShownForBookedChapters(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
            AppState.setSession(treasurerSession())
            val sub =
                PartgUnitDto(
                    unitKey = "SUB:0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90",
                    level = PartgUnitLevel.SUBORDINATE,
                    chapterId = "0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90",
                    chapterName = "Nord",
                )
            PartyReportDomSupport.unitsOverride = listOf(sub)
            PartyReportDomSupport.unitStatement = statement(unit = sub)
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-chapter-report-sub") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the preview") { element().allOf("[data-partg-chapter-preview]").isNotEmpty() }
                    assertTrue(element().shows("Für nachgeordnete Gliederungen gibt es kein Buchungsjournal."))
                    assertEquals(0, element().allOf("[data-partg-chapter-empty]").size)
                    recordingDownloads { downloads ->
                        element().buttonNamed("Buchungsjournal (CSV)").click()
                        assertEquals(emptyList(), downloads(), "no CSV is requested for a subordinate summary")
                    }
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }

    @Test
    fun aBookedChapter_showsNoEmptyHint(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
            AppState.setSession(treasurerSession())
            PartyReportDomSupport.unitStatement = statement(posted = 7)
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-chapter-report-booked") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the preview") { element().allOf("[data-partg-chapter-preview]").isNotEmpty() }
                    assertEquals(0, element().allOf("[data-partg-chapter-empty]").size)
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }
}
