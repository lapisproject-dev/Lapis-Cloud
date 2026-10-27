package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DynamicListExplanationDto
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MailingSendProgressDto
import network.lapis.cloud.shared.domain.RecipientPreviewDto
import network.lapis.cloud.shared.domain.RuleExplanationItem
import network.lapis.cloud.shared.domain.RuleExplanationKind
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.IMailingService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.111 "Dynamische Mailinglisten" -- the rule builder of the create form, the badges and the opt-out of automatic lists, the
 * progress of a send that waits for its recipients. Drives the REAL mounted screen with a stubbed `fetch`.
 */
class DynamicMailingListBuilderDomTest {
    private val formId = "mailing-list-create"

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun dynamicList(
        id: String,
        name: String,
        mandatory: Boolean = false,
        subscribed: Boolean = true,
        optedOut: Boolean = false,
        explanation: DynamicListExplanationDto? =
            DynamicListExplanationDto(
                op = RuleOp.ALL,
                items = listOf(RuleExplanationItem(RuleExplanationKind.COMMITTEE, labels = listOf("Vorstandsrunde"))),
            ),
    ) = MailingListDto(
        id = id,
        name = name,
        description = null,
        createdBy = "x",
        subscriberCount = 0,
        isSubscribedByCurrentMember = subscribed,
        kind = MailingListKind.DYNAMIC,
        mandatory = mandatory,
        ruleExplanation = explanation,
        currentMemberOptedOutAt = if (optedOut) LocalDateTime(2026, 10, 1, 10, 0) else null,
    )

    private fun listJson(rows: List<MailingListDto>) = jsonOf(ListSerializer(MailingListDto.serializer()), rows)

    private fun previewJson(
        eligible: Int,
        optedOut: Int = 0,
        noEmail: Int = 0,
    ) = jsonOf(RecipientPreviewDto.serializer(), RecipientPreviewDto(eligible, optedOut, noEmail))

