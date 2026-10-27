package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderResponseDto
import network.lapis.cloud.shared.domain.MemberStatus
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.145 -- the member screen of the finder: the status gate, the two modes, the consent flow with the stale text, the same answer for every request,
 * incoming requests without a sender, the one place where a name appears, and the allowlist of what may be in the DOM.
 */
class MemberFinderScreenDomTest {
    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun withPage(
        status: MemberStatus = MemberStatus.ACTIVE,
        prepare: FinderServer.() -> Unit = {},
        block: suspend FinderServer.(HTMLElement, List<RecordedRequest>) -> Unit,
    ) {
        val routes = finderRoutes()
        val server = FinderServer(routes)
        server.prepare()
        withFetchStub(respond = { server.respond(it) }) { requests ->
            mountedForm("member-finder") { root, element ->
                renderMemberFinderContent(root, status)
                server.block(element(), requests)
            }
        }
    }

    private suspend fun FinderServer.awaitParticipation(element: HTMLElement) =
        awaitUntil("the participation is shown") { element.text().contains("Ihre Teilnahme") && element.text().contains("Anfrage senden") }

    private fun HTMLElement.radios(): List<HTMLInputElement> = allOf("input[type=radio]").map { it as HTMLInputElement }

    private fun HTMLElement.live(): HTMLElement =
        assertNotNull(querySelector("[aria-live=polite].visually-hidden") as? HTMLElement, "no live region")

    // ---- gate and states ----

    @Test
    fun aMemberWhoIsNotActive_makesNoCallAtAll(): Promise<Unit> =
        formTest {
            withPage(status = MemberStatus.FRIEND) { element, requests ->
                assertTrue(element.text().contains("Der Mitglieder-Finder steht nur Mitgliedern zur Verfügung."))
                settleAppScope(300)
                assertEquals(0, requests.rpcCount, "not a single call for a non-ACTIVE status")
            }
        }

