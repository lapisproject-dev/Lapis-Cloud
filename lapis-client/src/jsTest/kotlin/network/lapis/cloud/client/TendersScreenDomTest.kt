package network.lapis.cloud.client

import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderDefaultsDto
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderMotionIneligibility
import network.lapis.cloud.shared.domain.TenderMotionOptionDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.202 -- the list of tenders: who sees what, the filter, the collapsed form with its guarded choices, the publish dialog. */
class TendersScreenDomTest {
    private val tenders =
        listOf(
            tenderSummary(id = "t1", title = "Website neu aufsetzen", count = 3, mine = TenderBidStatus.SUBMITTED),
            tenderSummary(id = "t2", title = "Flyer drucken", status = TenderStatus.CLOSED, count = null),
            tenderSummary(id = "t3", title = "Abgesagt", status = TenderStatus.CANCELLED, count = null),
            tenderSummary(id = "t4", title = "Fertig", status = TenderStatus.COMPLETED, count = 1),
        )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun HTMLElement.radios(): List<HTMLElement> = allOf("[role=radio]")

    private fun HTMLElement.radioNamed(text: String): HTMLElement =
        assertNotNull(radios().firstOrNull { it.text().trim() == text }, "no segment '$text' in ${radios().map { it.text().trim() }}")

