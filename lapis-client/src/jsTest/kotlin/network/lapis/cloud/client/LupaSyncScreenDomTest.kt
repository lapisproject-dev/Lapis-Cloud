package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.226 -- the LupaBB sync page: credential form (never pre-filled, autocomplete off), preview, icon-only row actions (R58), the apply button. */
class LupaSyncScreenDomTest {
    /** One item per record: a row of the table on a wide viewport, a card on a narrow one (not the rows of the nested field comparison). */
    private val rowSelector = "table:not(.lapis-lupabb-diff) > tbody > tr, .lapis-data-card"

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    @Test
    fun withoutCredentials_theFormIsEmpty_theFieldsHideTheirContent_andThePreviewCannotBeLoaded(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            withFetchStub(respond = { request -> LupaSyncDomSupport.answer(routes, request, LupaSyncDomSupport.noCredentials) }) {
                mountedForm("lupa-sync-empty") { root, element ->
                    renderLupaSyncScreen(root)
                    awaitUntil("the credential form") { element().allOf("input").size >= 3 }
                    val page = element()
                    assertTrue(page.shows("LupaBB-Abgleich"))
                    val inputs = listOf("Benutzername", "Passwort", "API-Schlüssel").map { page.controlOf(it) as HTMLInputElement }
                    assertEquals(listOf("text", "password", "password"), inputs.map { it.type })
                    inputs.forEach {
                        assertEquals("", it.value, "never pre-filled")
                        assertEquals("off", it.getAttribute("autocomplete"))
                    }
                    assertTrue(page.shows("Prüfen und speichern"))
                    val load = page.buttonNamed("Vorschau laden")
                    assertTrue(load.asDynamic().disabled as Boolean, "no credentials, no preview")
                    assertTrue(page.shows("Bitte zuerst die Zugangsdaten eingeben."))
                }
            }
        }

    @Test
    fun withCredentials_noFormAndNoValue_butReplaceRemoveAndAnEnabledPreview(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            withFetchStub(respond = { request -> LupaSyncDomSupport.answer(routes, request, LupaSyncDomSupport.withCredentials) }) {
                mountedForm("lupa-sync-set") { root, element ->
                    renderLupaSyncScreen(root)
                    awaitUntil("the stored state") { element().shows("Zugangsdaten sind hinterlegt.") }
                    val page = element()
                    assertEquals(0, page.allOf("input[type=password]").size)
                    assertNotNull(page.allOf("button").firstOrNull { it.textContent?.trim() == "Zugangsdaten ersetzen" })
                    assertNotNull(page.allOf("button").firstOrNull { it.textContent?.trim() == "Zugangsdaten entfernen" })
                    assertFalse(page.buttonNamed("Vorschau laden").asDynamic().disabled as Boolean)
                    assertTrue(page.shows("Dana Keller"))
                }
            }
        }

    @Test
    fun thePreview_showsCountsAndChips_theRowActionIsAnIconWithAName_andApplyNamesItsNumbers(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            withFetchStub(respond = { request -> LupaSyncDomSupport.answer(routes, request, LupaSyncDomSupport.withCredentials) }) {
                mountedForm("lupa-sync-preview") { root, element ->
                    renderLupaSyncScreen(root)
                    awaitUntil("the stored state") { element().shows("Zugangsdaten sind hinterlegt.") }
                    element().buttonNamed("Vorschau laden").asDynamic().click()
                    // a table row on a wide viewport, a card on a narrow one (earlier tests of the suite may have left the window narrow)
                    awaitUntil(
                        "the preview rows",
                        detail = {
                            element()
                                .textContent
                                .orEmpty()
                                .substringAfter("Vorschau neu laden")
                                .take(700) +
                                " | toasts: " + document.querySelectorAll(".toastify, .toast, .alert").length +
                                " | rows: " + element().allOf("tr").size + " / cards: " + element().allOf(".lapis-data-card").size
                        },
                    ) { element().allOf(rowSelector).size == 2 }
                    val page = element()
                    assertTrue(
                        page.shows("1 neu · 1 geändert · 3 unverändert · 1 unklar · 0 übersprungen · 0 Fehler · 0 nicht mehr in LupaBB"),
                    )
                    val chips = page.allOf(".btn-group button").map { it.textContent?.trim() }
                    assertTrue("Handlungsbedarf (1)" in chips && "Neu (1)" in chips && "Alle (6)" in chips)
                    // the ambiguous record offers the link as an icon with tooltip and accessible name, no visible text
                    val link = page.allOf(rowSelector)[0].allOf("button").single()
                    // several candidates often share a name: name and e-mail address tell the buttons apart
                    val linkName = "Mit diesem Mitglied verknüpfen: Erika Mustermann (em@example.org)"
                    assertEquals(linkName, link.getAttribute("aria-label"))
                    assertEquals(linkName, link.getAttribute("title"))
                    assertTrue(link.textContent?.trim().isNullOrEmpty(), "icon only")
                    // the changed record has an expandable field comparison
                    assertTrue(page.allOf("details").single().shows("1 Felder im Vergleich"))
                    assertTrue(page.shows("Alt") && page.shows("Braunschweig"))
                    page.buttonNamed("1 neue und 1 geänderte Datensätze übernehmen")
                }
            }
        }
}
