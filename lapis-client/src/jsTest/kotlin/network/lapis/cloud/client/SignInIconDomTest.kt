package network.lapis.cloud.client

import io.kvision.navbar.NavbarExpand
import io.kvision.navbar.navbar
import io.kvision.offcanvas.OffPlacement
import io.kvision.offcanvas.offcanvas
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.83 -- "sign in" carries the [ActionIcon.ENTER] icon in both places it is offered: the primary button of the login form and
 * the navbar link of the anonymous state. The icon is decoration (`aria-hidden`), the accessible name stays "Anmelden".
 */
class SignInIconDomTest {
    @Test
    fun loginFormButton_hasTheEnterIcon_andKeepsItsName() {
        withMountedRoot("signin-icon-login") { root, element ->
            renderLoginScreen(root)
            val button = assertNotNull(element().allOf("button").firstOrNull { it.textContent?.trim() == "Anmelden" }, "the login button")
            assertTrue(button.classList.contains("btn-primary"), "stays the primary action")
            assertSignInIcon(button)
        }
    }

    @Test
    fun anonymousNavbarLink_hasTheEnterIcon_andKeepsItsName() {
        withMountedRoot("signin-icon-navbar") { root, element ->
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            AppState.setSession(null)
            try {
                val navbar = root.navbar(label = "Lapis", expand = NavbarExpand.ALWAYS, className = "lapis-navbar")
                val sidebar = root.offcanvas(placement = OffPlacement.START, className = "lapis-sidebar")
                refreshNavbar(navbar, sidebar, onLanguageChange = {})
                val link =
                    assertNotNull(
                        element().allOf("a").firstOrNull { it.textContent?.trim() == "Anmelden" },
                        "the sign-in link of the anonymous navbar",
                    )
                assertEquals("#${Routes.LOGIN}", link.getAttribute("href"))
                assertSignInIcon(link)
            } finally {
                UnreadMessages.resetForTest()
            }
        }
    }

    private fun assertSignInIcon(host: HTMLElement) {
        assertEquals("Anmelden", host.textContent?.trim(), "the accessible name must not change")
        val icon = assertNotNull(host.querySelector("i") as? HTMLElement, "no icon")
        assertEquals("true", icon.getAttribute("aria-hidden"), "the icon is decoration")
        assertTrue(icon.classList.contains("fa-right-to-bracket"), "expected fa-right-to-bracket, got ${icon.className}")
    }
}