    @Test
    fun aMemberSeesTheTable_withRealLinks_theHiddenCount_andNeitherFormNorDraftFilter(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession(AccountRole.MEMBER))
            val list = routeOf { tenderService().listTenders(null) }
            val defaults = routeOf { tenderService().getTenderDefaults() }
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == list -> req.answerWith(jsonOf(ListSerializer(TenderSummaryDto.serializer()), tenders))
                        else -> req.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("tenders-member") { root, element ->
                    renderTendersScreen(root)
                    awaitUntil("the table") { element().allOf("a[data-record-id]").size == 4 }
                    assertEquals(
                        listOf("Titel", "Status", "Frist", "Vergütung", "Angebote", "Mein Angebot", "Aktionen"),
                        element().allOf("thead th").map { it.text().trim() },
                    )
                    assertEquals("#/tenders/t1", element().allOf("a[data-record-id]").first().getAttribute("href"))
                    val rows = element().allOf("tbody tr")
                    assertTrue(
                        rows[0].text().contains("Veröffentlicht") && rows[0].text().contains("Eingereicht") && rows[0].text().contains("3"),
                    )
                    // the number of bids is hidden where the viewer may not see it: a dash with the reason (tooltip and hidden text)
                    assertTrue(rows[1].text().contains("–"))
                    assertTrue(rows[1].text().contains("Die Zahl der Angebote ist verdeckt."))
                    assertEquals(
                        "Die Zahl der Angebote ist verdeckt.",
                        rows[1].allOf("span").first { it.text().trim() == "–" }.getAttribute("title"),
                    )
                    // the row action is an icon-only button with a name (R58)
                    val action = rows[0].allOf("button").single()
                    assertEquals("Ausschreibung Website neu aufsetzen öffnen", action.getAttribute("aria-label"))
                    assertEquals("", action.text().trim())
                    assertEquals(0, calls.toRoute(defaults).size, "a member never asks for the defaults (403)")
                    assertTrue(element().querySelector("button[aria-controls='lapis-create-tender']") == null, "no create button")
                    assertFalse(element().radios().any { it.text().trim() == "Entwürfe" })
                }
            }
        }

    @Test
    fun theFilter_asksForTheStatus_andCompletedIsCutOnTheClient(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession(AccountRole.MEMBER))
            val list = routeOf { tenderService().listTenders(null) }
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == list -> req.answerWith(jsonOf(ListSerializer(TenderSummaryDto.serializer()), tenders))
                        else -> req.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("tenders-filter") { root, element ->
                    renderTendersScreen(root)
                    awaitUntil("the table") { element().allOf("a[data-record-id]").size == 4 }
                    assertEquals(
                        listOf("Alle", "Offen", "Frist abgelaufen", "Vergeben", "Abgeschlossen"),
                        element().radios().map { it.text().trim() },
                    )
                    element().radioNamed("Vergeben").click()
                    awaitUntil("the second list call") { calls.toRoute(list).size == 2 }
                    assertEquals("AWARDED", calls.toRoute(list)[1].rpcParam(0) as String)
                    element().radioNamed("Abgeschlossen").click()
                    awaitUntil("the third list call") { calls.toRoute(list).size == 3 }
                    assertEquals(null, calls.toRoute(list)[2].rpcParam(0), "Abgeschlossen asks for all and cuts on the client")
                    awaitUntil("only completed and cancelled remain") { element().allOf("a[data-record-id]").size == 2 }
                    assertEquals(listOf("t3", "t4"), element().allOf("a[data-record-id]").map { it.getAttribute("data-record-id") })
                }
            }
        }

    @Test
    fun aTreasurerGetsTheDraftFilter_butNoCreateButton(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession(AccountRole.TREASURER))
            val list = routeOf { tenderService().listTenders(null) }
            val defaults = routeOf { tenderService().getTenderDefaults() }
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == list -> req.answerWith(jsonOf(ListSerializer(TenderSummaryDto.serializer()), tenders))
                        req.rpcRoute == defaults ->
                            req.answerWith(
                                jsonOf(TenderDefaultsDto.serializer(), TenderDefaultsDto(canManage = false, canReadInternal = true)),
                            )
                        else -> req.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("tenders-treasurer") { root, element ->
                    renderTendersScreen(root)
                    awaitUntil("the draft filter") { element().radios().any { it.text().trim() == "Entwürfe" } }
                    assertEquals(1, calls.toRoute(defaults).size)
                    assertTrue(
                        element().querySelector("button[aria-controls='lapis-create-tender']") == null,
                        "the treasurer reads, never manages",
                    )
                }
            }
        }

    private val motionOptions =
        listOf(
            TenderMotionOptionDto("m1", "Haushalt 2099", "Mitgliederversammlung", selectable = true),
            TenderMotionOptionDto("m2", "Neuer Antrag", "Vorstand", selectable = false, reason = TenderMotionIneligibility.NOT_RESOLVED),
        )

    private suspend fun boardStub(calls: (String) -> String?): (RecordedRequest) -> StubResponse {
        val list = routeOf { tenderService().listTenders(null) }
        val defaults = routeOf { tenderService().getTenderDefaults() }
        val options = routeOf { tenderService().listTenderMotionOptions() }
        return { req ->
            when {
                !req.isRpc -> StubResponse()
                req.rpcRoute == list -> req.answerWith("[]")
                req.rpcRoute == defaults ->
                    req.answerWith(
                        jsonOf(
                            TenderDefaultsDto.serializer(),
                            TenderDefaultsDto(
                                defaultVisibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                                canManage = true,
                                canReadInternal = true,
                            ),
                        ),
                    )
                req.rpcRoute == options -> req.answerWith(jsonOf(ListSerializer(TenderMotionOptionDto.serializer()), motionOptions))
                else -> calls(req.rpcRoute)?.let { req.answerWith(it) } ?: req.answerWith("null")
            }
        }
    }

    @Test
    fun theBoardForm_isCollapsed_refusesAMotionThatCannotBeChosen_showsEuroDisabled_andExplainsTheVisibility(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val respond = boardStub { null }
            withFetchStub(respond = respond) { _ ->
                mountedForm("tenders-board-form") { root, element ->
                    renderTendersScreen(root)
                    awaitUntil("the create button") { element().querySelector("button[aria-controls='lapis-create-tender']") != null }
                    assertTrue(element().querySelector("[id='lapis-create-tender'] .lapis-form") == null, "the form starts collapsed")
                    val form = openCreateForm(element(), "lapis-create-tender")
                    // the motion that cannot be chosen is in the list WITH its reason
                    val select = form.controlOf("Beschluss, auf dem die Ausschreibung beruht") as HTMLSelectElement
                    val labels =
                        (0 until select.options.length).map {
                            select.options
                                .item(it)!!
                                .textContent
                                .orEmpty()
                        }
                    assertTrue(labels.any { it.contains("Haushalt 2099") && it.contains("Mitgliederversammlung") })
                    assertTrue(
                        labels.any { it.contains("Neuer Antrag") && it.contains("nicht wählbar: noch nicht beschlossen") },
                        "$labels",
                    )
                    // EUR: shown, disabled, with the reason in plain sight (not only as a tooltip)
                    val euro = form.radioNamed("Euro")
                    assertEquals("true", euro.getAttribute("aria-disabled"))
                    assertTrue(form.text().contains("Euro-Vergütung kommt später."))
                    // the explanation of the visibility follows the choice
                    assertTrue(form.text().contains("Bis zum Ende der Frist sieht niemand fremde Angebote."))
                    form.radioNamed("Offen").click()
                    assertTrue(form.text().contains("Alle Beteiligten sehen die Angebote der anderen sofort."))
                    form.radioNamed("Nur eigenes Angebot sichtbar").click()
                    assertTrue(form.text().contains("Wer den Zuschlag erhält, wird allen angezeigt."))
                    // the criterion too
                    form.radioNamed("Bestes Angebot").click()
                    assertTrue(form.text().contains("Vergabebeschluss nach Ablauf der Frist"))
                    // choosing the motion that cannot be chosen is refused at the field
                    form.chooseIn("Beschluss, auf dem die Ausschreibung beruht", "m2")
                    form.controlOf("Beschluss, auf dem die Ausschreibung beruht").dispatchEvent(Event("blur"))
                    awaitUntil("the refusal") { form.shownErrors().any { it.contains("kann nicht gewählt werden") } }
                }
            }
        }

    @Test
    fun theMarkdownPreview_isRenderedByTheServer(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val preview = routeOf { tenderService().previewTenderDescription("x") }
            val base = boardStub { route -> if (route == preview) "\"<p><strong>fett</strong></p>\"" else null }
            withFetchStub(respond = base) { calls ->
                mountedForm("tenders-preview") { root, element ->
                    renderTendersScreen(root)
                    awaitUntil("the button") { element().querySelector("button[aria-controls='lapis-create-tender']") != null }
                    val form = openCreateForm(element(), "lapis-create-tender")
                    form.typeInto("Beschreibung", "**fett**")
                    form.buttonNamed("Vorschau").click()
                    awaitUntil("the preview was asked for") { calls.toRoute(preview).size == 1 }
                    assertEquals("**fett**", calls.singleCall(preview).rpcParam(0) as String)
                    awaitUntil("the server's HTML is shown") { form.querySelector(".lapis-agreement-preview strong") != null }
                }
            }
        }

    private suspend fun fillValidForm(form: HTMLElement) {
        form.typeInto("Titel", "Website neu aufsetzen")
        form.chooseIn("Beschluss, auf dem die Ausschreibung beruht", "m1")
        form.typeInto("Beschreibung", "Die **Webseite** wird neu aufgesetzt.")
        form.typeInto("Erwartetes Ergebnis", "Eine lauffähige Seite")
        form.typeDateTimeInto("Frist", "2099-11-01T18:00")
    }

    @Test
    fun saveAsDraft_createsOnly_andPublishAsksFirst_thenCreatesAndPublishes_andAFailedPublishKeepsTheDraft(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val create =
                routeOf {
                    tenderService().createTender(
                        network.lapis.cloud.shared.domain.TenderCreateInput(
                            "m",
                            "t",
                            "d",
                            "e",
                            kotlinx.datetime.LocalDateTime(2099, 1, 1, 0, 0),
                            TenderAwardCriterion.LOWEST_PRICE,
                        ),
                    )
                }
            val publish = routeOf { tenderService().publishTender("t1") }
            val created = jsonOf(TenderDetailDto.serializer(), tenderDetail(tenderSummary(status = TenderStatus.DRAFT)))
            val base = boardStub { route -> if (route == create) created else null }
            var publishRefused = true
            withFetchStub(
                respond = { req ->
                    if (req.isRpc && req.rpcRoute == publish && publishRefused) {
                        serviceExceptionResult(req.json.id as Int, "network.lapis.cloud.shared.rpc.ConflictException")
                    } else {
                        base(req)
                    }
                },
            ) { calls ->
                mountedForm("tenders-save-publish") { root, element ->
                    renderTendersScreen(root)
                    awaitUntil("the button") { element().querySelector("button[aria-controls='lapis-create-tender']") != null }
                    val form = openCreateForm(element(), "lapis-create-tender")
                    fillValidForm(form)
                    form.buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("the draft was created") { calls.toRoute(create).size == 1 }
                    val sent = calls.singleCall(create).rpcParam(0)
                    assertEquals("m1", sent.bindingMotionId as String)
                    assertEquals("Website neu aufsetzen", sent.title as String)
                    assertEquals("MARKDOWN", sent.descriptionFormat as String)
                    assertEquals("HIDDEN_UNTIL_DEADLINE", sent.bidVisibility as String)
                    assertEquals(0, calls.toRoute(publish).size, "saving a draft does not publish")

                    // publishing: a question first
                    val again = openCreateForm(element(), "lapis-create-tender")
                    fillValidForm(again)
                    again.buttonNamed("Veröffentlichen").click()
                    awaitUntil("the question") { kotlinx.browser.document.querySelector(".modal.show") != null }
                    assertTrue(lastOpenModal().text().contains("Ausschreibung veröffentlichen?"))
                    assertEquals(0, calls.toRoute(publish).size, "nothing before the confirmation")
                    lastOpenModal().buttonNamed("Veröffentlichen").click()
                    awaitUntil("created and published") { calls.toRoute(create).size == 2 && calls.toRoute(publish).size == 1 }
                    // the refused publication keeps the saved draft (no second create)
                    assertEquals(2, calls.toRoute(create).size)
                    publishRefused = false
                }
            }
        }

    @Test
    fun aRefusedPublish_thenAnotherClick_updatesTheSavedDraftInsteadOfCreatingASecondOne(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val create =
                routeOf {
                    tenderService().createTender(
                        network.lapis.cloud.shared.domain.TenderCreateInput(
                            "m",
                            "t",
                            "d",
                            "e",
                            kotlinx.datetime.LocalDateTime(2099, 1, 1, 0, 0),
                            TenderAwardCriterion.LOWEST_PRICE,
                        ),
                    )
                }
            val update =
                routeOf {
                    tenderService().updateTenderDraft(
                        "t1",
                        network.lapis.cloud.shared.domain.TenderDraftUpdateInput(
                            "m",
                            "t",
                            "d",
                            "e",
                            kotlinx.datetime.LocalDateTime(2099, 1, 1, 0, 0),
                            TenderAwardCriterion.LOWEST_PRICE,
                        ),
                    )
                }
            val publish = routeOf { tenderService().publishTender("t1") }
            val created = jsonOf(TenderDetailDto.serializer(), tenderDetail(tenderSummary(status = TenderStatus.DRAFT)))
            val base = boardStub { route -> if (route == create || route == update) created else null }
            withFetchStub(
                respond = { req ->
                    if (req.isRpc && req.rpcRoute == publish) {
                        serviceExceptionResult(req.json.id as Int, "network.lapis.cloud.shared.rpc.ConflictException")
                    } else {
                        base(req)
                    }
                },
            ) { calls ->
                mountedForm("tenders-refused-publish-twice") { root, element ->
                    renderTendersScreen(root)
                    awaitUntil("the button") { element().querySelector("button[aria-controls='lapis-create-tender']") != null }
                    val form = openCreateForm(element(), "lapis-create-tender")
                    fillValidForm(form)
                    form.buttonNamed("Veröffentlichen").click()
                    awaitUntil("the question") { kotlinx.browser.document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Veröffentlichen").click()
                    awaitUntil("created, publish refused") { calls.toRoute(create).size == 1 && calls.toRoute(publish).size == 1 }

                    // the form is still open: the second try must update the saved draft
                    form.buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("the draft was updated") { calls.toRoute(update).size == 1 }
                    assertEquals(1, calls.toRoute(create).size, "no second draft")
                }
            }
        }
}
