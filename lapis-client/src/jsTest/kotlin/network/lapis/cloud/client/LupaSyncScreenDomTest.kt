package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.MemberStatus
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
                    // V1.9.303: the ambiguous record offers the link as a small button WITH visible text; the accessible name carries the person
                    // (several candidates often share a name: name and e-mail address tell the buttons apart)
                    val ambiguous = page.allOf(rowSelector)[0]
                    val linkName = "Mit Erika Mustermann (em@example.org) verknüpfen"
                    val link = ambiguous.iconButtonNamed(linkName)
                    assertEquals(linkName, link.getAttribute("title"))
                    assertEquals("Verknüpfen", link.textContent?.trim())
                    // the record can be skipped (visible text, accessible name with the person) and searched for ("Welche Person ist gemeint?")
                    val skipName = "Erika Muster für diesen Abgleich überspringen"
                    val skip = ambiguous.iconButtonNamed(skipName)
                    assertEquals("Für diesen Abgleich überspringen", skip.textContent?.trim())
                    assertTrue(ambiguous.shows("Welche Person ist gemeint?"))
                    // no button of the row is a bare icon, and no i18n marker reaches an attribute
                    assertEquals(0, ambiguous.allOf("button").filter { it.textContent?.trim().isNullOrEmpty() }.size)
                    assertEquals(
                        0,
                        page
                            .allOf("[aria-label],[title]")
                            .filter {
                                it.getAttribute("aria-label").orEmpty().contains("###KvI18n") ||
                                    it.getAttribute("title").orEmpty().contains("###KvI18n")
                            }.size,
                    )
                    // fixed order: candidate link, search, skip LAST
                    val order = ambiguous.allOf("button, input[type=search]")
                    assertTrue(order.indexOf(link) < order.indexOf(order.first { it.tagName == "INPUT" }))
                    assertEquals(skip, order.last { it.tagName == "BUTTON" })
                    assertEquals(skip, order.last())
                    // the changed record has an expandable field comparison
                    assertTrue(
                        page
                            .allOf(rowSelector)[1]
                            .allOf("details")
                            .single()
                            .shows("1 Felder im Vergleich"),
                    )
                    assertTrue(page.shows("Alt") && page.shows("Braunschweig"))
                    page.buttonNamed("1 neue und 1 geänderte Datensätze übernehmen")
                }
            }
        }

    private suspend fun openPreview(
        routes: LupaSyncDomSupport.Routes,
        rows: List<network.lapis.cloud.shared.domain.LupaBbPreviewRowDto>,
        targets: List<network.lapis.cloud.shared.domain.LupaBbLinkTargetDto> = emptyList(),
        block: suspend (List<RecordedRequest>, () -> HTMLElement) -> Unit,
    ) = withFetchStub(
        respond = { request ->
            LupaSyncDomSupport.answer(routes, request, LupaSyncDomSupport.withCredentials, pageRows = rows, targets = targets)
        },
    ) { requests ->
        mountedForm("lupa-sync-rows") { root, element ->
            renderLupaSyncScreen(root)
            awaitUntil("the stored state") { element().shows("Zugangsdaten sind hinterlegt.") }
            element().buttonNamed("Vorschau laden").asDynamic().click()
            awaitUntil("the preview rows") { element().allOf(rowSelector).size == rows.size }
            block(requests, element)
        }
    }

    @Test
    fun aRowWithAMember_linksToTheMemberAdministration_inANewTab_withoutOpener(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            openPreview(routes, LupaSyncDomSupport.protectedRows + LupaSyncDomSupport.rows) { _, element ->
                val rows = element().allOf(rowSelector)
                val anchor = rows[0].allOf("a").single { it.shows("Mitglied öffnen") }
                assertEquals("Mitglied öffnen: Bruno Vorstand", anchor.getAttribute("aria-label"))
                assertEquals("#/members?member=member-4801", anchor.getAttribute("href"))
                assertEquals("_blank", anchor.getAttribute("target"))
                assertTrue(anchor.getAttribute("rel").orEmpty().contains("noopener"))
                // the ambiguous record is matched to no member: no link
                assertEquals(0, rows[3].allOf("a").filter { it.shows("Mitglied öffnen") }.size)
            }
        }

    @Test
    fun theTakeOverButton_appearsOnlyWhenItIsAllowed_theOwnRecordAndABlockedRowGetAnExplanation(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            openPreview(routes, LupaSyncDomSupport.protectedRows) { _, element ->
                val rows = element().allOf(rowSelector)
                val take = rows[0].iconButtonNamed("LupaBB-Werte übernehmen: Bruno Vorstand")
                assertEquals("LupaBB-Werte übernehmen", take.textContent?.trim())
                assertEquals(
                    0,
                    rows[1].allOf("button").filter { it.getAttribute("aria-label").orEmpty().startsWith("LupaBB-Werte übernehmen") }.size,
                )
                assertTrue(rows[1].shows("Ihren eigenen Datensatz gleicht ein anderer Administrator ab."))
                assertEquals(
                    0,
                    rows[2].allOf("button").filter { it.getAttribute("aria-label").orEmpty().startsWith("LupaBB-Werte übernehmen") }.size,
                )
                assertTrue(rows[2].shows("Zustimmung eines zweiten Administrators"))
            }
        }

    @Test
    fun theTakeOverDialog_showsStatusAndTable_andConfirmingCallsTheServer(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            openPreview(routes, LupaSyncDomSupport.protectedRows) { requests, element ->
                element()
                    .allOf(rowSelector)[0]
                    .iconButtonNamed("LupaBB-Werte übernehmen: Bruno Vorstand")
                    .asDynamic()
                    .click()
                awaitUntil("the dialog") { lastOpenModal().shows("LupaBB-Werte übernehmen?") }
                val modal = lastOpenModal()
                assertTrue(modal.shows("Status: Aktiv → Ausgetreten") || modal.shows("Status:"), "the status line")
                assertTrue(modal.shows("Neu 1") && modal.shows("Braunschweig"), "the comparison table")
                modal.buttonNamed("3 Werte übernehmen").asDynamic().click()
                awaitUntil("the server call") { requests.any { it.isRpc && it.rpcRoute == routes.applyProtected } }
                val call = requests.first { it.isRpc && it.rpcRoute == routes.applyProtected }
                assertEquals("4801", call.json.params[1] as String)
            }
        }

    @Test
    fun theSearch_startsAtThreeCharacters_marksLinkedMembers_andOffersTheOthers(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            val targets =
                listOf(
                    network.lapis.cloud.shared.domain.LupaBbLinkTargetDto(
                        "m-9",
                        "Erika Frei",
                        "frei@example.org",
                        MemberStatus.ACTIVE,
                        false,
                    ),
                    network.lapis.cloud.shared.domain.LupaBbLinkTargetDto(
                        "m-8",
                        "Erika Belegt",
                        "belegt@example.org",
                        MemberStatus.ACTIVE,
                        true,
                        9_000_000_082,
                    ),
                )
            openPreview(routes, LupaSyncDomSupport.rows, targets) { requests, element ->
                val box = element().allOf("input[type=search]").single() as HTMLInputElement
                assertEquals("Name oder E-Mail suchen", box.getAttribute("aria-label"))
                box.value = "Er"
                box.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                kotlinx.coroutines.delay(600)
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == routes.search }, "two characters: no call")
                box.value = "Erika"
                box.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                awaitUntil("the search call") { requests.any { it.isRpc && it.rpcRoute == routes.search } }
                assertEquals("Erika", requests.first { it.isRpc && it.rpcRoute == routes.search }.rpcParam(2) as String)
                awaitUntil("the hits") { element().shows("Erika Frei") && element().shows("Erika Belegt") }
                val page = element()
                // V1.9.303: the reason is visible text with the number (plain digits), tied to the disabled button by aria-describedby
                assertTrue(page.shows("Nicht verknüpfbar: bereits mit LupaBB-Person Nr. 9000000082 verknüpft."))
                page.iconButtonNamed("Mit Erika Frei (frei@example.org) verknüpfen")
                val disabled = page.allOf("button").single { it.textContent?.trim() == "Verknüpfen" && it.asDynamic().disabled as Boolean }
                val reason = document.getElementById(disabled.getAttribute("aria-describedby").orEmpty())
                assertTrue(reason?.textContent.orEmpty().contains("Nr. 9000000082"), "the reason is the description of the disabled button")
                // the search field is described by its hint
                val hint = document.getElementById(box.getAttribute("aria-describedby").orEmpty())
                assertTrue(hint?.textContent.orEmpty().contains("Tippen Sie Name oder E-Mail des Mitglieds"))
            }
        }

    @Test
    fun skipping_asksFirst_thenCallsTheServer(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            openPreview(routes, LupaSyncDomSupport.rows) { requests, element ->
                element()
                    .allOf(rowSelector)[0]
                    .iconButtonNamed("Erika Muster für diesen Abgleich überspringen")
                    .asDynamic()
                    .click()
                awaitUntil("the dialog") { lastOpenModal().shows("Für diesen Abgleich überspringen?") }
                assertTrue(lastOpenModal().shows("Beim nächsten Abgleich erscheint er wieder."))
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == routes.skip })
                lastOpenModal().buttonNamed("Überspringen").asDynamic().click()
                awaitUntil("the server call") { requests.any { it.isRpc && it.rpcRoute == routes.skip } }
            }
        }

    @Test
    fun aConflictRow_namesTheMemberAndBothNumbers_warnsAboutALikelyDouble_andOpensTheHolder(): Promise<Unit> =
        formTest {
            AppState.setSession(LupaSyncDomSupport.session())
            val routes = LupaSyncDomSupport.routes()
            openPreview(routes, LupaSyncDomSupport.conflictRows) { _, element ->
                val rows = element().allOf(rowSelector)
                // plain digits, no thousands separator
                assertTrue(
                    rows[0].shows(
                        "Die E-Mail gehört zum Mitglied Dr. Erika Doppel, das bereits mit LupaBB-Person Nr. 9000000071 verknüpft ist. " +
                            "Diese Zeile ist LupaBB-Person Nr. 9000000070.",
                    ),
                )
                assertTrue(rows[0].shows("Wahrscheinlich dieselbe Person doppelt in LupaBB"))
                val anchor = rows[0].allOf("a").single { it.shows("Mitglied öffnen") }
                assertEquals("#/members?member=member-71", anchor.getAttribute("href"))
                assertEquals("Mitglied öffnen: Dr. Erika Doppel", anchor.getAttribute("aria-label"))
                // no double hint without the flag
                assertTrue(rows[1].shows("Jemand Anderes") && rows[1].shows("Nr. 9000000073"))
                assertFalse(rows[1].shows("Wahrscheinlich dieselbe Person doppelt"))
                // an anonymised holder: the general sentence only, no name, no number, nothing to open
                assertFalse(rows[2].shows("Nr."))
                assertEquals(0, rows[2].allOf("a").filter { it.shows("Mitglied öffnen") }.size)
                // every conflict row can be skipped, the skip button comes last
                for (row in rows) {
                    assertEquals(
                        "Für diesen Abgleich überspringen",
                        row
                            .allOf("button")
                            .last()
                            .textContent
                            ?.trim(),
                    )
                }
            }
        }
}
