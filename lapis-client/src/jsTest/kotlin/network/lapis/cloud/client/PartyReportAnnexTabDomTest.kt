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
import kotlin.test.assertTrue

/** V1.9.269 -- the tab "Ausweise": labelled fields, the threshold, one open row, focus, the member count suggestion, the discard guard. */
class PartyReportAnnexTabDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private suspend fun withAnnexTab(
        id: String,
        prepare: () -> Unit = {},
        block: suspend (HTMLElement, () -> HTMLElement, List<RecordedRequest>) -> Unit,
    ) {
        runCatching { window.localStorage.setItem("lapis.partyReport.tab", "AUSWEISE") }
        AppState.setSession(PartyReportDomSupport.session())
        PartyReportDomSupport.resetR3()
        prepare()
        val routes = PartyReportDomSupport.routes()
        withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) { requests ->
            mountedForm(id) { root, element ->
                renderPartyReportScreen(root)
                awaitUntil("the donor table") { element().allOf("tbody tr").size == PartyReportDomSupport.annex.donors.size }
                block(root.getElement() as HTMLElement, element, requests)
            }
        }
        runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
    }

    /** The "Speichern" of the donor form (the page has a second one for the figures). */
    private fun HTMLElement.donorFormSave(): HTMLElement =
        (controlOf("Art der Person").closest(".lapis-form") as HTMLElement).buttonNamed("Speichern")

    private fun List<RecordedRequest>.countOf(route: String) = count { it.isRpc && it.rpcRoute == route }

    @Test
    fun everyFigureField_hasALabel_andTheHintSaysEmptyIsNotZero(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-labels") { _, element, _ ->
                val page = element()
                listOf(
                    "Nicht zuordenbare Spenden",
                    "Bar-Spenden über 1.000 €",
                    "Spenden über 3.300 € ohne Ausweis",
                    "Fälschlich gemeldete Spenden",
                    "Mitgliederzahl zum 31.12.",
                ).forEach { label ->
                    val control = page.controlOf(label)
                    assertTrue(control is HTMLInputElement, "'$label' is an input with a label")
                }
                assertTrue(page.shows("Leer bedeutet nicht erfasst, 0 ist ein Wert."))
                assertTrue(page.shows("Beträge an mehrere Gebietsverbände werden noch nicht zusammengeführt."))
                assertTrue(page.shows("Nur Vorstand und Admin sehen diese Liste."))
            }
        }

    @Test
    fun theDonorList_showsNameCityAmount_andASymbolWithWordsForTheHints(): Promise<Unit> =
        formTest {
            withAnnexTab(
                "annex-list",
                prepare = {
                    PartyReportDomSupport.findings =
                        listOf(
                            PartgFindingDto(
                                PartgFindingCode.P01,
                                PartgFindingLevel.CHECK,
                                "FEDERAL",
                                listOf(2),
                                target = PartgFindingTarget.ANNEX,
                            ),
                            PartgFindingDto(
                                PartgFindingCode.P04,
                                PartgFindingLevel.INFO,
                                "FEDERAL",
                                listOf(2),
                                target = PartgFindingTarget.ANNEX,
                            ),
                        )
                },
            ) { _, element, _ ->
                val rows = element().allOf("tbody tr")
                assertTrue(rows[0].shows("Fixture Person Eins") && rows[0].shows("Braunschweig") && rows[0].shows("12.000,00"))
                assertTrue(rows[1].shows("Anschrift fehlt") && rows[1].shows("Juristische Person"), "words, not only a colour")
                assertTrue(rows[1].allOf("[aria-hidden=true]").size >= 2, "the symbols are decoration")
                assertFalse(rows[0].shows("Anschrift fehlt"))
            }
        }

    @Test
    fun anAmountOfExactlyTenThousandEuro_isRefusedWithTheFixedText_andNoCallIsMade(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-threshold") { _, element, requests ->
                val routes = PartyReportDomSupport.routes()
                element().buttonNamed("Zeile hinzufügen").click()
                awaitUntil("the form") { element().allOf("label").any { it.shows("Art der Person") } }
                element().typeInto("Name", "Grenzfall Person")
                element().typeInto("Betrag (€)", "10.000,00")
                element().donorFormSave().click()
                awaitUntil("the refusal") { element().shows("In diese Liste gehören nur Personen mit mehr als 10.000 € im Jahr.") }
                assertEquals(0, requests.countOf(routes.saveDonor), "nothing was sent")
            }
        }

    @Test
    fun aValidRow_isSent_withTheTypedValues(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-save") { _, element, requests ->
                val routes = PartyReportDomSupport.routes()
                element().buttonNamed("Zeile hinzufügen").click()
                awaitUntil("the form") { element().allOf("label").any { it.shows("Art der Person") } }
                element().typeInto("Name", "Neue Person")
                element().typeInto("Betrag (€)", "10.000,01")
                element().donorFormSave().click()
                awaitUntil("the call") { requests.countOf(routes.saveDonor) == 1 }
                val call = requests.single { it.isRpc && it.rpcRoute == routes.saveDonor }
                assertEquals(1_000_001, call.rpcParam(3).amountCents as Int)
                assertEquals("Neue Person", call.rpcParam(3).name as String)
            }
        }

    @Test
    fun onlyOneRowIsOpen_andTheFirstFieldGetsTheFocus(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-one-open") { _, element, _ ->
                element().buttonNamed("Zeile hinzufügen").click()
                awaitUntil("the form") { element().allOf("label").any { it.shows("Art der Person") } }
                awaitUntil("the focus is in the first field") {
                    (document.activeElement as? HTMLElement)?.id ==
                        element().controlOf("Art der Person").id
                }
                // a second row while one is open: the discard question, then exactly one form with the other row
                element().allOf("tbody button[title=Bearbeiten]").first().click()
                awaitUntil("the question") {
                    document.body
                        ?.textContent
                        .orEmpty()
                        .contains("Eingaben verwerfen?")
                }
                (document.querySelectorAll(".modal-footer button").item(1) as HTMLElement).click()
                awaitUntil("the edit form") { element().shows("Zeile 1 bearbeiten") }
                assertEquals(1, element().allOf("label").count { it.shows("Art der Person") })
            }
        }

    @Test
    fun cancelOfANewRow_returnsTheFocusToTheAddButton(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-cancel-new") { _, element, _ ->
                element().buttonNamed("Zeile hinzufügen").click()
                awaitUntil("the form") { element().allOf("label").any { it.shows("Art der Person") } }
                element().buttonNamed("Abbrechen").click()
                awaitUntil("the focus", detail = { "active=${document.activeElement?.tagName}#${document.activeElement?.id}" }) {
                    (document.activeElement as? HTMLElement)?.id == "partg-donor-add"
                }
                assertEquals(0, element().allOf("label").count { it.shows("Art der Person") })
            }
        }

    @Test
    fun cancelOfAnEdit_returnsTheFocusToTheRowsEditButton(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-cancel-edit") { _, element, _ ->
                element().allOf("tbody button[title=Bearbeiten]")[1].click()
                awaitUntil("the form") { element().shows("Zeile 2 bearbeiten") }
                element().buttonNamed("Abbrechen").click()
                awaitUntil("the focus", detail = { "active=${document.activeElement?.tagName}#${document.activeElement?.id}" }) {
                    (document.activeElement as? HTMLElement)?.id == "partg-donor-edit-d2"
                }
            }
        }

    @Test
    fun theSuggestion_isShownWithItsDate_fillsTheFieldWithoutSaving(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-suggestion") { _, element, requests ->
                val routes = PartyReportDomSupport.routes()
                val box = element().allOf("[data-partg-suggestion]").single()
                assertTrue(box.shows("Heute im Mitgliederregister: 1234 (Stand 15.06.2025, nicht der 31.12.)"))
                box.allOf("button").first { it.shows("Vorschlag übernehmen") }.click()
                assertEquals("1234", (element().controlOf("Mitgliederzahl zum 31.12.") as HTMLInputElement).value)
                assertEquals(0, requests.countOf(routes.saveFigures), "taking the suggestion saves nothing")
            }
        }

    @Test
    fun withoutASuggestion_thereIsNoSuggestionBox(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-no-suggestion", prepare = {
                PartyReportDomSupport.annex = PartyReportDomSupport.annexDto(suggestion = false)
            }) { _, element, _ ->
                assertEquals(0, element().allOf("[data-partg-suggestion]").size)
            }
        }

    @Test
    fun changingTheTab_withAnOpenRow_asksBeforeDiscarding(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-guard") { _, element, _ ->
                element().buttonNamed("Zeile hinzufügen").click()
                awaitUntil("the form") { element().allOf("label").any { it.shows("Art der Person") } }
                element().allOf(".btn-group button").first { it.textContent?.trim() == "Bericht" }.click()
                awaitUntil("the question") {
                    document.body
                        ?.textContent
                        .orEmpty()
                        .contains("Eingaben verwerfen?")
                }
                // the tab did not change
                assertTrue(element().allOf("label").any { it.shows("Art der Person") })
            }
        }

    @Test
    fun keepEditingAfterASave_followsTheNewRevision_theTableAndTheUnsavedBaseline(): Promise<Unit> =
        formTest {
            withAnnexTab("annex-keep-editing") { _, element, requests ->
                val routes = PartyReportDomSupport.routes()
                val base = PartyReportDomSupport.annex
                // an unsaved figure makes the reload after the donor save ask first
                element().typeInto("Nicht zuordenbare Spenden", "5")
                element().buttonNamed("Zeile hinzufügen").click()
                awaitUntil("the form") { element().allOf("label").any { it.shows("Art der Person") } }
                element().typeInto("Name", "Neue Person")
                element().typeInto("Betrag (€)", "10.000,01")
                PartyReportDomSupport.annex =
                    base.copy(revision = 3, donors = base.donors + PartyReportDomSupport.donor("d3", 3, "Neue Person Drei"))
                element().donorFormSave().click()
                awaitUntil("the question") {
                    document.body
                        ?.textContent
                        .orEmpty()
                        .contains("Eingaben verwerfen?")
                }
                (document.querySelectorAll(".modal-footer button").item(0) as HTMLElement).click()
                awaitUntil("the new row in the table") { element().allOf("tbody tr").size == 3 }
                // the figures are saved with the revision the donor save returned
                PartyReportDomSupport.annex = PartyReportDomSupport.annex.copy(revision = 4)
                element().buttonNamed("Speichern").click()
                awaitUntil("the figures call") { requests.countOf(routes.saveFigures) == 1 }
                val call = requests.single { it.isRpc && it.rpcRoute == routes.saveFigures }
                assertEquals(3, call.rpcParam(2) as Int, "the revision of the donor save, not the stale one")
            }
        }
}
