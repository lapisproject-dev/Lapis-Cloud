package network.lapis.cloud.client

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.269 -- the tab "Erläuterungen": five blocks, a placeholder is no value, the relief choice only for its block, the cap of 50 holdings. */
class PartyReportNotesTabDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private suspend fun withNotesTab(
        id: String,
        block: suspend (() -> HTMLElement, List<RecordedRequest>) -> Unit,
    ) {
        runCatching { window.localStorage.setItem("lapis.partyReport.tab", "ERLAEUTERUNGEN") }
        AppState.setSession(PartyReportDomSupport.session())
        PartyReportDomSupport.resetR3()
        val routes = PartyReportDomSupport.routes()
        withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) { requests ->
            mountedForm(id) { root, element ->
                renderPartyReportScreen(root)
                awaitUntil("the notes") { element().shows("Allgemeine Angaben zur Rechnungslegung") }
                block(element, requests)
            }
        }
        runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
    }

    @Test
    fun fiveBlocks_eachWithASwitch_andOnlyTheReliefBlockHasTheTwoChoices(): Promise<Unit> =
        formTest {
            withNotesTab("notes-blocks") { element, _ ->
                val page = element()
                listOf(
                    "Allgemeine Angaben zur Rechnungslegung",
                    "Erleichterungen nach § 28 PartG",
                    "Beteiligungen",
                    "Medienerzeugnisse",
                    "Bewertung von Grundstücken",
                ).forEach { assertTrue(page.shows(it), it) }
                assertEquals(5, page.allOf("label").count { it.shows("In den Bericht aufnehmen") })
                assertEquals(1, page.allOf("label").count { it.shows("§ 28 Absatz 1") })
                assertEquals(1, page.allOf("label").count { it.shows("§ 28 Absatz 3") })
            }
        }

    @Test
    fun aPlaceholderIsNotAValue_andTheLimitIsNamed(): Promise<Unit> =
        formTest {
            withNotesTab("notes-placeholder") { element, requests ->
                val area = element().controlOf("Text: Allgemeine Angaben zur Rechnungslegung") as HTMLTextAreaElement
                assertEquals("", area.value, "the placeholder is not the value")
                assertTrue(area.getAttribute("placeholder").orEmpty().startsWith("Zum Beispiel"))
                assertTrue(element().shows("Höchstens 4000 Zeichen."))
                // the routes are learned through a private stub: before the click, or the page's own request would reach that stub
                val routes = PartyReportDomSupport.routes()
                element().buttonNamed("Speichern").click()
                awaitUntil(
                    "the save",
                    detail = {
                        "alert='${element().allOf(".lapis-form-alert").joinToString { it.textContent.orEmpty() }}' " +
                            "invalid=${element().allOf("[aria-invalid=true]").size} calls=${requests.map { it.url }}"
                    },
                ) { requests.any { it.isRpc && it.rpcRoute == routes.saveNotes } }
                val sent = requests.first { it.isRpc && it.rpcRoute == routes.saveNotes }.rpcParam(2)
                val bodies = (0 until (sent.length as Int)).map { sent[it].body }
                assertTrue(bodies.all { it == null }, "an untouched block sends no text")
            }
        }

    @Test
    fun atMostFiftyHoldings_theFiftyFirstIsRefused(): Promise<Unit> =
        formTest {
            withNotesTab("notes-holdings") { element, _ ->
                repeat(50) { element().buttonNamed("Beteiligung hinzufügen").click() }
                assertEquals(50, element().allOf("[data-partg-holding]").size)
                element().buttonNamed("Beteiligung hinzufügen").click()
                assertEquals(50, element().allOf("[data-partg-holding]").size)
                assertTrue(
                    element()
                        .allOf("[data-partg-holding]")
                        .first()
                        .allOf("input")
                        .all { it is HTMLInputElement },
                )
            }
        }
}