    @Test
    fun whenTheSwitchIsOff_onlyTheStateIsRead(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(offered = false, optedIn = false) }) { element, requests ->
                awaitUntil("the hint") { element.text().contains("nicht eingeschaltet") }
                assertEquals(1, requests.rpcCount)
                assertEquals(1, requests.toRoute(routes.getState).size)
            }
        }

    @Test
    fun notEligible_explainsAndLinksToTheProfile_withoutLoadingTheConsentText(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(eligible = false, optedIn = false) }) { element, requests ->
                awaitUntil("the box") { element.text().contains("Zum Profil") }
                val link = assertNotNull(element.querySelector("a[href='#${Routes.DSGVO_RIGHTS}']"))
                assertTrue(link.textContent.orEmpty().contains("Zum Profil"))
                assertTrue(element.text().contains("Frühere Einwilligung widerrufen"))
                assertEquals(0, requests.toRoute(routes.getConsentText).size)
            }
        }

    // ---- mode A ----

    @Test
    fun modeA_nothingIsPreselected_andTheButtonNeedsBothChoices(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(optedIn = false) }) { element, _ ->
                awaitUntil("the consent text") { element.text().contains("Einwilligung v1.") }
                val frame = assertNotNull(element.querySelector(".lapis-agreement-frame") as? HTMLElement)
                assertEquals("region", frame.getAttribute("role"))
                assertEquals("0", frame.getAttribute("tabindex"))
                assertEquals("Einwilligungstext", frame.getAttribute("aria-label"))
                val radios = element.radios()
                assertEquals(3, radios.size)
                assertTrue(radios.none { it.checked }, "no interest is preselected")
                val join = element.buttonNamed("Teilnehmen") as HTMLButtonElement
                assertTrue(join.disabled)
                radios.first().click()
                assertTrue((element.buttonNamed("Teilnehmen") as HTMLButtonElement).disabled, "the radio alone is not enough")
                element.tick("Ich bestätige, dass ich volljährig bin.")
                awaitUntil("the button is unlocked") { !(element.buttonNamed("Teilnehmen") as HTMLButtonElement).disabled }
            }
        }

    @Test
    fun modeA_aMarkdownConsentText_isTheServersHtml_inTheSameReadingFrame(): Promise<Unit> =
        formTest {
            withPage(prepare = {
                state = finderState(optedIn = false)
                consent =
                    finderConsent().copy(
                        body = "Quelltext **roh**",
                        format = network.lapis.cloud.shared.domain.AgreementTextFormat.MARKDOWN,
                        renderedHtml = "<h2>Einwilligung</h2>\n<p>Ich stimme <strong>ausdrücklich</strong> zu.</p>\n",
                    )
            }) { element, _ ->
                awaitUntil("the html body") { element.querySelector(".lapis-agreement-frame .lapis-agreement-body") != null }
                val frame = assertNotNull(element.querySelector(".lapis-agreement-frame") as? HTMLElement)
                assertEquals("region", frame.getAttribute("role"))
                assertEquals("Einwilligungstext", frame.getAttribute("aria-label"))
                assertEquals("ausdrücklich", frame.querySelector("strong")?.textContent)
                assertFalse(frame.textContent.orEmpty().contains("Quelltext"))
            }
        }

    @Test
    fun joining_sendsTheVersionAndHashOfTheShownText_andShowsTheParticipation(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(optedIn = false) }) { element, requests ->
                awaitUntil("the consent text") { element.text().contains("Einwilligung v1.") }
                element.radios()[1].click()
                element.tick("Ich bestätige, dass ich volljährig bin.")
                awaitUntil("unlocked") { !(element.buttonNamed("Teilnehmen") as HTMLButtonElement).disabled }
                element.buttonNamed("Teilnehmen").click()
                awaitUntil("the call") { requests.toRoute(routes.optIn).size == 1 }
                val call = requests.toRoute(routes.optIn).single()
                assertEquals("v1", call.rpcParam(0) as String)
                assertEquals("sha-v1", call.rpcParam(1) as String)
                assertEquals(MemberFinderInterest.MEETUP.name, call.rpcParam(2) as String)
                assertEquals(true, call.rpcParam(3) as Boolean)
                awaitUntil("the participation") { element.text().contains("Ihre Teilnahme") }
                assertEquals("Sie nehmen jetzt teil.", element.live().text())
            }
        }

    @Test
    fun aStaleText_isReloaded_theChoicesStay_andTheNewHashIsSent(): Promise<Unit> =
        formTest {
            withPage(prepare = {
                state = finderState(optedIn = false)
                outdatedOnceThen = finderConsent("v2")
            }) { element, requests ->
                awaitUntil("the consent text") { element.text().contains("Einwilligung v1.") }
                element.radios()[0].click()
                element.tick("Ich bestätige, dass ich volljährig bin.")
                awaitUntil("unlocked") { !(element.buttonNamed("Teilnehmen") as HTMLButtonElement).disabled }
                element.buttonNamed("Teilnehmen").click()
                awaitUntil("the new text and the notice above it") {
                    element.text().contains("Einwilligung v2.") && element.text().contains("Der Text wurde aktualisiert, bitte neu lesen.")
                }
                assertTrue(element.radios()[0].checked, "the interest stays")
                assertTrue((element.controlOf("Ich bestätige, dass ich volljährig bin.") as HTMLInputElement).checked, "the box stays")
                awaitUntil("the button is usable again") { !(element.buttonNamed("Teilnehmen") as HTMLButtonElement).disabled }
                element.buttonNamed("Teilnehmen").click()
                awaitUntil("the second call") { requests.toRoute(routes.optIn).size == 2 }
                assertEquals("v2", requests.toRoute(routes.optIn).last().rpcParam(0) as String)
                assertEquals("sha-v2", requests.toRoute(routes.optIn).last().rpcParam(1) as String)
            }
        }

    @Test
    fun anOutdatedConsentInTheState_showsTheNoticeAtOnce_andOffersTheWithdrawal(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(optedIn = false, consentOutdated = true) }) { element, _ ->
                awaitUntil("the notice") { element.text().contains("Der Text wurde aktualisiert, bitte neu lesen.") }
                assertTrue(element.text().contains("Einwilligung widerrufen"))
            }
        }

    @Test
    fun aCooldown_locksTheButton_andNamesTheTime(): Promise<Unit> =
        formTest {
            val until = kotlinx.datetime.LocalDateTime(2099, 1, 2, 10, 30)
            withPage(prepare = { state = finderState(optedIn = false, nextOptInAllowedAt = until) }) { element, _ ->
                awaitUntil("the consent text") { element.text().contains("Einwilligung v1.") }
                element.radios()[0].click()
                element.tick("Ich bestätige, dass ich volljährig bin.")
                settleAppScope(300)
                assertTrue((element.buttonNamed("Teilnehmen") as HTMLButtonElement).disabled)
                assertTrue(element.text().contains("Eine erneute Teilnahme ist ab ${formatSystemDateTime(until)} möglich."))
            }
        }

    // ---- mode B ----

    @Test
    fun modeB_withEmptyLists_showsOnlyTheStatusAndTheSendForm(): Promise<Unit> =
        formTest {
            withPage { element, _ ->
                awaitParticipation(element)
                awaitAppScopeIdle("all lists loaded")
                val text = element.text()
                listOf("Eingehende Anfragen", "Verbindungen", "Gesendete Anfragen", "Blockierte").forEach {
                    assertFalse(text.contains(it), "no heading for the empty list '$it'")
                }
                assertTrue(text.contains("Ihre Region: Postleitzahlbereich 38"))
            }
        }

    @Test
    fun theBand_isNeverANumber(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(band = MemberFinderBand.B10_19) }) { element, _ ->
                awaitParticipation(element)
                assertTrue(element.text().contains("10 bis 19 Teilnehmende"))
                assertNoNumberToken(element.text(), "12")
            }
            withPage(prepare = { state = finderState(band = null) }) { element, _ ->
                awaitParticipation(element)
                assertTrue(element.text().contains("Noch nicht sichtbar (weniger als fünf Teilnehmende)"))
            }
        }

    @Test
    fun leaving_asksFirst_focusIsOnCancel_andEscapeChangesNothing(): Promise<Unit> =
        formTest {
            withPage { element, requests ->
                awaitParticipation(element)
                element.buttonNamed("Nicht mehr teilnehmen").click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                escape(modal)
                awaitUntil("closed") { document.querySelector(".modal.show") == null }
                assertEquals(0, requests.toRoute(routes.optOut).size)

                element.buttonNamed("Nicht mehr teilnehmen").click()
                awaitUntil("the dialog again") { document.querySelector(".modal.show") != null }
                lastOpenModal().buttonNamed("Nicht mehr teilnehmen").click()
                awaitUntil("mode A") { element.text().contains("Woran haben Sie Interesse?") }
                assertEquals(1, requests.toRoute(routes.optOut).size)
                assertEquals("Ihre Teilnahme ist beendet.", element.live().text())
            }
        }

    // ---- sending a request ----

    @Test
    fun theInterest_decidesWhetherThePurposeIsAChoice(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(interest = MemberFinderInterest.MEETUP) }) { element, _ ->
                awaitParticipation(element)
                assertEquals(0, element.radios().size, "a single interest needs no choice")
                assertTrue(
                    element.text().contains(
                        "So lautet Ihre Anfrage: Ein Mitglied aus Ihrer Region möchte sich zu einem Stammtisch treffen.",
                    ),
                )
            }
            withPage(prepare = { state = finderState(interest = MemberFinderInterest.BOTH) }) { element, _ ->
                awaitParticipation(element)
                val radios = element.radios()
                assertEquals(2, radios.size)
                assertTrue(radios.none { it.checked }, "no default purpose")
                assertTrue((element.buttonNamed("Anfrage senden") as HTMLButtonElement).disabled)
                radios[0].click()
                awaitUntil("sendable") { !(element.buttonNamed("Anfrage senden") as HTMLButtonElement).disabled }
            }
        }

    @Test
    fun theNote_isCountedAsTheServerCountsIt_andLockedOverTheLimit(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(interest = MemberFinderInterest.MEETUP) }) { element, _ ->
                awaitParticipation(element)
                element.typeInto("Notiz (freiwillig)", "a\n\nb")
                awaitUntil("the counter") { element.text().contains("3/200") }
                element.typeInto("Notiz (freiwillig)", "x".repeat(185))
                awaitUntil("the warning") { element.allOf(".text-warning").any { it.text().contains("185/200") } }
                assertFalse((element.buttonNamed("Anfrage senden") as HTMLButtonElement).disabled)
                element.typeInto("Notiz (freiwillig)", "x".repeat(201))
                awaitUntil("locked") { (element.buttonNamed("Anfrage senden") as HTMLButtonElement).disabled }
            }
        }

    @Test
    fun fiveOpenRequests_lockTheSendButtonBeforehand(): Promise<Unit> =
        formTest {
            withPage(prepare = { state = finderState(interest = MemberFinderInterest.MEETUP, pendingOutgoing = 5) }) { element, _ ->
                awaitParticipation(element)
                assertTrue(element.text().contains("5 von 5 offenen Anfragen"))
                assertTrue((element.buttonNamed("Anfrage senden") as HTMLButtonElement).disabled)
            }
        }

    @Test
    fun everySendAnswer_looksTheSame_andComesAfterTheListWasReloaded(): Promise<Unit> =
        formTest {
            val seen = mutableListOf<String>()
            for (variant in 1..2) {
                withPage(prepare = {
                    state = finderState(interest = MemberFinderInterest.MEETUP)
                    sendAnswer =
                        outgoing().copy(
                            requestId =
                                if (variant ==
                                    1
                                ) {
                                    "00000000-0000-4000-8000-00000000000a"
                                } else {
                                    "00000000-0000-4000-8000-00000000000b"
                                },
                            sentOn = kotlinx.datetime.LocalDate(2026, 10, 10 + variant),
                        )
                }) { element, requests ->
                    awaitParticipation(element)
                    awaitAppScopeIdle("lists loaded")
                    val listCallsBefore = requests.toRoute(routes.listOutgoing).size
                    element.typeInto("Notiz (freiwillig)", "Hallo")
                    awaitUntil("sendable") { !(element.buttonNamed("Anfrage senden") as HTMLButtonElement).disabled }
                    element.buttonNamed("Anfrage senden").click()
                    awaitUntil("the live message") { element.live().text() == "Ihre Anfrage wurde gesendet." }
                    // the list was read again BEFORE the message was set
                    assertTrue(requests.toRoute(routes.listOutgoing).size > listCallsBefore)
                    val sendIndex = requests.indexOfFirst { it.isRpc && it.rpcRoute == routes.sendRequest }
                    val listIndex = requests.indexOfLast { it.isRpc && it.rpcRoute == routes.listOutgoing }
                    assertTrue(listIndex > sendIndex, "the reload follows the send")
                    assertTrue(element.text().contains("Sie erfahren nur, wenn jemand zustimmt."))
                    assertEquals(
                        "",
                        (element.controlOf("Notiz (freiwillig)") as org.w3c.dom.HTMLTextAreaElement).value,
                        "the note is cleared",
                    )
                    seen += element.live().text()
                }
            }
            assertEquals(1, seen.toSet().size, "the same live text in both cases")
        }

    @Test
    fun theRefusals_haveTheirFixedSentence_inAnAlert(): Promise<Unit> =
        formTest {
            listOf(
                "MemberFinderLimitReachedException" to "höchste Zahl offener Anfragen",
                "MemberFinderRateLimitedException" to "Zu viele Aktionen in kurzer Zeit",
                "MemberFinderRegionNotVisibleException" to "Ihre Region ist noch nicht sichtbar",
            ).forEach { (type, sentence) ->
                withPage(prepare = {
                    state = finderState(interest = MemberFinderInterest.MEETUP)
                    sendFails = type
                }) { element, _ ->
                    awaitParticipation(element)
                    element.buttonNamed("Anfrage senden").click()
                    awaitUntil("the sentence for $type") { element.allOf("[role=alert]").any { it.text().contains(sentence) } }
                }
            }
        }

    @Test
    fun noConsentAnyMore_sendsTheMemberBackToModeA(): Promise<Unit> =
        formTest {
            withPage(prepare = {
                state = finderState(interest = MemberFinderInterest.MEETUP)
                sendFails = "MemberFinderConsentRequiredException"
            }) { element, _ ->
                awaitParticipation(element)
                // after the refusal the state is read again; by then the consent is gone
                state = finderState(optedIn = false)
                element.buttonNamed("Anfrage senden").click()
                awaitUntil("mode A") { element.text().contains("Woran haben Sie Interesse?") }
            }
        }

    // ---- incoming requests and connections ----

    @Test
    fun anIncomingRequest_hasNoSender_showsTheNoteAsText_andLabelsWithoutAName(): Promise<Unit> =
        formTest {
            withPage(prepare = { incomingList = listOf(incoming(note = "<b>fett</b> Notiz")) }) { element, _ ->
                awaitUntil("the request") { element.text().contains("Eingehende Anfragen") }
                assertTrue(element.text().contains("Ein Mitglied aus Ihrer Region möchte eine Ortsgruppe gründen."))
                assertTrue(element.text().contains("<b>fett</b> Notiz"), "the note is a text node")
                assertNull(element.querySelector("li b"), "no element was created from the note")
                val accept = element.allOf("button").first { it.text().trim() == "Annehmen" }
                val label = accept.getAttribute("aria-label").orEmpty()
                assertEquals("Anfrage vom ${formatDate(finderDay)} annehmen", label)
                assertFalse(label.contains("fett"))
            }
        }

    @Test
    fun accepting_createsTheConnection_theNameIsOnlyInItsCard_andTheFocusGoesThere(): Promise<Unit> =
        formTest {
            withPage(prepare = {
                incomingList = listOf(incoming())
                respondAnswer = MemberFinderResponseDto(connection = connection())
            }) { element, requests ->
                awaitUntil("the request") { element.text().contains("Eingehende Anfragen") }
                // after the answer the server lists the connection
                connectionList = listOf(connection())
                element.allOf("button").first { it.text().trim() == "Annehmen" }.click()
                awaitUntil("the connection card") { element.text().contains(FinderIds.OTHER_PERSON) }
                val call = requests.toRoute(routes.respond).single()
                assertEquals(true, call.rpcParam(1) as Boolean)
                assertEquals(false, call.rpcParam(2) as Boolean)
                awaitUntil("the live message") { element.live().text() == "Verbindung hergestellt." }
                assertFalse(element.live().text().contains(FinderIds.OTHER_PERSON), "the message names nobody")
                val heading = assertNotNull(element.querySelector("[data-connection-id] h3") as? HTMLElement)
                assertEquals(FinderIds.OTHER_PERSON, heading.text())
                awaitUntil("focus on the heading", 1000) { document.activeElement === heading }
                assertNull(element.querySelector("[data-request-id=\"${FinderIds.INCOMING}\"]"), "the request is gone")
            }
        }

    @Test
    fun decliningAndBlocking_asksFirst_withTheFocusOnCancel(): Promise<Unit> =
        formTest {
            withPage(prepare = { incomingList = listOf(incoming()) }) { element, requests ->
                awaitUntil("the request") { element.text().contains("Eingehende Anfragen") }
                element.allOf("button").first { it.text().trim() == "Ablehnen und blockieren" }.click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(modal.text().contains("Wer es ist, erfahren Sie nicht."))
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                modal.buttonNamed("Ablehnen und blockieren").click()
                awaitUntil("the call") { requests.toRoute(routes.respond).size == 1 }
                val call = requests.toRoute(routes.respond).single()
                assertEquals(false, call.rpcParam(1) as Boolean)
                assertEquals(true, call.rpcParam(2) as Boolean)
            }
        }

    @Test
    fun aConnection_canBeMessaged_andBlocked_withoutTheNameLeavingItsCard(): Promise<Unit> =
        formTest {
            withPage(prepare = { connectionList = listOf(connection()) }) { element, requests ->
                awaitUntil("the connection") { element.text().contains(FinderIds.OTHER_PERSON) }
                element.buttonNamed("Nachricht senden").click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue((modal.buttonNamed("Senden") as HTMLButtonElement).disabled, "empty text stays locked")
                modal.typeInto("Ihre Nachricht", "   ")
                assertTrue((modal.buttonNamed("Senden") as HTMLButtonElement).disabled, "blank text stays locked")
                modal.typeInto("Ihre Nachricht", "Guten Tag!")
                awaitUntil("unlocked") { !(modal.buttonNamed("Senden") as HTMLButtonElement).disabled }
                modal.buttonNamed("Senden").click()
                awaitUntil("the call") { requests.toRoute(routes.contact).size == 1 }
                val call = requests.toRoute(routes.contact).single()
                assertEquals(FinderIds.CONNECTION, call.rpcParam(0) as String)
                assertEquals("Guten Tag!", call.rpcParam(1) as String)
                awaitUntil("the message") {
                    element.live().text() == "Nachricht gesendet. Die Unterhaltung finden Sie unter Nachrichten."
                }

                element.buttonNamed("Verbindung blockieren").click()
                awaitUntil("the question") { document.querySelector(".modal.show") != null }
                lastOpenModal().buttonNamed("Verbindung blockieren").click()
                awaitUntil("the block is listed") { element.text().contains("Blockierte") }
                assertEquals(1, requests.toRoute(routes.blockConnection).size)
                assertFalse(element.text().contains(FinderIds.OTHER_PERSON))
            }
        }

    @Test
    fun ownRequests_canBeWithdrawnWhilePending_andBlocksCanBeLifted(): Promise<Unit> =
        formTest {
            withPage(prepare = {
                outgoingList =
                    listOf(outgoing(), outgoing(MemberFinderOutgoingState.ENDED).copy(requestId = "00000000-0000-4000-8000-0000000000e1"))
                blockList = listOf(finderBlock())
            }) { element, requests ->
                awaitUntil("both lists") { element.text().contains("Gesendete Anfragen") && element.text().contains("Blockierte") }
                assertEquals(
                    1,
                    element.allOf("button").count { it.text().trim() == "Zurückziehen" },
                    "only the pending one can be withdrawn",
                )
                element.buttonNamed("Zurückziehen").click()
                awaitUntil("the call") { requests.toRoute(routes.withdraw).size == 1 }
                assertEquals(FinderIds.OUTGOING, requests.toRoute(routes.withdraw).single().rpcParam(0) as String)
                awaitUntil("the message") { element.live().text() == "Anfrage zurückgezogen." }
                element.buttonNamed("Entsperren").click()
                awaitUntil("the unblock") { requests.toRoute(routes.unblock).size == 1 }
                assertEquals(FinderIds.BLOCK, requests.toRoute(routes.unblock).single().rpcParam(0) as String)
                awaitUntil("the message") { element.live().text() == "Entsperrt." }
            }
        }

    // ---- the allowlist of what may be in the DOM ----

    @Test
    fun theDom_holdsOnlyRandomIdsOfRequestsConnectionsAndBlocks_andOneNameInOneCard(): Promise<Unit> =
        formTest {
            withPage(prepare = {
                incomingList = listOf(incoming())
                outgoingList = listOf(outgoing())
                connectionList = listOf(connection())
                blockList = listOf(finderBlock())
            }) { element, _ ->
                awaitUntil("everything is shown") {
                    val t = element.text()
                    t.contains("Eingehende Anfragen") &&
                        t.contains("Verbindungen") &&
                        t.contains("Gesendete Anfragen") &&
                        t.contains("Blockierte")
                }
                val html = element.outerHTML
                val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                val allowed = setOf(FinderIds.INCOMING, FinderIds.OUTGOING, FinderIds.CONNECTION, FinderIds.BLOCK)
                val found = uuid.findAll(html).map { it.value }.toSet()
                assertTrue(allowed.containsAll(found), "only the ids of requests, connections and blocks: $found")
                val dataAttributes =
                    element
                        .allOf("*")
                        .flatMap { node -> (0 until node.attributes.length).map { node.attributes.item(it)!!.name } }
                        .filter { it.startsWith("data-") }
                        .toSet()
                val permitted = setOf("data-request-id", "data-connection-id", "data-block-id")
                // the Bootstrap/KVision machinery may add its own data-bs-* attributes; none of the finder's own may be anything else
                assertTrue(
                    dataAttributes.filterNot { it.startsWith("data-bs-") }.all { it in permitted },
                    "data-* attributes: $dataAttributes",
                )
                // the name appears in the connection card only: not in an attribute, not in the title, not in an aria label
                assertEquals(1, element.allOf("*").count { it.childElementCount == 0 && it.text().contains(FinderIds.OTHER_PERSON) })
                assertFalse(document.title.contains(FinderIds.OTHER_PERSON))
                val attributeValues =
                    element.allOf("*").flatMap { node -> (0 until node.attributes.length).map { node.attributes.item(it)!!.value } }
                assertTrue(attributeValues.none { it.contains(FinderIds.OTHER_PERSON) || it.contains("Bahnhof") })
                assertTrue(element.live().text().isEmpty() || !element.live().text().contains(FinderIds.OTHER_PERSON))
            }
        }
}
