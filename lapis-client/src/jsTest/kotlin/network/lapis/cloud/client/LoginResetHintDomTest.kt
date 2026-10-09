package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Welle V1.9.81 -- the password-reset form says, always and word for word, that the e-mail can take a few minutes (it may wait in the
 * send queue). One fixed sentence: it must never depend on the account or on the state of the budget -- either would be an
 * account-enumeration oracle.
 */
class LoginResetHintDomTest {
    @Test
    fun theResetForm_alwaysCarriesTheSameWaitingHint() {
        withMountedRoot("login-reset-hint") { root, element ->
            renderLoginScreen(root)
            openResetPanel(element())
            val sentence = "Die E-Mail kann einige Minuten brauchen."
            val text = element().textContent.orEmpty()
            assertEquals(1, Regex(Regex.escape(sentence)).findAll(text).count(), text)
        }
    }

    @Test
    fun theHint_isIdenticalOnEveryRender() {
        val first = renderedHint("login-reset-hint-a")
        val second = renderedHint("login-reset-hint-b")
        assertEquals(first, second)
    }

    /** The reset panel is collapsed behind the "Passwort vergessen?" link; a hidden KVision panel is not in the DOM at all. */
    private fun openResetPanel(host: HTMLElement) {
        val link = assertNotNull(host.allOf("a").firstOrNull { it.textContent?.trim() == "Passwort vergessen?" }, "the toggle link")
        link.click()
    }

    private fun renderedHint(id: String): String {
        var hint = ""
        withMountedRoot(id) { root, element ->
            renderLoginScreen(root)
            openResetPanel(element())
            hint =
                element()
                    .textContent
                    .orEmpty()
                    .substringAfter("Die E-Mail kann", "")
                    .substringBefore(".")
        }
        return hint
    }
}
