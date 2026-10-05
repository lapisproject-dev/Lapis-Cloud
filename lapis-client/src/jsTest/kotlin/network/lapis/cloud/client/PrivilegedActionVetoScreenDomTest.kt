package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.57 -- the objection link of a temporary-password request in a real mounted root with a stubbed `window.fetch`: nothing is
 * sent on load, the token leaves the URL at once, one click sends exactly one POST, and only fixed sentences are shown (the server
 * answers the same for every token, so the page never claims that a request existed).
 */
class PrivilegedActionVetoScreenDomTest {
    private fun HTMLElement.hasText(fragment: String) = textContent.orEmpty().contains(fragment)

    private inline fun withScreen(
        id: String,
        crossinline respond: (RecordedRequest) -> StubResponse = { StubResponse(status = 200) },
        crossinline block: suspend (() -> HTMLElement, List<RecordedRequest>) -> Unit,
    ): Promise<Unit> =
        formTest {
            window.location.hash = "#${Routes.PRIVILEGED_ACTION_VETO}?token=veto-tok-123"
            withFetchStub(respond = { respond(it) }) { calls ->
                mountedForm(id) { root, element ->
                    renderPrivilegedActionVetoScreen(root, parseHashQueryParam(window.location.hash, "token"))
                    block(element, calls)
                }
            }
        }

    @Test
    fun nothingIsSentOnLoad_andTheTokenLeavesTheUrl(): Promise<Unit> =
        withScreen("pav-load") { el, calls ->
            delay(150)
            assertEquals(0, calls.size, "opening the link must not send anything")
            assertEquals("#${Routes.PRIVILEGED_ACTION_VETO}", window.location.hash)
            assertFalse(window.location.href.contains("veto-tok-123"))
            assertTrue(el().hasText("Widerspruch"))
        }

    @Test
    fun oneClickSendsExactlyOnePost_withTheToken(): Promise<Unit> =
        withScreen("pav-click") { el, calls ->
            el().buttonNamed("Widerspruch einlegen").click()
            awaitUntil("the POST") { calls.isNotEmpty() }
            delay(100)
            assertEquals(1, calls.size)
            assertEquals("/api/auth/privileged-action/veto", calls.single().url)
            assertEquals("POST", calls.single().method)
            assertEquals("veto-tok-123", calls.single().json.token as String)
            awaitUntil("success text") { el().hasText("Ihr Widerspruch wurde übermittelt") }
            // the sentence never claims that a request existed
            assertFalse(el().hasText("Der Antrag wurde beendet"))
            assertTrue(el().allOf("a").any { it.getAttribute("href") == "#${Routes.LOGIN}" }, "a link to the sign-in page, no redirect")
        }

    @Test
    fun serverAnswers_areFixedSentences_neverServerText(): Promise<Unit> {
        val cases =
            listOf(
                StubResponse(status = 429, text = "rate-limited") to "Zu viele Versuche",
                StubResponse(status = 500, text = "Interner Fehler mit Geheimtext") to "fehlgeschlagen",
                StubResponse(networkError = true) to "fehlgeschlagen",
            )
        return formTest {
            cases.forEachIndexed { index, (response, fragment) ->
                window.location.hash = "#${Routes.PRIVILEGED_ACTION_VETO}?token=tok-$index"
                withFetchStub(respond = { response }) { calls ->
                    mountedForm("pav-errors-$index") { root, element ->
                        renderPrivilegedActionVetoScreen(root, parseHashQueryParam(window.location.hash, "token"))
                        element().buttonNamed("Widerspruch einlegen").click()
                        awaitUntil("answer shown for case $index") { element().hasText(fragment) }
                        assertFalse(element().hasText("Geheimtext"), "server text never reaches the page")
                        assertTrue(calls.size <= 1)
                        assertFalse(element().hasText("Ihr Widerspruch wurde übermittelt"))
                    }
                }
            }
        }
    }

    @Test
    fun aMissingToken_isTheInvalidSentence_andSendsNothing(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("pav-no-token") { root, element ->
                    renderPrivilegedActionVetoScreen(root, null)
                    delay(100)
                    assertTrue(element().hasText("ungültig oder abgelaufen"))
                    assertEquals(0, element().allOf("button").size)
                    assertEquals(0, calls.size)
                }
            }
        }
}
