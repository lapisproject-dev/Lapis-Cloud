package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderAwardMotionIneligibility
import network.lapis.cloud.shared.domain.TenderAwardMotionOptionDto
import network.lapis.cloud.shared.domain.TenderAwardPublicDto
import network.lapis.cloud.shared.domain.TenderAwardReadiness
import network.lapis.cloud.shared.domain.TenderBidBlockedReason
import network.lapis.cloud.shared.domain.TenderBidInput
import network.lapis.cloud.shared.domain.TenderBidView
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderStatus
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.202 -- the detail page: everything it shows or offers is a field of the DTO (the server's decision). First bid, change, withdraw, the four
 * reasons that close the bid card, what is said where bids are hidden, the board's conflict notice, the award icons only on `awardable`, the
 * award motion, cancel, acceptance, the cancelled tender and a friend without the link to the binding motion.
 */
class TenderDetailScreenDomTest {
    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun HTMLElement.buttonsNamed(label: String): List<HTMLElement> =
        allOf("button").filter { it.getAttribute("aria-label") == label }

    private fun json(dto: TenderDetailDto) = jsonOf(TenderDetailDto.serializer(), dto)

    /** Runs [body] against a stub where `getTender` answers [shown]; every other tender route answers [others] or `null`. */
    private fun withDetail(
        id: String,
        shown: TenderDetailDto,
        others: (RecordedRequest, Map<String, String>) -> String? = { _, _ -> null },
        refuse: Set<String> = emptySet(),
        body: suspend (HTMLElement, () -> HTMLElement, List<RecordedRequest>, Map<String, String>) -> Unit,
    ): Promise<Unit> =
        formTest {
            val routes =
                mapOf(
                    "get" to routeOf { tenderService().getTender("t1") },
                    "submit" to routeOf { tenderService().submitBid("t1", TenderBidInput(1.0.toDecimalForTest(), "x")) },
                    "withdraw" to routeOf { tenderService().withdrawBid("t1") },
                    "award" to routeOf { tenderService().awardTender(TenderAwardInput("t1", "b1")) },
                    "register" to routeOf { tenderService().registerAwardMotion("t1", "m") },
                    "awardOptions" to routeOf { tenderService().listAwardMotionOptions("t1") },
                    "cancel" to routeOf { tenderService().cancelTender("t1", "x") },
                    "accept" to
                        routeOf {
                            tenderService().recordAcceptance(
                                TenderAcceptanceInput("t1", TenderAcceptanceDecision.ACCEPTED),
                            )
                        },
                    "publish" to routeOf { tenderService().publishTender("t1") },
                )
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == routes.getValue("get") -> req.answerWith(json(shown))
                        refuse.any { routes.getValue(it) == req.rpcRoute } ->
                            serviceExceptionResult(req.json.id as Int, "network.lapis.cloud.shared.rpc.ConflictException")
                        else -> others(req, routes)?.let { req.answerWith(it) } ?: req.answerWith(json(shown))
                    }
                },
            ) { calls ->
                mountedForm(id) { root, element ->
                    renderTenderDetailScreen(root, "t1")
                    awaitUntil("the page is loaded") { element().text().contains("Erwartetes Ergebnis") }
                    body(element(), element, calls, routes)
                }
            }
        }

    private fun Double.toDecimalForTest() = this.toDecimal()

    private val open = tenderSummary()

    // ---- bids ----------------------------------------------------------------------------------------------------------------

    @Test
    fun theFirstBid_showsTheFormAtOnce_andSendsAPointDecimal(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession())
            withDetail(
                "tender-first-bid",
                tenderDetail(open) { copy(bidView = TenderBidView.NONE, minutesUntilDeadline = 3 * 24 * 60L + 5 * 60) },
            ) { el, _, calls, routes ->
                assertTrue(el.text().contains("noch 3 Tage und 5 Stunden"), "the remaining time comes from the server's minutes")
                assertTrue(el.text().contains("Beruht auf:") && el.text().contains("Haushalt 2099"))
                el.typeInto("Preis in LTR", "1.250,50")
                el.typeInto("Ihr Angebot", "  Ich mache das gern.  ")
                el.buttonNamed("Angebot abgeben").click()
                awaitUntil("submitBid") { calls.toRoute(routes.getValue("submit")).size == 1 }
                val sent = calls.singleCall(routes.getValue("submit"))
                assertEquals("t1", sent.rpcParam(0) as String)
                val input = sent.rpcParam(1)
                assertEquals(1250.5, (input.price).toString().toDouble())
                assertEquals("Ich mache das gern.", input.text as String)
            }
        }

    @Test
    fun aRefusalOfTheServer_appearsAsAGermanSentence_neverAsTheRawText(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession())
            initNotifications()
            withDetail("tender-refusal", tenderDetail(open), refuse = setOf("submit")) { el, _, calls, routes ->
                el.typeInto("Preis in LTR", "20")
                el.typeInto("Ihr Angebot", "Text")
                el.buttonNamed("Angebot abgeben").click()
                awaitUntil("submitBid") { calls.toRoute(routes.getValue("submit")).size == 1 }
                awaitUntil("the toast") { document.querySelectorAll(".toast").length > 0 }
                val toast =
                    document
                        .querySelectorAll(".toast")
                        .item(0)!!
                        .textContent
                        .orEmpty()
                assertTrue(toast.contains("Die Aktion ist gerade nicht möglich. Bitte laden Sie die Seite neu."), toast)
                assertFalse(toast.contains("simulated"), "the raw server text is never shown")
            }
        }

    @Test
    fun anExistingBid_isText_withChange_prefilled_cancel_andWithdrawAfterAQuestion(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession())
            val mine = tenderBid("b1", "Vera Vorstand", 20.0, mine = true, text = "Mein Konzept")
            withDetail(
                "tender-my-bid",
                tenderDetail(open) {
                    copy(myBid = mine, bids = listOf(mine), bidView = TenderBidView.OWN)
                },
            ) { el, element, calls, routes ->
                assertTrue(el.text().contains("Mein Konzept"))
                assertTrue(el.allOf("label").none { it.text().startsWith("Preis in LTR") }, "no form while the bid is shown as text")
                el.buttonNamed("Angebot ändern").click()
                awaitUntil("the form") { element().allOf("label").any { it.text().startsWith("Preis in LTR") } }
                assertEquals("20,00", (element().controlOf("Preis in LTR") as org.w3c.dom.HTMLInputElement).value)
                assertEquals("Mein Konzept", (element().controlOf("Ihr Angebot") as org.w3c.dom.HTMLTextAreaElement).value)
                element().buttonNamed("Abbrechen").click()
                awaitUntil("the form is gone") { element().allOf("label").none { it.text().startsWith("Preis in LTR") } }
                element().buttonNamed("Angebot zurückziehen").click()
                awaitUntil("the question") { document.querySelector(".modal.show") != null }
                assertEquals(0, calls.toRoute(routes.getValue("withdraw")).size)
                lastOpenModal().buttonNamed("Zurückziehen").click()
                awaitUntil("withdrawn") { calls.toRoute(routes.getValue("withdraw")).size == 1 }
            }
        }

    @Test
    fun everyBlockedReason_replacesTheFormByItsText(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession())
            val expected =
                mapOf(
                    TenderBidBlockedReason.GUEST_LTR to "Gäste führen ihr LTR-Konto auf ihrem Heimatserver",
                    TenderBidBlockedReason.NOT_PARTICIPANT to "Sie können auf diese Ausschreibung nicht bieten.",
                    TenderBidBlockedReason.NOT_OPEN to "Die Frist ist abgelaufen.",
                    TenderBidBlockedReason.MAX_BIDS_REACHED to "keine weiteren Angebote aufnehmen",
                )
            expected.forEach { (reason, sentence) ->
                val get = routeOf { tenderService().getTender("t1") }
                withFetchStub(
                    respond = { req ->
                        if (req.isRpc &&
                            req.rpcRoute == get
                        ) {
                            req.answerWith(json(tenderDetail(open) { copy(bidBlockedReason = reason) }))
                        } else {
                            StubResponse()
                        }
                    },
                ) { _ ->
                    mountedForm("tender-blocked-$reason") { root, element ->
                        renderTenderDetailScreen(root, "t1")
                        awaitUntil("the page") { element().text().contains("Erwartetes Ergebnis") }
                        assertTrue(element().text().contains(sentence), "$reason shows its sentence")
                        assertTrue(element().allOf("label").none { it.text().startsWith("Preis in LTR") }, "$reason: no form")
                        assertTrue(element().allOf("button").none { it.text().trim() == "Angebot abgeben" })
                    }
                }
            }
        }

    @Test
    fun whereBidsAreHidden_thePageSaysWhoSeesWhat(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession())

            data class Case(
                val dto: TenderDetailDto,
                val sentence: String,
            )
            val cases =
                listOf(
                    Case(
                        tenderDetail(tenderSummary(visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE)) {
                            copy(minutesUntilDeadline = 600)
                        },
                        "Die Angebote anderer sind bis",
                    ),
                    Case(
                        tenderDetail(tenderSummary(status = TenderStatus.CLOSED, visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE)),
                        "Die Angebote sehen nur die Bieter und der Vorstand.",
                    ),
                    Case(
                        tenderDetail(tenderSummary(visibility = TenderBidVisibility.OWN_ONLY)) {
                            copy(minutesUntilDeadline = 600)
                        },
                        "sieht jeder Bieter nur sein eigenes Angebot",
                    ),
                    Case(
                        tenderDetail(tenderSummary(visibility = TenderBidVisibility.OPEN)) {
                            copy(bidView = TenderBidView.ALL, minutesUntilDeadline = 600)
                        },
                        "Noch keine Angebote.",
                    ),
                )
            cases.forEachIndexed { index, case ->
                val get = routeOf { tenderService().getTender("t1") }
                withFetchStub(respond = { req ->
                    if (req.isRpc &&
                        req.rpcRoute == get
                    ) {
                        req.answerWith(json(case.dto))
                    } else {
                        StubResponse()
                    }
                }) { _ ->
                    mountedForm("tender-hidden-$index") { root, element ->
                        renderTenderDetailScreen(root, "t1")
                        awaitUntil("the page") { element().text().contains("Erwartetes Ergebnis") }
                        assertTrue(element().text().contains(case.sentence), "case $index: ${case.sentence}")
                    }
                }
            }
        }

    // ---- board ---------------------------------------------------------------------------------------------------------------

    private val closedBoard =
        tenderSummary(status = TenderStatus.CLOSED, count = 2)

    @Test
    fun aConflictedBoardMember_seesOnlyTheNotice_noBoardButtons(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val dto = tenderDetail(closedBoard) { copy(canManage = true, viewerIsConflicted = true, bidView = TenderBidView.ALL) }
            withDetail("tender-conflicted", dto) { el, _, _, _ ->
                assertTrue(el.text().contains("Sie haben selbst ein Angebot abgegeben"))
                assertTrue(
                    el.allOf("button").none {
                        it.text().trim() in
                            setOf("Ausschreibung absagen", "Vergabebeschluss festlegen", "Abnahme erfassen")
                    },
                )
            }
        }

    @Test
    fun theAwardIcon_sitsOnlyOnAwardableBids_andAsksForAReasonWhenTheServerSaysSo(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val a = tenderBid("b1", "Anna", 20.0, awardable = true)
            val b = tenderBid("b2", "Bernd", 25.0, awardable = false)
            val dto =
                tenderDetail(closedBoard) {
                    copy(
                        canManage = true,
                        bids = listOf(a, b),
                        bidView = TenderBidView.ALL,
                        awardReadiness = TenderAwardReadiness.READY,
                        awardJustificationRequired = true,
                    )
                }
            withDetail("tender-award", dto) { el, _, calls, routes ->
                assertEquals(1, el.allOf("button").count { it.getAttribute("aria-label")?.startsWith("Zuschlag erteilen an") == true })
                assertEquals(1, el.buttonsNamed("Zuschlag erteilen an Anna").size)
                el.buttonsNamed("Zuschlag erteilen an Anna").single().click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(modal.text().contains("Der Zuschlag kann nicht zurückgenommen werden."))
                modal.buttonNamed("Zuschlag erteilen").click()
                assertEquals(0, calls.toRoute(routes.getValue("award")).size, "a missing reason stops the call")
                modal.typeInto("Begründung", "Gleicher Preis, bessere Referenzen")
                modal.buttonNamed("Zuschlag erteilen").click()
                awaitUntil("awardTender") { calls.toRoute(routes.getValue("award")).size == 1 }
                val sent = calls.singleCall(routes.getValue("award")).rpcParam(0)
                assertEquals("b1", sent.bidId as String)
                assertEquals("Gleicher Preis, bessere Referenzen", sent.justification as String)
            }
        }

    @Test
    fun theAwardMotion_isPickedFromTheServersList_withReasonsForTheOthers(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val dto =
                tenderDetail(
                    tenderSummary(
                        status = TenderStatus.CLOSED,
                        criterion = network.lapis.cloud.shared.domain.TenderAwardCriterion.BEST_OFFER,
                    ),
                ) {
                    copy(canManage = true, bidView = TenderBidView.ALL, awardReadiness = TenderAwardReadiness.NEEDS_AWARD_MOTION)
                }
            val options =
                listOf(
                    TenderAwardMotionOptionDto("a1", "Vergabe Webseite", selectable = true),
                    TenderAwardMotionOptionDto(
                        "a2",
                        "Anderer Antrag",
                        selectable = false,
                        reason = TenderAwardMotionIneligibility.DECISION_STARTED,
                    ),
                )
            withDetail(
                "tender-award-motion",
                dto,
                others = { req, routes ->
                    if (req.rpcRoute ==
                        routes.getValue("awardOptions")
                    ) {
                        jsonOf(ListSerializer(TenderAwardMotionOptionDto.serializer()), options)
                    } else {
                        null
                    }
                },
            ) { el, element, calls, routes ->
                assertTrue(el.text().contains("Legen Sie unter Anträge einen Antrag für den Vergabebeschluss an."))
                awaitUntil("the picker") { element().allOf("label").any { it.text().startsWith("Vergabeantrag") } }
                val select = element().controlOf("Vergabeantrag") as org.w3c.dom.HTMLSelectElement
                val labels =
                    (0 until select.options.length).map {
                        select.options
                            .item(it)!!
                            .textContent
                            .orEmpty()
                    }
                assertTrue(labels.any { it.contains("Anderer Antrag") && it.contains("nicht wählbar: wird schon abgestimmt") }, "$labels")
                element().chooseIn("Vergabeantrag", "a1")
                element().buttonNamed("Vergabebeschluss festlegen").click()
                awaitUntil("registerAwardMotion") { calls.toRoute(routes.getValue("register")).size == 1 }
                assertEquals("a1", calls.singleCall(routes.getValue("register")).rpcParam(1) as String)
            }
        }

    @Test
    fun cancelAsksForAReason_emptyBlocks_andTheReasonIsSent(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            withDetail("tender-cancel", tenderDetail(open) { copy(canManage = true) }) { el, _, calls, routes ->
                el.buttonNamed("Ausschreibung absagen").click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(modal.text().contains("Der Grund ist für alle sichtbar, die die Ausschreibung sehen."))
                modal.buttonNamed("Absagen").click()
                assertEquals(0, calls.toRoute(routes.getValue("cancel")).size, "an empty reason blocks")
                modal.typeInto("Grund", "Der Beschluss wurde aufgehoben")
                modal.buttonNamed("Absagen").click()
                awaitUntil("cancelTender") { calls.toRoute(routes.getValue("cancel")).size == 1 }
                assertEquals("Der Beschluss wurde aufgehoben", calls.singleCall(routes.getValue("cancel")).rpcParam(1) as String)
            }
        }

    @Test
    fun theAcceptance_needsANoteWhenRejecting(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession(AccountRole.BOARD))
            val awarded =
                tenderSummary(
                    status = TenderStatus.AWARDED,
                ).copy(award = TenderAwardPublicDto("Anna", 20.0.toDecimalForTest(), TenderCurrency.LTR, LocalDateTime(2099, 11, 4, 9, 0)))
            withDetail("tender-accept", tenderDetail(awarded) { copy(canManage = true) }) { el, _, calls, routes ->
                assertTrue(el.text().contains("Anna hat den Zuschlag erhalten"))
                el.allOf("[role=radio]").first { it.text().trim() == "Ablehnen" }.click()
                el.buttonNamed("Abnahme erfassen").click()
                awaitUntil("the field error") { el.shownErrors().any { it.contains("Bei einer Ablehnung ist eine Notiz nötig.") } }
                assertEquals(0, calls.toRoute(routes.getValue("accept")).size)
                el.typeInto("Notiz", "Nachbessern bitte")
                el.buttonNamed("Abnahme erfassen").click()
                awaitUntil("recordAcceptance") { calls.toRoute(routes.getValue("accept")).size == 1 }
                val sent = calls.singleCall(routes.getValue("accept")).rpcParam(0)
                assertEquals("REJECTED", sent.decision as String)
                assertEquals("Nachbessern bitte", sent.reason as String)
            }
        }

    // ---- other readers -------------------------------------------------------------------------------------------------------

    @Test
    fun aCancelledTender_showsTheReasonInsteadOfTheStatusRow(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession())
            val cancelled = tenderDetail(tenderSummary(status = TenderStatus.CANCELLED)) { copy(cancelReason = "Beschluss aufgehoben") }
            withDetail("tender-cancelled", cancelled) { el, _, _, _ ->
                assertTrue(el.text().contains("Abgesagt") && el.text().contains("Beschluss aufgehoben"))
                assertTrue(el.allOf("[aria-current=step]").isEmpty())
                assertTrue(el.allOf("button").none { it.text().trim() == "Ausschreibung absagen" })
            }
        }

    @Test
    fun theStatusRow_marksTheCurrentStep(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession())
            withDetail("tender-steps", tenderDetail(open)) { el, _, _, _ ->
                val steps = el.allOf("[role=listitem]").map { it.text().trim() }
                assertEquals(listOf("Entwurf", "Veröffentlicht", "Frist abgelaufen", "Vergeben", "Abgenommen"), steps)
                assertEquals(
                    "Veröffentlicht",
                    el
                        .allOf("[aria-current=step]")
                        .single()
                        .text()
                        .trim(),
                )
            }
        }

    @Test
    fun aFriend_getsNoLinkToTheBindingMotion_andAMarkdownDescriptionIsTheServersHtml(): Promise<Unit> =
        run {
            AppState.setSession(tenderSession(status = MemberStatus.FRIEND))
            val dto =
                tenderDetail(open) {
                    copy(
                        bindingMotionTitle = "",
                        descriptionFormat = TenderDescriptionFormat.MARKDOWN,
                        descriptionHtml = "<p>Das ist <strong>wichtig</strong></p>",
                    )
                }
            withDetail("tender-friend", dto) { el, _, _, _ ->
                assertFalse(el.text().contains("Beruht auf:"))
                assertTrue(el.allOf("a").none { it.getAttribute("href")?.contains("/motions/") == true })
                assertNotNull(el.querySelector(".lapis-agreement-body strong"), "the server's HTML is shown")
            }
        }

    @Test
    fun theBackButton_navigatesToTheList(): Promise<Unit> =
        formTest {
            AppState.setSession(tenderSession())
            val get = routeOf { tenderService().getTender("t1") }
            withFetchStub(respond = { req ->
                if (req.isRpc &&
                    req.rpcRoute == get
                ) {
                    req.answerWith(json(tenderDetail(open)))
                } else {
                    StubResponse()
                }
            }) { _ ->
                mountedForm("tender-back") { root, element ->
                    val target = mutableListOf<String>()
                    renderTenderDetailScreen(root, "t1", navigate = { target += it })
                    awaitUntil("the page") { element().text().contains("Erwartetes Ergebnis") }
                    element().buttonNamed("Zur Übersicht").click()
                    assertEquals(listOf("/tenders"), target)
                    assertEquals("Ausschreibung", (element().querySelector("h1") as HTMLElement).text().trim())
                }
            }
        }
}
