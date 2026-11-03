package network.lapis.cloud.client

import kotlinx.browser.window
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.269 -- the plausibility hints above the download buttons: symbol and words, no red, no name, the buttons stay enabled, the link switches tabs. */
class PartyReportFindingsDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private val findings =
        listOf(
            PartgFindingDto(PartgFindingCode.P01, PartgFindingLevel.CHECK, "FEDERAL", listOf(1, 3, 7), target = PartgFindingTarget.ANNEX),
            PartgFindingDto(
                PartgFindingCode.P05,
                PartgFindingLevel.INFO,
                "FEDERAL",
                positionCode = "E01",
                target = PartgFindingTarget.SHEET,
            ),
            PartgFindingDto(
                PartgFindingCode.P06,
                PartgFindingLevel.CHECK,
                null,
                gap = "GENERAL_ACCOUNTING",
                target = PartgFindingTarget.NOTES,
            ),
        )

    private suspend fun withReportTab(
        id: String,
        hints: List<PartgFindingDto>,
        block: suspend (() -> HTMLElement) -> Unit,
    ) {
        runCatching { window.localStorage.setItem("lapis.partyReport.tab", "REPORT") }
        AppState.setSession(PartyReportDomSupport.session())
        PartyReportDomSupport.resetR3()
        PartyReportDomSupport.findings = hints
        val routes = PartyReportDomSupport.routes()
        withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
            mountedForm(id) { root, element ->
                renderPartyReportScreen(root)
                awaitUntil("the hints") { element().allOf("[data-partg-findings]").isNotEmpty() }
                block(element)
            }
        }
        runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
    }

    @Test
    fun theHints_carrySymbolAndWords_noRed_andOnlyRowNumbers(): Promise<Unit> =
        formTest {
            withReportTab("findings-look", findings) { element ->
                val box = element().allOf("[data-partg-findings]").single()
                val lines = box.allOf("[data-partg-finding]")
                assertEquals(3, lines.size)
                assertTrue(lines[0].shows("Bitte prüfen") && lines[0].shows("Bundesverband, Zeilen 1, 3 und 7: Anschrift fehlt"))
                assertTrue(lines[1].shows("Zur Info") && lines[1].shows("Position 1 weicht deutlich vom Vorjahr ab"))
                assertTrue(lines[2].shows("Organisation") && lines[2].shows("allgemeinen Angaben zur Rechnungslegung"))
                lines.forEach { assertTrue(it.allOf("[aria-hidden=true]").isNotEmpty(), "a symbol next to the words") }
                assertEquals(0, box.allOf(".text-danger, .alert-danger, .bg-danger").size, "no red")
            }
        }

    @Test
    fun theDownloadButtons_stayEnabled_whateverTheHintsSay(): Promise<Unit> =
        formTest {
            withReportTab("findings-buttons", findings) { element ->
                listOf("PDF herunterladen", "Excel herunterladen").forEach {
                    assertFalse(element().buttonNamed(it).hasAttribute("disabled"), it)
                }
            }
        }

    @Test
    fun noHints_saysSo(): Promise<Unit> =
        formTest {
            withReportTab("findings-none", emptyList()) { element ->
                assertTrue(element().shows("Keine Hinweise."))
                assertEquals(0, element().allOf("[data-partg-finding]").size)
            }
        }

    @Test
    fun theLinkOfAHint_switchesToTheMatchingTab(): Promise<Unit> =
        formTest {
            withReportTab("findings-link", findings) { element ->
                element().allOf("[data-partg-finding=P06] button").single().click()
                awaitUntil("the notes tab") { element().shows("Schalten Sie die Bausteine ein") }
            }
        }
}
