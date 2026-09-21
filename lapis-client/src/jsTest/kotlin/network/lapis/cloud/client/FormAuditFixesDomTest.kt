package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IContributionReliefService
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.4.29 audit fixes (B-1, M-1, M-2, M-4, M-6, M-7, m-14, m-16, m-18 and the checkbox-`change` question of the audit): every test
 * drives a REAL form in a mounted root with a stubbed `window.fetch`, the same way [FormSubmitBodyPart2DomTest] does. The negative
 * assertions say "no RPC AT ALL" (`rpcCount`), not "no RPC with n parameters".
 */
class FormAuditFixesDomTest {
    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    // ── B-1: the door banner is the signal at the door; it must never show the result of the PREVIOUS scan ──────────────

    private val eventId = "00000000-0000-0000-0000-0000000000e1"
    private val rosterJson =
        """{"eventId":"$eventId","eventTitle":"Sommerfest","startsAt":"2026-08-15T18:00:00","locationText":"Halle 3",""" +
            """"rows":[],"confirmedCount":0,"checkedInCount":0}"""
    private val codeSelector = "input[placeholder='ABCD-EFGH-JKMN-PQRS']"

    private fun HTMLElement.enterIn(input: HTMLInputElement) {
        input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", cancelable = true, bubbles = true)))
    }

    private suspend fun scan(
        element: () -> HTMLElement,
        code: String,
    ) {
        val input = element().first(codeSelector) as HTMLInputElement
        input.value = code
        input.dispatchEvent(Event("input"))
        element().enterIn(input)
    }

