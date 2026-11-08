package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgAccountMappingRowDto
import network.lapis.cloud.shared.domain.PartgAccountMappingViewDto
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgLedgerProposalDto
import network.lapis.cloud.shared.domain.PartgLedgerProposalRowDto
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.289 (R4a) -- the tab "Kontenzuordnung", the dialog "Vorschlag aus Buchungen", the mode column and the tabs of a chapter treasurer. */
class PartyReportR4DomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun mappingRow(
        id: String,
        type: LedgerAccountType = LedgerAccountType.INCOME,
        code: String? = null,
        suggestion: String? = null,
        postings: Int = 0,
        balance: Long? = null,
        name: String = "Konto $id",
        nameSuggestion: String? = null,
        nameKeyword: String? = null,
    ) = PartgAccountMappingRowDto(
        accountId = id,
        number = "4000$id",
        name = name,
        type = type,
        active = true,
        positionCode = code,
        templateSuggestion = suggestion,
        balanceCents = balance,
        postingCount = postings,
        nameSuggestion = nameSuggestion,
        nameSuggestionKeyword = nameKeyword,
    )

    private fun view(
        editable: Boolean,
        rows: List<PartgAccountMappingRowDto>,
    ) = PartgAccountMappingViewDto(
        rows = rows,
        unmappedCount = rows.count { it.positionCode == null },
        unmappedWithActivityCount = rows.count { it.positionCode == null && it.postingCount > 0 },
        editable = editable,
        year = 2025,
        unitKey = "FEDERAL",
    )

    @Test
    fun theMappingTab_showsTheUnmappedBlock_theDraftNotice_andSuggestionsThatMarkNothingUntilTaken(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "KONTENZUORDNUNG") }
            AppState.setSession(PartyReportDomSupport.session())
            PartyReportDomSupport.mappingView =
                view(
                    editable = true,
                    rows =
                        listOf(
                            mappingRow("1", suggestion = "E01", postings = 3, balance = 12_000),
                            mappingRow("2", type = LedgerAccountType.EXPENSE, code = "A01"),
                            mappingRow("3", type = LedgerAccountType.EXPENSE, balance = 100),
                        ),
                )
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-mapping") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the mapping block") { element().allOf("[data-partg-unmapped]").isNotEmpty() }
                    val page = element()
                    assertTrue(page.shows("Entwurf, kein amtliches Formular"), "the draft notice")
                    assertTrue(page.shows("Nicht zugeordnet: 2 Konten"))
                    assertTrue(page.shows("davon 1 mit Buchungen im Jahr 2025"))
                    assertEquals(0, page.allOf("[data-partg-suggestion]").size, "no suggestion before it is asked for")
                    assertFalse(page.shows("Nicht gespeichert"), "nothing is unsaved")
                    page.buttonNamed("Vorschläge anzeigen").click()
                    awaitUntil("the suggestion") { element().allOf("[data-partg-suggestion]").size == 1 }
                    assertTrue(element().shows("aus Parteien-Kontenplan"))
                    assertTrue(element().shows("Vorschläge. Bitte prüfen, bevor Sie sie übernehmen."))
                    assertFalse(element().shows("Nicht gespeichert"), "a suggestion is no mapping")
                    // taking the ticked suggestion over only MARKS it: the save bar says "Nicht gespeichert"
                    element().buttonNamed("Alle Kontenplan-Vorschläge auswählen").click()
                    element().buttonNamed("Ausgewählte übernehmen").click()
                    awaitUntil("the unsaved mark", detail = { element().textContent.orEmpty().take(600) }) {
                        element().shows("Nicht gespeichert")
                    }
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }

    @Test
    fun aGuessFromTheAccountName_isLabelledAsAGuess_andTheBulkTickTakesOnlyTheChartSuggestions(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "KONTENZUORDNUNG") }
            AppState.setSession(PartyReportDomSupport.session())
            PartyReportDomSupport.mappingView =
                view(
                    editable = true,
                    rows =
                        listOf(
                            mappingRow("1", suggestion = "E01", postings = 3, balance = 12_000),
                            mappingRow(
                                "2",
                                type = LedgerAccountType.EXPENSE,
                                name = "Personal",
                                nameSuggestion = "A01",
                                nameKeyword = "personal",
                                postings = 1,
                                balance = 500,
                            ),
                        ),
                )
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-mapping-name") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the mapping block") { element().allOf("[data-partg-unmapped]").isNotEmpty() }
                    element().buttonNamed("Vorschläge anzeigen").click()
                    awaitUntil("both suggestions") { element().allOf("[data-partg-suggestion]").size == 2 }
                    val sources =
                        element().allOf("[data-partg-suggestion]").associate {
                            it.getAttribute("data-partg-suggestion").orEmpty() to it.getAttribute("data-partg-suggestion-source").orEmpty()
                        }
                    assertEquals(mapOf("1" to "chart", "2" to "name"), sources)
                    assertTrue(element().shows("Vermutung nach Kontoname („personal“)"), "the guess names the word it came from")
                    assertTrue(element().shows("aus Parteien-Kontenplan"))
                    // the bulk tick takes the chart suggestion only; a guess stays unticked
                    element().buttonNamed("Alle Kontenplan-Vorschläge auswählen").click()
                    val ticked =
                        element().allOf("[data-partg-pick]").associate {
                            it.getAttribute("data-partg-pick").orEmpty() to
                                it.isChecked()
                        }
                    assertEquals(mapOf("1" to true, "2" to false), ticked)
                    element().buttonNamed("Ausgewählte übernehmen").click()
                    awaitUntil("the chart suggestion is taken over, the guess is not") { element().shows("Nicht gespeichert") }
                    assertEquals(1, element().allOf("[data-partg-suggestion]").size, "the guess is still only a suggestion")
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }

    private fun HTMLElement.isChecked(): Boolean =
        (this as? org.w3c.dom.HTMLInputElement)?.checked ?: (querySelector("input") as? org.w3c.dom.HTMLInputElement)?.checked ?: false

    @Test
    fun theMappingTab_isReadOnlyForAChapterTreasurer_andSaysWho(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "KONTENZUORDNUNG") }
            AppState.setSession(
                PartyReportDomSupport.session(
                    role = AccountRole.MEMBER,
                    chapterTreasurerScopes = listOf(RegionalChapterRefDto("0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90", "Nord")),
                ),
            )
            PartyReportDomSupport.mappingView = view(editable = false, rows = listOf(mappingRow("1", code = "E01"), mappingRow("2")))
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-mapping-readonly") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the mapping block") { element().allOf("[data-partg-unmapped]").isNotEmpty() }
                    val page = element()
                    assertTrue(page.shows("Die Zuordnung der Konten pflegt der Vorstand."))
                    assertEquals(0, page.allOf("tbody select").size, "no editing control")
                    assertFalse(page.buttons().contains("Zuordnung speichern") && page.allOf(".lapis-partg-savebar").any { it.isVisible() })
                    assertFalse(page.buttons().contains("Vorschläge anzeigen"))
                    // the tabs of a chapter treasurer: overview, entry, mapping and (V1.9.292, R4b) the unit part
                    val tabs = page.allOf(".btn-group button").map { it.textContent?.trim() }
                    assertEquals(listOf("Übersicht", "Werte erfassen", "Kontenzuordnung", "Bericht"), tabs)
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }

    private fun HTMLElement.buttons(): List<String> = allOf("button").map { it.textContent?.trim().orEmpty() }

    private fun HTMLElement.isVisible(): Boolean = getAttribute("style")?.contains("display: none") != true && offsetParent != null

    @Test
    fun theOverview_showsTheModeAsAnEditableSelect_forTheFederalRoles_andAsTextForAChapterTreasurer(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
            val sub =
                PartgUnitDto(
                    unitKey = "SUB:0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90",
                    level = PartgUnitLevel.SUBORDINATE,
                    chapterId = "0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90",
                    chapterName = "Nord",
                )
            PartyReportDomSupport.overviewOverride =
                listOf(
                    PartgOverviewRowDto(
                        PartyReportDomSupport.federal,
                        0,
                        35,
                        mode = PartgBookkeepingMode.A,
                        bookingCount = 2,
                        modeEditable = true,
                    ),
                    PartgOverviewRowDto(sub, 0, 35),
                )
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-modes") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview rows") { element().allOf("tbody tr").size == 2 }
                    val rows = element().allOf("tbody tr")
                    assertEquals(1, rows[0].allOf("select").size, "the federal roles can change the mode")
                    assertEquals(
                        "A",
                        (
                            rows[0]
                                .allOf("select")
                                .single()
                                .asDynamic()
                                .value as String
                        ),
                    )
                    assertEquals(0, rows[1].allOf("select").size, "a sub-unit summary is always C")
                    assertTrue(rows[1].shows("C – Eigene Kasse außerhalb"))
                    assertTrue(rows[1].shows("immer außerhalb von Lapis"))
                }
            }
            // the same data for a chapter treasurer: text only, no edit control
            PartyReportDomSupport.overviewOverride =
                listOf(PartgOverviewRowDto(PartyReportDomSupport.state, 0, 35, mode = PartgBookkeepingMode.B, bookingCount = 2))
            AppState.setSession(
                PartyReportDomSupport.session(
                    role = AccountRole.MEMBER,
                    chapterTreasurerScopes = listOf(RegionalChapterRefDto("0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90", "Nord")),
                ),
            )
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-modes-readonly") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview row") { element().allOf("tbody tr").size == 1 }
                    val row = element().allOf("tbody tr").single()
                    assertEquals(0, row.allOf("select").size)
                    assertTrue(row.shows("B – Eigene Kasse in Lapis"))
                    assertFalse(element().buttons().contains("Bericht erzeugen"), "the report export is federal")
                }
            }
        }

    @Test
    fun theProposalDialog_preselectsOnlyUntouchedRows_blocksANegativeRow_andCountsTheTicks(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            AppState.setSession(PartyReportDomSupport.session())
            mountedForm("party-report-proposal") { root, _ ->
                root.div()
                openPartgLedgerProposalDialog(
                    proposal =
                        PartgLedgerProposalDto(
                            year = 2025,
                            unitKey = "FEDERAL",
                            rows =
                                listOf(
                                    PartgLedgerProposalRowDto("E01", null, null, 10_000, preselected = true, transferable = true),
                                    PartgLedgerProposalRowDto(
                                        "E02",
                                        5_000,
                                        PartgValueSource.MANUAL,
                                        10_000,
                                        preselected = false,
                                        transferable = true,
                                    ),
                                    PartgLedgerProposalRowDto("A01", null, null, -500, preselected = false, transferable = false),
                                ),
                            revision = 0,
                            priorRevision = 0,
                            mode = PartgBookkeepingMode.A,
                        ),
                    onApplied = {},
                    onReload = {},
                )
                val modal = lastOpenModal()
                assertTrue(modal.shows("Vorschlag aus Buchungen: 2025"))
                val boxes = modal.allOf("input[type=checkbox]")
                assertEquals(2, boxes.size, "the negative row has no checkbox")
                assertEquals(listOf(true, false), boxes.map { it.asDynamic().checked as Boolean })
                assertEquals(1, modal.allOf("[data-partg-negative]").size)
                assertTrue(modal.shows("Negativ, nicht übertragbar. Bitte Zuordnung oder Buchungen prüfen."))
                assertTrue(modal.buttons().contains("1 Werte übernehmen"))
                boxes[1].click()
                awaitUntil("the new count", detail = {
                    modal.buttons().toString() +
                        modal.allOf("input[type=checkbox]").map { it.asDynamic().checked }
                }) {
                    modal.buttons().contains("2 Werte übernehmen")
                }
            }
        }
}
