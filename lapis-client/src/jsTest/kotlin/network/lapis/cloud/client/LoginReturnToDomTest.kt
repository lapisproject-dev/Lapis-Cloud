package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLScriptElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val RETURN_TO_BRAND_ID = "lapis-brand"
private const val VALID_RETURN_TO =
    "/federation/oidc/authorize?response_type=code&client_id=abc&redirect_uri=http%3A%2F%2F127.0.0.1%3A53682%2Fcallback&state=xyz"
private const val HINT = "Nach der Anmeldung geht es mit der Verbindung Ihres Assistenten weiter."

private external fun encodeURIComponent(value: String): String

private fun encode(value: String): String = encodeURIComponent(value)

private fun setBrand(keycloakMode: Boolean) {
    document.getElementById(RETURN_TO_BRAND_ID)?.remove()
    val script = document.createElement("script") as HTMLScriptElement
    script.type = "application/json"
    script.id = RETURN_TO_BRAND_ID
    script.textContent = """{"title":"ELB","logoUrl":null,"keycloakMode":$keycloakMode,"emergencyAdminLoginEnabled":true}"""
    document.head?.appendChild(script)
}

/**
 * V1.9.89: the login -> consent round trip on the client -- [completeLogin], [continueToReturnTo], the hint and the SSO link.
 * `fullPageNavigate` is replaced by a recorder, so no test ever leaves the Karma page. `navigateTo(...)` is a no-op here (no
 * routing instance is installed) and the toast container is not mounted, so "goes to the dashboard" is asserted as "completeLogin
 * returns false and nothing was navigated away" -- the DOM-side limits of this suite.
 */
class LoginReturnToDomTest {
    private val jumps = mutableListOf<String>()
    private val session =
        SessionInfoDto(
            memberId = "m1",
            displayName = "Testperson",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )
    private val originalHash = window.location.hash

    @BeforeTest
    fun setUp() {
        jumps.clear()
        fullPageNavigate = { jumps += it }
    }

    @AfterTest
    fun tearDown() {
        fullPageNavigate = { url -> window.location.replace(url) }
        window.location.hash = originalHash
        AppState.setSession(null)
        document.getElementById(RETURN_TO_BRAND_ID)?.remove()
    }

    private fun setHash(returnTo: String?) {
        window.location.hash = if (returnTo == null) "#/login" else "#/login?returnTo=" + encode(returnTo)
    }

    @Test
    fun validReturnTo_completeLoginJumpsOnceToExactlyThatUrl() {
        setHash(VALID_RETURN_TO)
        assertTrue(completeLogin(session))
        assertEquals(listOf(VALID_RETURN_TO), jumps)
    }

    @Test
    fun invalidReturnTo_completeLoginStaysOnTheNormalPath() {
        listOf("//evil.example", "https://evil.example", "/federation/oidc/authorize%2f..?", "/federation/oidc/authorize?x=%252F")
            .forEach { bad ->
                setHash(bad)
                assertFalse(completeLogin(session), bad)
            }
        window.location.hash = "#/login?returnTo=%E0%A4%A"
        assertFalse(completeLogin(session), "malformed escape")
        assertTrue(jumps.isEmpty(), "no full-page jump for any of them")
    }

    @Test
    fun noReturnTo_behavesAsBefore() {
        setHash(null)
        assertFalse(completeLogin(session))
        assertTrue(jumps.isEmpty())
        assertNotNull(AppState.session, "the session is stored either way")
    }

    @Test
    fun alreadySignedIn_loginRouteContinuesToAValidReturnToWithoutRenderingTheForm() {
        setHash(VALID_RETURN_TO)
        assertTrue(continueToReturnTo())
        assertEquals(listOf(VALID_RETURN_TO), jumps)
    }

    @Test
    fun alreadySignedIn_invalidOrMissingReturnToFallsBackToTheDashboardRoute() {
        setHash("//evil.example")
        assertFalse(continueToReturnTo())
        setHash(null)
        assertFalse(continueToReturnTo())
        assertTrue(jumps.isEmpty())
    }

    @Test
    fun navigateToReturnTo_revalidatesAtTheJumpPoint() {
        assertFalse(navigateToReturnTo("https://evil.example"))
        assertTrue(jumps.isEmpty())
        assertTrue(navigateToReturnTo(VALID_RETURN_TO))
        assertEquals(1, jumps.size)
    }

    @Test
    fun hint_isShownOnlyForAValidReturnTo_andNeverEchoesTheUrl() {
        setBrand(keycloakMode = false)
        setHash(VALID_RETURN_TO)
        withMountedRoot("login-return-to-hint") { root, element ->
            renderLoginScreen(root)
            val text = element().textContent.orEmpty()
            assertTrue(text.contains(HINT))
            assertFalse(text.contains("federation"))
            assertFalse(text.contains("abc"))
        }
        setHash("//evil.example")
        withMountedRoot("login-return-to-hint-invalid") { root, element ->
            renderLoginScreen(root)
            assertFalse(element().textContent.orEmpty().contains(HINT))
        }
        setHash(null)
        withMountedRoot("login-return-to-hint-none") { root, element ->
            renderLoginScreen(root)
            assertFalse(element().textContent.orEmpty().contains(HINT))
        }
    }

    private fun ssoHref(element: HTMLElement): String? =
        element.allOf("a").firstOrNull { it.textContent?.trim() == "Mit ELB anmelden" }?.getAttribute("href")

    @Test
    fun keycloakLink_carriesAValidReturnToEncoded_andOtherwiseIsThePlainStartRoute() {
        setBrand(keycloakMode = true)
        setHash(VALID_RETURN_TO)
        withMountedRoot("login-return-to-sso-valid") { root, element ->
            renderLoginScreen(root)
            assertEquals("/auth/keycloak/start?returnTo=" + encode(VALID_RETURN_TO), ssoHref(element()))
        }
        setHash("https://evil.example")
        withMountedRoot("login-return-to-sso-invalid") { root, element ->
            renderLoginScreen(root)
            assertEquals("/auth/keycloak/start", ssoHref(element()))
        }
        setHash(null)
        withMountedRoot("login-return-to-sso-none") { root, element ->
            renderLoginScreen(root)
            assertEquals("/auth/keycloak/start", ssoHref(element()))
        }
    }

    @Test
    fun emergencyAdminForm_isTheSameFormAsTheClassicOne() {
        // Both mount renderEmailPasswordLoginForm (single success path completeLogin) -- the server-side tripwire pins the wiring;
        // here only that the form exists inside the emergency disclosure.
        setBrand(keycloakMode = true)
        setHash(VALID_RETURN_TO)
        withMountedRoot("login-return-to-emergency") { root, element ->
            renderLoginScreen(root)
            element().allOf("a").first { it.textContent?.trim() == "Interner Notfall-Zugang für Administratoren" }.click()
            assertEquals(1, element().querySelectorAll("input[type=password]").length)
        }
    }
}