    @Test
    fun door_aValidScanThenGarbage_theGreenBannerIsGone_andARedUnknownCodeStandsInItsPlace(): Promise<Unit> =
        formTest {
            val checkIn = routeOf { rpcService<IEventService>().checkInByCode(eventId, "x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == checkIn ->
                            request.answerWith("""{"outcome":"OK","participantName":"Anna Schmidt"}""")
                        else -> request.answerWith(rosterJson)
                    }
                },
            ) { calls ->
                mountedForm("audit-door-garbage") { root, element ->
                    renderEventCheckInScreen(root, eventId)
                    awaitUntil("the code field", timeoutMs = 800) { element().querySelector(codeSelector) != null }
                    delay(150)
                    scan(element, "ABCD-EFGH-JKMN-PQRS")
                    awaitUntil("the check-in went out", timeoutMs = 800) { calls.toRoute(checkIn).size == 1 }
                    awaitUntil("the green banner", timeoutMs = 800) { element().querySelector(".alert-success") != null }
                    assertTrue(element().textContent.orEmpty().contains("Eingecheckt: Anna Schmidt"))
                    delay(200)

                    // Now nonsense: the door must NOT keep showing the previous guest as checked in.
                    scan(element, "garbage")
                    delay(80)
                    assertEquals(1, calls.toRoute(checkIn).size, "no request for obvious garbage")
                    assertNull(element().querySelector(".alert-success"), "the green banner of the previous scan is gone")
                    assertFalse(element().textContent.orEmpty().contains("Eingecheckt: Anna Schmidt"))
                    val danger = assertNotNull(element().querySelector(".alert-danger") as? HTMLElement, "a red banner stands there")
                    assertEquals("Code unbekannt.", danger.textContent?.trim())
                    assertEquals("Code unbekannt.", element().shownErrors().single(), "and the field reports it as well")

                    // The barcode-scanner path: the error is still standing, a correct code + Enter sends at once and replaces the red.
                    scan(element, "WXYZ-2345-6789-ABCD")
                    awaitUntil("the corrected code is sent", timeoutMs = 800) { calls.toRoute(checkIn).size == 2 }
                    awaitUntil("the green banner is back", timeoutMs = 800) { element().querySelector(".alert-success") != null }
                    assertNull(element().querySelector(".alert-danger"), "the red banner is gone again")
                }
            }
        }

    @Test
    fun door_anEmptyScan_isRedToo_andNeverLeavesTheGreenOfTheScanBefore(): Promise<Unit> =
        formTest {
            val checkIn = routeOf { rpcService<IEventService>().checkInByCode(eventId, "x") }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == checkIn) {
                        request.answerWith("""{"outcome":"OK","participantName":"Anna Schmidt"}""")
                    } else if (request.isRpc) {
                        request.answerWith(rosterJson)
                    } else {
                        StubResponse()
                    }
                },
            ) { calls ->
                mountedForm("audit-door-empty") { root, element ->
                    renderEventCheckInScreen(root, eventId)
                    awaitUntil("the code field", timeoutMs = 800) { element().querySelector(codeSelector) != null }
                    delay(150)
                    scan(element, "ABCD-EFGH-JKMN-PQRS")
                    awaitUntil("the green banner", timeoutMs = 800) { element().querySelector(".alert-success") != null }
                    delay(200)
                    element().enterIn(element().first(codeSelector) as HTMLInputElement)
                    delay(80)
                    assertNull(element().querySelector(".alert-success"))
                    assertNotNull(element().querySelector(".alert-danger"))
                    assertEquals(1, calls.toRoute(checkIn).size)
                }
            }
        }

    @Test
    fun door_aValidScanThenANetworkError_theBannerIsNoLongerThePreviousSuccess(): Promise<Unit> =
        formTest {
            val checkIn = routeOf { rpcService<IEventService>().checkInByCode(eventId, "x") }
            var checkIns = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == checkIn -> {
                            checkIns++
                            if (checkIns == 1) {
                                request.answerWith("""{"outcome":"OK","participantName":"Anna Schmidt"}""")
                            } else {
                                StubResponse(networkError = true)
                            }
                        }
                        else -> request.answerWith(rosterJson)
                    }
                },
            ) { calls ->
                mountedForm("audit-door-network") { root, element ->
                    renderEventCheckInScreen(root, eventId)
                    awaitUntil("the code field", timeoutMs = 800) { element().querySelector(codeSelector) != null }
                    delay(150)
                    scan(element, "ABCD-EFGH-JKMN-PQRS")
                    awaitUntil("the green banner", timeoutMs = 800) { element().querySelector(".alert-success") != null }
                    awaitUntil("the button is unlocked", timeoutMs = 800) { !element().buttonNamed("Prüfen").hasAttribute("disabled") }
                    delay(200)

                    scan(element, "WXYZ-2345-6789-ABCD")
                    awaitUntil("the second request went out", timeoutMs = 800) { calls.toRoute(checkIn).size == 2 }
                    awaitUntil("the button is unlocked again", timeoutMs = 1500) {
                        !element().buttonNamed("Prüfen").hasAttribute("disabled")
                    }
                    assertNull(
                        element().querySelector(".alert-success"),
                        "the success of the previous guest must not stand after a failed scan",
                    )
                    assertFalse(element().textContent.orEmpty().contains("Eingecheckt: Anna Schmidt"))
                }
            }
        }

    // ── M-1: nobody is subscribed by a single click without a deliberate choice ─────────────────────────────────────────

    private fun mailingList() =
        MailingListDto(
            id = "list-1",
            name = "Vorstand",
            description = null,
            createdBy = "admin-1",
            subscriberCount = 0,
            isSubscribedByCurrentMember = false,
        )

    private val members =
        listOf(MemberSummaryDto(id = "m1", displayName = "Amara Okafor"), MemberSummaryDto(id = "m2", displayName = "Kofi Mensah"))

    @Test
    fun communication_addingAMember_needsADeliberateChoice_andSendsTheChosenMember(): Promise<Unit> =
        formTest {
            val listMembers = routeOf { rpcService<IMemberService>().listMembers() }
            val subscribe = routeOf { rpcService<IMailingService>().adminSubscribeMember("l", "m") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listMembers ->
                            request.answerWith(
                                jsonOf(kotlinx.serialization.builtins.ListSerializer(MemberSummaryDto.serializer()), members),
                            )
                        request.rpcRoute == subscribe -> request.answerWith("null")
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("audit-communication-member") { root, element ->
                    renderMailingListDetail(root, mailingList(), refreshSelfService = {})
                    val select = element().controlOf("Mitglied") as HTMLSelectElement
                    awaitUntil("the members are loaded", timeoutMs = 800) { select.options.length == 3 }
                    assertEquals("", select.value, "nothing is preselected: the empty placeholder stands")
                    assertEquals(
                        "— bitte wählen —",
                        select.options
                            .item(0)
                            ?.textContent
                            ?.trim(),
                    )
                    val before = calls.rpcCount

                    element().buttonNamed("Hinzufügen").click()
                    delay(100)
                    assertEquals(before, calls.rpcCount, "no RPC at all without a choice (not even a load)")
                    assertEquals(0, calls.toRoute(subscribe).size)
                    assertEquals("Bitte ein Mitglied auswählen.", element().shownErrors().single())

                    element().chooseIn("Mitglied", "m2")
                    element().buttonNamed("Hinzufügen").click()
                    awaitUntil("adminSubscribeMember", timeoutMs = 800) { calls.toRoute(subscribe).isNotEmpty() }
                    val sent = calls.singleCall(subscribe)
                    assertEquals("list-1", sent.rpcParam(0) as String)
                    assertEquals("m2", sent.rpcParam(1) as String, "the member the person chose, not the first one of the list")
                }
            }
        }

    // ── M-2: the confirmation dialogs cannot fire twice ─────────────────────────────────────────────────────────────────

    @Test
    fun confirmWithReason_aDoubleClick_firesTheActionOnce_evenWithASlowRequest(): Promise<Unit> =
        formTest {
            val listMembers = routeOf { rpcService<IMemberService>().listMembers() }
            withFetchStub(respond = { request -> request.answerWith("[]").let { StubResponse(text = it.text, delayMs = 250) } }) { calls ->
                mountedForm("audit-confirm-double") { _, _ ->
                    confirmWithReasonDialog(
                        title = "Stornieren",
                        message = "Wirklich?",
                        reasonLabel = "Grund",
                        reasonRequired = true,
                        onConfirm = { AppScope.launch { guarded { rpcService<IMemberService>().listMembers() } } },
                    )
                    val modal = lastOpenModal()
                    modal.typeInto("Grund", "  zu Recht  ")
                    val confirm = modal.buttonNamed("Bestätigen")
                    confirm.click()
                    confirm.click()
                    delay(30)
                    confirm.click()
                    assertTrue(confirm.hasAttribute("disabled"), "the confirm button is locked")
                    delay(500)
                    assertEquals(1, calls.toRoute(listMembers).size, "exactly one request, however often the button was clicked")
                }
            }
        }

    @Test
    fun confirmWithReason_anInvalidClick_doesNotUseUpTheDialog(): Promise<Unit> =
        formTest {
            mountedForm("audit-confirm-invalid") { _, _ ->
                var received = 0
                confirmWithReasonDialog(
                    title = "Stornieren",
                    message = "Wirklich?",
                    reasonLabel = "Grund",
                    reasonRequired = true,
                    onConfirm = { received++ },
                )
                val modal = lastOpenModal()
                val confirm = modal.buttonNamed("Bestätigen")
                confirm.click()
                assertEquals(0, received)
                assertFalse(confirm.hasAttribute("disabled"), "an invalid click leaves the button usable")
                modal.typeInto("Grund", "jetzt ein Grund")
                confirm.click()
                confirm.click()
                assertEquals(1, received)
            }
        }

    @Test
    fun confirmDialog_andTheTypedConfirmation_fireOnceOnADoubleClick(): Promise<Unit> =
        formTest {
            mountedForm("audit-confirm-plain") { _, _ ->
                var plain = 0
                confirmDialog(title = "Löschen", message = "Sicher?", confirmLabel = "Ja, löschen", onConfirm = { plain++ })
                val plainModal = lastOpenModal()
                val button = plainModal.buttonNamed("Ja, löschen")
                button.click()
                button.click()
                assertEquals(1, plain)
                closeOpenModals(timeoutMs = 500)

                var typed = 0
                confirmWithTypedConfirmationDialog(
                    title = "Löschen",
                    message = "Endgültig.",
                    expectedText = "Amara",
                    onConfirm = { typed++ },
                )
                val typedModal = lastOpenModal()
                val input = typedModal.first("input[type=text]") as HTMLInputElement
                input.value = "Amara"
                input.dispatchEvent(Event("input"))
                delay(40)
                val confirm = typedModal.buttonNamed("Endgültig löschen")
                confirm.click()
                confirm.click()
                assertEquals(1, typed)
            }
        }

    // ── M-6: the double-click protection itself (form.submit -> runBusy -> runGuardedAction) ────────────────────────────

    @Test
    fun submit_aDoubleClick_sendsOneRequest_evenAFastOne(): Promise<Unit> =
        formTest {
            val listMembers = routeOf { rpcService<IMemberService>().listMembers() }
            withFetchStub(respond = { request -> StubResponse(text = request.answerWith("[]").text, delayMs = 250) }) { calls ->
                mountedForm("audit-double-submit") { root, element ->
                    val form = root.lapisForm()
                    val field = form.textField(label = tr("Notiz"), required = true)
                    field.setValue("x")
                    val button = Button("Absenden", style = ButtonStyle.PRIMARY)
                    form.buttons(primary = button)
                    button.onClick { form.submit(button) { guarded { rpcService<IMemberService>().listMembers() } } }
                    val dom = element().buttonNamed("Absenden")

                    dom.click()
                    dom.click() // synchronously behind the first: the DOM attribute has not been rendered yet
                    delay(40)
                    dom.click() // and once more after it has
                    assertTrue(dom.hasAttribute("disabled"), "the button is locked while the request runs")
                    assertEquals("true", dom.getAttribute("aria-busy"))
                    awaitUntil("the request is answered", timeoutMs = 1500) { !dom.hasAttribute("disabled") }
                    assertEquals("false", dom.getAttribute("aria-busy"))
                    assertEquals(1, calls.toRoute(listMembers).size, "one request for three clicks")

                    // Unlocked again: the next deliberate click goes out.
                    dom.click()
                    awaitUntil("a second, deliberate submit", timeoutMs = 1500) { calls.toRoute(listMembers).size == 2 }
                }
            }
        }

    // ── M-4 + M-7: the relief decision note ─────────────────────────────────────────────────────────────────────────────

    private fun reliefRequest(
        status: ContributionReliefStatus,
        executionError: String? = null,
    ) = ContributionReliefRequestDto(
        id = "req-7",
        subjectMemberId = "member-1",
        subjectDisplayName = "Amara Okafor",
        kind = ContributionReliefKind.EXEMPTION,
        status = status,
        reasonCategory = ContributionReliefReason.FINANCIAL_HARDSHIP,
        reasonText = null,
        requestedAt = LocalDateTime(2026, 1, 1, 10, 0),
        requestedBy = "member-1",
        requestedByDisplayName = "Amara Okafor",
        executionError = executionError,
    )

    @Test
    fun reliefDecide_theNoteIsMarkedAsRequired_andBothDecisionsCarryTheTrimmedNoteAndTheirOwnFlag(): Promise<Unit> =
        formTest {
            val decide = routeOf { rpcService<IContributionReliefService>().decideReliefRequest("i", true, "n") }
            withFetchStub { calls ->
                mountedForm("audit-relief-decide") { root, element ->
                    renderReliefRequestedDecidePanel(root, reliefRequest(ContributionReliefStatus.REQUESTED), onChanged = {})
                    // M-7: a single required field is recognisable as required (star + legend), not silently "case (c)".
                    assertEquals(1, element().querySelectorAll(".lapis-required-mark").length, "a star on the note")
                    assertTrue(element().textContent.orEmpty().contains("* Pflichtfeld"), "with the legend")
                    assertEquals("true", element().first("textarea").getAttribute("aria-required"))

                    // empty and blank note: nothing goes out, at all
                    element().buttonNamed("Genehmigen und ausführen").click()
                    delay(60)
                    assertEquals(0, calls.rpcCount, "no RPC at all for an empty note")
                    assertEquals("Bitte eine Entscheidungsnotiz eingeben.", element().shownErrors().single())
                    element().typeInto("Entscheidungsnotiz", "   ")
                    element().buttonNamed("Ablehnen").click()
                    delay(60)
                    assertEquals(0, calls.rpcCount, "a blank note blocks Ablehnen as well")

                    element().typeInto("Entscheidungsnotiz", "  Härtefall geprüft  ")
                    element().buttonNamed("Genehmigen und ausführen").click()
                    awaitUntil("approve", timeoutMs = 800) { calls.toRoute(decide).size == 1 }
                    val approve = calls.singleCall(decide)
                    assertEquals("req-7", approve.rpcParam(0) as String)
                    assertEquals(true, approve.rpcParam(1) as Boolean, "Genehmigen sends approve = true")
                    assertEquals("Härtefall geprüft", approve.rpcParam(2) as String, "the note arrives trimmed")
                    awaitUntil("the buttons are unlocked", timeoutMs = 800) {
                        !element().buttonNamed("Ablehnen").hasAttribute("disabled")
                    }

                    element().buttonNamed("Ablehnen").click()
                    awaitUntil("reject", timeoutMs = 800) { calls.toRoute(decide).size == 2 }
                    val reject = calls.toRoute(decide)[1]
                    assertEquals("req-7", reject.rpcParam(0) as String)
                    assertEquals(false, reject.rpcParam(1) as Boolean, "Ablehnen sends approve = false")
                    assertEquals("Härtefall geprüft", reject.rpcParam(2) as String)
                }
            }
        }

    @Test
    fun reliefRetry_theRetryNeedsNoNote_onlyRejectingDoes_andTheHintSaysSo(): Promise<Unit> =
        formTest {
            val decide = routeOf { rpcService<IContributionReliefService>().decideReliefRequest("i", true, "n") }
            val retry = routeOf { rpcService<IContributionReliefService>().retryReliefExecution("i") }
            withFetchStub { calls ->
                mountedForm("audit-relief-retry") { root, element ->
                    renderReliefApprovedRetryPanel(root, reliefRequest(ContributionReliefStatus.APPROVED, "STATE_CHANGED"), onChanged = {})
                    assertTrue(
                        element().textContent.orEmpty().contains("Nur für \"Ablehnen\" erforderlich."),
                        "the hint tells that the star is for Ablehnen only",
                    )
                    // Ablehnen without a note: blocked, no RPC at all
                    element().buttonNamed("Ablehnen").click()
                    delay(60)
                    assertEquals(0, calls.rpcCount)
                    assertEquals(1, element().shownErrors().size)

                    // The retry needs no note
                    element().buttonNamed("Ausführung wiederholen").click()
                    awaitUntil("retry", timeoutMs = 800) { calls.toRoute(retry).size == 1 }
                    assertEquals("req-7", calls.singleCall(retry).rpcParam(0) as String)
                    assertEquals(0, calls.toRoute(decide).size, "retry is not a decision")
                    awaitUntil("unlocked", timeoutMs = 800) { !element().buttonNamed("Ablehnen").hasAttribute("disabled") }

                    element().typeInto("Entscheidungsnotiz", "  nicht ausführbar  ")
                    element().buttonNamed("Ablehnen").click()
                    awaitUntil("reject", timeoutMs = 800) { calls.toRoute(decide).size == 1 }
                    val reject = calls.singleCall(decide)
                    assertEquals(false, reject.rpcParam(1) as Boolean)
                    assertEquals("nicht ausführbar", reject.rpcParam(2) as String)
                }
            }
        }

    // ── M-7: a single required field is recognisable as required ────────────────────────────────────────────────────────

    @Test
    fun aSingleRequiredField_getsAStarAndTheLegend_twoRequiredFieldsStillDoNot(): Promise<Unit> =
        formTest {
            withMountedRoot("audit-single-required") { root, element ->
                val form = root.lapisForm()
                form.textField(label = tr("Grund"), required = true)
                form.buttons(primary = Button("Weiter"))
                assertEquals(1, element().querySelectorAll(".lapis-required-mark").length)
                assertTrue(element().textContent.orEmpty().contains("* Pflichtfeld"))
            }
            withMountedRoot("audit-two-required") { root, element ->
                val form = root.lapisForm()
                form.textField(label = tr("A"), required = true)
                form.textField(label = tr("B"), required = true)
                form.buttons(primary = Button("Weiter"))
                assertEquals(0, element().querySelectorAll(".lapis-required-mark").length)
                assertFalse(element().textContent.orEmpty().contains("Pflichtfeld"))
            }
            withMountedRoot("audit-single-optional") { root, element ->
                val form = root.lapisForm()
                form.textField(label = tr("Notiz"))
                form.buttons(primary = Button("Weiter"))
                assertEquals(0, element().querySelectorAll(".lapis-required-mark").length, "a single OPTIONAL field needs no marking")
            }
        }

    @Test
    fun theReasonDialog_marksItsRequiredReason_theOptionalOneNot(): Promise<Unit> =
        formTest {
            mountedForm("audit-reason-marking") { _, _ ->
                confirmWithReasonDialog(title = "A", message = "M", reasonLabel = "Grund", reasonRequired = true, onConfirm = {})
                val required = lastOpenModal()
                assertEquals(1, required.querySelectorAll(".lapis-required-mark").length, "the required reason carries the star")
                assertTrue(required.textContent.orEmpty().contains("* Pflichtfeld"))
                closeOpenModals(timeoutMs = 500)

                confirmWithReasonDialog(title = "B", message = "M", reasonLabel = "Grund", reasonRequired = false, onConfirm = {})
                assertEquals(0, lastOpenModal().querySelectorAll(".lapis-required-mark").length)
            }
        }

    @Test
    fun theOptionalReasonHint_saysAtMost_notZeroTo(): Promise<Unit> =
        formTest {
            mountedForm("audit-reason-hint") { _, _ ->
                confirmWithReasonDialog(
                    title = "A",
                    message = "M",
                    reasonLabel = "Grund",
                    reasonRequired = false,
                    reasonMaxLength = 500,
                    onConfirm = {},
                )
                val text = lastOpenModal().textContent.orEmpty()
                assertTrue(text.contains("Höchstens 500 Zeichen."), text)
                assertFalse(text.contains("0 bis 500"), "an optional reason has no lower bound to announce")
            }
        }

    // ── checkbox `change` clears the error at once (Browser-only point 3 of the audit) ─────────────────────────────────

    @Test
    fun aRequiredCheckbox_theErrorGoesAwayImmediatelyWhenTheBoxIsTicked(): Promise<Unit> =
        formTest {
            withMountedRoot("audit-check-change") { root, element ->
                val form = root.lapisForm()
                form.checkField(label = tr("Ich stimme zu."), required = true, requiredMessage = "Bitte zustimmen.")
                form.textField(label = tr("Name"))
                form.buttons(primary = Button("Weiter"))
                assertFalse(form.validateAndReport(), "unticked: blocked")
                assertEquals("Bitte zustimmen.", element().shownErrors().single())
                val box = element().first("input[type=checkbox]") as HTMLInputElement
                assertEquals("true", box.getAttribute("aria-invalid"))
                box.click() // a real click: input + change fire synchronously, no timer in between
                assertEquals(0, element().shownErrors().size, "the error is gone the moment the box is ticked, without a timer")
                assertEquals("false", box.getAttribute("aria-invalid"))
                assertEquals(
                    "",
                    element().first("[role=alert]").textContent?.trim(),
                    "and the collective message of the form (if any) cleared too",
                )
                box.click() // unticked again: no NEW error appears by clicking (typing never creates an error)
                assertEquals(0, element().shownErrors().size)
            }
        }

    // ── m-14 + m-16: the member editor is six forms in one modal ────────────────────────────────────────────────────────

    private fun adminSession() =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2030, 1, 1, 12, 0),
        )

    private fun row(
        role: AccountRole?,
        status: MemberStatus,
    ) = MemberAdminRowDto(
        id = "member-5",
        displayName = "Amara Okafor",
        email = "amara@example.org",
        status = status,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = false,
    )

    private fun assertControlsAreUnique(modal: HTMLElement) {
        val ids = modal.allOf("[id]").map { it.id }
        assertEquals(ids.size, ids.toSet().size, "every id in the modal is unique: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        val labels = modal.allOf("label[for]")
        assertTrue(labels.isNotEmpty())
        labels.forEach { label ->
            val target = document.getElementById(label.getAttribute("for").orEmpty())
            assertNotNull(target, "label '${label.textContent?.trim()}' points at a missing control")
            assertTrue(modal.contains(target), "label '${label.textContent?.trim()}' points at a control outside its modal")
        }
        // aria-describedby points at existing slots, all inside the modal
        modal.allOf("[aria-describedby]").forEach { control ->
            control.getAttribute("aria-describedby").orEmpty().split(" ").filter { it.isNotBlank() }.forEach { id ->
                assertTrue(modal.querySelector("[id='$id']") != null, "aria-describedby points at '$id', which is not in the modal")
            }
        }
    }

    @Test
    fun memberEditor_everyControlIdIsUnique_andEveryLabelPointsAtItsOwnControl(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withFetchStub { _ ->
                // Three shapes: an active member with an account, a deceased one (the death-date form appears), one without an account (grant form).
                listOf(
                    row(AccountRole.MEMBER, MemberStatus.ACTIVE),
                    row(AccountRole.MEMBER, MemberStatus.DECEASED),
                    row(null, MemberStatus.ACTIVE),
                ).forEachIndexed { index, member ->
                    mountedForm("audit-editor-ids-$index") { _, _ ->
                        openMemberEditorDialog(member, onChanged = {})
                        val modal = lastOpenModal()
                        assertControlsAreUnique(modal)
                        // Equal labels exist several times ("Begründung", "Rolle" ...): the `for` of each resolves to a DIFFERENT control.
                        val labelled = modal.allOf("label[for]").map { it.getAttribute("for").orEmpty() }
                        assertEquals(labelled.size, labelled.toSet().size, "no two labels share a control")
                    }
                }
            }
        }

    @Test
    fun memberEditor_theLegendStandsOnceForTheWholeModal_theStarsStayOnEveryRequiredLabel(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withFetchStub { _ ->
                mountedForm("audit-editor-legend") { _, _ ->
                    openMemberEditorDialog(row(AccountRole.MEMBER, MemberStatus.DECEASED), onChanged = {})
                    val modal = lastOpenModal()
                    val legends = modal.allOf(".lapis-form .text-muted.small").filter { it.textContent?.trim() == "* Pflichtfeld" }
                    assertEquals(1, legends.size, "one legend for the whole dialog, not one per section")
                    assertTrue(modal.querySelectorAll(".lapis-required-mark").length >= 3, "the stars stay on every required label")
                    assertEquals(
                        modal.allOf(".lapis-required-mark").size,
                        modal.allOf("label").count { it.querySelector(".lapis-required-mark") != null },
                        "one star per label",
                    )
                }
            }
        }

    // ── m-18: the pointer gate cannot get stuck ─────────────────────────────────────────────────────────────────────────

    private fun mouse(type: String) = MouseEvent(type, MouseEventInit(bubbles = true, cancelable = true))

    /** A dirty, invalid field next to a button; pressing the button defers the blur check, `release` must bring it back. */
    private suspend fun assertGateReleasedBy(
        id: String,
        release: () -> Unit,
    ) {
        withMountedRoot(id) { root, element ->
            window.dispatchEvent(Event("blur")) // a clean gate, whatever the previous test left
            val form = root.lapisForm()
            form.textField(label = tr("Alter"), rule = { FormRules.wholeNumber(value = it) })
            val button = Button("Absenden", style = ButtonStyle.PRIMARY)
            form.buttons(primary = button)
            val input = element().first("input") as HTMLInputElement
            input.value = "abc"
            input.dispatchEvent(Event("input"))

            element().buttonNamed("Absenden").dispatchEvent(mouse("mousedown"))
            assertTrue(PointerGate.isHeld, "the gate is held while the button is pressed")
            input.dispatchEvent(Event("blur"))
            assertEquals(0, element().shownErrors().size, "the blur check is deferred while the button is pressed")

            release() // the mouseup never arrives
            assertFalse(PointerGate.isHeld, "the gate is released")
            assertEquals(1, element().shownErrors().size, "the deferred check ran on release")

            // and a later blur is checked at once again
            input.value = "5"
            input.dispatchEvent(Event("input"))
            input.value = "abc"
            input.dispatchEvent(Event("input"))
            assertEquals(0, element().shownErrors().size, "typing never creates an error")
            input.dispatchEvent(Event("blur"))
            assertEquals(1, element().shownErrors().size, "afterwards blur checks are immediate")
        }
    }

    @Test
    fun pointerGate_isReleasedByAWindowBlur_whenTheMouseupNeverArrives(): Promise<Unit> =
        formTest { assertGateReleasedBy("audit-gate-blur") { window.dispatchEvent(Event("blur")) } }

    @Test
    fun pointerGate_isReleasedByAPointerCancel(): Promise<Unit> =
        formTest {
            assertGateReleasedBy("audit-gate-cancel") { document.dispatchEvent(Event("pointercancel")) }
        }

    @Test
    fun pointerGate_isReleasedByAContextMenu(): Promise<Unit> =
        formTest {
            assertGateReleasedBy("audit-gate-context") { document.dispatchEvent(Event("contextmenu")) }
        }

    @Test
    fun pointerGate_isReleasedWhenThePointerLeavesTheWindow(): Promise<Unit> =
        formTest {
            assertGateReleasedBy("audit-gate-leave") { document.documentElement?.dispatchEvent(Event("mouseleave")) }
        }

    @Test
    fun pointerGate_aStuckGateFreesItselfAfterTheSafetyTimeout(): Promise<Unit> =
        formTest {
            val original = PointerGate.safetyTimeoutMs
            PointerGate.safetyTimeoutMs = 60
            try {
                withMountedRoot("audit-gate-timeout") { root, element ->
                    window.dispatchEvent(Event("blur"))
                    val form = root.lapisForm()
                    form.textField(label = tr("Alter"), rule = { FormRules.wholeNumber(value = it) })
                    form.buttons(primary = Button("Absenden", style = ButtonStyle.PRIMARY))
                    val input = element().first("input") as HTMLInputElement
                    input.value = "abc"
                    input.dispatchEvent(Event("input"))
                    element().buttonNamed("Absenden").dispatchEvent(mouse("mousedown"))
                    input.dispatchEvent(Event("blur"))
                    assertEquals(0, element().shownErrors().size)
                    delay(200)
                    assertFalse(PointerGate.isHeld, "released by the timeout")
                    assertEquals(1, element().shownErrors().size, "and the deferred check ran")
                }
            } finally {
                PointerGate.safetyTimeoutMs = original
            }
        }

    @Test
    fun pointerGate_aNormalClickStillDefersTheBlurUntilAfterTheClick(): Promise<Unit> =
        formTest {
            window.dispatchEvent(Event("blur"))
            var clicks = 0
            withMountedRoot("audit-gate-normal") { root, element ->
                val form = root.lapisForm()
                form.textField(label = tr("Alter"), rule = { FormRules.wholeNumber(value = it) })
                val button = Button("Absenden", style = ButtonStyle.PRIMARY)
                form.buttons(primary = button)
                button.onClick { clicks++ }
                val input = element().first("input") as HTMLInputElement
                input.value = "abc"
                input.dispatchEvent(Event("input"))
                val dom = element().buttonNamed("Absenden")
                dom.dispatchEvent(mouse("mousedown"))
                input.dispatchEvent(Event("blur"))
                dom.dispatchEvent(mouse("mouseup"))
                dom.click()
                delay(30)
                assertEquals(1, clicks, "the first click is delivered")
                assertEquals(1, element().shownErrors().size, "and the deferred check ran after it")
                assertFalse(PointerGate.isHeld)
            }
        }
}
