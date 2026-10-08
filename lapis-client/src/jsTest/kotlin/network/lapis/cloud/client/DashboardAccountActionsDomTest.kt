package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.75: "Passwort ändern" carries the key, "Abmelden" the exit symbol (one verb, one picture: the same [ActionIcon.LEAVE] as
 * leaving a room and as the menu entry), both keep their visible text. "Abmelden" stays a filled grey button -- never a danger button --
 * so that it ranks below nothing and above "Austritt".
 */
class DashboardAccountActionsDomTest {
    private fun HTMLElement.buttons(): List<HTMLElement> =
        (0 until querySelectorAll("button").length).map { querySelectorAll("button").item(it) as HTMLElement }

    @Test
    fun logout_hasTheLeaveSymbol_visibleText_andStaysSecondary(): Promise<Unit> =
        formTest {
            mountedForm("dashboard-account-actions") { root, element ->
                renderAccountActions(root)
                val logout = assertNotNull(element().buttons().firstOrNull { it.textContent?.trim() == "Abmelden" }, "no Abmelden button")
                assertNotNull(logout.querySelector("i.fa-right-from-bracket"), "no exit symbol: ${logout.outerHTML}")
                assertTrue(logout.classList.contains("btn-secondary"), "classes ${logout.className}")
                assertFalse(logout.classList.contains("btn-danger") || logout.classList.contains("btn-outline-danger"))
                assertEquals("true", logout.querySelector("i")?.getAttribute("aria-hidden"), "the symbol is decoration")
                val exit = element().buttons().first { it.textContent?.trim()?.startsWith("Austritt") == true }
                assertTrue(exit.classList.contains("btn-outline-danger"), "Austritt keeps its danger outline")
            }
        }

    @Test
    fun changePassword_hasTheKey_andNoHorizontalOverflowAt360px(): Promise<Unit> =
        formTest {
            mountedForm("dashboard-change-password") { root, element ->
                element().style.width = "360px"
                element().style.boxSizing = "border-box"
                renderChangePassword(root)
                renderAccountActions(root)
                val save = assertNotNull(element().buttons().firstOrNull { it.textContent?.trim() == "Passwort ändern" }, "no save button")
                assertNotNull(save.querySelector("i.fa-key"), "no key symbol: ${save.outerHTML}")
                assertTrue(save.classList.contains("btn-primary"))
                assertTrue(
                    element().scrollWidth <= element().clientWidth,
                    "horizontal overflow at 360 px: ${element().scrollWidth} > ${element().clientWidth}",
                )
            }
        }
}
