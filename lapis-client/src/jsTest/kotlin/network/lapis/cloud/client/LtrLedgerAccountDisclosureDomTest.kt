package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArbitrationTransferInput
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.domain.PeerTransferResultDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IPeerTransferService
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.185 -- the LTR account with progressive disclosure: "LTR senden" is the one button in the balance card (the form is collapsed),
 * the filter of "Meine Buchungen" reloads by itself (no "Aktualisieren"), and the treasury tools sit behind "Werkzeuge anzeigen" as three
 * collapsed tools of which only one is open at a time. The role gating itself is server-side and unchanged.
 */
class LtrLedgerAccountDisclosureDomTest {
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"
    private val sendFormId = "lapis-ltr-send-form"
    private val lookupFormId = "lapis-ltr-lookup-form"
    private val mintFormId = "lapis-ltr-mint-form"
    private val arbitrationFormId = "lapis-ltr-arbitration-form"
    private val toolsBodyId = "lapis-ltr-treasury-tools"

    private fun session(
        role: AccountRole,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = SessionInfoDto(
        memberId = "member-1",
        displayName = "Dana Keller",
        role = role,
        status = status,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
    )

    private val members =
        listOf(
            MemberSummaryDto("member-1", "Dana Keller"),
            MemberSummaryDto("member-2", "Emil Roth"),
            MemberSummaryDto("member-3", "Fritz Lang"),
        )

    private fun entry(
        id: String,
        type: LtrLedgerEntryType,
        reference: LtrLedgerReferenceType?,
    ) = LtrLedgerEntryDto(
        id = id,
        memberId = "member-1",
        memberDisplayName = "Dana Keller",
        entryType = type,
        amountLtr = 5.0.toDecimal(),
        referenceType = reference,
        referenceId = null,
        note = null,
        createdById = null,
        createdByDisplayName = null,
        createdAt = LocalDateTime(2026, 1, 2, 12, 0),
    )

    private val twoEntries =
        listOf(
            entry("e1", LtrLedgerEntryType.MINT, null),
            entry("e2", LtrLedgerEntryType.PEER_TRANSFER_OUT, LtrLedgerReferenceType.PEER_TRANSFER),
        )

    private fun transferResult(senderId: String = "member-1") =
        PeerTransferResultDto(
            transferId = "t1",
            senderMemberId = senderId,
            senderDisplayName = "Dana Keller",
            recipientMemberId = "member-2",
            recipientDisplayName = "Emil Roth",
            amountLtr = 12.5.toDecimal(),
            characterization = PeerTransferCharacterization.SONSTIGES,
            purpose = null,
            initiatedById = null,
            initiatedByDisplayName = null,
            outEntryId = "out-77",
            inEntryId = "in-88",
            createdAt = LocalDateTime(2026, 1, 2, 12, 0),
        )

    private class Routes(
        val balance: String,
        val entries: String,
        val members: String,
        val transfer: String,
        val mint: String,
        val arbitration: String,
    )

    private suspend fun withLtrStub(
        entriesAnswer: (
            RecordedRequest,
        ) -> StubResponse = { it.answerWith(jsonOf(ListSerializer(LtrLedgerEntryDto.serializer()), twoEntries)) },
        transferAnswer: (RecordedRequest) -> StubResponse = { it.answerWith(jsonOf(PeerTransferResultDto.serializer(), transferResult())) },
        block: suspend (List<RecordedRequest>, Routes) -> Unit,
    ) {
        val routes =
            Routes(
                balance = routeOf { rpcService<ILtrLedgerService>().getMyBalance() },
                entries = routeOf { rpcService<ILtrLedgerService>().listMyEntries() },
                members = routeOf { rpcService<IMemberService>().listMembers() },
                transfer =
                    routeOf {
                        rpcService<IPeerTransferService>().transferLtr(
                            PeerTransferInput("x", 1.0.toDecimal(), PeerTransferCharacterization.SONSTIGES),
                        )
                    },
                mint = routeOf { rpcService<ILtrLedgerService>().mintLtr(MintLtrInput("x", 1.0.toDecimal())) },
                arbitration =
                    routeOf {
                        rpcService<IPeerTransferService>().executeArbitrationTransfer(
                            ArbitrationTransferInput("a", "b", 1.0.toDecimal(), PeerTransferCharacterization.SONSTIGES, "p"),
                        )
                    },
            )
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == routes.balance ->
                        request.answerWith(jsonOf(LtrLedgerBalanceDto.serializer(), LtrLedgerBalanceDto("member-1", 40.0.toDecimal())))
                    request.rpcRoute == routes.entries -> entriesAnswer(request)
                    request.rpcRoute == routes.members -> request.answerWith(jsonOf(ListSerializer(MemberSummaryDto.serializer()), members))
                    request.rpcRoute == routes.transfer -> transferAnswer(request)
                    request.rpcRoute == routes.mint ->
                        request.answerWith(jsonOf(LtrLedgerEntryDto.serializer(), entry("m1", LtrLedgerEntryType.MINT, null)))
                    request.rpcRoute == routes.arbitration ->
                        request.answerWith(jsonOf(PeerTransferResultDto.serializer(), transferResult("member-2")))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, routes) }
    }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hostOpen(formId: String): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

    private fun HTMLElement.rendered(): Boolean = (asDynamic().getClientRects().length as Int) > 0

    private fun HTMLElement.hasButton(text: String): Boolean = allOf("button").any { it.textContent?.trim() == text }

    private suspend fun HTMLElement.awaitLoaded() {
        awaitUntil("the entries are listed") { shows("Gutschrift (Mint)") }
        awaitAppScopeIdle("the screen has loaded everything")
    }

    private suspend fun confirmModal(label: String) {
        awaitUntil("a dialog is shown") { document.querySelector(".modal.show") != null }
        lastOpenModal().buttonNamed(label).click()
        awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
    }

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun HTMLElement.openTools() {
        buttonNamed("Werkzeuge anzeigen").click()
        awaitUntil("the tools are shown") { shows("Sichtbar für Kassenwarte, Vorstand und Administratoren.") }
    }

    private suspend fun HTMLElement.fillSend() {
        chooseIn("Empfänger", "member-2")
        typeInto("Betrag (LTR)", "12.5")
    }

    // ---- 1 + 8 + 12: initial state ------------------------------------------------------------------------------

    @Test
    fun memberStartsWithOnlyTheSendButtonInTheCard_noRefresh_noTreasuryAreaAtAll(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            withLtrStub { calls, routes ->
                mountedForm("ltr-disclosure-start") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    assertEquals(1, screen.allOf("button").count { it.textContent?.trim() == "LTR senden" }, "one send button")
                    val card =
                        assertNotNull(
                            screen.querySelector(".lapis-page-action")?.closest(".border") as? HTMLElement,
                            "the button sits in the balance card",
                        )
                    assertTrue(card.shows("Ihr LTR-Guthaben"), "the card is the balance card")
                    assertFalse(screen.hasButton("Aktualisieren"), "no refresh button")
                    assertFalse(screen.hostOpen(sendFormId), "the send form is collapsed")
                    assertFalse(screen.shows("Treuhänder-Werkzeuge"), "a plain member has no treasury area")
                    assertTrue(screen.querySelector("#$toolsBodyId") == null)
                    assertFalse(screen.shows("TREASURER/BOARD/ADMIN"), "no role constants as text")
                    assertEquals(1, calls.toRoute(routes.entries).size, "the list is loaded exactly once at build")
                }
            }
        }

    @Test
    fun aFriend_getsNoSendButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER, MemberStatus.FRIEND))
            withLtrStub { _, _ ->
                mountedForm("ltr-disclosure-friend") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    assertFalse(element().hasButton("LTR senden"))
                    assertTrue(element().querySelector("[id='$sendFormId']") == null)
                }
            }
        }

    // ---- 2 + 11: open / cancel / escape ---------------------------------------------------------------------------

    @Test
    fun sendForm_opensOnClick_cancelsWithoutQuestionWhenUnchanged_askedWhenChanged(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            withLtrStub { _, _ ->
                mountedForm("ltr-disclosure-open") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    val button = createFormButton(screen, sendFormId)
                    assertEquals("BUTTON", button.tagName, "a native button: Enter and Space work")
                    assertEquals("false", button.getAttribute("aria-expanded"))
                    val host = openCreateFormHost(screen, sendFormId)
                    assertEquals("true", button.getAttribute("aria-expanded"))
                    assertTrue(host.shows("Empfänger") && host.shows("Betrag (LTR)"))

                    host.buttonNamed("Abbrechen").click()
                    awaitUntil("closed without a question") { !screen.hostOpen(sendFormId) }
                    assertTrue(document.querySelector(".modal.show") == null)
                    assertEquals("false", button.getAttribute("aria-expanded"))

                    val reopened = openCreateFormHost(screen, sendFormId)
                    reopened.typeInto("Betrag (LTR)", "3")
                    reopened.buttonNamed("Abbrechen").click()
                    awaitUntil("the discard question") { document.querySelector(".modal.show") != null }
                    assertTrue(lastOpenModal().shows("Eingaben verwerfen?"))
                    confirmModal("Weiter bearbeiten")
                    assertTrue(screen.hostOpen(sendFormId), "still open after 'Weiter bearbeiten'")

                    escape(reopened)
                    awaitUntil("the discard question again") { document.querySelector(".modal.show") != null }
                    confirmModal("Verwerfen")
                    awaitUntil("closed after discarding") { !screen.hostOpen(sendFormId) }
                }
            }
        }

    // ---- 3: success -----------------------------------------------------------------------------------------------

    @Test
    fun aSuccessfulSend_closesTheForm_keepsTheReceiptOutsideIt_reloadsBothLists_andTheButtonSurvives(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            withLtrStub { calls, routes ->
                mountedForm("ltr-disclosure-send") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    val balanceBase = calls.toRoute(routes.balance).size
                    val entriesBase = calls.toRoute(routes.entries).size
                    val host = openCreateFormHost(screen, sendFormId)
                    host.fillSend()
                    host.buttonNamed("LTR übertragen").click()
                    confirmModal("Endgültig übertragen")
                    awaitUntil("the transfer went out") { calls.toRoute(routes.transfer).size == 1 }
                    assertEquals("member-2", calls.singleCall(routes.transfer).rpcParam(0).recipientMemberId as String)
                    awaitUntil("the form folded back") { !screen.hostOpen(sendFormId) }
                    val receipt = assertNotNull(screen.querySelector("#lapis-ltr-send-receipt") as? HTMLElement)
                    awaitUntil("the receipt is shown") { receipt.shows("Buchung (Soll): out-77") }
                    assertTrue(receipt.shows("Buchung (Haben): in-88"))
                    assertTrue(receipt.closest("[id='$sendFormId']") == null, "the receipt is not inside the form")
                    awaitUntil("balance and list were fetched again") {
                        calls.toRoute(routes.balance).size > balanceBase && calls.toRoute(routes.entries).size > entriesBase
                    }
                    awaitAppScopeIdle("reloads finished")
                    assertEquals(
                        1,
                        screen.allOf("button").count { it.textContent?.trim() == "LTR senden" },
                        "the button survived the balance reload",
                    )

                    // Opening again clears the receipt.
                    createFormButton(screen, sendFormId).click()
                    awaitUntil("the receipt is cleared") { !receipt.shows("Buchung (Soll)") }
                }
            }
        }

    // ---- 4 + 5: failure and validation --------------------------------------------------------------------------

    @Test
    fun aFailedSend_keepsTheFormOpen_withoutReceipt(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            withLtrStub(transferAnswer = { serviceExceptionResult(it.json.id as Int, conflict) }) { calls, routes ->
                mountedForm("ltr-disclosure-send-fail") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    val host = openCreateFormHost(screen, sendFormId)
                    host.fillSend()
                    host.buttonNamed("LTR übertragen").click()
                    confirmModal("Endgültig übertragen")
                    awaitUntil("the transfer went out") { calls.toRoute(routes.transfer).size == 1 }
                    awaitAppScopeIdle("the failed answer was handled")
                    delay(100)
                    assertTrue(screen.hostOpen(sendFormId), "the form stays open")
                    assertFalse(
                        screen
                            .querySelector("#lapis-ltr-send-receipt")!!
                            .textContent
                            .orEmpty()
                            .contains("Buchung"),
                    )
                    assertFalse(host.buttonNamed("LTR übertragen").hasAttribute("disabled"), "the button can be used again")
                }
            }
        }

    @Test
    fun anEmptyAmount_showsTheKnownMessage_andSendsNothing(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            withLtrStub { calls, routes ->
                mountedForm("ltr-disclosure-validation") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val host = openCreateFormHost(element(), sendFormId)
                    host.buttonNamed("LTR übertragen").click()
                    awaitUntil(
                        "the message",
                    ) { host.shows("Bitte Empfänger, einen positiven LTR-Betrag und eine Charakterisierung angeben.") }
                    assertTrue(document.querySelector(".modal.show") == null, "no confirmation without valid input")
                    assertTrue(calls.toRoute(routes.transfer).isEmpty())
                }
            }
        }

    // ---- 6: the filter --------------------------------------------------------------------------------------------

    @Test
    fun theFilter_fetchesTheListAgainOnEveryChange_andFiltersIt(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            withLtrStub { calls, routes ->
                mountedForm("ltr-disclosure-filter") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    assertEquals(1, calls.toRoute(routes.entries).size)
                    assertTrue(screen.shows("Übertragung gesendet") && screen.shows("Gutschrift (Mint)"))
                    screen.chooseIn("Filter", "PEER_TRANSFER")
                    awaitUntil("the list was fetched again") { calls.toRoute(routes.entries).size == 2 }
                    awaitUntil("only the transfer is listed") { !screen.shows("Gutschrift (Mint)") }
                    assertTrue(screen.shows("Übertragung gesendet"))
                    screen.chooseIn("Filter", "NONE")
                    awaitUntil("fetched a third time") { calls.toRoute(routes.entries).size == 3 }
                    awaitUntil("only the entry without reference is listed") { !screen.shows("Übertragung gesendet") }
                    assertFalse(screen.hasButton("Aktualisieren"))
                }
            }
        }

    @Test
    fun aFailedFirstLoad_staysAnErrorStateWithRetry_aFilterChangeDoesNotOverwriteIt(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            withLtrStub(entriesAnswer = { StubResponse(networkError = true) }) { calls, routes ->
                mountedForm("ltr-disclosure-filter-error") { root, element ->
                    renderLtrLedgerScreen(root)
                    val screen = element()
                    awaitUntil("the error state with a retry") { screen.hasButton("Erneut versuchen") }
                    awaitAppScopeIdle("loads finished")
                    val base = calls.toRoute(routes.entries).size
                    screen.chooseIn("Filter", "PEER_TRANSFER")
                    delay(200)
                    assertEquals(base, calls.toRoute(routes.entries).size, "no extra fetch while the first load failed")
                    assertTrue(screen.hasButton("Erneut versuchen"), "the error state stays")
                    assertFalse(screen.shows("Keine Buchungen für diesen Filter."))
                }
            }
        }

    // ---- 7 + 12: roles and the treasury tools ----------------------------------------------------------------------

    @Test
    fun treasuryAreaIsClosedForTreasurerBoardAndAdmin_andOpensToThreeCollapsedTools(): Promise<Unit> =
        formTest {
            for (role in listOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
                AppState.setSession(session(role))
                withLtrStub { _, _ ->
                    mountedForm("ltr-disclosure-tools-$role") { root, element ->
                        renderLtrLedgerScreen(root)
                        element().awaitLoaded()
                        val screen = element()
                        awaitUntil("the tools toggler for $role") { screen.hasButton("Werkzeuge anzeigen") }
                        assertTrue(screen.shows("Treuhänder-Werkzeuge") && screen.shows("Für Kassenwarte, Vorstand und Administratoren"))
                        // Closed: KVision does not render a hidden panel at all, so the tools are not in the DOM.
                        assertTrue(
                            (screen.querySelector("#$toolsBodyId") as? HTMLElement)?.rendered() != true,
                            "closed: the tools are not visible",
                        )
                        assertFalse(
                            screen.shows("Mitglied ansehen") || screen.shows("Gutschrift erfassen") || screen.shows("Korrektur erfassen"),
                        )
                        val toggler = screen.buttonNamed("Werkzeuge anzeigen")
                        assertEquals("BUTTON", toggler.tagName)
                        assertEquals("false", toggler.getAttribute("aria-expanded"))

                        screen.openTools()
                        val body = assertNotNull(screen.querySelector("#$toolsBodyId") as? HTMLElement)
                        assertTrue(body.rendered())
                        assertEquals("true", toggler.getAttribute("aria-expanded"))
                        assertTrue(body.shows("Guthaben & Buchungen eines Mitglieds"))
                        assertTrue(body.shows("LTR gutschreiben (Mint)"))
                        assertTrue(body.shows("Schiedsverfahren-Korrekturübertragung"))
                        assertTrue(body.shows("Zeigt das Guthaben und die Buchungen eines beliebigen Mitglieds an."))
                        assertTrue(body.shows("Die Übertragung ist endgültig."))
                        for ((formId, label) in listOf(
                            lookupFormId to "Mitglied ansehen",
                            mintFormId to "Gutschrift erfassen",
                            arbitrationFormId to "Korrektur erfassen",
                        )) {
                            assertFalse(screen.hostOpen(formId), "$formId is collapsed")
                            val tool = createFormButton(screen, formId)
                            assertEquals("BUTTON", tool.tagName)
                            assertEquals(label, tool.textContent?.trim())
                        }
                        assertFalse(screen.shows("TREASURER/BOARD/ADMIN"))
                    }
                }
            }
        }

    // ---- 9: one tool at a time ------------------------------------------------------------------------------------

    @Test
    fun onlyOneToolIsOpen_aChangedOneAsksBeforeTheNextOpens(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            withLtrStub { _, _ ->
                mountedForm("ltr-disclosure-one-tool") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    screen.openTools()
                    val mint = openCreateFormHost(screen, mintFormId)
                    // Unchanged: opening the next one simply closes this one.
                    createFormButton(screen, arbitrationFormId).click()
                    awaitUntil("the arbitration form is built") { screen.hostOpen(arbitrationFormId) }
                    assertFalse(screen.hostOpen(mintFormId), "the mint tool was closed")

                    // Changed: it asks first.
                    val lookup = openCreateFormHost(screen, mintFormId)
                    lookup.typeInto("Betrag (LTR)", "5")
                    createFormButton(screen, lookupFormId).click()
                    awaitUntil("the discard question") { document.querySelector(".modal.show") != null }
                    confirmModal("Weiter bearbeiten")
                    assertTrue(screen.hostOpen(mintFormId), "the changed tool stays open")
                    assertFalse(screen.hostOpen(lookupFormId), "and the new one stays closed")
                    assertFalse(screen.hostOpen(arbitrationFormId))

                    createFormButton(screen, lookupFormId).click()
                    awaitUntil("the discard question again") { document.querySelector(".modal.show") != null }
                    confirmModal("Verwerfen")
                    awaitUntil("the lookup opened") { screen.hostOpen(lookupFormId) }
                    assertFalse(screen.hostOpen(mintFormId))
                    assertTrue(mint.childElementCount == 0)
                }
            }
        }

    // ---- 10: mint and arbitration ---------------------------------------------------------------------------------

    @Test
    fun mint_closesAfterSuccess_andReloadsBalanceAndList(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            withLtrStub { calls, routes ->
                mountedForm("ltr-disclosure-mint") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    val balanceBase = calls.toRoute(routes.balance).size
                    val entriesBase = calls.toRoute(routes.entries).size
                    screen.openTools()
                    val host = openCreateFormHost(screen, mintFormId)
                    host.chooseIn("Mitglied", "member-2")
                    host.typeInto("Betrag (LTR)", "7")
                    host.buttonNamed("Gutschreiben").click()
                    confirmModal("Gutschreiben")
                    awaitUntil("the mint went out") { calls.toRoute(routes.mint).size == 1 }
                    awaitUntil("the form folded back") { !screen.hostOpen(mintFormId) }
                    awaitUntil("balance and list were fetched again") {
                        calls.toRoute(routes.balance).size > balanceBase && calls.toRoute(routes.entries).size > entriesBase
                    }
                }
            }
        }

    @Test
    fun arbitrationTransfer_closesAfterSuccess_keepsItsReceipt_andReloads(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withLtrStub { calls, routes ->
                mountedForm("ltr-disclosure-arbitration") { root, element ->
                    renderLtrLedgerScreen(root)
                    element().awaitLoaded()
                    val screen = element()
                    val balanceBase = calls.toRoute(routes.balance).size
                    screen.openTools()
                    val host = openCreateFormHost(screen, arbitrationFormId)
                    host.chooseIn("Absender", "member-2")
                    host.chooseIn("Empfänger", "member-3")
                    host.typeInto("Betrag (LTR)", "4")
                    host.typeInto("Schiedsanordnungs-Referenz", "Az. 12/26")
                    host.buttonNamed("Korrekturübertragung ausführen").click()
                    confirmModal("Endgültig ausführen")
                    awaitUntil("the correction went out") { calls.toRoute(routes.arbitration).size == 1 }
                    awaitUntil("the form folded back") { !screen.hostOpen(arbitrationFormId) }
                    awaitUntil("the receipt is shown") { screen.shows("Buchung (Soll): out-77") }
                    awaitUntil("the balance was fetched again") { calls.toRoute(routes.balance).size > balanceBase }
                }
            }
        }
}
