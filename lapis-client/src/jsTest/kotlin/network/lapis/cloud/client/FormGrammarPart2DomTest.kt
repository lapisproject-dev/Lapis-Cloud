package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Welle V1.4.29 (W4b): what the two additions to the form grammar (`selectField`, `checkField`), the reworked
 * [confirmWithReasonDialog] and the door check-in do in a REAL mounted document (Karma runs in a real Chrome; `blur`, `change`,
 * `keydown` are real DOM events). A text-level test cannot see: that `blur` reaches a `<select>` (it does not bubble), that
 * the hint/error slots of a choice field sit OUTSIDE the `<select>`, that a checkbox is "required" although its string value
 * is never empty, and that the Enter key of a barcode scanner still submits.
 */
class FormGrammarPart2DomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            // Leftovers of other test classes (a hidden modal, a live focus trap) must not be found as 'the last modal'.
            closeOpenModals(timeoutMs = 300)
            try {
                block()
            } finally {
                closeOpenModals(timeoutMs = 400)
            }
        }

    /**
     * [withMountedRoot] that closes every open modal BEFORE the root is disposed: disposing the root tears the modal out of the
     * document while Bootstrap still holds its focus trap, which pulls `document.activeElement` onto a modal button in whichever
     * test runs next (see [closeOpenModals]).
     */
    private suspend inline fun <T> mounted(
        id: String,
        block: (io.kvision.panel.Root, () -> HTMLElement) -> T,
    ): T =
        withMountedRoot(id) { root, element ->
            try {
                block(root, element)
            } finally {
                closeOpenModals(timeoutMs = 1000)
            }
        }

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun lastModal(): HTMLElement {
        val modals = document.querySelectorAll(".modal")
        return assertNotNull(modals.item(modals.length - 1) as? HTMLElement, "no modal")
    }

    private fun HTMLElement.button(text: String): HTMLElement = all("button").first { it.textContent?.trim() == text }

    private fun mouse(type: String) = MouseEvent(type, MouseEventInit(bubbles = true, cancelable = true))

    // ── selectField ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun selectField_blurOnTheSelectShowsTheRequiredError_hintAndErrorAreOutsideTheSelect() {
        withMountedRoot("p2-select") { root, element ->
            val form = root.lapisForm()
            form.selectField(
                label = tr("Tarif"),
                options = listOf("" to "— kein Tarif —", "a" to "Tarif A"),
                value = "",
                required = true,
                hint = "Ein Hinweis zum Tarif.",
            )
            form.textField(label = tr("Name"))
            form.buttons(primary = Button("Speichern"))
            val select = element().first("select") as HTMLSelectElement
            assertNull(select.querySelector(".lapis-field-error"), "the error slot must not live inside the <select>")
            assertNull(select.querySelector(".form-text"), "the hint must not live inside the <select>")
            val describedBy = assertNotNull(select.getAttribute("aria-describedby")).split(" ")
            describedBy.forEach { id ->
                val target = assertNotNull(document.getElementById(id), "aria-describedby points at a missing element: $id")
                assertFalse(select.contains(target), "aria-describedby must not point at a descendant of the select")
            }
            assertEquals("true", select.getAttribute("aria-required"))
            // `blur` does not bubble: it only reaches the validation if the listener sits on the <select> itself. A `change`
            // first (the person touched the field), then leaving it.
            select.dispatchEvent(Event("change"))
            select.dispatchEvent(Event("blur"))
            assertEquals("Dieses Feld muss ausgefüllt werden.", element().first(".lapis-field-error--shown").textContent?.trim())
            assertEquals("true", select.getAttribute("aria-invalid"))
        }
    }

    @Test
    fun selectField_setValueAfterLoadingOptions_setsTheValue_keepsTheFieldUntouched_andClearsAStaleError() {
        withMountedRoot("p2-select-setvalue") { root, element ->
            val form = root.lapisForm()
            val field =
                form.selectField(
                    label = tr("Mitglied"),
                    options = listOf("" to "— bitte wählen —", "m1" to "Amara", "m2" to "Kofi"),
                    value = "",
                    required = true,
                )
            form.buttons(primary = Button("Weiter"))
            assertFalse(form.validateAndReport(), "an empty required select is invalid")
            assertTrue(element().querySelectorAll(".lapis-field-error--shown").length == 1)
            field.setValue("m2")
            field.validate(force = false)
            assertEquals("m2", field.value)
            assertEquals(0, element().querySelectorAll(".lapis-field-error--shown").length, "the stale error is gone")
            assertTrue(form.validateAndReport())
            val fresh = form.fields.first()
            assertFalse(fresh.dirty, "a programmatic write is not typing: the field stays untouched")
        }
    }

    // ── checkField ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun checkField_requiredMeansTicked_theErrorStandsAtTheField_notInTheCollectiveArea() {
        withMountedRoot("p2-check") { root, element ->
            val form = root.lapisForm()
            form.checkField(label = tr("Ich akzeptiere."), required = true, requiredMessage = "Bitte bestätigen.")
            form.buttons(primary = Button("Weiter"))
            val box = element().first("input[type=checkbox]") as HTMLInputElement
            assertEquals("true", box.getAttribute("aria-required"))
            assertFalse(form.validateAndReport(), "an unchecked required checkbox blocks (its string value 'false' is not empty)")
            assertEquals("Bitte bestätigen.", element().first(".lapis-field-error--shown").textContent?.trim())
            assertEquals("", element().first("[role=alert]").textContent?.trim(), "a single failing field: no collective sentence")
            box.click()
            assertTrue(form.validateAndReport())
            assertEquals(0, element().querySelectorAll(".lapis-field-error--shown").length)
        }
    }

    @Test
    fun checkField_theBlurListenerSitsOnTheInput() {
        withMountedRoot("p2-check-blur") { root, element ->
            val form = root.lapisForm()
            form.checkField(label = tr("Zustimmung"), required = true, requiredMessage = "Bitte bestätigen.")
            form.textField(label = tr("Name"))
            form.buttons(primary = Button("Weiter"))
            val box = element().first("input[type=checkbox]") as HTMLInputElement
            box.dispatchEvent(Event("change"))
            box.dispatchEvent(Event("blur"))
            assertEquals("Bitte bestätigen.", element().first(".lapis-field-error--shown").textContent?.trim())
        }
    }

    // ── confirmWithReasonDialog ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun confirmWithReason_blankReason_reportsAtTheField_focusesIt_keepsTheModalOpen_andDoesNotConfirm(): Promise<Unit> =
        test {
            mounted("p2-confirmwithreason_blankr") { _, _ ->
                var confirmed = 0
                confirmWithReasonDialog(
                    title = "Stornieren",
                    message = "Wirklich?",
                    reasonLabel = "Grund",
                    reasonRequired = true,
                    onConfirm = { confirmed++ },
                )
                val modal = lastModal()
                // Focus lands only in a VISIBLE modal: Bootstrap shows it a frame after `show()`.
                awaitUntil("the modal is shown", timeoutMs = 600) { modal.classList.contains("show") }
                val confirm = modal.button("Bestätigen")
                assertFalse(
                    confirm.hasAttribute("disabled"),
                    "the confirm button is NOT disabled: a grey button without an explanation lies",
                )
                confirm.click()
                assertEquals(0, confirmed)
                assertEquals("Dieses Feld muss ausgefüllt werden.", modal.first(".lapis-field-error--shown").textContent?.trim())
                assertSame(modal.first("input[type=text]"), document.activeElement, "the focus goes to the field")
                assertTrue(modal.isConnected, "the modal stays open")
            }
        }

    @Test
    fun confirmWithReason_trimmedReasonIsPassed_maxLengthBlocks_optionalEmptyIsNull(): Promise<Unit> =
        test {
            mounted("p2-confirmwithreason_trimme") { _, _ ->
                var received: String? = "unset"
                confirmWithReasonDialog(
                    title = "Stornieren",
                    message = "Wirklich?",
                    reasonLabel = "Grund",
                    reasonRequired = true,
                    reasonMaxLength = 10,
                    onConfirm = { received = it },
                )
                var modal = lastModal()
                val input = modal.first("input[type=text]") as HTMLInputElement
                input.value = "  zu lang für das Limit  "
                input.dispatchEvent(Event("input"))
                modal.button("Bestätigen").click()
                assertEquals("unset", received, "over the limit: no confirmation")
                assertTrue(
                    modal
                        .first(".lapis-field-error--shown")
                        .textContent
                        .orEmpty()
                        .isNotBlank(),
                )
                input.value = "  kurz  "
                input.dispatchEvent(Event("input"))
                modal.button("Bestätigen").click()
                assertEquals("kurz", received, "the reason arrives trimmed")
                closeOpenModals(timeoutMs = 500)

                received = "unset"
                confirmWithReasonDialog(
                    title = "Absagen",
                    message = "Wirklich?",
                    reasonLabel = "Grund",
                    reasonRequired = false,
                    onConfirm = { received = it },
                )
                modal = lastModal()
                modal.button("Bestätigen").click()
                assertNull(received, "an optional, empty reason confirms with null")
            }
        }

    @Test
    fun confirmWithTypedConfirmation_theButtonStaysDisabledUntilTheTextMatches(): Promise<Unit> =
        test {
            mounted("p2-confirmwithtypedconfirma") { _, _ ->
                var confirmed = 0
                confirmWithTypedConfirmationDialog(
                    title = "Löschen",
                    message = "Endgültig.",
                    expectedText = "Amara Okafor",
                    onConfirm = { confirmed++ },
                )
                val modal = lastModal()
                val confirm = modal.button("Endgültig löschen")
                assertTrue(confirm.hasAttribute("disabled"), "the strictest tier keeps its friction on purpose")
                val input = modal.first("input[type=text]") as HTMLInputElement
                input.value = "Amara Okafo"
                input.dispatchEvent(Event("input"))
                delay(30)
                assertTrue(confirm.hasAttribute("disabled"))
                input.value = "Amara Okafor"
                input.dispatchEvent(Event("input"))
                delay(30)
                assertFalse(confirm.hasAttribute("disabled"))
                confirm.click()
                assertEquals(1, confirmed)
            }
        }

    @Test
    fun pointerGate_insideAModal_theFirstClickOnTheConfirmButtonIsNotSwallowedByTheFieldError(): Promise<Unit> =
        test {
            mounted("p2-pointergate_insideamodal") { _, _ ->
                var confirmed = 0
                confirmWithReasonDialog(
                    title = "Stornieren",
                    message = "Wirklich?",
                    reasonLabel = "Grund",
                    reasonRequired = true,
                    reasonMaxLength = 5,
                    onConfirm = { confirmed++ },
                )
                val modal = lastModal()
                val input = modal.first("input[type=text]") as HTMLInputElement
                input.value = "viel zu lang"
                input.dispatchEvent(Event("input"))
                val confirm = modal.button("Bestätigen")
                // The real sequence of a mouse click on a button while the field has focus: mousedown -> blur -> mouseup -> click.
                confirm.dispatchEvent(mouse("mousedown"))
                input.dispatchEvent(Event("blur"))
                assertEquals(
                    0,
                    modal.querySelectorAll(".lapis-field-error--shown").length,
                    "the check is deferred while the button is pressed",
                )
                confirm.dispatchEvent(mouse("mouseup"))
                confirm.click()
                delay(30)
                assertEquals(0, confirmed, "the invalid reason never confirms")
                assertEquals(1, modal.querySelectorAll(".lapis-field-error--shown").length, "after the click the deferred check ran")
            }
        }

    // ── the door: EventCheckInScreen ────────────────────────────────────────────────────────────────────────

    private val eventId = "00000000-0000-0000-0000-0000000000e1"

    private val rosterJson =
        """{"eventId":"$eventId","eventTitle":"Sommerfest","startsAt":"2026-08-15T18:00:00","locationText":"Halle 3",""" +
            """"rows":[],"confirmedCount":0,"checkedInCount":0}"""

    /** The route of the code check-in: a request is attributed by its route (the service method), never by its number of parameters. */
    private suspend fun checkInRoute(): String =
        routeOf {
            rpcService<network.lapis.cloud.shared.rpc.IEventService>().checkInByCode(eventId, "x")
        }

    /** Answers a code check-in (the route [checkIn]) with [checkInJson] and everything else (the roster load) with an empty roster. */
    private fun doorAnswers(
        checkIn: String,
        checkInJson: String,
    ): (RecordedRequest) -> StubResponse =
        { request ->
            val id = request.json.id as Int
            if (!request.isRpc) {
                StubResponse()
            } else if (request.rpcRoute == checkIn) {
                rpcResult(id, checkInJson)
            } else {
                rpcResult(id, rosterJson)
            }
        }

    private fun HTMLElement.enter(input: HTMLInputElement) {
        input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", cancelable = true, bubbles = true)))
    }

    @Test
    fun door_enterSendsTheCode_thenTheFieldIsEmptyCleanAndFocused_andTheNextScanSendsAgain(): Promise<Unit> =
        test {
            val checkIn = checkInRoute()
            withFetchStub(respond = doorAnswers(checkIn, """{"outcome":"OK","participantName":"Amara"}""")) { calls ->
                mounted("p2-door") { root, element ->
                    // The screen patches itself once more when the roster arrives: always look the live field up again.
                    fun codeInput() = element().first("input[placeholder='ABCD-EFGH-JKMN-PQRS']") as HTMLInputElement
                    renderEventCheckInScreen(root, eventId)
                    awaitUntil("the code field", timeoutMs = 600) {
                        element().querySelector("input[placeholder='ABCD-EFGH-JKMN-PQRS']") !=
                            null
                    }
                    delay(200)
                    assertEquals("characters", codeInput().getAttribute("autocapitalize"))
                    codeInput().value = "ABCD-EFGH-JKMN-PQRS"
                    codeInput().dispatchEvent(Event("input"))
                    element().enter(codeInput())
                    awaitUntil(
                        "first check-in RPC",
                        timeoutMs = 600,
                    ) { calls.count { it.isRpc && it.rpcRoute == checkIn } == 1 }
                    val sent = calls.first { it.isRpc && it.rpcRoute == checkIn }
                    assertEquals(eventId, sent.rpcParam(0) as String)
                    assertEquals("ABCD-EFGH-JKMN-PQRS", sent.rpcParam(1) as String)
                    delay(300)
                    assertEquals("", codeInput().value, "the field is emptied after a scan")
                    assertEquals(0, element().querySelectorAll(".lapis-field-error--shown").length, "the field is clean after a scan")
                    assertSame(
                        codeInput(),
                        document.activeElement,
                        "the focus is back in the field: the next scan must not type into the void",
                    )
                    assertTrue(element().textContent.orEmpty().contains("Eingecheckt: Amara"))
                    codeInput().value = "WXYZ-2345-6789-ABCD"
                    codeInput().dispatchEvent(Event("input"))
                    element().enter(codeInput())
                    awaitUntil("second check-in RPC", timeoutMs = 600) {
                        calls.count { it.isRpc && it.rpcRoute == checkIn } ==
                            2
                    }
                }
            }
        }

    @Test
    fun door_anInvalidCode_isReportedAtTheField_withoutARequest_andACorrectedCodeSendsOnTheFirstEnter(): Promise<Unit> =
        test {
            val checkIn = checkInRoute()
            withFetchStub(respond = doorAnswers(checkIn, """{"outcome":"OK","participantName":"Amara"}""")) { calls ->
                mounted("p2-door-invalid") { root, element ->
                    renderEventCheckInScreen(root, eventId)
                    awaitUntil("the code field", timeoutMs = 600) {
                        element().querySelector("input[placeholder='ABCD-EFGH-JKMN-PQRS']") !=
                            null
                    }

                    // Look the live field up every time: an invalid code shows the red result banner (the door's signal), and that patch
                    // re-creates the input element -- a reference captured before it would be a detached node.
                    fun input() = element().first("input[placeholder='ABCD-EFGH-JKMN-PQRS']") as HTMLInputElement
                    input().value = "garbage"
                    input().dispatchEvent(Event("input"))
                    element().enter(input())
                    delay(60)
                    assertEquals("Code unbekannt.", element().first(".lapis-field-error--shown").textContent?.trim())
                    assertEquals(0, calls.count { it.isRpc && it.rpcRoute == checkIn }, "no request for obvious garbage")
                    // Enter again with the error still standing: it still reaches the handler (and still reports, without a request).
                    element().enter(input())
                    delay(60)
                    assertEquals(0, calls.count { it.isRpc && it.rpcRoute == checkIn })
                    input().value = "ABCD-EFGH-JKMN-PQRS"
                    input().dispatchEvent(Event("input"))
                    element().enter(input())
                    awaitUntil("the corrected code is sent", timeoutMs = 600) {
                        calls.count { it.isRpc && it.rpcRoute == checkIn } == 1
                    }
                }
            }
        }

    // ── XSS: a foreign text is text, never markup ───────────────────────────────────────────────────────────

    @Test
    fun aForeignDisplayName_inAModalTitleAndAFieldError_staysText(): Promise<Unit> =
        test {
            mounted("p2-aforeigndisplayname_inam") { _, _ ->
                val payload = "<img src=x onerror=alert(1)>"
                rejectApplicationDialog(applicantName = payload, onConfirm = {})
                val modal = lastModal()
                assertNull(modal.querySelector("img"), "the applicant name in the modal title must not become an element")
                assertTrue(modal.textContent.orEmpty().contains(payload), "the payload is shown as text")
                modal.button("Ablehnen").click()
                assertEquals("Bitte einen Grund angeben.", modal.first(".lapis-field-error--shown").textContent?.trim())
                assertNull(modal.querySelector("img"))
            }
        }

    // ── network failure: a destructive action reports honestly and unlocks the button ─────────────────────────

    @Test
    fun aNetworkErrorOnAnAction_unlocksTheButton_andSetsAriaBusyBack(): Promise<Unit> =
        test {
            withFetchStub(respond = { StubResponse(networkError = true) }) { calls ->
                mounted("p2-network") { root, element ->
                    val form = root.lapisForm()
                    val field = form.textField(label = tr("Notiz"), required = true)
                    val button = Button("Absenden", style = ButtonStyle.PRIMARY)
                    form.buttons(primary = button)
                    field.setValue("x")
                    button.onClick {
                        form.submit(button) {
                            guarded { rpcService<network.lapis.cloud.shared.rpc.IMemberService>().listMembers() }
                        }
                    }
                    val domButton = element().button("Absenden")
                    domButton.click()
                    awaitUntil("the request went out", timeoutMs = 600) { calls.isNotEmpty() }
                    awaitUntil("the button is unlocked again", timeoutMs = 600) { !domButton.hasAttribute("disabled") }
                    assertEquals("false", domButton.getAttribute("aria-busy"))
                }
            }
        }

    // ── the marker of the i18n layer never reaches the screen ────────────────────────────────────────────────

    @Test
    fun noTranslationMarkerAppearsInAFormOfThisWave(): Promise<Unit> =
        test {
            mounted("p2-marker") { root, element ->
                val form = root.lapisForm()
                form.selectField(label = tr("Tarif"), options = listOf("" to "—", "a" to "A"), value = "", required = true)
                form.checkField(label = tr("Zustimmung"), required = true)
                form.textAreaField(label = tr("Begründung"), rows = 2, required = true, hint = "3 bis 1000 Zeichen.")
                form.buttons(primary = Button("Weiter"))
                assertFalse(form.validateAndReport())
                val html = element().innerHTML
                assertFalse(html.contains("###KvI18nS###"), "no i18n marker in the markup or in an attribute")
                assertTrue(html.contains("Bitte korrigieren Sie diese Felder"))
                val area = element().first("textarea") as HTMLTextAreaElement
                assertEquals("true", area.getAttribute("aria-invalid"))
            }
        }
}
