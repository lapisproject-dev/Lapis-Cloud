package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Welle V1.9.56 -- the three address-change mail-link screens in a real mounted root with a stubbed `window.fetch`:
 * nothing is sent on load, the token leaves the URL at once, one click sends exactly one POST, and only fixed sentences are shown.
 */
class EmailChangeDeepLinkScreensDomTest {
    private fun HTMLElement.hasText(fragment: String) = textContent.orEmpty().contains(fragment)

    private inline fun withScreen(
        id: String,
        route: String,
        // 200, not the server's 204: a `Response` with a null-body status cannot be built with a body argument in the stub.
        crossinline respond: (RecordedRequest) -> StubResponse = { StubResponse(status = 200) },
        crossinline render: (io.kvision.panel.SimplePanel, String?) -> Unit,
        crossinline block: suspend (() -> HTMLElement, List<RecordedRequest>) -> Unit,
    ): Promise<Unit> =
        formTest {
            window.location.hash = "#$route?token=tok-abc-123"
            withFetchStub(respond = { respond(it) }) { calls ->
                mountedForm(id) { root, element ->
                    render(root, parseHashQueryParam(window.location.hash, "token"))
                    block(element, calls)
                }
            }
        }

    @Test
    fun confirmEmail_sendsNothingOnLoad_andStripsTheTokenFromTheUrl(): Promise<Unit> =
        withScreen("ec-confirm-load", Routes.CONFIRM_EMAIL, render = { r, t -> renderConfirmEmailScreen(r, t) }) { el, calls ->
            delay(150)
            assertEquals(0, calls.size, "opening the link must not send anything")
            assertEquals("#${Routes.CONFIRM_EMAIL}", window.location.hash, "the token is removed from the URL")
            assertFalse(window.location.href.contains("tok-abc-123"))
            assertTrue(el().hasText("Passwort"))
        }

    @Test
    fun confirmEmail_oneClickSendsOnePost_withTheTokenAndThePassword(): Promise<Unit> =
        withScreen("ec-confirm-click", Routes.CONFIRM_EMAIL, render = { r, t -> renderConfirmEmailScreen(r, t) }) { el, calls ->
            el().typeInto("Passwort", "mein-geheimes-passwort")
            el().buttonNamed("Neue Adresse übernehmen").click()
            awaitUntil("the POST") { calls.isNotEmpty() }
            delay(100)
            assertEquals(1, calls.size)
            assertEquals("/api/auth/email-change/confirm", calls.single().url)
            assertEquals("POST", calls.single().method)
            val body = calls.single().json
            assertEquals("tok-abc-123", body.token as String)
            assertEquals("mein-geheimes-passwort", body.password as String)
            awaitUntil("success text") { el().hasText("jetzt aktiv") }
            assertTrue(el().allOf("a").any { it.getAttribute("href") == "#${Routes.LOGIN}" }, "a link to the sign-in page, no redirect")
        }

    @Test
    fun confirmEmail_anEmptyPassword_sendsNothing(): Promise<Unit> =
        withScreen("ec-confirm-empty", Routes.CONFIRM_EMAIL, render = { r, t -> renderConfirmEmailScreen(r, t) }) { el, calls ->
            el().buttonNamed("Neue Adresse übernehmen").click()
            delay(200)
            assertEquals(0, calls.size)
        }

    @Test
    fun verifyNewEmail_hasNoPasswordField_andSendsNoPasswordKey(): Promise<Unit> =
        withScreen("ec-verify", Routes.VERIFY_NEW_EMAIL, render = { r, t -> renderVerifyNewEmailScreen(r, t) }) { el, calls ->
            assertEquals(0, el().allOf("input[type=password]").size)
            delay(100)
            assertEquals(0, calls.size)
            el().buttonNamed("Adresse bestätigen").click()
            awaitUntil("the POST") { calls.isNotEmpty() }
            delay(100)
            assertEquals(1, calls.size)
            assertEquals("/api/auth/email-change/confirm", calls.single().url)
            assertEquals("tok-abc-123", calls.single().json.token as String)
            assertTrue(calls.single().json.password == undefined, "the ownership-proof link carries no password key")
            awaitUntil("success text") { el().hasText("Danke, die Adresse ist bestätigt") }
        }

    @Test
    fun revokeEmailChange_sendsToTheRevokeEndpoint(): Promise<Unit> =
        withScreen("ec-revoke", Routes.REVOKE_EMAIL_CHANGE, render = { r, t -> renderRevokeEmailChangeScreen(r, t) }) { el, calls ->
            delay(100)
            assertEquals(0, calls.size)
            el().buttonNamed("Änderung ablehnen").click()
            awaitUntil("the POST") { calls.isNotEmpty() }
            delay(100)
            assertEquals(1, calls.size)
            assertEquals("/api/auth/email-change/revoke", calls.single().url)
            awaitUntil("success text") { el().hasText("abgelehnt") }
        }

    @Test
    fun theServerAnswers_areShownAsFixedSentences_neverAsServerText(): Promise<Unit> {
        val cases =
            listOf(
                StubResponse(status = 400, text = "invalid") to "ungültig oder abgelaufen",
                StubResponse(status = 400, text = "wrong-password") to "Passwort ist nicht korrekt",
                StubResponse(status = 400, text = "unavailable") to "nicht mehr verfügbar",
                StubResponse(status = 429, text = "rate-limited") to "Zu viele Versuche",
                StubResponse(status = 500, text = "Interner Fehler mit Geheimtext") to "fehlgeschlagen",
            )
        return formTest {
            cases.forEachIndexed { index, (response, fragment) ->
                window.location.hash = "#${Routes.CONFIRM_EMAIL}?token=tok-$index"
                withFetchStub(respond = { response }) { calls ->
                    mountedForm("ec-errors-$index") { root, element ->
                        renderConfirmEmailScreen(root, parseHashQueryParam(window.location.hash, "token"))
                        element().typeInto("Passwort", "irgendein-passwort-1")
                        element().buttonNamed("Neue Adresse übernehmen").click()
                        awaitUntil("answer shown for case $index") { element().hasText(fragment) }
                        assertFalse(element().hasText("Geheimtext"), "server text never reaches the page")
                        assertEquals(1, calls.size)
                        // a failed attempt leaves no success text and no sign-in link
                        assertFalse(element().hasText("jetzt aktiv"))
                    }
                }
            }
        }
    }

    @Test
    fun aNetworkFailure_isAFixedSentenceToo(): Promise<Unit> =
        withScreen(
            "ec-network",
            Routes.REVOKE_EMAIL_CHANGE,
            respond = { StubResponse(networkError = true) },
            render = { r, t -> renderRevokeEmailChangeScreen(r, t) },
        ) { el, _ ->
            el().buttonNamed("Änderung ablehnen").click()
            awaitUntil("failure sentence") { el().hasText("fehlgeschlagen") }
        }

    @Test
    fun aMissingToken_isTheInvalidSentence_andSendsNothing(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("ec-no-token") { root, element ->
                    renderRevokeEmailChangeScreen(root, null)
                    delay(100)
                    assertTrue(element().hasText("ungültig oder abgelaufen"))
                    assertEquals(0, element().allOf("button").size)
                    assertEquals(0, calls.size)
                    assertNotEquals(0, element().allOf("a").size)
                }
            }
        }
}
