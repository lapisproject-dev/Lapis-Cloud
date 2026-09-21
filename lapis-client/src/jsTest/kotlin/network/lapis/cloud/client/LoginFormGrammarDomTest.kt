package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 (W4a): the real [renderLoginScreen] on the form grammar. The login is the one form where the
 * grammar deliberately does LESS: two required fields (no star, no legend, but `aria-required`), and the password
 * is only ever checked for "not empty" -- an old password that was valid when it was set must never be declared
 * wrong by the interface. The server text is shown word for word (it is enumeration-hardened).
 */
class LoginFormGrammarDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    @Test
    fun theFields_carryTheRightAutocompleteAndAriaRequired() {
        withMountedRoot("login-grammar-attributes") { root, element ->
            renderLoginScreen(root)
            val email = element().first("input[type=email]")
            val password = element().first("input[type=password]")
            assertEquals("username", email.getAttribute("autocomplete"))
            assertEquals("current-password", password.getAttribute("autocomplete"))
            assertEquals("true", email.getAttribute("aria-required"))
            assertEquals("true", password.getAttribute("aria-required"))
        }
    }

    @Test
    fun twoRequiredFields_showNeitherAStarNorALegend() {
        withMountedRoot("login-grammar-no-star") { root, element ->
            renderLoginScreen(root)
            assertEquals(0, element().querySelectorAll(".lapis-required-mark").length)
            assertFalse(element().textContent.orEmpty().contains("Pflichtfeld"))
        }
    }

    @Test
    fun theLoginPassword_isNeverCheckedAgainstThePasswordPolicy() {
        withMountedRoot("login-grammar-policy") { root, element ->
            renderLoginScreen(root)
            val password = element().first("input[type=password]") as HTMLInputElement
            password.value = "kurz"
            password.dispatchEvent(Event("input"))
            password.dispatchEvent(Event("blur"))
            assertFalse(password.classList.contains("is-invalid"), "a 4-character old password must not be flagged")
            // The error slot of the PASSWORD field (the first `.lapis-field-error` on the page belongs to the e-mail field).
            val errorId = assertNotNull(password.getAttribute("aria-describedby")).split(" ").last()
            val passwordError = assertNotNull(document.getElementById(errorId), "the password field's error slot")
            assertEquals("", passwordError.textContent.orEmpty().trim())
            assertFalse(passwordError.classList.contains("lapis-field-error--shown"))
        }
    }

    @Test
    fun theServerText_isShownWordForWord_inTheAlertRegion(): Promise<Unit> =
        test {
            val realFetch = window.asDynamic().fetch
            window.asDynamic().fetch = { _: dynamic, _: dynamic ->
                js("Promise.resolve(new Response('Anmeldung nicht möglich (Testtext).', { status: 401 }))")
            }
            try {
                withMountedRoot("login-grammar-server-text") { root, element ->
                    renderLoginScreen(root)
                    val email = element().first("input[type=email]") as HTMLInputElement
                    val password = element().first("input[type=password]") as HTMLInputElement
                    email.value = "amara@example.org"
                    email.dispatchEvent(Event("input"))
                    password.value = "irgendwas"
                    password.dispatchEvent(Event("input"))
                    element().first("button.btn-primary").click()
                    val alert = element().first("[role=alert]")
                    repeat(100) { if (alert.textContent.orEmpty().isBlank()) delay(20) }
                    assertEquals("Anmeldung nicht möglich (Testtext).", alert.textContent?.trim())
                    assertTrue(alert.classList.contains("lapis-form-alert--shown"))
                }
            } finally {
                window.asDynamic().fetch = realFetch
            }
        }

    @Test
    fun submittingAnEmptyForm_flagsBothFieldsAndCallsNoServer(): Promise<Unit> =
        test {
            var fetchCalls = 0
            val realFetch = window.asDynamic().fetch
            window.asDynamic().fetch = { _: dynamic, _: dynamic ->
                fetchCalls++
                js("Promise.resolve(new Response('', { status: 200 }))")
            }
            try {
                withMountedRoot("login-grammar-empty") { root, element ->
                    renderLoginScreen(root)
                    element().first("button.btn-primary").click()
                    delay(50)
                    assertEquals(0, fetchCalls)
                    assertEquals(2, element().querySelectorAll("input.is-invalid").length)
                    assertEquals(
                        "Bitte korrigieren Sie diese Felder: E-Mail, Passwort.",
                        element().first("[role=alert]").textContent?.trim(),
                    )
                }
            } finally {
                window.asDynamic().fetch = realFetch
            }
        }

    /**
     * Regression: the client must never decide that an e-mail is "not an address" on the login screen. Accounts whose
     * identifier has no dot after the `@` (`admin@localhost` via LAPIS_BOOTSTRAP_ADMIN_EMAIL, `hans@intranet` via CSV
     * import) exist and had a working login -- the server, not the client, is the authority for the login and for the
     * reset request.
     */
    @Test
    fun anIdentifierWithoutADotInTheDomain_isSentToTheServer_notBlockedByTheClient(): Promise<Unit> =
        test {
            val urls = mutableListOf<String>()
            val realFetch = window.asDynamic().fetch
            window.asDynamic().fetch = { url: dynamic, _: dynamic ->
                urls += url.toString()
                js("Promise.resolve(new Response('Servertext.', { status: 401 }))")
            }
            try {
                withMountedRoot("login-grammar-local-address") { root, element ->
                    renderLoginScreen(root)
                    // The reset form sits behind the "Passwort vergessen?" toggle and is only rendered once it is opened.
                    val toggle =
                        (0 until element().querySelectorAll("a").length)
                            .map { element().querySelectorAll("a").item(it).unsafeCast<HTMLElement>() }
                            .first { it.textContent?.trim() == "Passwort vergessen?" }
                    toggle.click()
                    val emails = element().querySelectorAll("input[type=email]")
                    assertEquals(2, emails.length, "login + reset e-mail inputs")
                    val loginEmail = emails.item(0).unsafeCast<HTMLInputElement>()
                    val resetEmail = emails.item(1).unsafeCast<HTMLInputElement>()
                    val password = element().first("input[type=password]").unsafeCast<HTMLInputElement>()
                    loginEmail.value = "admin@localhost"
                    loginEmail.dispatchEvent(Event("input"))
                    loginEmail.dispatchEvent(Event("blur"))
                    assertFalse(loginEmail.classList.contains("is-invalid"), "no client-side e-mail format check on the login")
                    password.value = "irgendwas"
                    password.dispatchEvent(Event("input"))
                    element().first("button.btn-primary").click()
                    repeat(100) { if (urls.isEmpty()) delay(20) }
                    assertEquals(1, urls.size, "the login request must reach the server: $urls")
                    assertFalse(loginEmail.classList.contains("is-invalid"))

                    resetEmail.value = "hans@intranet"
                    resetEmail.dispatchEvent(Event("input"))
                    resetEmail.dispatchEvent(Event("blur"))
                    assertFalse(resetEmail.classList.contains("is-invalid"), "no client-side e-mail format check on the reset request")
                    val requestButton =
                        (0 until element().querySelectorAll("button").length)
                            .map { element().querySelectorAll("button").item(it).unsafeCast<HTMLElement>() }
                            .first { it.textContent?.trim() == "Zurücksetzen anfordern" }
                    requestButton.click()
                    repeat(100) { if (urls.size < 2) delay(20) }
                    assertEquals(2, urls.size, "the reset request must reach the server: $urls")
                }
            } finally {
                window.asDynamic().fetch = realFetch
            }
        }
}
