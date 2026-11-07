package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.PartgEnterpriseCheckDto
import network.lapis.cloud.shared.domain.PartgEnterpriseCheckStatus
import network.lapis.cloud.shared.domain.PartgEnterpriseRules
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.287 (R3c) -- the block D "Unternehmenstätigkeit" of the tab "Ausweise". */
class PartyReportEnterpriseDomTest {
    @AfterTest
    fun cleanUp() {
        // dialogs, backdrops and the stored tab of a test must not leak into the next one
        val leftovers = document.querySelectorAll(".modal, .modal-backdrop, .toast-container")
        for (i in 0 until leftovers.length) {
            (leftovers.item(i) as? HTMLElement)?.remove()
        }
        runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        PartyReportDomSupport.resetR3()
    }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun List<RecordedRequest>.countOf(route: String) = count { it.isRpc && it.rpcRoute == route }

    private fun HTMLElement.regionOf(anchor: String): HTMLElement = allOf("#$anchor-region").single()

    private fun finding(
        code: PartgFindingCode,
        rows: List<Int> = emptyList(),
        position: String? = null,
        level: PartgFindingLevel = PartgFindingLevel.CHECK,
    ) = PartgFindingDto(code, level, "FEDERAL", rows, positionCode = position, target = PartgFindingTarget.ANNEX)

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
        withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) { requests ->
            mountedForm(id) { root, element ->
                renderPartyReportScreen(root)
                awaitUntil("the jump bar") { element().allOf("nav.partg-annex-nav").isNotEmpty() }
                block(element, requests)
            }
        }
    }

    private suspend fun openD(element: () -> HTMLElement) {
        element()
            .allOf("nav.partg-annex-nav button")
            .first {
                it.textContent
                    .orEmpty()
                    .trim()
                    .startsWith("D")
            }.click()
        awaitUntil("D is open") { element().allOf("#partg-block-d-toggle").single().getAttribute("aria-expanded") == "true" }
    }

    @Test
    fun theShareParser_readsAPercentValueAsHundredths_neverAsABarePercent() {
        assertEquals(2500, partgBasisPoints("25"))
        assertEquals(2550, partgBasisPoints("25,5"))
        assertEquals(2505, partgBasisPoints("25,05"))
        assertEquals(0, partgBasisPoints("0"))
        assertEquals(10_000, partgBasisPoints("100"))
        assertNull(partgBasisPoints("100,01"))
        assertNull(partgBasisPoints("-1"))
        assertNull(partgBasisPoints("abc"))
        assertNull(partgBasisPoints(""))
        assertEquals("25,00 %", partgShareText(2500))
    }

    @Test
    fun theCheckTexts_nameThePositionAndTheStatus_inWords() {
        fun text(status: PartgEnterpriseCheckStatus) = partgEnterpriseCheckText(PartgEnterpriseCheckDto("E05", status))
        assertEquals("E05: stimmt überein", text(PartgEnterpriseCheckStatus.MATCHES))
        assertEquals("E05: weicht ab (mehr als 1,00 EUR)", text(PartgEnterpriseCheckStatus.DIFFERS))
        assertEquals("E05: weicht ab (Position im Bericht nicht erfasst)", text(PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED))
        assertEquals("E05: nicht vergleichbar, Angaben fehlen", text(PartgEnterpriseCheckStatus.NOT_COMPARABLE))
    }

    @Test
    fun theHintsOfD_belongToTheBlockD() {
        listOf(PartgFindingCode.P11, PartgFindingCode.P12, PartgFindingCode.P13).forEach {
            assertEquals(AnnexBlock.D, partgFindingBlock(finding(it)))
        }
        val counts =
            partgBlockCounts(
                listOf(finding(PartgFindingCode.P12, rows = listOf(1)), finding(PartgFindingCode.P13, position = "E05")),
                "FEDERAL",
            )
        assertEquals(2, counts[AnnexBlock.D])
    }

    @Test
    fun theJumpBar_countsTheHintsOfD_asText_andOpensD(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "ent-nav",
                prepare = {
                    PartyReportDomSupport.findings =
                        listOf(finding(PartgFindingCode.P12, rows = listOf(1)), finding(PartgFindingCode.P13, position = "E05"))
                },
            ) { element, _ ->
                val labels = element().allOf("nav.partg-annex-nav button").map { it.textContent.orEmpty().trim() }
                assertTrue("D (2)" in labels, "the count is part of the button text: $labels")
                // D has the most hints: it is open on load, with the disclosure attributes
                val toggle = element().allOf("#partg-block-d-toggle").single()
                assertEquals("true", toggle.getAttribute("aria-expanded"))
                assertEquals("partg-block-d-region", toggle.getAttribute("aria-controls"))
            }
        }

    @Test
    fun theEmptyState_andTheHelpText_areShown_withoutInputs(): Promise<Unit> =
        formTest {
            withAnnexTab("ent-empty") { element, _ ->
                openD(element)
                val region = element().regionOf("partg-block-d")
                assertTrue(region.shows("Keine Unternehmen erfasst."))
                assertTrue(region.shows("Bitte keine Namen von Personen eintragen."))
                assertEquals(0, region.allOf("input, textarea, select").size, "no form is open yet")
                assertEquals(0, region.allOf("[data-partg-enterprise-check]").size, "no adjustment without a row")
            }
        }

    @Test
    fun theList_showsShareFigures_theResult_andTheSum(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "ent-list",
                prepare = {
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            enterprises =
                                listOf(
                                    PartyReportDomSupport.enterprise("e1", 1, "Fixture Verlag", 100_000L, 40_000L, 2500),
                                    PartyReportDomSupport.enterprise("e2", 2, "Fixture Druck", 5_000L, 7_000L),
                                    PartyReportDomSupport.enterprise("e3", 3, "Fixture Ohne Zahlen"),
                                ),
                        )
                    PartyReportDomSupport.findings = listOf(finding(PartgFindingCode.P12, rows = listOf(3)))
                },
            ) { element, _ ->
                val region = element().regionOf("partg-block-d")
                val rows = region.allOf("tbody tr")
                assertEquals(3, rows.size)
                assertTrue(rows[0].shows("Fixture Verlag") && rows[0].shows("25,00 %") && rows[0].shows("600,00"))
                assertTrue(
                    Regex("[-\u2212]\\s?20,00").containsMatchIn(rows[1].textContent.orEmpty()),
                    "a negative result stays negative: ${rows[1].textContent}",
                )
                assertTrue(rows[2].shows("nicht berechenbar") && rows[2].shows("Unternehmen ohne vollständige Zahlen"))
                val total = region.allOf("[data-partg-enterprise-total]").single()
                assertTrue(total.shows("nicht berechenbar"), "a missing figure makes the sum not computable")
                assertTrue(rows[0].allOf("button").all { (it.getAttribute("aria-label") ?: it.textContent).orEmpty().isNotBlank() })
            }
        }

    @Test
    fun theSum_isShown_whenEveryRowHasTheFigures(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "ent-sum",
                prepare = {
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            enterprises =
                                listOf(
                                    PartyReportDomSupport.enterprise("e1", 1, "Eins", 100_000L, 40_000L),
                                    PartyReportDomSupport.enterprise("e2", 2, "Zwei", 5_000L, 7_000L),
                                ),
                        )
                },
            ) { element, _ ->
                val total = element().regionOf("partg-block-d").allOf("[data-partg-enterprise-total]").single()
                assertTrue(total.shows("Einnahmen 1.050,00") && total.shows("Ausgaben 470,00") && total.shows("Ergebnis 580,00"))
            }
        }

    @Test
    fun theAdjustmentLines_showSymbolAndWord_forEveryStatus_andNoAmount(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "ent-checks",
                prepare = {
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            enterprises = listOf(PartyReportDomSupport.enterprise("e1", 1, "Eins", 123_456L, 99_999L)),
                            enterpriseChecks =
                                listOf(
                                    PartgEnterpriseCheckDto("E05", PartgEnterpriseCheckStatus.DIFFERS),
                                    PartgEnterpriseCheckDto("A02F", PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED),
                                ),
                        )
                },
            ) { element, _ ->
                val lines = element().regionOf("partg-block-d").allOf("[data-partg-enterprise-check]")
                assertEquals(2, lines.size)
                assertTrue(lines[0].shows("E05: weicht ab (mehr als 1,00 EUR)"))
                assertTrue(lines[1].shows("A02F: weicht ab (Position im Bericht nicht erfasst)"))
                lines.forEach { line ->
                    val symbol = line.allOf("[aria-hidden=true]").single()
                    assertEquals("≠", symbol.textContent)
                    assertFalse(
                        Regex("""\d+[.,]\d{2}""").containsMatchIn(line.textContent.orEmpty().replace("1,00 EUR", "")),
                        "no amount in the line",
                    )
                }
            }
        }

    @Test
    fun aNewEnterprise_isSent_withBlankFieldsAsNull_andTheShareInHundredths(): Promise<Unit> =
        formTest {
            withAnnexTab("ent-save") { element, requests ->
                val routes = PartyReportDomSupport.routes()
                openD(element)
                element().buttonNamed("Unternehmen hinzufügen").click()
                awaitUntil("the form") { element().shows("Neues Unternehmen") }
                element().typeInto("Bezeichnung", "Neuer Betrieb")
                element().typeInto("Anteil der Partei in % (optional)", "25")
                element().typeInto("Einnahmen (€)", "12,50")
                (element().controlOf("Bezeichnung").closest(".lapis-form") as HTMLElement).buttonNamed("Speichern").click()
                awaitUntil("the call") { requests.countOf(routes.saveEnterprise) == 1 }
                val enterprise = requests.single { it.isRpc && it.rpcRoute == routes.saveEnterprise }.rpcParam(3)
                assertEquals("Neuer Betrieb", enterprise.name as String)
                assertEquals(2500, enterprise.shareBasisPoints as Int)
                assertEquals(1250, enterprise.incomeCents as Int)
                assertTrue(enterprise.expensesCents == null)
                assertTrue(enterprise.legalForm == null && enterprise.seat == null)
            }
        }

    @Test
    fun cancelling_closesTheForm_andFocusReturnsToTheAddButton(): Promise<Unit> =
        formTest {
            withAnnexTab("ent-cancel") { element, requests ->
                val routes = PartyReportDomSupport.routes()
                openD(element)
                element().buttonNamed("Unternehmen hinzufügen").click()
                awaitUntil("the form") { element().shows("Neues Unternehmen") }
                element().buttonNamed("Abbrechen").click()
                awaitUntil("the form is gone") { !element().shows("Neues Unternehmen") }
                assertEquals(0, requests.countOf(routes.saveEnterprise))
                awaitUntil("the focus") { document.activeElement?.id == "partg-enterprise-add" }
            }
        }

    @Test
    fun aShareAbove100_isRefusedWithTheFixedText_andNoCallIsMade(): Promise<Unit> =
        formTest {
            withAnnexTab("ent-share") { element, requests ->
                val routes = PartyReportDomSupport.routes()
                openD(element)
                element().buttonNamed("Unternehmen hinzufügen").click()
                awaitUntil("the form") { element().shows("Neues Unternehmen") }
                element().typeInto("Bezeichnung", "Zu viel")
                element().typeInto("Anteil der Partei in % (optional)", "100,01")
                (element().controlOf("Bezeichnung").closest(".lapis-form") as HTMLElement).buttonNamed("Speichern").click()
                awaitUntil("the refusal") { element().shows("Bitte einen Anteil zwischen 0 und 100 eingeben.") }
                assertEquals(0, requests.countOf(routes.saveEnterprise))
            }
        }

    @Test
    fun onlyOneFormIsOpen_theSecondAsksFirst(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "ent-guard",
                prepare = {
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            enterprises = listOf(PartyReportDomSupport.enterprise("e1", 1, "Eins", 1L, 1L)),
                        )
                },
            ) { element, _ ->
                openD(element)
                element().buttonNamed("Unternehmen hinzufügen").click()
                awaitUntil("the add form") { element().shows("Neues Unternehmen") }
                element()
                    .regionOf("partg-block-d")
                    .allOf("button")
                    .first { it.id.startsWith("partg-enterprise-edit-") }
                    .click()
                awaitUntil("the question") {
                    document.body
                        ?.textContent
                        .orEmpty()
                        .contains("Eingaben verwerfen?")
                }
                (document.querySelectorAll(".modal-footer button").item(1) as HTMLElement).click()
                awaitUntil("the edit form") { element().shows("Zeile 1 bearbeiten") }
                assertFalse(element().shows("Neues Unternehmen"))
            }
        }

    @Test
    fun deleting_asksForConfirmation_andSendsTheId(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "ent-delete",
                prepare = {
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            enterprises = listOf(PartyReportDomSupport.enterprise("e1", 1, "Eins", 1L, 1L)),
                        )
                },
            ) { element, requests ->
                val routes = PartyReportDomSupport.routes()
                openD(element)
                element()
                    .regionOf("partg-block-d")
                    .allOf("button")
                    .first { it.id == "partg-enterprise-delete-e1" }
                    .click()
                awaitUntil("the dialog") {
                    document.body
                        ?.textContent
                        .orEmpty()
                        .contains("Zeile löschen?")
                }
                assertEquals(0, requests.countOf(routes.deleteEnterprise), "nothing is sent before the confirmation")
                (document.querySelectorAll(".modal-footer button").item(1) as HTMLElement).click()
                awaitUntil("the call") { requests.countOf(routes.deleteEnterprise) == 1 }
                assertEquals("e1", requests.single { it.isRpc && it.rpcRoute == routes.deleteEnterprise }.rpcParam(3) as String)
            }
        }

    @Test
    fun atTheCap_theAddButtonIsDisabled_andTheReasonIsText(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "ent-cap",
                prepare = {
                    PartyReportDomSupport.annex =
                        PartyReportDomSupport.annexDto().copy(
                            enterprises =
                                (1..PartgEnterpriseRules.MAX_ENTERPRISES_PER_UNIT).map {
                                    PartyReportDomSupport.enterprise("e$it", it, "Eintrag $it", 1L, 1L)
                                },
                        )
                },
            ) { element, _ ->
                openD(element)
                val add = element().allOf("#partg-enterprise-add").single()
                assertNotNull(add.getAttribute("disabled"))
                assertTrue(element().regionOf("partg-block-d").shows("höchstens 50 Unternehmen"))
            }
        }

    @Test
    fun theNotesButton_switchesToTheNotesTab(): Promise<Unit> =
        formTest {
            withAnnexTab("ent-notes") { element, _ ->
                openD(element)
                element().regionOf("partg-block-d").buttonNamed("Zu den Erläuterungen").click()
                awaitUntil("the notes tab") { element().shows("Erläuterungen") && element().allOf("nav.partg-annex-nav").isEmpty() }
            }
        }
}
