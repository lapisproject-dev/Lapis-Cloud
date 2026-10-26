package network.lapis.cloud.client

import io.kvision.navbar.NavbarExpand
import io.kvision.navbar.navbar
import io.kvision.offcanvas.OffPlacement
import io.kvision.offcanvas.offcanvas
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.219 "Kopfleiste mobil": below 768px every navbar trigger (envelope, theme, language, account) is one 44x44 box on a common vertical
 * centre line, and the language/account menus are right-aligned and stay inside the viewport (no horizontal scroll). Measured on the real
 * markup of [refreshNavbar] (mounted in a real `Root`, then cloned into a [measureAtWidth] frame) against the real cascade. Limits: the clone
 * has no handlers, so a menu is opened by adding Bootstrap's `show` classes; nothing is verified on a real phone.
 */
class NavbarMobileDomTest {
    private val longName = "Maximilian-Alexander von Hohenzollern-Sigmaringen"

    private fun <T> withNavbar(block: (() -> HTMLElement) -> T): T =
        withMountedRoot("navbar-mobile") { root, element ->
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            AppState.setSession(
                SessionInfoDto(
                    memberId = "m-1",
                    displayName = longName,
                    role = AccountRole.TREASURER,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                ),
            )
            try {
                val navbar = root.navbar(label = "Lapis", expand = NavbarExpand.ALWAYS, className = "lapis-navbar")
                val sidebar = root.offcanvas(placement = OffPlacement.START, className = "lapis-sidebar")
                refreshNavbar(navbar, sidebar, onLanguageChange = {})
                block(element)
            } finally {
                AppState.setSession(null)
                UnreadMessages.resetForTest()
            }
        }

    private fun HTMLElement.triggers(): List<HTMLElement> =
        listOf(
            queryOne(".lapis-navbar-unread"),
            queryOne("i.fa-moon, i.fa-sun").parentElement.unsafeCast<HTMLElement>(),
            queryOne(".lapis-language-toggle"),
            queryOne(".lapis-account-toggle"),
        )

    private fun centreY(e: HTMLElement): Double = e.getBoundingClientRect().let { it.top + it.height / 2 }

    /** KVision renders toggle and `.dropdown-menu` as siblings inside one wrapper (`div.dropdown`). */
    private fun menuOf(toggle: HTMLElement): HTMLElement =
        toggle.parentElement.unsafeCast<HTMLElement>().queryOne(":scope > .dropdown-menu")

    private fun open(toggle: HTMLElement): HTMLElement {
        val menu = menuOf(toggle)
        toggle.classList.add("show")
        toggle.setAttribute("aria-expanded", "true")
        menu.classList.add("show")
        // In a navbar Bootstrap marks the menu as statically placed (no Popper): `.dropdown-menu[data-bs-popper]{left:0}`.
        menu.setAttribute("data-bs-popper", "static")
        return menu
    }

    private fun close(toggle: HTMLElement) {
        toggle.classList.remove("show")
        toggle.setAttribute("aria-expanded", "false")
        menuOf(toggle).classList.remove("show")
    }

    private fun checkPhone(width: Int) =
        withNavbar { element ->
            measureAtWidth(element(), width) { doc, host ->
                val win = doc.defaultView!!
                val triggers = host.triggers()
                val reference = centreY(triggers[0])
                triggers.forEach { t ->
                    val r = t.getBoundingClientRect()
                    assertTrue(
                        r.width >= 43.99 && r.height >= 43.99,
                        "${t.className} is ${r.width}x${r.height}, expected >= 44x44 at $width",
                    )
                    assertTrue(abs(centreY(t) - reference) <= 2.0, "${t.className} centre ${centreY(t)} vs $reference at $width")
                }
                listOf(triggers[2], triggers[3]).forEach { toggle ->
                    val menu = open(toggle)
                    val r = menu.getBoundingClientRect()
                    assertTrue(r.right <= win.innerWidth + 0.5, "${toggle.className} menu right ${r.right} > ${win.innerWidth} at $width")
                    assertTrue(r.left >= -0.5, "${toggle.className} menu left ${r.left} at $width")
                    val root = doc.documentElement.unsafeCast<HTMLElement>()
                    assertTrue(root.scrollWidth <= root.clientWidth, "horizontal scroll with open ${toggle.className} menu at $width")
                    assertTrue(menu.scrollWidth <= menu.clientWidth + 1, "menu entries wrap instead of overflowing at $width")
                    close(toggle)
                }
            }
        }

    @Test
    fun phone360() = checkPhone(360)

    @Test
    fun phone375() = checkPhone(375)

    @Test
    fun phone390() = checkPhone(390)

    @Test
    fun desktop1280_accountShowsNameAndMenuAlignsToTheRight() =
        withNavbar { element ->
            measureAtWidth(element(), 1280) { doc, host ->
                val win = doc.defaultView!!
                val account = host.queryOne(".lapis-account-toggle")
                val style = win.getComputedStyle(account)
                assertEquals("inline-block", style.display)
                assertEquals("ellipsis", style.textOverflow)
                assertTrue(account.offsetWidth > 44, "the name is visible on desktop: ${account.offsetWidth}")
                val menu = open(account)
                val wrapper = account.parentElement.unsafeCast<HTMLElement>()
                assertEquals(
                    wrapper.getBoundingClientRect().right,
                    menu.getBoundingClientRect().right,
                    1.0,
                    "menu right edge = toggle wrapper",
                )
            }
        }
}