    @Test
    fun theKindSwitch_showsTheBuilderAndHidesItAgain(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
                mountedForm("dyn-kind") { root, element ->
                    renderMailingListsScreen(root, "#/mailing-lists?tab=manage")
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    val host = openCreateForm(element(), formId)
                    assertFalse(host.shows("Vorlagen"), "manual is the default: no builder")
                    assertTrue(host.shows("Die Art lässt sich später nicht ändern."))
                    host.buttonNamed("Automatisch (nach Regel)").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    assertTrue(host.shows("Pflichtliste (keine Abmeldung möglich)"))
                    assertTrue(host.shows("Nur für Vereinsmitteilungen wie die Einladung zur Mitgliederversammlung."))
                    assertTrue(host.shows("Das Merkmal lässt sich später nicht ändern."), "the warning says it is permanent")
                    assertTrue(host.shows("Immer: nur aktive Mitglieder mit E-Mail-Adresse, ohne Abmeldung."))
                    host.buttonNamed("Manuell").click()
                    awaitUntil("the builder is gone") { !host.shows("Vorlagen") }
                    assertFalse(host.shows("Pflichtliste (keine Abmeldung möglich)"))
                }
            }
        }

    @Test
    fun presetsFillTheRule_theLimitDisablesAdd_andEveryRowCanBeRemovedByName(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
                mountedForm("dyn-rows") { root, element ->
                    renderMailingListsScreen(root, "#/mailing-lists?tab=manage")
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    val host = openCreateForm(element(), formId)
                    host.buttonNamed("Automatisch (nach Regel)").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    val region = assertNotNull(host.querySelector("[aria-live=polite]") as? HTMLElement, "the live region")
                    assertEquals("status", region.getAttribute("role"))

                    host.buttonNamed("Neue Mitglieder (30 Tage)").click()
                    awaitUntil("the preset created a day condition") { (host.controlOf("Tage") as HTMLInputElement).value == "30" }
                    assertTrue(host.shows("Zahl von 1 bis 3650"))

                    val add = host.buttonNamed("Bedingung hinzufügen") as HTMLButtonElement
                    repeat(7) { add.click() }
                    awaitUntil("eight rows") { host.allOf("button[aria-label='Bedingung entfernen']").size == 8 }
                    assertTrue(add.disabled, "the ninth condition cannot be added")
                    assertTrue(host.shows("Höchstens 8 Bedingungen."))
                    host.allOf("button[aria-label='Bedingung entfernen']").forEach {
                        assertEquals("Bedingung entfernen", it.getAttribute("title"), "icon-only buttons carry title and aria-label")
                    }
                    host.allOf("button[aria-label='Bedingung entfernen']").last().click()
                    awaitUntil("add is possible again") { !(host.buttonNamed("Bedingung hinzufügen") as HTMLButtonElement).disabled }
                }
            }
        }

    @Test
    fun theLivePreview_isDebounced_oneRequestPerPause_andShowsTheCounts(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val preview = routeOf { rpcService<IMailingService>().previewDynamicRecipients("{}", null, false) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == preview -> request.answerWith(previewJson(eligible = 7, optedOut = 2, noEmail = 1))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("dyn-debounce") { root, element ->
                    renderMailingListsScreen(root, "#/mailing-lists?tab=manage")
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    val host = openCreateForm(element(), formId)
                    host.buttonNamed("Automatisch (nach Regel)").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    delay(900) // lets the preview of the empty starting rule settle
                    val before = calls.toRoute(preview).size
                    host.buttonNamed("Neue Mitglieder (30 Tage)").click()
                    awaitUntil("the day field") { (host.controlOf("Tage") as HTMLInputElement).value == "30" }
                    // fast typing: three changes inside one pause
                    host.typeInto("Tage", "4")
                    host.typeInto("Tage", "45")
                    host.typeInto("Tage", "450")
                    awaitUntil("the answer is shown") { host.shows("7 Empfänger werden erreicht") }
                    assertTrue(host.shows("Nicht enthalten: 2 abgemeldet, 1 ohne E-Mail-Adresse"))
                    delay(900)
                    val sent = calls.toRoute(preview).drop(before)
                    assertEquals(1, sent.size, "one request for the whole burst, not one per keystroke")
                    assertTrue((sent.single().rpcParam(0) as String).contains("450"), "and it carries the final value")
                }
            }
        }

    @Test
    fun aLateAnswerForAnOlderRule_neverOverwritesTheCurrentOne(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val preview = routeOf { rpcService<IMailingService>().previewDynamicRecipients("{}", null, false) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == preview -> {
                            val rule = request.rpcParam(0) as String
                            if (rule.contains("\"days\":30")) {
                                // the OLD rule answers late and with a different number
                                StubResponse(status = 200, text = request.answerWith(previewJson(eligible = 111)).text, delayMs = 1500)
                            } else {
                                request.answerWith(previewJson(eligible = 5))
                            }
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("dyn-stale") { root, element ->
                    renderMailingListsScreen(root, "#/mailing-lists?tab=manage")
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    val host = openCreateForm(element(), formId)
                    host.buttonNamed("Automatisch (nach Regel)").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    host.buttonNamed("Neue Mitglieder (30 Tage)").click()
                    awaitUntil("the day field") { (host.controlOf("Tage") as HTMLInputElement).value == "30" }
                    delay(700) // the request for 30 days is out and pending
                    host.typeInto("Tage", "45")
                    awaitUntil("the current answer") { host.shows("5 Empfänger werden erreicht") }
                    delay(1800) // the stale answer has arrived by now
                    assertTrue(host.shows("5 Empfänger werden erreicht"), host.textContent.orEmpty())
                    assertFalse(host.shows("111 Empfänger"))
                }
            }
        }

    @Test
    fun anAutomaticList_isCreatedWithItsRuleAndTheMandatoryFlag(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val create = routeOf { rpcService<IMailingService>().createDynamicMailingList("n", null, "{}", false) }
            val preview = routeOf { rpcService<IMailingService>().previewDynamicRecipients("{}", null, false) }
            val rows = mutableListOf<MailingListDto>()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == preview -> request.answerWith(previewJson(eligible = 3))
                        request.rpcRoute == create -> {
                            rows += dynamicList("new-dyn", "Neue Mitglieder", mandatory = true)
                            request.answerWith(jsonOf(MailingListDto.serializer(), rows.last()))
                        }
                        else -> request.answerWith(listJson(rows.toList()))
                    }
                },
            ) { calls ->
                mountedForm("dyn-create") { root, element ->
                    renderMailingListsScreen(root, "#/mailing-lists?tab=manage")
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    val host = openCreateForm(element(), formId)
                    host.buttonNamed("Automatisch (nach Regel)").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    host.buttonNamed("Neue Mitglieder (30 Tage)").click()
                    awaitUntil("the day field") { (host.controlOf("Tage") as HTMLInputElement).value == "30" }
                    host.typeInto("Name", "Neue Mitglieder")
                    host.tick("Pflichtliste (keine Abmeldung möglich)")
                    host.buttonNamed("Anlegen").click()
                    awaitUntil("the create call") { calls.toRoute(create).size == 1 }
                    val call = calls.singleCall(create)
                    assertEquals("Neue Mitglieder", call.rpcParam(0) as String)
                    val ruleJson = call.rpcParam(2) as String
                    assertTrue(ruleJson.contains("JOINED_WITHIN_DAYS") && ruleJson.contains("\"days\":30"), ruleJson)
                    assertEquals(true, call.rpcParam(3), "the mandatory flag")
                    awaitUntil("the form folded back") { !element().shows("Pflichtliste (keine Abmeldung möglich)") }
                }
            }
        }

    @Test
    fun anIncompleteRow_blocksTheSubmit_andNothingIsSent(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val create = routeOf { rpcService<IMailingService>().createDynamicMailingList("n", null, "{}", false) }
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) { calls ->
                mountedForm("dyn-incomplete") { root, element ->
                    renderMailingListsScreen(root, "#/mailing-lists?tab=manage")
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    val host = openCreateForm(element(), formId)
                    host.buttonNamed("Automatisch (nach Regel)").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    host.buttonNamed("Bedingung hinzufügen").click() // a role row with no role ticked
                    awaitUntil("the row") { host.allOf("button[aria-label='Bedingung entfernen']").size == 1 }
                    host.typeInto("Name", "Unvollständig")
                    host.buttonNamed("Anlegen").click()
                    delay(600)
                    assertEquals(0, calls.toRoute(create).size)
                    assertTrue(host.shows("Bitte bei jeder Bedingung einen gültigen Wert festlegen."))
                }
            }
        }

    @Test
    fun theSelfServiceRows_showBadgesWithText_theReason_andOnlyOffer_whatIsAllowed(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val optOut = routeOf { rpcService<IMailingService>().optOutOfDynamicList("x") }
            val rows =
                listOf(
                    dynamicList("l-normal", "Rundbrief"),
                    dynamicList("l-mandatory", "Einladungen", mandatory = true),
                    dynamicList("l-out", "Newsletter", subscribed = false, optedOut = true),
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == optOut -> request.answerWith("null")
                        else -> request.answerWith(listJson(rows))
                    }
                },
            ) { calls ->
                mountedForm("dyn-self") { root, element ->
                    renderMailingListsScreen(root)
                    awaitUntil("the lists") { element().shows("Rundbrief") && element().shows("Einladungen") }
                    assertEquals(3, element().allOf(".badge").count { it.textContent?.trim() == "Automatisch" })
                    assertEquals(1, element().allOf(".badge").count { it.textContent?.trim() == "Pflichtliste" })
                    assertTrue(element().shows("Sie erhalten diese Liste, weil Sie Mitglied im Gremium Vorstandsrunde sind."))
                    assertTrue(element().shows("Pflichtliste: keine Abmeldung möglich"))
                    assertEquals(
                        1,
                        element().allOf("button").count { it.getAttribute("aria-label") == "Von der Liste abmelden" },
                        "only the normal list",
                    )
                    assertEquals(1, element().allOf("button").count { it.getAttribute("aria-label") == "Wieder anmelden" })
                    assertFalse(element().shows("Abonnenten)"), "no subscriber count for an automatic list")
                    assertFalse(element().shows("Öffnungen zählen"), "no tracking switches for an automatic list")
                    element().iconButtonNamed("Von der Liste abmelden").click()
                    awaitUntil("the opt-out call") { calls.toRoute(optOut).size == 1 }
                    assertEquals("l-normal", calls.singleCall(optOut).rpcParam(0) as String)
                }
            }
        }

    @Test
    fun aSendWaitingForItsRecipients_isNotFinished_andSaysSo(): Promise<Unit> =
        formTest {
            mountedForm("dyn-progress") { root, element ->
                var polls = 0
                var finished = 0

                fun dto(
                    pending: Int,
                    total: Int,
                    awaiting: Boolean,
                ) = MailingSendProgressDto(
                    "m1",
                    total,
                    total - pending,
                    0,
                    0,
                    0,
                    pending,
                    null,
                    null,
                    if (pending >
                        0
                    ) {
                        60L
                    } else {
                        null
                    },
                    awaiting,
                )
                val panel =
                    MailingSendProgressPanel(
                        "m1",
                        onFinished = { finished++ },
                        pollIntervalMs = 20,
                        fetch = {
                            polls++
                            when {
                                polls <= 3 -> dto(pending = 0, total = 0, awaiting = true)
                                polls <= 6 -> dto(pending = 2, total = 2, awaiting = false)
                                else -> dto(pending = 0, total = 2, awaiting = false)
                            }
                        },
                    )
                root.addWithLifecycle(panel, onInsert = { panel.start() }, onDestroy = { panel.stop() })
                awaitUntil("the waiting text") { element().textContent.orEmpty().contains("Empfänger werden ermittelt …") }
                assertEquals(0, finished, "no pending rows yet is NOT finished")
                awaitUntil("finished once the rows were sent") { finished == 1 }
                delay(200)
                assertEquals(1, finished)
            }
        }

    @Test
    fun explanations_areOneNaturalSentenceForAMember_andTheWholeRuleForTheBoard() {
        fun item(
            kind: RuleExplanationKind,
            labels: List<String> = emptyList(),
            days: Int? = null,
        ) = RuleExplanationItem(kind, labels, days)
        assertEquals(
            "Sie erhalten diese Liste, weil Sie Mitglied im Gremium Beirat sind.",
            explainRule(
                DynamicListExplanationDto(RuleOp.ANY, listOf(item(RuleExplanationKind.COMMITTEE, listOf("Beirat")))),
                forMember = true,
            ),
        )
        assertEquals(
            "Sie erhalten diese Liste, weil Sie die Rolle Vorstand oder Schatzmeister haben.",
            explainRule(
                DynamicListExplanationDto(RuleOp.ANY, listOf(item(RuleExplanationKind.ROLE, listOf("BOARD", "TREASURER")))),
                forMember = true,
            ),
        )
        assertEquals(
            "Aktive Mitglieder, auf die zutrifft: Beitritt in den letzten 30 Tagen oder Mitgliedschaftsstufe Voll.",
            explainRule(
                DynamicListExplanationDto(
                    RuleOp.ANY,
                    listOf(item(RuleExplanationKind.JOINED_WITHIN_DAYS, days = 30), item(RuleExplanationKind.TIER, listOf("Voll"))),
                ),
                forMember = false,
            ),
        )
        assertEquals("Alle aktiven Mitglieder.", explainRule(DynamicListExplanationDto(RuleOp.ALL, emptyList()), forMember = false))
        assertEquals(
            "Sie erhalten diese Liste, weil Sie ein aktives Mitglied sind.",
            explainRule(DynamicListExplanationDto(RuleOp.ALL, emptyList()), forMember = true),
        )
    }

    @Test
    fun presets_areTheFourExpectedOnes_andTheCommitteeOneNeedsACommittee() {
        assertEquals(4, MailingRulePresets.ALL.size)
        assertEquals(null, MailingRulePresets.rule(MailingRulePreset.COMMITTEE, firstCommitteeId = null))
        assertNotNull(MailingRulePresets.rule(MailingRulePreset.COMMITTEE, firstCommitteeId = "11111111-1111-1111-1111-111111111111"))
        assertEquals(0, MailingRulePresets.rule(MailingRulePreset.ACTIVE_MEMBERS, null)!!.conditions.size)
        assertEquals(RuleOp.ANY, MailingRulePresets.rule(MailingRulePreset.BOARD_ROLES, null)!!.op)
        assertFalse(MailingRulePresets.ALL.any { MailingRulePresets.label(it).contains("Beitrag") }, "no 'Beitrag offen' preset")
    }
}
