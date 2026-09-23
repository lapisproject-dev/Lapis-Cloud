package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLScriptElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BRAND_ELEMENT_ID = "lapis-brand"

private fun setKeycloakBrandElement(
    keycloakMode: Boolean,
    emergencyAdminLoginEnabled: Boolean = true,
) {
    document.getElementById(BRAND_ELEMENT_ID)?.remove()
    val script = document.createElement("script") as HTMLScriptElement
    script.type = "application/json"
    script.id = BRAND_ELEMENT_ID
    script.textContent =
        """{"title":"ELB","logoUrl":null,"keycloakMode":$keycloakMode,"emergencyAdminLoginEnabled":$emergencyAdminLoginEnabled}"""
    document.head?.appendChild(script)
}

/**
 * V1.7.2 sub-wave 2b "Keycloak als externe Benutzerverwaltung -- UI": [renderLoginScreen]'s
 * Keycloak-mode branch ([Branding.keycloakMode] `== true`, read from the same pre-login `#lapis-brand`
 * payload [BrandingTest] already covers for `title`/`logoUrl`) -- the SSO button replaces the
 * email/password form, the emergency-ADMIN disclosure stays collapsed until clicked, "Passwort
 * vergessen?" disappears (a 404 dead end server-side in this mode), and -- unchanged -- the classic
 * mode still shows exactly what it always did.
 */
class LoginScreenKeycloakModeDomTest {
    @AfterTest
    fun cleanup() {
        document.getElementById(BRAND_ELEMENT_ID)?.remove()
    }

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    private fun HTMLElement.linkNamed(text: String): HTMLElement? = allOf("a").firstOrNull { it.textContent?.trim() == text }

    @Test
    fun keycloakMode_showsTheSsoButton_asAFullPageLinkToTheStartRoute() {
        setKeycloakBrandElement(keycloakMode = true)
        withMountedRoot("login-keycloak-sso-button") { root, element ->
            renderLoginScreen(root)
            val ssoLink = assertNotNull(element().linkNamed("Mit ELB anmelden"), "the SSO button")
            assertEquals("/auth/keycloak/start", ssoLink.getAttribute("href"))
            // dataNavigo = false -- the exact opt-out `link(... dataNavigo = false)` already establishes
            // for the federation login link, see `renderKeycloakLoginPanel` KDoc.
            assertEquals("false", ssoLink.getAttribute("data-navigo"))
            assertTrue(ssoLink.classList.contains("btn"), "styled as a button")
            assertTrue(ssoLink.classList.contains("btn-primary"))
        }
    }

    @Test
    fun keycloakMode_hidesTheEmailPasswordForm_untilTheEmergencyDisclosureIsOpened() {
        setKeycloakBrandElement(keycloakMode = true)
        withMountedRoot("login-keycloak-hides-form") { root, element ->
            renderLoginScreen(root)
            assertEquals(0, element().querySelectorAll("input[type=email]").length, "no e-mail field before the disclosure is opened")
            assertEquals(0, element().querySelectorAll("input[type=password]").length, "no password field before the disclosure is opened")

            val toggle =
                assertNotNull(element().linkNamed("Interner Notfall-Zugang für Administratoren"), "the emergency disclosure toggle")
            toggle.click()

            assertEquals(1, element().querySelectorAll("input[type=email]").length, "the email/password form appears once opened")
            assertEquals(1, element().querySelectorAll("input[type=password]").length)
        }
    }

    @Test
    fun keycloakMode_emergencyDisclosure_stillLogsInThroughTheClassicForm() {
        setKeycloakBrandElement(keycloakMode = true)
        withMountedRoot("login-keycloak-emergency-form") { root, element ->
            renderLoginScreen(root)
            val toggle = assertNotNull(element().linkNamed("Interner Notfall-Zugang für Administratoren"))
            toggle.click()
            val email = element().first("input[type=email]")
            val password = element().first("input[type=password]")
            assertEquals("username", email.getAttribute("autocomplete"))
            assertEquals("current-password", password.getAttribute("autocomplete"))
            assertNotNull(element().first("button.btn-primary"), "an Anmelden button still exists inside the disclosure")
        }
    }

    @Test
    fun keycloakMode_hasNoForgotPasswordLink() {
        setKeycloakBrandElement(keycloakMode = true)
        withMountedRoot("login-keycloak-no-forgot-password") { root, element ->
            renderLoginScreen(root)
            assertNull(element().linkNamed("Passwort vergessen?"), "password reset is a dead end (404) in Keycloak mode")
        }
    }

    // Review fix (MINOR 3): the emergency-admin disclosure must stay hidden entirely when the
    // deployment has `emergencyAdminLoginEnabled == false` -- rendering it would be a dead end, since
    // every local login is rejected server-side in that configuration (`AuthRoutes.kt`).
    @Test
    fun keycloakMode_withEmergencyAdminLoginDisabled_rendersNoEmergencyDisclosureAtAll() {
        setKeycloakBrandElement(keycloakMode = true, emergencyAdminLoginEnabled = false)
        withMountedRoot("login-keycloak-emergency-disabled") { root, element ->
            renderLoginScreen(root)
            assertNull(
                element().linkNamed("Interner Notfall-Zugang für Administratoren"),
                "no emergency disclosure when the deployment disabled it",
            )
            assertEquals(0, element().querySelectorAll("input[type=email]").length)
            assertEquals(0, element().querySelectorAll("input[type=password]").length)
        }
    }

    @Test
    fun nonKeycloakMode_stillShowsTheClassicFormDirectly_andForgotPassword() {
        setKeycloakBrandElement(keycloakMode = false)
        withMountedRoot("login-non-keycloak-unchanged") { root, element ->
            renderLoginScreen(root)
            assertEquals(1, element().querySelectorAll("input[type=email]").length, "the classic form is the default content")
            assertEquals(1, element().querySelectorAll("input[type=password]").length)
            assertNotNull(element().linkNamed("Passwort vergessen?"))
            assertNull(element().linkNamed("Interner Notfall-Zugang für Administratoren"), "no emergency disclosure outside Keycloak mode")
            assertFalse(
                element().allOf("a").any { it.getAttribute("href") == "/auth/keycloak/start" },
                "no SSO button outside Keycloak mode",
            )
        }
    }
}
