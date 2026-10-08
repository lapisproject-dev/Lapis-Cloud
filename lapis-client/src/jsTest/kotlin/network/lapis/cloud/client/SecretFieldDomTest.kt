package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 (W4a), security-relevant: secret fields (the stream key, a temporary password generated for ANOTHER
 * person) must not be offered to password managers, must never leak their value into an attribute, a title, an
 * error message or the collective message, and the reveal toggle must be a proper toggle button.
 *
 * Honest limit: `autocomplete="off"` on a password field is ignored by some browsers and the `data-*` attributes
 * are a request to 1Password/LastPass/Bitwarden, not a guarantee -- these tests pin that the request IS made.
 */
class SecretFieldDomTest {
    /**
     * The password dialogs are Bootstrap modals on `body`, outside the mounted root. They are closed the way a person closes them
     * ([closeOpenModals]) so Bootstrap's focus trap is released -- a modal merely left in the document keeps stealing focus.
     */
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private fun HTMLElement.all(selector: String): List<Element> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as Element }

    /** Every attribute value and the text of every element outside `<input>` -- where a secret could leak. */
    private fun HTMLElement.leakSurface(): List<String> =
        all("*").flatMap { el ->
            val attributes = (0 until el.attributes.length).map { el.attributes.item(it)!!.value }
            attributes + (if (el.tagName == "INPUT") emptyList() else listOf(el.textContent.orEmpty()))
        }

    private fun assertRequestsNoManagerHelp(input: HTMLInputElement) {
        assertEquals("password", input.type)
        assertEquals("off", input.getAttribute("autocomplete"))
        assertEquals("true", input.getAttribute("data-1p-ignore"))
        assertEquals("true", input.getAttribute("data-lpignore"))
        assertEquals("other", input.getAttribute("data-form-type"))
        assertEquals("false", input.getAttribute("spellcheck"))
        assertEquals("off", input.getAttribute("autocapitalize"))
    }

    @Test
    fun theStreamKeyField_requestsNoManagerHelp_andNeverLeaksItsValue(): Promise<Unit> =
        formTest {
            mountedForm("secret-stream-key") { root, element ->
                renderConferenceStreamDestinationsScreen(root)
                // V1.9.49: the create form sits behind the "Neues Stream-Ziel" button.
                openCreateForm(element(), "stream-destination-create")
                val key = assertNotNull(element().querySelector("input[type=password]") as? HTMLInputElement, "no stream key field")
                assertRequestsNoManagerHelp(key)
                val secret = "s3cr3t-STREAM-key-4711"
                key.value = secret
                key.dispatchEvent(Event("input"))
                key.dispatchEvent(Event("blur"))
                // Bezeichnung und URL bleiben leer -> ein Absenden erzeugt Feldfehler UND eine Sammelmeldung.
                (element().all("button").first { it.textContent?.trim() == "Stream-Ziel anlegen" } as HTMLElement).click()
                assertTrue(element().all(".lapis-field-error--shown").isNotEmpty(), "the submit must show field errors")
                assertFalse(element().leakSurface().any { it.contains(secret) }, "the secret leaked into an attribute or a message")
            }
        }

    @Test
    fun aRevealToggle_showsItsVerbAsText_swapsSymbolTextAndName_andKeepsTheValue() {
        withMountedRoot("secret-reveal") { root, element ->
            val form = root.lapisForm()
            form.passwordField(label = tr("Temporäres Passwort"), value = "abcd-efgh", suppressManagers = true, reveal = true)
            form.buttons(primary = Button("Setzen", style = ButtonStyle.DANGER))
            val input = element().all("input").first() as HTMLInputElement
            assertRequestsNoManagerHelp(input)
            val toggle = assertNotNull(element().querySelector(".lapis-field-actions button") as? HTMLElement, "no reveal toggle")
            // V1.9.75: visible text next to the eye; the long name contains the visible text (WCAG 2.5.3).
            assertEquals("Anzeigen", toggle.textContent?.trim())
            assertNotNull(toggle.querySelector("i.fa-eye"), "no eye symbol: ${toggle.outerHTML}")
            assertEquals("Passwort anzeigen", toggle.getAttribute("title"))
            assertEquals("Passwort anzeigen", toggle.getAttribute("aria-label"))
            assertTrue(toggle.getAttribute("aria-label")!!.lowercase().contains(toggle.textContent!!.trim().lowercase()))
            assertFalse(toggle.hasAttribute("aria-pressed"), "name and state would contradict each other")
            assertFalse(toggle.outerHTML.contains("###KvI18nS###"))

            toggle.click()
            assertEquals("text", input.type)
            assertEquals("abcd-efgh", input.value, "revealing must keep the value")
            assertEquals("Verbergen", toggle.textContent?.trim())
            assertNotNull(toggle.querySelector("i.fa-eye-slash"), "no crossed-out eye: ${toggle.outerHTML}")
            assertNull(toggle.querySelector("i.fa-eye:not(.fa-eye-slash)"))
            assertEquals("true", toggle.querySelector("i")?.getAttribute("aria-hidden"), "the swapped symbol stays decoration")
            assertFalse(toggle.hasAttribute("aria-pressed"))
            assertEquals("Passwort verbergen", toggle.getAttribute("aria-label"))
            assertEquals("Passwort verbergen", toggle.getAttribute("title"))
            assertTrue(toggle.getAttribute("aria-label")!!.lowercase().contains(toggle.textContent!!.trim().lowercase()))

            toggle.click()
            assertEquals("password", input.type)
            assertEquals("Anzeigen", toggle.textContent?.trim())
            assertNotNull(toggle.querySelector("i.fa-eye"))
            assertNull(toggle.querySelector("i.fa-eye-slash"))
            assertFalse(toggle.hasAttribute("aria-pressed"))
            assertEquals("Passwort anzeigen", toggle.getAttribute("aria-label"))
        }
    }

    @Test
    fun aSecretValueNeverAppearsInAFieldErrorOrTheCollectiveMessage() {
        withMountedRoot("secret-no-leak") { root, element ->
            val form = root.lapisForm()
            val secret = "topsecret-PASSWORD-99"
            form.passwordField(
                label = tr("Passwort"),
                required = true,
                suppressManagers = true,
                rule = { FieldCheck.Invalid("Zu schwach.") },
            )
            form.textField(label = tr("Name"), required = true)
            form.buttons(primary = Button("Weiter"))
            val input = element().all("input").first() as HTMLInputElement
            input.value = secret
            input.dispatchEvent(Event("input"))
            form.submit(Button("x")) { }
            assertEquals(
                "Zu schwach.",
                element()
                    .all(".lapis-field-error--shown")
                    .first()
                    .textContent
                    ?.trim(),
            )
            assertFalse(element().leakSurface().any { it.contains(secret) })
        }
    }

    @Test
    fun theTemporaryPasswordDialog_requestsNoManagerHelp_hasARevealToggle_andLabelsItsIconButtons(): Promise<Unit> =
        test {
            withMountedRoot("secret-temp-password") { root, element ->
                openMemberPasswordResetDialog(
                    row =
                        MemberAdminRowDto(
                            id = "00000000-0000-0000-0000-000000000099",
                            displayName = "Test Mitglied",
                            email = "test@example.org",
                            status = MemberStatus.ACTIVE,
                            role = AccountRole.MEMBER,
                            joinedAt = LocalDate(2026, 1, 1),
                            anonymized = false,
                        ),
                    onChanged = {},
                )
                val scope = root.getElement() as? HTMLElement ?: element()
                val host = document.body as HTMLElement
                val key =
                    assertNotNull(host.querySelector(".modal input[type=password]") as? HTMLInputElement, "no password field in the dialog")
                assertRequestsNoManagerHelp(key)
                val actions = assertNotNull(host.querySelector(".modal .lapis-field-actions") as? HTMLElement)
                val buttons = actions.all("button")
                assertEquals(2, buttons.size, "reveal toggle + regenerate")
                // V1.9.75: both buttons carry visible text; the reveal toggle keeps its longer name, "Neu erzeugen" is its own name.
                assertEquals("Anzeigen", buttons[0].textContent?.trim())
                assertEquals("Neu erzeugen", buttons[1].textContent?.trim())
                assertNotNull(buttons[1].querySelector("i.fa-arrows-rotate"), "regenerate uses ActionIcon.REFRESH")
                buttons.forEach { assertTrue(it.textContent!!.isNotBlank(), "no icon-only button: ${it.outerHTML}") }
                assertFalse(scope.innerHTML.contains("###KvI18nS###"))
            }
        }

    @Test
    fun theTemporaryPasswordDialog_keepsOneRowAt312px(): Promise<Unit> =
        test {
            withMountedRoot("secret-narrow") { _, _ ->
                openMemberPasswordResetDialog(
                    row =
                        MemberAdminRowDto(
                            id = "00000000-0000-0000-0000-000000000097",
                            displayName = "Narrow Mitglied",
                            email = "narrow@example.org",
                            status = MemberStatus.ACTIVE,
                            role = AccountRole.MEMBER,
                            joinedAt = LocalDate(2026, 1, 1),
                            anonymized = false,
                        ),
                    onChanged = {},
                )
                val modal = (document.body as HTMLElement).all(".modal").last() as HTMLElement
                val actions = assertNotNull(modal.querySelector(".lapis-field-actions") as? HTMLElement)
                (actions.parentElement as HTMLElement).style.width = "312px"
                val buttons = actions.all("button").map { it as HTMLElement }
                assertEquals(2, buttons.size)
                awaitUntil("dialog laid out") { buttons.all { it.offsetHeight > 0 } }
                assertTrue(actions.scrollWidth <= actions.clientWidth, "row overflows: ${actions.scrollWidth} > ${actions.clientWidth}")
                assertEquals(buttons[0].offsetTop, buttons[1].offsetTop, "the buttons must share one row")
                // The 44 px target is the theme rule for coarse pointers (`@media (pointer: coarse)`), not measurable with a mouse.
                assertEquals(buttons[0].offsetHeight, buttons[1].offsetHeight, "both buttons are equally high")
            }
        }

    @Test
    fun regenerate_clearsAnAlreadyShownFieldError_becauseTheNewValueIsValid(): Promise<Unit> =
        test {
            withMountedRoot("secret-regenerate") { _, _ ->
                openMemberPasswordResetDialog(
                    row =
                        MemberAdminRowDto(
                            id = "00000000-0000-0000-0000-000000000098",
                            displayName = "Regenerate Mitglied",
                            email = "regenerate@example.org",
                            status = MemberStatus.ACTIVE,
                            role = AccountRole.MEMBER,
                            joinedAt = LocalDate(2026, 1, 1),
                            anonymized = false,
                        ),
                    onChanged = {},
                )
                val host = document.body as HTMLElement
                val modals = host.all(".modal")
                val modal = modals.last() as HTMLElement
                val input = assertNotNull(modal.querySelector("input[type=password]") as? HTMLInputElement, "no password field")
                input.value = "abc"
                input.dispatchEvent(Event("input"))
                input.dispatchEvent(Event("blur"))
                assertTrue(input.classList.contains("is-invalid"), "a 3-character password must be flagged on leaving the field")
                assertEquals("true", input.getAttribute("aria-invalid"))

                val regenerate = modal.all(".lapis-field-actions button")[1] as HTMLElement
                regenerate.click()
                assertTrue(input.value.length >= 12, "regenerate must fill a valid password, was '${input.value.length}' characters")
                assertFalse(input.classList.contains("is-invalid"), "the stale error frame must be gone")
                assertEquals("false", input.getAttribute("aria-invalid"), "a screen reader must not keep announcing the field as invalid")
                assertEquals(0, modal.all(".lapis-field-error--shown").size, "the stale error text must be gone")
            }
        }
}
