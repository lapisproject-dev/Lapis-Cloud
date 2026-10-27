package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.225 -- entry of the values: placeholders, live sums, the save bar, "fill with 0" (report year only), the write and the unsaved-changes probe. */
class PartyReportSheetEditorDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.input(
        column: String,
        code: String,
    ): HTMLInputElement = document.getElementById("partg-$column-$code") as HTMLInputElement

    private fun type(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input", EventInit(bubbles = true)))
    }

    private suspend fun openEditor(
        routes: PartyReportDomSupport.Routes,
        element: () -> HTMLElement,
        root: io.kvision.panel.SimplePanel,
    ) {
        renderPartyReportScreen(root)
        awaitUntil("the overview") { element().allOf("tbody tr").size == 2 }
        element().buttonNamed("Werte erfassen").click()
        awaitUntil("the entry table") { element().allOf("input[id^='partg-current-']").size == 35 }
    }

    @Test
    fun theTable_hasOneEmptyFieldPerTypedInPosition_inBothYears_andShowsTheHint(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "SHEET") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-editor") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the entry table") { element().allOf("input[id^='partg-current-']").size == 35 }
                    assertEquals(35, element().allOf("input[id^='partg-prior-']").size)
                    assertTrue(element().allOf("input[id^='partg-current-']").all { (it as HTMLInputElement).value.isEmpty() })
                    assertTrue(element().input("current", "E01").getAttribute("placeholder") == "nicht erfasst")
                    assertTrue(element().shows("Nur Pflichtbeiträge"), "the organisation's hint is shown")
                    assertTrue(element().shows("Vorjahr 2024") && element().shows("Rechnungsjahr 2025"))
                    assertTrue(element().shows("0 Änderungen"))
                    assertTrue(element().buttonNamed("Speichern").hasAttribute("disabled"))
                }
            }
        }

    @Test
    fun typing_updatesTheLiveSumsAndTheSaveBar_andFlagsAnInvalidCell(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "SHEET") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-editor-sums") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the entry table") { element().allOf("input[id^='partg-current-']").size == 35 }
                    type(element().input("current", "E01"), "1.000,00")
                    type(element().input("current", "E03"), "500")
                    awaitUntil("the live sum") { element().shows("1.500,00") }
                    assertTrue(element().shows("2 Änderungen"))
                    assertFalse(element().buttonNamed("Speichern").hasAttribute("disabled"))
                    // an invalid cell is marked and blocks saving
                    type(element().input("current", "A01"), "abc")
                    awaitUntil("the field error") { element().input("current", "A01").getAttribute("aria-invalid") == "true" }
                    assertTrue(element().buttonNamed("Speichern").hasAttribute("disabled"))
                }
            }
        }

    @Test
    fun fillWithZero_asksFirst_andFillsOnlyTheReportYear(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "SHEET") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-editor-zero") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the entry table") { element().allOf("input[id^='partg-current-']").size == 35 }
                    element().buttonNamed("Leere Felder im Rechnungsjahr mit 0 füllen").click()
                    awaitUntil("the confirmation") { lastOpenModal().shows("Mit 0 füllen") }
                    assertTrue(
                        element().allOf("input[id^='partg-current-']").all { (it as HTMLInputElement).value.isEmpty() },
                        "nothing before the answer",
                    )
                    lastOpenModal().buttonNamed("Mit 0 füllen").click()
                    awaitUntil("the report year is filled") { element().input("current", "E01").value == "0,00" }
                    assertTrue(element().allOf("input[id^='partg-current-']").all { (it as HTMLInputElement).value == "0,00" })
                    assertTrue(
                        element().allOf("input[id^='partg-prior-']").all { (it as HTMLInputElement).value.isEmpty() },
                        "the prior year is untouched",
                    )
                    assertTrue(element().shows("35 Änderungen"))
                }
            }
        }

    @Test
    fun saving_sendsTheChangesOfBothYears_andTheUnsavedProbeFollowsTheDraft(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "SHEET") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) { calls ->
                mountedForm("party-report-editor-save") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the entry table") { element().allOf("input[id^='partg-current-']").size == 35 }
                    type(element().input("current", "E01"), "10,50")
                    type(element().input("prior", "E01"), "9")
                    element().buttonNamed("Speichern").click()
                    awaitUntil("the save call") { calls.any { it.isRpc && it.rpcRoute == routes.save } }
                    val call = calls.first { it.isRpc && it.rpcRoute == routes.save }
                    assertEquals(2025, call.rpcParam(0) as Int)
                    assertEquals("FEDERAL", call.rpcParam(1) as String)
                    val changes = call.rpcParam(4)
                    assertEquals(2, changes.length as Int)
                    val byYear =
                        (0 until (changes.length as Int)).associate {
                            (changes[it].year as Int) to
                                (changes[it].amountCents as Number).toLong()
                        }
                    assertEquals(mapOf(2024 to 900L, 2025 to 1050L), byYear)
                }
            }
        }

    @Test
    fun changingTheYearWithTypedInValues_asksBeforeThrowingThemAway(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "SHEET") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-editor-guard") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the entry table") { element().allOf("input[id^='partg-current-']").size == 35 }
                    type(element().input("current", "E01"), "5")
                    awaitUntil("the draft is open") { element().shows("1 Änderungen") }
                    val select = element().controlOf("Rechnungsjahr") as org.w3c.dom.HTMLSelectElement
                    val other =
                        (0 until select.options.length).map { (select.options.item(it) as org.w3c.dom.HTMLOptionElement).value }.first {
                            it !=
                                select.value
                        }
                    select.value = other
                    select.dispatchEvent(Event("change", EventInit(bubbles = true)))
                    awaitUntil("the question") { lastOpenModal().shows("Eingaben verwerfen?") }
                    lastOpenModal().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the draft is kept") { element().input("current", "E01").value == "5" }
                }
            }
        }
}
