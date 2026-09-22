package network.lapis.cloud.client

import io.kvision.modal.Modal
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DunningLevelDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ReceivableDunningLevelDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Welle V1.4.28 audit, the smaller findings: the `FieldCheck` contract, the fee bounds, the lock icon and the SEPA load path. */
class FormAuditMinorTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private val marker = "###KvI18nS###"

    private fun message(check: FieldCheck): String = (check as FieldCheck.Invalid).message

    // ── FieldCheck.Invalid carries RESOLVED text, never a tr() marker ───────────────────────────────────────

    @Test
    fun feeChecks_returnResolvedText_neverATrMarker() {
        listOf("abc", "1e1", "12,999", "-3", "1,2,3").forEach { raw ->
            listOf(dunningFeeCheck(raw), receivableFeeCheck(raw), FormRules.optionalFee(raw, 25.0)).forEach { check ->
                assertTrue(check is FieldCheck.Invalid, "'$raw' must be invalid")
                assertFalse(message(check).contains(marker), "raw marker in the message for '$raw': ${message(check)}")
                assertTrue(message(check).isNotBlank())
            }
        }
    }

    @Test
    fun theFeeMessage_namesTheFieldsOwnBound_notTheBookingAmountLimit() {
        val tooLarge = "999999999999,00"
        listOf(dunningFeeCheck(tooLarge), receivableFeeCheck(tooLarge)).forEach { check ->
            val text = message(check)
            assertTrue(text.contains("25,00"), "the bound of the fee field must be named: $text")
            assertFalse(text.contains("1000000000"), "the booking-amount limit must not surface for a fee: $text")
        }
        assertEquals("Die Gebühr muss zwischen 0,00$NBSP€ und 25,00$NBSP€ liegen.", message(FormRules.optionalFee("25,01", 25.0)))
    }

    @Test
    fun aFeeOfZeroTo25_isValid_inBothLevelForms_andBlankIsValid() {
        listOf("0", "0,00", "0.0", "12,5", "25", "25,00", "").forEach { raw ->
            assertEquals(FieldCheck.Ok, dunningFeeCheck(raw), "dunning fee '$raw'")
            assertEquals(FieldCheck.Ok, receivableFeeCheck(raw), "receivable fee '$raw'")
        }
        assertTrue(dunningFeeCheck("25,01") is FieldCheck.Invalid)
        assertTrue(receivableFeeCheck("25,01") is FieldCheck.Invalid)
    }

    @Test
    fun aBookingAmount_stillMustBeGreaterThanZero_andBelowTheBookingLimit() {
        assertTrue(parseAmountInput("0") is AmountInput.Invalid)
        assertTrue(parseAmountInput("0,00") is AmountInput.Invalid)
        assertTrue(parseAmountInput("1000000000,01") is AmountInput.Invalid)
    }

    // ── the pure builders (field for field) ─────────────────────────────────────────────────────────────────

    @Test
    fun theDunningBuilder_trimsAndCutsTheName_andKeepsEveryNumberInItsOwnSlot() {
        val input =
            buildDunningLevelInput(
                levelNumber = " 3 ",
                name = " " + "x".repeat(150),
                graceDays = "14",
                responseDays = "9",
                fee = null,
                active = false,
            )
        assertEquals(3, input.levelNumber)
        assertEquals(100, input.name.length, "the server truncates at 100, the client mirrors it")
        assertEquals(14, input.graceDays)
        assertEquals(9, input.responseDays)
        assertEquals(false, input.active)
    }

    // ── the lock icon is an icon, not an emoji ──────────────────────────────────────────────────────────────

    @Test
    fun theStreamKeyFieldsLockIcon_isAHiddenFontAwesomeIcon_notAnEmoji() {
        withMountedRoot("audit-lock-icon") { root, element ->
            renderConferenceStreamDestinationsScreen(root)
            val icon = assertNotNull(element().querySelector(".lapis-field-actions .fa-lock") as? HTMLElement, "no lock icon")
            assertEquals("true", icon.getAttribute("aria-hidden"), "the icon carries no information a screen reader needs")
            assertFalse(element().textContent.orEmpty().contains("🔒"), "no lock emoji in the rendered screen")
        }
    }

    // ── SEPA: a stale error must not survive the loaded (valid) settings ─────────────────────────────────────

    @Test
    fun sepaLoadedSettings_clearAnErrorThatAppearedWhileTheyWereLoading(): Promise<Unit> =
        test {
            val settingsJson =
                """{"sepaCreditorId":"DE98ZZZ09999999999","sepaCreditorName":"Verein",""" +
                    """"sepaPrenotificationDays":5,"readyForFileGeneration":true}"""
            // Answer every argument-less call late, so the form can be submitted (empty) while the load is still in flight.
            val respond: (RecordedRequest) -> StubResponse = { request ->
                val paramCount = request.json.params.length as Int
                val stub = rpcResult(request.json.id as Int, if (paramCount == 0) settingsJson else "null")
                StubResponse(status = stub.status, text = stub.text, delayMs = if (paramCount == 0) 250 else 0)
            }
            withFetchStub(respond = respond) { _ ->
                withMountedRoot("audit-sepa-load") { root, element ->
                    renderSepaSettingsScreen(root)
                    (element().querySelectorAll("button").let { list -> (0 until list.length).map { list.item(it) as HTMLElement } })
                        .first { it.textContent?.trim() == "Speichern" }
                        .click()
                    assertTrue(
                        element().querySelectorAll(".lapis-field-error--shown").length > 0,
                        "precondition: the empty required field shows its error",
                    )
                    awaitUntil("the loaded settings arrive") {
                        (element().querySelectorAll("input[type=text]").item(2) as HTMLInputElement).value == "5"
                    }
                    assertEquals(0, element().querySelectorAll(".lapis-field-error--shown").length, "the stale error is gone")
                    assertEquals(0, element().querySelectorAll("input.is-invalid").length)
                    // Dirty/submitted must not turn a valid loaded value into an error on the next blur.
                    (element().querySelectorAll("input[type=text]").item(2) as HTMLInputElement).dispatchEvent(Event("blur"))
                    assertEquals(0, element().querySelectorAll(".lapis-field-error--shown").length)
                }
            }
        }

    // ── modals: the primary action stands in the footer, cancel left ─────────────────────────────────────────

    private fun footerButtons(modal: HTMLElement): List<HTMLElement> =
        (0 until modal.querySelectorAll(".modal-footer button").length).map {
            modal.querySelectorAll(".modal-footer button").item(it) as HTMLElement
        }

    private fun assertFooterOrder(modal: HTMLElement) {
        val buttons = footerButtons(modal)
        assertEquals(listOf("Abbrechen", "Speichern"), buttons.map { it.textContent?.trim() }, "cancel left, save right")
        assertTrue(buttons.last().classList.contains("btn-primary"), "the confirming action is the primary button")
        assertEquals(0, modal.querySelectorAll(".modal-body button").length, "no button row inside the form body")
        assertNotNull(modal.querySelector("[role=alert]"), "the alert region is mounted by finish()")
    }

    private fun openModal(): HTMLElement {
        val modals = document.querySelectorAll(".modal")
        return modals.item(modals.length - 1) as HTMLElement
    }

    @Test
    fun theDunningLevelModal_hasCancelLeftAndSaveRightInTheFooter_andNoButtonRowInTheBody(): Promise<Unit> =
        test {
            withMountedRoot("audit-modal-footer-dunning") { _, _ ->
                val modal = Modal(caption = "Mahnstufe bearbeiten")
                renderDunningLevelForm(
                    modal,
                    existing =
                        DunningLevelDto(
                            id = "d",
                            levelNumber = 2,
                            name = "Stufe",
                            graceDays = 5,
                            responseDays = 6,
                            feeAmount = null,
                            active = true,
                        ),
                    modal = modal,
                ) {}
                modal.show()
                assertFooterOrder(openModal())
            }
        }

    @Test
    fun theReceivableDunningLevelModal_hasCancelLeftAndSaveRightInTheFooter_andNoButtonRowInTheBody(): Promise<Unit> =
        test {
            withMountedRoot("audit-modal-footer-receivable") { _, _ ->
                val modal = Modal(caption = "Mahnstufe bearbeiten")
                renderReceivableLevelForm(
                    modal,
                    existing = ReceivableDunningLevelDto(id = "r", levelNumber = 2, name = "Stufe", graceDays = 5, responseDays = 6),
                    modal = modal,
                ) {}
                modal.show()
                assertFooterOrder(openModal())
            }
        }

    @Test
    fun theTemporaryPasswordAction_isADestructiveButtonInTheDangerZone_andTheReasonKeepsItsLengthHint(): Promise<Unit> =
        test {
            withMountedRoot("audit-password-destructive") { _, _ ->
                openMemberPasswordResetDialog(
                    row =
                        MemberAdminRowDto(
                            id = "member-1",
                            displayName = "Danger Test",
                            email = "danger@example.org",
                            status = MemberStatus.ACTIVE,
                            role = AccountRole.MEMBER,
                            joinedAt = LocalDate(2026, 1, 1),
                            anonymized = false,
                        ),
                    onChanged = {},
                )
                val modals = document.querySelectorAll(".modal")
                val modal = modals.item(modals.length - 1) as HTMLElement
                val zoneButton = assertNotNull(modal.querySelector(".lapis-form-danger-zone button") as? HTMLElement, "no danger zone")
                assertEquals("Passwort setzen und alle Sitzungen beenden", zoneButton.textContent?.trim())
                assertTrue(zoneButton.classList.contains("btn-danger"))
                assertEquals(
                    0,
                    modal.querySelectorAll(".modal-body button.btn-primary").length,
                    "the form has no PRIMARY (R28: two forbidden, none allowed)",
                )
                assertTrue(
                    modal.textContent.orEmpty().contains("3 bis 1000 Zeichen."),
                    "the length rule of the reason stays visible as a hint",
                )
            }
        }
}
