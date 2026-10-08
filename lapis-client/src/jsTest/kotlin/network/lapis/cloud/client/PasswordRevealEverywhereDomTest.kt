package network.lapis.cloud.client

import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.78: every own-password field has a reveal toggle; a confirmation field shares the toggle of its main field; the field is
 * concealed again on submit (also on a validation error), when the page goes to the background and when the form is left; Enter
 * submits only where a form opted in.
 */
class PasswordRevealEverywhereDomTest {
    private fun HTMLElement.all(selector: String): List<Element> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as Element }

    private fun HTMLElement.inputs(): List<HTMLInputElement> = all("input").map { it as HTMLInputElement }

    private fun keydown(
        input: HTMLInputElement,
        init: dynamic = js("({})"),
    ) {
        init.key = init.key ?: "Enter"
        init.bubbles = true
        init.cancelable = true
        input.dispatchEvent(js("new KeyboardEvent('keydown', init)").unsafeCast<Event>())
    }

    private fun toggles(root: HTMLElement): List<HTMLElement> = root.all(".lapis-field-actions button").map { it as HTMLElement }

    @Test
    fun theLoginPassword_hasAnEye_thatKeepsAutocompleteAndWiresAriaControls() {
        withMountedRoot("reveal-login") { root, element ->
            renderLoginScreen(root)
            val password = element().querySelector("input[autocomplete=current-password]") as HTMLInputElement
            val toggle = toggles(element()).first { it.getAttribute("aria-controls") == password.id }
            assertEquals("button", toggle.getAttribute("type"))
            assertFalse(toggle.hasAttribute("aria-pressed"))
            assertEquals("Anzeigen", toggle.textContent?.trim())
            password.value = "geheim-123"
            toggle.click()
            assertEquals("text", password.type)
            assertEquals("current-password", password.getAttribute("autocomplete"))
            assertEquals("false", password.getAttribute("spellcheck"))
            assertEquals("off", password.getAttribute("autocapitalize"))
            assertEquals("off", password.getAttribute("autocorrect"))
            assertEquals("Verbergen", toggle.textContent?.trim())
            assertFalse(toggle.outerHTML.contains("geheim-123"), "the value must never reach the toggle")
            toggle.click()
            assertEquals("password", password.type)
        }
    }

    @Test
    fun theChangePasswordForm_hasEyesForAllThreeFields_andTheConfirmationSharesTheEye() {
        withMountedRoot("reveal-dashboard") { root, element ->
            renderChangePassword(root)
            val passwords = element().inputs().filter { it.getAttribute("autocomplete")?.endsWith("password") == true }
            assertEquals(3, passwords.size)
            val eyes = toggles(element())
            assertEquals(2, eyes.size, "current + (new and confirmation share one eye)")
            val shared = eyes.first { (it.getAttribute("aria-controls") ?: "").contains(" ") }
            assertEquals(setOf(passwords[1].id, passwords[2].id), shared.getAttribute("aria-controls")!!.split(" ").toSet())
            shared.click()
            assertEquals(listOf("password", "text", "text"), passwords.map { it.type })
            val cancel = element().all("button").first { it.textContent?.trim() == "Abbrechen" } as HTMLElement
            cancel.click()
            assertEquals(listOf("password", "password", "password"), passwords.map { it.type })
        }
    }

    @Test
    fun submitting_concealsAgain_evenWhenValidationFails() {
        withMountedRoot("reveal-submit") { root, element ->
            val form = root.lapisForm()
            form.passwordField(label = tr("Passwort"), required = true, autocomplete = Autocomplete.CURRENT_PASSWORD, reveal = true)
            val go = Button("Los", style = ButtonStyle.PRIMARY)
            form.buttons(primary = go)
            form.submit(go) { }
            val input = element().inputs().first()
            toggles(element()).first().click()
            assertEquals("text", input.type)
            form.submit(go) { }
            assertEquals("password", input.type, "a failed validation must conceal")
        }
    }

    @Test
    fun pageGoingToTheBackground_conceals_andTheListenerIsGoneAfterTheFormIsLeft() {
        withMountedRoot("reveal-visibility") { root, element ->
            val form = root.lapisForm()
            form.passwordField(label = tr("Passwort"), autocomplete = Autocomplete.CURRENT_PASSWORD, reveal = true)
            val input = element().inputs().first()
            toggles(element()).first().click()
            assertEquals("text", input.type)
            js("Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })")
            try {
                kotlinx.browser.document.dispatchEvent(Event("visibilitychange"))
                assertEquals("password", input.type)
            } finally {
                js("delete document.visibilityState")
            }
        }
    }

    @Test
    fun enter_submitsOnlyWhereTheFormOptedIn() {
        withMountedRoot("reveal-enter") { root, element ->
            var optIn = 0
            val form = root.lapisForm()
            form.textField(label = tr("Name"))
            val go = Button("Los", style = ButtonStyle.PRIMARY)
            form.buttons(primary = go, enterSubmits = true)
            go.onClick { optIn++ }
            val input = element().inputs().first()
            keydown(input)
            assertEquals(1, optIn)
            keydown(input, js("({ key: 'Enter', isComposing: true })"))
            keydown(input, js("({ key: 'Enter', repeat: true })"))
            keydown(input, js("({ key: 'Enter', shiftKey: true })"))
            keydown(input, js("({ key: 'a' })"))
            assertEquals(1, optIn)
        }
        withMountedRoot("reveal-enter-default") { root, element ->
            var clicks = 0
            val form = root.lapisForm()
            form.textField(label = tr("Name"))
            val go = Button("Los", style = ButtonStyle.PRIMARY)
            form.buttons(primary = go)
            go.onClick { clicks++ }
            keydown(element().inputs().first())
            assertEquals(0, clicks, "without the opt-in, Enter does nothing")
        }
    }

    @Test
    fun theEyeItself_neverSubmitsOnEnter() {
        withMountedRoot("reveal-enter-eye") { root, element ->
            var clicks = 0
            val form = root.lapisForm()
            form.passwordField(label = tr("Passwort"), reveal = true)
            val go = Button("Los", style = ButtonStyle.PRIMARY)
            form.buttons(primary = go, enterSubmits = true)
            go.onClick { clicks++ }
            val event = js("new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })").unsafeCast<Event>()
            toggles(element()).first().dispatchEvent(event)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun invalidCombinations_failLoudly() {
        withMountedRoot("reveal-require") { root, _ ->
            val form = root.lapisForm()
            val plain = form.passwordField(label = tr("Ohne Auge"))
            val main = form.passwordField(label = tr("Mit Auge"), reveal = true)
            assertFailsWith<IllegalArgumentException> { form.passwordField(label = tr("a"), reveal = true, revealedBy = main) }
            assertFailsWith<IllegalArgumentException> { form.passwordField(label = tr("b"), revealedBy = plain) }
            assertFailsWith<IllegalArgumentException> { form.passwordField(label = tr("c"), revealedBy = main, suppressManagers = true) }
            val other = root.lapisForm().passwordField(label = tr("d"), reveal = true)
            assertFailsWith<IllegalArgumentException> { form.passwordField(label = tr("e"), revealedBy = other) }
            assertFailsWith<IllegalArgumentException> { form.buttons(primary = null, enterSubmits = true) }
        }
    }

    @Test
    fun theToggle_isAtLeast44pxHigh() {
        withMountedRoot("reveal-target-size") { root, element ->
            val form = root.lapisForm()
            form.passwordField(label = tr("Passwort"), reveal = true)
            val height = toggles(element()).first().getBoundingClientRect().height
            assertTrue(height >= 44.0, "touch target is $height px")
        }
    }
}
