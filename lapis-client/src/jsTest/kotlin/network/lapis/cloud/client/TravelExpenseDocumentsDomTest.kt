package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelContactHintFormat
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseDocumentDto
import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseSettingsInput
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.199 -- the organisation documents (admin section, member list), the Markdown hint editor and the contact block. */
class TravelExpenseDocumentsDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(memberId = "m-1", displayName = "Person", role = role, expiresAt = LocalDateTime(2099, 1, 1, 0, 0))

    private fun doc(
        id: String,
        title: String,
        mime: String = TravelExpenseDocumentRules.MIME_PDF,
        uploader: String? = "Erika Vorstand",
    ) = TravelExpenseDocumentDto(
        id = id,
        title = title,
        filename = "$title.pdf",
        mimeType = mime,
        sizeBytes = 245_000,
        uploadedAt = LocalDateTime(2026, 5, 1, 9, 30),
        uploaderName = uploader,
    )

    private fun rates(
        documents: List<TravelExpenseDocumentDto> = emptyList(),
        canManage: Boolean = true,
        contact: TravelExpenseContactDto = TravelExpenseContactDto(name = "Erika"),
        admin: Boolean = true,
    ) = TravelExpenseRatesDto(
        rateSet = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30),
        contact = contact,
        canEditSettings = admin,
        documents = documents,
        canManageDocuments = canManage,
    )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private suspend fun routes() =
        Triple(
            routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() },
            routeOf { rpcService<IAccountingService>().listLedgerAccounts(true) },
            routeOf {
                rpcService<ITravelExpenseService>().updateTravelExpenseSettings(
                    TravelExpenseSettingsInput(emptyList(), emptyList(), 0, 0, 0, null, 0),
                )
            },
        )

    private fun HTMLElement.byLabel(label: String): HTMLElement =
        assertNotNull(allOf("button, a").firstOrNull { it.getAttribute("aria-label") == label }, "no button or link '$label'")

    @Test
    fun adminPage_showsTheDocumentsBelowTheForm_outsideIt_sortedAndWithNamedIconActions(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val (ratesRoute, accountsRoute, saveRoute) = routes()
            val docs = listOf(doc("d-2", "zeta Beschluss"), doc("d-1", "Alpha Ordnung", mime = TravelExpenseDocumentRules.MIME_XLSX))
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates(docs)),
                        accountsRoute to jsonOf(ListSerializer(LedgerAccountDto.serializer()), emptyList()),
                        saveRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates(docs)),
                    ),
            ) {
                mountedForm("te-docs-admin") { root, element ->
                    renderTravelExpenseSettingsScreen(root)
                    awaitUntil("documents and form rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().let {
                            it.contains(
                                "Alpha Ordnung",
                            ) &&
                                it.contains("Ansprechpartner für Reisekosten")
                        }
                    }
                    val page = element().textContent.orEmpty()
                    val headings = element().allOf("h2").map { it.textContent?.trim().orEmpty() }
                    assertTrue(
                        headings.indexOf("Unterlagen für Mitglieder") > headings.indexOf("Ansprechpartner für Reisekosten"),
                        "below the form",
                    )
                    assertTrue(page.indexOf("Alpha Ordnung") < page.indexOf("zeta Beschluss"), "sorted by title")
                    val heading = element().allOf("h2").first { it.textContent?.trim() == "Unterlagen für Mitglieder" }
                    assertNull(heading.closest(".lapis-form"), "the section is NOT part of the settings form")
                    assertTrue(page.contains("Excel") && page.contains("PDF"), "a text label for the type, not only a glyph")
                    for (title in listOf("Alpha Ordnung", "zeta Beschluss")) {
                        element().byLabel("„$title“ herunterladen")
                        element().byLabel("„$title“ umbenennen")
                        element().byLabel("„$title“ löschen")
                    }
                    assertEquals(
                        "/api/travel-expenses/documents/d-1/download",
                        element().byLabel("„Alpha Ordnung“ herunterladen").getAttribute("href"),
                    )
                    val add = element().buttonNamed("Unterlage hinzufügen")
                    assertFalse((add as org.w3c.dom.HTMLButtonElement).disabled)

                    fun uploadButton() = element().allOf("button").firstOrNull { it.textContent?.trim() == "Hochladen" }
                    assertTrue(uploadButton()?.offsetParent == null, "the add form starts collapsed")
                    add.click()
                    delay(80)
                    assertNotNull(uploadButton()?.offsetParent, "the add form opens on click")
                }
            }
        }

    @Test
    fun atTenDocuments_theAddButtonIsDisabled_andTheLimitIsExplained(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val (ratesRoute, accountsRoute, saveRoute) = routes()
            val docs = (1..10).map { doc("d-$it", "Unterlage $it") }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates(docs)),
                        accountsRoute to jsonOf(ListSerializer(LedgerAccountDto.serializer()), emptyList()),
                        saveRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates(docs)),
                    ),
            ) {
                mountedForm("te-docs-limit") { root, element ->
                    renderTravelExpenseSettingsScreen(root)
                    awaitUntil("documents rendered", timeoutMs = 800) { element().textContent.orEmpty().contains("Unterlage 10") }
                    assertTrue((element().buttonNamed("Unterlage hinzufügen") as org.w3c.dom.HTMLButtonElement).disabled)
                    assertTrue(element().textContent.orEmpty().contains("Es sind höchstens 10 Unterlagen möglich."))
                }
            }
        }

    @Test
    fun aBoardMember_seesOnlyTheDocuments_andTheSentenceAboutTheAdministration(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val (ratesRoute, _, _) = routes()
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates(listOf(doc("d-1", "Ordnung")), admin = false)),
                    ),
            ) {
                mountedForm("te-docs-board") { root, element ->
                    renderTravelExpenseSettingsScreen(root)
                    awaitUntil("documents rendered", timeoutMs = 800) { element().textContent.orEmpty().contains("Ordnung") }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Die Erstattungssätze und den Ansprechpartner pflegt die Administration."))
                    assertTrue(element().allOf("button").none { it.textContent?.trim() == "Beispielwerte der Vorlage übernehmen" })
                    assertNull(element().querySelector(".lapis-form input[type=text]"), "no settings form for BOARD")
                    assertTrue(text.contains("Unterlage hinzufügen"))
                }
            }
        }

    @Test
    fun deleteAsks_thenSendsDelete_andReloadsOnlyTheDocuments(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val (ratesRoute, _, _) = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        request.isRpc ->
                            request.answerWith(
                                jsonOf(TravelExpenseRatesDto.serializer(), rates(listOf(doc("d-1", "Ordnung")), admin = false)),
                            )
                        request.method == "DELETE" -> StubResponse(status = 200)
                        else -> StubResponse()
                    }
                },
            ) { calls ->
                mountedForm("te-docs-delete") { root, element ->
                    renderTravelExpenseSettingsScreen(root)
                    awaitUntil("documents rendered", timeoutMs = 800) { element().textContent.orEmpty().contains("Ordnung") }
                    element().byLabel("„Ordnung“ löschen").click()
                    delay(60)
                    val dialog = document.body!!.textContent.orEmpty()
                    assertTrue(dialog.contains("„Ordnung“ wirklich löschen?"), "a confirmation first")
                    assertEquals(0, calls.count { it.method == "DELETE" })
                    document.body!!
                        .allOf("button")
                        .first { it.textContent?.trim() == "Löschen" }
                        .click()
                    awaitUntil("delete sent", timeoutMs = 800) { calls.count { it.method == "DELETE" } == 1 }
                    assertTrue(calls.first { it.method == "DELETE" }.url.endsWith("/api/travel-expenses/documents/d-1"))
                    delay(400)
                    assertTrue(calls.toRoute(ratesRoute).size >= 2, calls.joinToString { "${it.method} ${it.url}" })
                }
            }
        }

    @Test
    fun memberList_hasOneDownloadLinkPerDocument_withTheNameTypeAndSize_andNoBlockWithoutDocuments(): Promise<Unit> =
        formTest {
            mountedForm("te-docs-list") { root, element ->
                renderTravelExpenseDocumentsList(
                    root,
                    rates(
                        listOf(doc("d-1", "Reisekostenordnung"), doc("d-2", "Tabelle", mime = TravelExpenseDocumentRules.MIME_XLS)),
                        canManage = false,
                    ),
                )
                delay(40)
                assertTrue(element().textContent.orEmpty().contains("Unterlagen und Beschlüsse"))
                val links = element().allOf("a.lapis-te-doc-link")
                assertEquals(2, links.size)
                assertEquals("Reisekostenordnung herunterladen (PDF, 239 KB)", links[0].getAttribute("aria-label"))
                assertEquals("Tabelle herunterladen (Excel, 239 KB)", links[1].getAttribute("aria-label"))
                assertEquals("/api/travel-expenses/documents/d-1/download", links[0].getAttribute("href"))
                assertFalse(element().textContent.orEmpty().contains("Erika Vorstand"), "no uploader for members")
                assertNull(element().allOf("button").firstOrNull { it.textContent?.trim() == "Unterlagen verwalten" })
            }
            mountedForm("te-docs-list-empty") { root, element ->
                renderTravelExpenseDocumentsList(root, rates(emptyList(), canManage = false))
                delay(40)
                assertFalse(element().textContent.orEmpty().contains("Unterlagen und Beschlüsse"), "no heading without documents")
                assertNull(element().querySelector(".$TE_DOCUMENTS_LIST_CLASS"))
            }
            mountedForm("te-docs-list-manager") { root, element ->
                renderTravelExpenseDocumentsList(root, rates(emptyList(), canManage = true))
                delay(40)
                element().buttonNamed("Unterlagen verwalten")
            }
        }

    @Test
    fun contactBlock_showsPlainHintAsText_andMarkdownThroughTheAgreementBody(): Promise<Unit> =
        formTest {
            mountedForm("te-hint-plain") { root, element ->
                renderTravelExpenseContactBlock(root, rates(contact = TravelExpenseContactDto(name = "E", hint = "<b>fett</b>\nZeile 2")))
                delay(40)
                val box = assertNotNull(element().querySelector(".lapis-te-contact-hint") as? HTMLElement)
                assertFalse(box.classList.contains("lapis-te-contact-hint--md"))
                assertNull(box.querySelector("b"), "plain text is never markup")
                assertTrue(box.textContent.orEmpty().contains("<b>fett</b>"))
            }
            mountedForm("te-hint-md") { root, element ->
                val contact =
                    TravelExpenseContactDto(
                        name = "E",
                        hint = "**fett**",
                        hintFormat = TravelContactHintFormat.MARKDOWN,
                        hintHtml = "<p><strong>fett</strong></p>",
                    )
                renderTravelExpenseContactBlock(root, rates(contact = contact))
                delay(40)
                val box = assertNotNull(element().querySelector(".lapis-te-contact-hint--md") as? HTMLElement)
                assertNotNull(box.querySelector(".lapis-agreement-body strong"), "rendered through the one rich site")
            }
            mountedForm("te-hint-md-nohtml") { root, element ->
                val contact =
                    TravelExpenseContactDto(name = "E", hint = "**fett**", hintFormat = TravelContactHintFormat.MARKDOWN, hintHtml = null)
                renderTravelExpenseContactBlock(root, rates(contact = contact))
                delay(40)
                val box = assertNotNull(element().querySelector(".lapis-te-contact-hint") as? HTMLElement)
                assertTrue(box.textContent.orEmpty().contains("**fett**"), "without usable HTML the text is shown as text")
            }
        }

    @Test
    fun hintEditor_hasTabs_rendersThePreviewByTheServer_andOnlyAnEditedHintBecomesMarkdown(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val (ratesRoute, accountsRoute, saveRoute) = routes()
            val previewRoute = routeOf { rpcService<ITravelExpenseService>().previewTravelContactHint("x") }
            val legacy =
                rates(contact = TravelExpenseContactDto(name = "Erika", hint = "Alter Text", hintFormat = TravelContactHintFormat.PLAIN))
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), legacy),
                        accountsRoute to jsonOf(ListSerializer(LedgerAccountDto.serializer()), emptyList()),
                        saveRoute to jsonOf(TravelExpenseRatesDto.serializer(), legacy),
                        previewRoute to "\"<p><strong>fett</strong></p>\"",
                    ),
            ) { calls ->
                mountedForm("te-hint-editor") { root, element ->
                    renderTravelExpenseSettingsScreen(root)
                    awaitUntil(
                        "settings rendered",
                        timeoutMs = 800,
                    ) { element().allOf("button").any { it.textContent?.trim() == "Speichern" } }
                    delay(80)
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Der bisherige Text ist einfacher Text."), "the info sentence for a legacy plain hint")
                    val tabs = element().allOf("[role=tab]")
                    assertEquals(listOf("Schreiben", "Vorschau"), tabs.map { it.textContent?.trim().orEmpty() })
                    assertEquals("true", tabs[0].getAttribute("aria-selected"))

                    // saving WITHOUT touching the hint keeps the stored format
                    element().buttonNamed("Speichern").click()
                    awaitUntil("first save", timeoutMs = 800) { calls.toRoute(saveRoute).size == 1 }
                    val firstContact: String = JSON.stringify(calls.singleCall(saveRoute).rpcParam(0).contact)
                    assertFalse(firstContact.contains("MARKDOWN"), "the stored plain format is kept: $firstContact")

                    // a successful save reloads the page: the form is rebuilt, so look the tabs up again
                    delay(300)
                    val freshTabs = element().allOf("[role=tab]")
                    // editing it sends Markdown, and the preview tab asks the server
                    element().typeInto("Hinweistext (Mehr erfahren)", "**fett**")
                    freshTabs[1].click()
                    awaitUntil("preview asked", timeoutMs = 800) { calls.toRoute(previewRoute).isNotEmpty() }
                    awaitUntil("preview shown", timeoutMs = 800) { element().querySelector(".lapis-agreement-preview strong") != null }
                    assertEquals("true", freshTabs[1].getAttribute("aria-selected"))
                    freshTabs[0].click()
                    element().buttonNamed("Speichern").click()
                    awaitUntil("second save", timeoutMs = 800) { calls.toRoute(saveRoute).size == 2 }
                    val secondContact: String = JSON.stringify(calls.toRoute(saveRoute)[1].rpcParam(0).contact)
                    assertTrue(secondContact.contains("\"hintFormat\":\"MARKDOWN\""), "an edited hint is saved as Markdown: $secondContact")
                }
            }
        }
}
