package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.283 (R3b) -- the jump bar, the blocks C, D and F of the tab "Ausweise". */
class PartyReportStatementsDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun finding(
        code: PartgFindingCode,
        rows: List<Int> = listOf(1),
        level: PartgFindingLevel = PartgFindingLevel.CHECK,
    ) = PartgFindingDto(code, level, "FEDERAL", rows, target = PartgFindingTarget.ANNEX)

    private suspend fun withAnnexTab(
        id: String,
        prepare: () -> Unit = {},
        block: suspend (() -> HTMLElement, List<RecordedRequest>) -> Unit,
    ) {
        runCatching { window.localStorage.setItem("lapis.partyReport.tab", "AUSWEISE") }
        AppState.setSession(PartyReportDomSupport.session())
        PartyReportDomSupport.resetR3()
        prepare()
        val routes = PartyReportDomSupport.routes()
        try {
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) { requests ->
                mountedForm(id) { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the jump bar") { element().allOf("nav.partg-annex-nav").isNotEmpty() }
                    block(element, requests)
                }
            }
        } finally {
            // live regions, dialogs and sticky bars of a test must not leak into the next one
            document.querySelectorAll(".modal, .modal-backdrop").let { list ->
                for (i in 0 until list.length) list.item(i)?.let { (it as? HTMLElement)?.remove() }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }
    }

    private fun List<RecordedRequest>.countOf(route: String) = count { it.isRpc && it.rpcRoute == route }

    private fun HTMLElement.regionOf(anchor: String): HTMLElement = allOf("#$anchor-region").single()

    private fun HTMLElement.isOpen(anchor: String): Boolean = allOf("#$anchor-toggle").single().getAttribute("aria-expanded") == "true"

    @Test
    fun theJumpBar_listsAllBlocks_withCountsAsText_andOpensA_byDefault(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-nav",
                prepare = {
                    PartyReportDomSupport.findings =
                        listOf(
                            finding(PartgFindingCode.P07),
                            finding(PartgFindingCode.P08, rows = listOf(2)),
                            finding(PartgFindingCode.P10),
                        )
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            sponsors = listOf(PartyReportDomSupport.sponsor("s1", 1, "Fixture Sponsor", listOf(80_000L))),
                        )
                },
            ) { element, _ ->
                val nav = element().allOf("nav.partg-annex-nav").single()
                assertEquals("Ausweise", nav.getAttribute("aria-label"))
                val labels = nav.allOf("button").map { it.textContent.orEmpty().trim() }
                assertEquals(listOf("A", "Mitglieder", "B", "C (2)", "D", "F (1)"), labels)
                assertEquals("C, 2 Hinweise", nav.allOf("button")[3].getAttribute("aria-label"))
                // C has the most hints: only C is open
                assertTrue(element().isOpen("partg-block-c"))
                assertFalse(element().isOpen("partg-block-a"))
                assertFalse(element().isOpen("partg-block-f"))
                assertEquals("partg-block-c-region", element().allOf("#partg-block-c-toggle").single().getAttribute("aria-controls"))
            }
        }

    @Test
    fun withoutHints_blockAIsOpen(): Promise<Unit> =
        formTest {
            withAnnexTab("stmt-default") { element, _ ->
                assertTrue(element().isOpen("partg-block-a"))
                assertFalse(element().isOpen("partg-block-b"))
                assertFalse(element().isOpen("partg-block-c"))
            }
        }

    @Test
    fun clickingAJumpButton_opensItsBlock(): Promise<Unit> =
        formTest {
            withAnnexTab("stmt-jump") { element, _ ->
                element().allOf("nav.partg-annex-nav button").first { it.textContent.orEmpty().trim() == "F" }.click()
                awaitUntil("F is open") { element().isOpen("partg-block-f") }
            }
        }

    @Test
    fun theSponsorList_showsTheStatusAsSymbolAndWords(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-list",
                prepare = {
                    PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P09, rows = listOf(2)))
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            sponsors =
                                listOf(
                                    PartyReportDomSupport.sponsor("s1", 1, "Fixture Grosssponsor", listOf(80_000L)),
                                    PartyReportDomSupport.sponsor("s2", 2, "Fixture Kleinsponsor", listOf(10_000L)),
                                ),
                        )
                },
            ) { element, _ ->
                val rows = element().regionOf("partg-block-c").allOf("tbody tr")
                assertEquals(2, rows.size)
                assertTrue(rows[0].shows("Fixture Grosssponsor") && rows[0].shows("Im Bericht: Einzelleistung über 750 €"))
                assertTrue(rows[1].shows("Nicht im Bericht: unter beiden Grenzen") && rows[1].shows("Sponsor unter beiden Grenzen"))
                assertTrue(rows[0].allOf("[aria-hidden=true]").isNotEmpty(), "the symbol is decoration")
            }
        }

    @Test
    fun theStatusLine_followsTheTypedAmounts_afterTheDebounce(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-status",
                prepare = { PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P07)) },
            ) { element, _ ->
                element().buttonNamed("Sponsor hinzufügen").click()
                awaitUntil("the form") { element().shows("Neuer Sponsor") }
                val live = element().allOf("[data-partg-status]").single()
                awaitUntil("the empty state") { live.shows("Nicht im Bericht: unter beiden Grenzen") }
                element().typeInto("Betrag (€)", "750,01")
                awaitUntil("single") { live.shows("Im Bericht: Einzelleistung über 750 €") }
                element().typeInto("Betrag (€)", "750,00")
                awaitUntil("under both") { live.shows("Nicht im Bericht: unter beiden Grenzen") }
                assertTrue(element().shows("Jahressumme 750,00"))
            }
        }

    @Test
    fun aValidSponsor_isSent_withItsServices(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-save",
                prepare = { PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P07)) },
            ) { element, requests ->
                val routes = PartyReportDomSupport.routes()
                element().buttonNamed("Sponsor hinzufügen").click()
                awaitUntil("the form") { element().shows("Neuer Sponsor") }
                element().typeInto("Name", "Neue Sponsorfirma")
                element().typeInto("Datum", "2025-03-01")
                element().typeInto("Betrag (€)", "1.000,00")
                element().typeInto("Zweck", "Sommerfest")
                (element().controlOf("Name").closest(".lapis-form") as HTMLElement).buttonNamed("Speichern").click()
                awaitUntil("the call") { requests.countOf(routes.saveSponsor) == 1 }
                val sponsor = requests.single { it.isRpc && it.rpcRoute == routes.saveSponsor }.rpcParam(3)
                assertEquals("Neue Sponsorfirma", sponsor.name as String)
                assertEquals(1, (sponsor.entries as Array<*>).size)
                assertEquals(100_000, sponsor.entries[0].amountCents as Int)
                assertEquals("Sommerfest", sponsor.entries[0].purpose as String)
            }
        }

    @Test
    fun aDateOutsideTheYear_isRefusedWithTheFixedText_andNoCallIsMade(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-date",
                prepare = { PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P07)) },
            ) { element, requests ->
                val routes = PartyReportDomSupport.routes()
                element().buttonNamed("Sponsor hinzufügen").click()
                awaitUntil("the form") { element().shows("Neuer Sponsor") }
                element().typeInto("Name", "Falsches Jahr")
                element().typeInto("Datum", "2024-12-31")
                element().typeInto("Betrag (€)", "10,00")
                (element().controlOf("Name").closest(".lapis-form") as HTMLElement).buttonNamed("Speichern").click()
                awaitUntil("the refusal") { element().shows("Das Datum muss im Berichtsjahr liegen.") }
                assertEquals(0, requests.countOf(routes.saveSponsor))
            }
        }

    @Test
    fun onlyOneFormIsOpenInTheWholeTab_theSecondAsksFirst(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-guard",
                prepare = {
                    PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P07))
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            sponsors = listOf(PartyReportDomSupport.sponsor("s1", 1, "Fixture Sponsor", listOf(80_000L))),
                        )
                },
            ) { element, _ ->
                element().buttonNamed("Sponsor hinzufügen").click()
                awaitUntil("the sponsor form") { element().shows("Neuer Sponsor") }
                // open the youth form (block F) while the sponsor form is open
                element().allOf("nav.partg-annex-nav button").first { it.textContent.orEmpty().trim() == "F" }.click()
                awaitUntil("F open") { element().isOpen("partg-block-f") }
                element().buttonNamed("Jugendorganisation hinzufügen").click()
                awaitUntil("the question") {
                    document.body
                        ?.textContent
                        .orEmpty()
                        .contains("Eingaben verwerfen?")
                }
                (document.querySelectorAll(".modal-footer button").item(1) as HTMLElement).click()
                awaitUntil("the youth form") { element().shows("Neue Jugendorganisation") }
                assertFalse(element().shows("Neuer Sponsor"), "the sponsor form is gone")
            }
        }

    @Test
    fun theYouthResult_isShown_orNotCalculable(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-youth",
                prepare = {
                    PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P10, rows = listOf(2)))
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            youthOrgs =
                                listOf(
                                    PartyReportDomSupport.youth("y1", 1, "Fixture Jugend Eins", 500_000L, 400_000L),
                                    PartyReportDomSupport.youth("y2", 2, "Fixture Jugend Zwei"),
                                ),
                        )
                },
            ) { element, _ ->
                val rows = element().regionOf("partg-block-f").allOf("tbody tr")
                assertTrue(rows[0].shows("1.000,00"), "income minus expenses")
                assertTrue(rows[1].shows("nicht berechenbar") && rows[1].shows("Jugendorganisation ohne Zahlen"))
            }
        }

    @Test
    fun aYouthOrganisation_isSent(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-youth-save",
                prepare = { PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P10)) },
            ) { element, requests ->
                val routes = PartyReportDomSupport.routes()
                element().buttonNamed("Jugendorganisation hinzufügen").click()
                awaitUntil("the form") { element().shows("Neue Jugendorganisation") }
                element().typeInto("Name", "Junge Neue")
                element().typeInto("Einnahmen (€)", "12,50")
                (element().controlOf("Name").closest(".lapis-form") as HTMLElement).buttonNamed("Speichern").click()
                awaitUntil("the call") { requests.countOf(routes.saveYouthOrg) == 1 }
                val youth = requests.single { it.isRpc && it.rpcRoute == routes.saveYouthOrg }.rpcParam(3)
                assertEquals("Junge Neue", youth.name as String)
                assertEquals(1250, youth.incomeCents as Int)
                assertTrue(youth.expensesCents == null)
            }
        }

    @Test
    fun afterASave_theBlockOfTheUserStaysOpen_notTheOneWithTheMostHints(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "stmt-stay-open",
                // the hint sits in A, so A is open on load
                prepare = { PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P03, rows = emptyList())) },
            ) { element, requests ->
                val routes = PartyReportDomSupport.routes()
                assertTrue(element().isOpen("partg-block-a"))
                element().allOf("nav.partg-annex-nav button").first { it.textContent.orEmpty().trim() == "F" }.click()
                awaitUntil("F open") { element().isOpen("partg-block-f") }
                val annexCalls = requests.countOf(routes.getAnnex)
                element().buttonNamed("Jugendorganisation hinzufügen").click()
                awaitUntil("the form") { element().shows("Neue Jugendorganisation") }
                element().typeInto("Name", "Junge Bleibende")
                (element().controlOf("Name").closest(".lapis-form") as HTMLElement).buttonNamed("Speichern").click()
                awaitUntil("the call") { requests.countOf(routes.saveYouthOrg) == 1 }
                awaitUntil("the reload") { requests.countOf(routes.getAnnex) > annexCalls }
                awaitUntil("the tab is rendered again") { element().allOf("nav.partg-annex-nav").isNotEmpty() }
                assertTrue(element().isOpen("partg-block-f"), "the block that was worked in stays open")
                assertFalse(element().isOpen("partg-block-a"))
            }
        }

    @Test
    fun statementD_isAPlaceholder_withoutAnyInput(): Promise<Unit> =
        formTest {
            withAnnexTab("stmt-d") { element, _ ->
                val region = element().regionOf("partg-block-d")
                assertTrue(region.shows("Ausweis D (Unternehmenstätigkeit) ist noch nicht erfasst. Folgt mit R3c."))
                assertEquals(0, region.allOf("input, textarea, select, form").size, "not editable")
                assertNotNull(region.allOf("button").firstOrNull { it.shows("Zu den Erläuterungen") })
                assertTrue((element().allOf("#partg-block-d-toggle").single() as HTMLElement).shows("D: Unternehmenstätigkeit (Entwurf)"))
                assertTrue(element().shows("C: Sponsoring (Entwurf)") && element().shows("F: Jugendorganisationen (Entwurf)"))
            }
        }

    @Test
    fun aHintLink_opensTheBlockOfTheHint(): Promise<Unit> =
        formTest {
            val inputs = emptyList<HTMLInputElement>()
            assertTrue(inputs.isEmpty())
            val context = PartyReportContext(2025)
            var opened: PartyReportTab? = null
            context.openTab = { opened = it }
            context.openFinding(finding(PartgFindingCode.P10), null)
            assertEquals(AnnexBlock.F, context.annexFocusBlock)
            assertEquals(PartyReportTab.AUSWEISE, opened)
            context.openFinding(finding(PartgFindingCode.P07), null)
            assertEquals(AnnexBlock.C, context.annexFocusBlock)
        }
}
