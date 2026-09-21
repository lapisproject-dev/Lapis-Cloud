package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DunningLevelDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ReceivableDunningLevelDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 audit (MA-5): WHAT a form sends. The earlier DOM tests counted `fetch` calls; nothing pinned the request
 * body, so two same-typed fields swapped in a form (name/e-mail, creditor id/name, grace/response days), a password that
 * got trimmed, or a value that stopped being trimmed would have passed every test. Each test drives the REAL screen in a
 * mounted root with a stubbed `window.fetch` ([withFetchStub]) and parses what actually went over the wire -- the field
 * values are deliberately DISTINCT and padded with spaces so a swap or a wrong trim is visible.
 *
 * Rule under test everywhere: text fields are trimmed, a PASSWORD is never trimmed (a trimmed password would be another
 * password than the one the person chose).
 */
class FormSubmitBodyDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun HTMLElement.inputs(selector: String): List<HTMLInputElement> = all(selector).map { it as HTMLInputElement }

    private fun fill(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
        input.dispatchEvent(Event("blur"))
    }

    private fun HTMLElement.button(text: String): HTMLElement = all("button").first { it.textContent?.trim() == text }

    /** The most recently opened modal (Bootstrap modals live on `body`, outside the mounted root). */
    private fun lastModal(): HTMLElement? {
        val modals = document.querySelectorAll(".modal")
        return if (modals.length == 0) null else modals.item(modals.length - 1) as HTMLElement
    }

    private val padPassword = "  pass word 4711 x  "

    // ── AuthHttp forms (plain fetch, JSON body) ─────────────────────────────────────────────────────────────

    @Test
    fun login_sendsTheEmailTrimmed_andThePasswordExactlyAsTyped(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("body-login") { root, element ->
                    renderLoginScreen(root)
                    fill(element().inputs("input[type=email]")[0], "   amara@example.org  ")
                    fill(element().inputs("input[type=password]")[0], "  s3cret pass  ")
                    element().button("Anmelden").click()
                    awaitUntil("login request") { calls.any { it.url.endsWith("/api/auth/login") } }
                    val body = calls.first { it.url.endsWith("/api/auth/login") }.json
                    assertEquals("amara@example.org", body.email as String, "the e-mail is trimmed")
                    assertEquals("  s3cret pass  ", body.password as String, "the password is NEVER trimmed")
                }
            }
        }

    @Test
    fun passwordResetDeepLink_sendsTheTokenAndTheUntrimmedPassword(): Promise<Unit> =
        test {
            withFetchStub(respond = { StubResponse(status = 400, text = "Ungültig.") }) { calls ->
                withMountedRoot("body-reset-deeplink") { root, element ->
                    renderPasswordResetScreen(root, token = "tok-from-mail-77")
                    fill(element().inputs("input[type=password]")[0], padPassword)
                    element().button("Neues Passwort setzen").click()
                    awaitUntil("confirm request") { calls.any { it.url.endsWith("/api/auth/password-reset/confirm") } }
                    val body = calls.first { it.url.endsWith("/api/auth/password-reset/confirm") }.json
                    assertEquals("tok-from-mail-77", body.token as String)
                    assertEquals(padPassword, body.newPassword as String)
                }
            }
        }

    @Test
    fun forgotPasswordPanel_requestSendsTheTrimmedEmail_confirmSendsTheTrimmedTokenAndTheUntrimmedPassword(): Promise<Unit> =
        test {
            withFetchStub(respond = { StubResponse(status = 400, text = "Nein.") }) { calls ->
                withMountedRoot("body-forgot") { root, element ->
                    renderLoginScreen(root)
                    element().all("a").first { it.textContent?.trim() == "Passwort vergessen?" }.click()
                    fill(element().inputs("input[type=email]")[1], "  hans@intranet ")
                    element().button("Zurücksetzen anfordern").click()
                    awaitUntil("request request") { calls.any { it.url.endsWith("/api/auth/password-reset/request") } }
                    assertEquals("hans@intranet", calls.first { it.url.endsWith("/password-reset/request") }.json.email as String)

                    fill(element().inputs("input[type=text]")[0], "  token-42  ")
                    fill(element().inputs("input[type=password]")[1], padPassword)
                    element().button("Neues Passwort setzen").click()
                    awaitUntil("confirm request") { calls.any { it.url.endsWith("/api/auth/password-reset/confirm") } }
                    val body = calls.first { it.url.endsWith("/password-reset/confirm") }.json
                    assertEquals("token-42", body.token as String)
                    assertEquals(padPassword, body.newPassword as String)
                }
            }
        }

    // ── Kilua RPC forms (fetch(Request), JSON-RPC body) ─────────────────────────────────────────────────────

    /** Answers the argument-less load call (`getMembershipAgreement`/`getFriendTerms`) with [loadJson], everything else with `null`. */
    private fun answerLoadWith(loadJson: String): (RecordedRequest) -> StubResponse =
        { request ->
            val paramCount = request.json.params.length as Int
            rpcResult(request.json.id as Int, if (paramCount == 0) loadJson else "null")
        }

    private val agreementJson = """{"version":"v7","text":"Vertragstext","sha256":"sha-agreement-abc"}"""

    @Test
    fun registration_sendsEveryFieldInItsOwnSlot_nameAndEmailTrimmed_passwordUntrimmed(): Promise<Unit> =
        test {
            withFetchStub(respond = answerLoadWith(agreementJson)) { calls ->
                withMountedRoot("body-registration") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector("input[type=email]") != null }
                    fill(element().inputs("input[type=text]")[0], "  Amara Okafor  ")
                    fill(element().inputs("input[type=email]")[0], "  amara@example.org ")
                    val passwords = element().inputs("input[type=password]")
                    fill(passwords[0], padPassword)
                    fill(passwords[1], padPassword)
                    element().inputs("input[type=checkbox]")[0].click()
                    element().button("Antrag einreichen").click()
                    awaitUntil("registerApplication") { calls.any { it.isRpc && (it.json.params.length as Int) == 1 } }
                    val input = calls.first { it.isRpc && (it.json.params.length as Int) == 1 }.rpcParam(0)
                    assertEquals("Amara Okafor", input.displayName as String)
                    assertEquals("amara@example.org", input.email as String)
                    assertEquals(padPassword, input.password as String)
                    assertEquals("v7", input.agreementVersion as String)
                    assertEquals("sha-agreement-abc", input.agreementSha256 as String)
                }
            }
        }

    @Test
    fun friendRegistration_sendsEveryFieldInItsOwnSlot(): Promise<Unit> =
        test {
            val termsJson = """{"version":"f3","text":"Bedingungen","sha256":"sha-terms-xyz"}"""
            withFetchStub(respond = answerLoadWith(termsJson)) { calls ->
                withMountedRoot("body-friend") { root, element ->
                    renderFriendRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector("input[type=email]") != null }
                    fill(element().inputs("input[type=text]")[0], "  Kofi Mensah ")
                    fill(element().inputs("input[type=email]")[0], " kofi@example.org  ")
                    val passwords = element().inputs("input[type=password]")
                    fill(passwords[0], padPassword)
                    fill(passwords[1], padPassword)
                    element().inputs("input[type=checkbox]")[0].click()
                    element().button("Freund-Konto anlegen").click()
                    awaitUntil("registerFriend") { calls.any { it.isRpc && (it.json.params.length as Int) == 1 } }
                    val input = calls.first { it.isRpc && (it.json.params.length as Int) == 1 }.rpcParam(0)
                    assertEquals("Kofi Mensah", input.displayName as String)
                    assertEquals("kofi@example.org", input.email as String)
                    assertEquals(padPassword, input.password as String)
                    assertEquals("f3", input.termsVersion as String)
                    assertEquals("sha-terms-xyz", input.termsSha256 as String)
                }
            }
        }

    @Test
    fun sepaCreditorSettings_creditorIdAndNameAreNotSwapped_blankBecomesNull(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("body-sepa") { root, element ->
                    renderSepaSettingsScreen(root)
                    val texts = element().inputs("input[type=text]")
                    fill(texts[0], "  DE98ZZZ09999999999 ")
                    fill(texts[1], "  Musterverein e. V.  ")
                    fill(texts[2], " 7 ")
                    element().button("Speichern").click()
                    awaitUntil("update request") { calls.any { it.isRpc && (it.json.params.length as Int) == 1 } }
                    val input = calls.first { it.isRpc && (it.json.params.length as Int) == 1 }.rpcParam(0)
                    assertEquals("DE98ZZZ09999999999", input.sepaCreditorId as String)
                    assertEquals("Musterverein e. V.", input.sepaCreditorName as String)
                    assertEquals(7, input.sepaPrenotificationDays as Int)
                }
            }
            withFetchStub { calls ->
                withMountedRoot("body-sepa-blank-id") { root, element ->
                    renderSepaSettingsScreen(root)
                    val texts = element().inputs("input[type=text]")
                    fill(texts[1], "Nur Name")
                    fill(texts[2], "5")
                    element().button("Speichern").click()
                    awaitUntil("update request") { calls.any { it.isRpc && (it.json.params.length as Int) == 1 } }
                    val input = calls.first { it.isRpc && (it.json.params.length as Int) == 1 }.rpcParam(0)
                    assertTrue(input.sepaCreditorId == null, "a blank creditor id is sent as null, not as an empty string")
                    assertEquals("Nur Name", input.sepaCreditorName as String)
                    assertEquals(5, input.sepaPrenotificationDays as Int)
                }
            }
        }

    @Test
    fun dunningLevel_everyNumberGoesIntoItsOwnField(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("body-dunning") { root, element ->
                    renderDunningLevelForm(root, existing = null) {}
                    val texts = element().inputs("input[type=text]")
                    fill(texts[0], " 3 ")
                    fill(texts[1], "  Zweite Mahnung  ")
                    fill(texts[2], " 14 ")
                    fill(texts[3], " 9 ")
                    fill(texts[4], "12,5")
                    element().button("Mahnstufe anlegen").click()
                    awaitUntil("create request") { calls.any { it.isRpc } }
                    val input = calls.first { it.isRpc }.rpcParam(0)
                    assertEquals(3, input.levelNumber as Int)
                    assertEquals("Zweite Mahnung", input.name as String)
                    assertEquals(14, input.graceDays as Int)
                    assertEquals(9, input.responseDays as Int)
                    assertEquals(12.5, input.feeAmount.toString().toDouble())
                    // `active = true` is the default value and default values are not encoded.
                    assertTrue(input.active == undefined || input.active == true, "a new level is active")
                }
            }
        }

    @Test
    fun receivableDunningLevel_everyNumberGoesIntoItsOwnField_andFeeZeroIsAccepted(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("body-receivable") { root, element ->
                    renderReceivableLevelForm(root, existing = null) {}
                    val texts = element().inputs("input[type=text]")
                    fill(texts[0], " 4 ")
                    fill(texts[1], "  Letzte Mahnung ")
                    fill(texts[2], " 21 ")
                    fill(texts[3], " 8 ")
                    fill(texts[4], "0,00")
                    assertFalse(texts[4].classList.contains("is-invalid"), "a fee of 0,00 is valid (the server allows 0..25)")
                    element().button("Mahnstufe anlegen").click()
                    awaitUntil("create request") { calls.any { it.isRpc } }
                    val input = calls.first { it.isRpc }.rpcParam(0)
                    assertEquals(4, input.levelNumber as Int)
                    assertEquals("Letzte Mahnung", input.name as String)
                    assertEquals(21, input.graceDays as Int)
                    assertEquals(8, input.responseDays as Int)
                    assertEquals(0.0, input.feeAmount.toString().toDouble(), "fee 0 is saved as 0, not dropped")
                }
            }
        }

    @Test
    fun aLevelWithFeeZero_isNotFlaggedWhenLoaded_andCanBeEdited_inBothDunningForms(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("body-fee-zero") { root, element ->
                    val level =
                        ReceivableDunningLevelDto(
                            id = "lvl-r",
                            levelNumber = 2,
                            name = "Stufe",
                            graceDays = 5,
                            responseDays = 6,
                            feeAmount = 0.0,
                        )
                    renderReceivableLevelForm(root, existing = level) {}
                    val fee = element().inputs("input[type=text]")[4]
                    assertFalse(fee.classList.contains("is-invalid"), "the preset 0 must not be red")
                    fill(fee, "0")
                    assertFalse(fee.classList.contains("is-invalid"))
                    element().button("Speichern").click()
                    awaitUntil("update request") { calls.any { it.isRpc } }
                    val params = calls.first { it.isRpc }
                    assertEquals("lvl-r", params.rpcParam(0) as String)
                    assertEquals(
                        0.0,
                        params
                            .rpcParam(1)
                            .feeAmount
                            .toString()
                            .toDouble(),
                    )
                }
            }
            withFetchStub { calls ->
                withMountedRoot("body-fee-zero-dunning") { root, element ->
                    val level =
                        DunningLevelDto(
                            id = "lvl-d",
                            levelNumber = 2,
                            name = "Stufe",
                            graceDays = 5,
                            responseDays = 6,
                            feeAmount = 0.0,
                            active = true,
                        )
                    renderDunningLevelForm(root, existing = level) {}
                    val fee = element().inputs("input[type=text]")[4]
                    assertFalse(fee.classList.contains("is-invalid"))
                    fill(fee, "0,00")
                    assertFalse(fee.classList.contains("is-invalid"))
                    element().button("Speichern").click()
                    awaitUntil("update request") { calls.any { it.isRpc } }
                    assertEquals(
                        0.0,
                        calls
                            .first { it.isRpc }
                            .rpcParam(1)
                            .feeAmount
                            .toString()
                            .toDouble(),
                    )
                }
            }
        }

    @Test
    fun dunningLevelOne_theValidatorRefusesAFee_evenIfTheLockedFieldStillHoldsOne(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("body-level-one-fee") { root, element ->
                    renderDunningLevelForm(root, existing = null) {}
                    val texts = element().inputs("input[type=text]")
                    fill(texts[1], "Erinnerung")
                    fill(texts[2], "14")
                    fill(texts[3], "7")
                    fill(texts[0], "1")
                    assertTrue(texts[4].disabled, "level 1 locks the fee field")
                    // Der Wert gelangt am Sperr-Mechanismus vorbei ins Feld (das Attribut `disabled` hindert kein Skript).
                    fill(texts[4], "5")
                    element().button("Mahnstufe anlegen").click()
                    delay(150)
                    assertEquals(0, calls.count { it.isRpc }, "the section 286 BGB rule blocks the request")
                    assertTrue(
                        element()
                            .first(".lapis-form-alert")
                            .textContent
                            .orEmpty()
                            .contains("unzulässig"),
                        "the collective message names the reason",
                    )
                }
            }
        }

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    @Test
    fun apiKeyIssue_sendsTheTrimmedLabel(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("body-apikey") { root, element ->
                    renderApiKeysScreen(root)
                    fill(element().inputs("input[type=text]")[0], "   CI-Schlüssel  ")
                    element().button("Neuen Schlüssel ausstellen").click()
                    awaitUntil("issue request") {
                        calls.any {
                            it.isRpc &&
                                (it.json.params.length as Int) >= 1 &&
                                (it.json.params[0] as String).contains("CI")
                        }
                    }
                    val request =
                        calls.first {
                            it.isRpc &&
                                (it.json.params.length as Int) >= 1 &&
                                (it.json.params[0] as String).contains("CI")
                        }
                    assertEquals("CI-Schlüssel", request.rpcParam(0) as String)
                }
            }
        }

    private val destinationJson =
        """[{"id":"dest-1","label":"Alt","platform":"GENERIC_RTMP","rtmpUrl":"rtmp://old.example.org/live","streamKeyMask":"********",""" +
            """"streamKeySetAt":"2026-01-01T10:00:00","createdByDisplayName":"Admin","enabled":true}]"""

    @Test
    fun streamDestinationCreate_sendsLabelPlatformUrlAndKeyInTheirOwnSlots_keyUntrimmed(): Promise<Unit> =
        test {
            withFetchStub(respond = answerLoadWith("[]")) { calls ->
                withMountedRoot("body-destination-create") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    val texts = element().inputs("input[type=text]")
                    fill(texts[0], "  PdV Kanal  ")
                    fill(texts[1], "  rtmps://ingest.example.org/live ")
                    val select = element().first("select") as HTMLSelectElement
                    select.value = "TWITCH"
                    select.dispatchEvent(Event("change"))
                    fill(element().inputs("input[type=password]")[0], " key with spaces ")
                    element().button("Stream-Ziel anlegen").click()
                    awaitUntil("create request") { calls.any { it.isRpc && (it.json.params.length as Int) == 4 } }
                    val request = calls.first { it.isRpc && (it.json.params.length as Int) == 4 }
                    assertEquals("PdV Kanal", request.rpcParam(0) as String)
                    assertEquals("TWITCH", request.rpcParam(1) as String)
                    assertEquals("rtmps://ingest.example.org/live", request.rpcParam(2) as String)
                    assertEquals(" key with spaces ", request.rpcParam(3) as String, "the stream key is never trimmed")
                }
            }
        }

    @Test
    fun streamDestinationUpdate_sendsIdLabelUrlAndTheNewKey_orNullWhenLeftEmpty(): Promise<Unit> =
        test {
            withFetchStub(respond = answerLoadWith(destinationJson)) { calls ->
                withMountedRoot("body-destination-update") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the row") { element().all("button").any { it.textContent?.trim() == "Bearbeiten" } }
                    element().button("Bearbeiten").click()
                    val modal = assertNotNull(lastModal(), "no modal")
                    val texts = modal.inputs("input[type=text]")
                    fill(texts[0], "  Neu benannt ")
                    fill(texts[1], " rtmps://new.example.org/live  ")
                    // Schlüssel leer lassen: `newStreamKey` muss `null` sein (Passwortfeld-Semantik).
                    modal.button("Speichern").click()
                    awaitUntil("update request") { calls.any { it.isRpc && (it.json.params.length as Int) == 4 } }
                    val request = calls.first { it.isRpc && (it.json.params.length as Int) == 4 }
                    assertEquals("dest-1", request.rpcParam(0) as String)
                    assertEquals("Neu benannt", request.rpcParam(1) as String)
                    assertEquals("rtmps://new.example.org/live", request.rpcParam(2) as String)
                    val keyParam: dynamic = request.json.params[3]
                    assertTrue(keyParam == null || keyParam == "null", "an empty key field sends null, was: $keyParam")
                }
            }
        }

    @Test
    fun memberTemporaryPassword_sendsMemberIdPasswordAndTrimmedReasonInTheirOwnSlots(): Promise<Unit> =
        test {
            // `getMemberAccessPreflight(memberId)` is the only one-parameter call of this dialog; its answer enables the button.
            val preflightJson = """{"mailDelivery":"NOT_CONFIGURED","activeSessionCount":2}"""
            val respond: (RecordedRequest) -> StubResponse = { request ->
                val paramCount = request.json.params.length as Int
                rpcResult(request.json.id as Int, if (paramCount == 1) preflightJson else "null")
            }
            withFetchStub(respond = respond) { calls ->
                withMountedRoot("body-member-reset") { _, _ ->
                    openMemberPasswordResetDialog(
                        row =
                            MemberAdminRowDto(
                                id = "member-77",
                                displayName = "Body Test",
                                email = "body@example.org",
                                status = MemberStatus.ACTIVE,
                                role = AccountRole.MEMBER,
                                joinedAt = LocalDate(2026, 1, 1),
                                anonymized = false,
                            ),
                        onChanged = {},
                    )
                    val modal = assertNotNull(lastModal(), "no modal")
                    fill(modal.inputs("input[type=password]")[0], "  chosen password 4711  ")
                    val reason = modal.all("textarea")[0] as HTMLTextAreaElement
                    reason.value = "  Mitglied hat Zugang verloren "
                    reason.dispatchEvent(Event("input"))
                    val button = modal.button("Passwort setzen und alle Sitzungen beenden")
                    awaitUntil("the preflight enables the button") { !button.hasAttribute("disabled") }
                    button.click()
                    awaitUntil("set request") { calls.any { it.isRpc && (it.json.params.length as Int) == 3 } }
                    val request = calls.first { it.isRpc && (it.json.params.length as Int) == 3 }
                    assertEquals("member-77", request.rpcParam(0) as String)
                    assertEquals("  chosen password 4711  ", request.rpcParam(1) as String, "the chosen password is sent untrimmed")
                    assertEquals("Mitglied hat Zugang verloren", request.rpcParam(2) as String)
                }
            }
        }
}
