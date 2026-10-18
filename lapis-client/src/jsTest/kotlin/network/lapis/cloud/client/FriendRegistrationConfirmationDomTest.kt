package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.160 -- the confirmation after a friend registration says the same thing whether the address was new or already known (the server answers
 * identically on purpose, account-enumeration hardening). Before, it announced "Freund-Konto angelegt" and "sofort nutzbar" for both, and a person
 * with a known address waited for a mail that never came. Now the header claims nothing, the first paragraph is conditional, and the second one tells
 * a person who registered before to log in or use "Passwort vergessen?".
 */
class FriendRegistrationConfirmationDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private suspend fun withConfirmation(block: suspend (HTMLElement) -> Unit) {
        withFetchStub {
            withMountedRoot("friend-confirmation") { root: Root, element ->
                renderFriendRegistrationConfirmation(root)
                delay(50)
                block(element())
            }
        }
    }

    @Test
    fun theHeaderClaimsNoAccountWasCreated(): Promise<Unit> =
        test {
            withConfirmation { element ->
                val h1 = element.querySelectorAll("h1")
                assertEquals(1, h1.length, "exactly one h1")
                assertEquals("Registrierung abgeschickt", h1.item(0)?.textContent)
                assertFalse(
                    h1
                        .item(0)
                        ?.textContent
                        .orEmpty()
                        .contains("angelegt"),
                    "no unconditional 'created' in the header",
                )
                val fresh = element.querySelector(".$FRIEND_CONFIRMATION_NEW_CLASS")?.textContent.orEmpty()
                assertFalse(fresh.contains("angelegt"), "no unconditional 'created' in the first paragraph")
            }
        }

    @Test
    fun bothCasesAreAnsweredInTheSameText(): Promise<Unit> =
        test {
            withConfirmation { element ->
                val fresh = assertNotNull(element.querySelector(".$FRIEND_CONFIRMATION_NEW_CLASS"), "paragraph for a new address")
                val known = assertNotNull(element.querySelector(".$FRIEND_CONFIRMATION_KNOWN_CLASS"), "paragraph for a known address")
                assertTrue(fresh.textContent.orEmpty().startsWith("Ist diese Adresse neu"), "conditional, not a promise")
                assertTrue(known.textContent.orEmpty().contains("Passwort vergessen?"), "names the way out")
                assertTrue(known.textContent.orEmpty().contains("kein neues Konto angelegt"), "says that nothing was created")
                assertTrue(element.querySelector("a[href=\"#${Routes.LOGIN}\"]") != null, "link to the login")
            }
        }
}
