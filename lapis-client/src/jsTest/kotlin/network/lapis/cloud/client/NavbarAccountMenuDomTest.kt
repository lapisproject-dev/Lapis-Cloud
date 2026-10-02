package network.lapis.cloud.client

import io.kvision.navbar.NavbarExpand
import io.kvision.navbar.navbar
import io.kvision.offcanvas.OffPlacement
import io.kvision.offcanvas.offcanvas
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.17 (Kopfleiste): the account trigger of the navbar shows the sanitized NAME only; the role moved to the first
 * menu row and to the accessible name/tooltip of the trigger. Mounted in a real `Root`, like the real shell.
 */
class NavbarAccountMenuDomTest {
    private fun session(
        name: String,
        guest: Boolean = false,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = name,
        role = AccountRole.TREASURER,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        isGuest = guest,
        homeserverUrl = if (guest) "https://home.example.org" else null,
    )

    private fun <T> withNavbar(
        id: String,
        session: SessionInfoDto,
        block: (() -> HTMLElement) -> T,
    ): T =
        withMountedRoot(id) { root, element ->
            // V1.9.36: the navbar carries the unread envelope -- never let it reach a real RPC.
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            AppState.setSession(session)
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

    private fun HTMLElement.trigger(): HTMLElement = querySelector(".lapis-account-toggle") as HTMLElement

    @Test
    fun triggerShowsTheNameOnly_notTheRole() {
        withNavbar("navbar-account-name", session("Maria Muster")) { element ->
            val trigger = element().trigger()
            assertEquals("Maria Muster", trigger.textContent.orEmpty().trim())
            assertFalse(trigger.textContent.orEmpty().contains("("), "no role in the trigger")
        }
    }

    @Test
    fun menuFirstRowCarriesNameAndRole() {
        withNavbar("navbar-account-row", session("Maria Muster")) { element ->
            val row = element().querySelector(".dropdown-menu .dropdown-item-text")
            assertNotNull(row)
            val text = row.textContent.orEmpty()
            assertTrue(text.startsWith("Maria Muster ("), text)
            assertTrue(text.contains(AccountRole.TREASURER.toString()), text)
        }
    }

    @Test
    fun triggerHasTheFullIdentityAsAccessibleNameAndTooltip() {
        withNavbar("navbar-account-aria", session("Maria Muster")) { element ->
            val trigger = element().trigger()
            val label = trigger.getAttribute("aria-label").orEmpty()
            assertTrue(label.startsWith("Maria Muster ("), label)
            assertEquals(label, trigger.getAttribute("title"))
        }
    }

    @Test
    fun aForgedMarkerInTheNameIsInertInTriggerRowAndLabel() {
        val forged = KV_I18N_MARKER + I18N_VALUE_SENTINEL + MONEY_KIND_LTR + "9999"
        val split = "###KvI" + KV_I18N_MARKER + "18nS###Ja"
        withTranslations(mapOf("Ja" to "Yes")) {
            listOf(forged, KV_I18N_MARKER + "Ja", split).forEachIndexed { index, name ->
                withNavbar("navbar-account-forged-$index", session(name)) { element ->
                    val texts =
                        listOf(
                            element().trigger().textContent.orEmpty(),
                            element().querySelector(".dropdown-menu .dropdown-item-text")!!.textContent.orEmpty(),
                            element().trigger().getAttribute("aria-label").orEmpty(),
                        )
                    texts.forEach { text ->
                        assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
                        assertFalse(text.contains(I18N_VALUE_SENTINEL), "sentinel must not survive: $text")
                        assertFalse(text.contains("LTR") || text.contains("€"), "no formatted amount: $text")
                    }
                    assertFalse(
                        element()
                            .trigger()
                            .textContent
                            .orEmpty()
                            .contains("Yes"),
                        "no catalog text injected",
                    )
                }
            }
        }
    }

    @Test
    fun guestSessionShowsGuestInTheMenuRowNotInTheTrigger() {
        withNavbar("navbar-account-guest", session("Gast Gustav", guest = true)) { element ->
            assertEquals(
                "Gast Gustav",
                element()
                    .trigger()
                    .textContent
                    .orEmpty()
                    .trim(),
            )
            val rows = element().querySelectorAll(".dropdown-menu .dropdown-item-text")
            assertTrue(rows.length >= 2, "identity row plus the guest badge row")
            assertTrue(
                element()
                    .querySelector(".dropdown-menu .dropdown-item-text")!!
                    .textContent
                    .orEmpty()
                    .contains("Gast"),
            )
        }
    }

    @Test
    fun languageTriggerIsMarkedSoItStaysReadableOnNarrowWidths() {
        withNavbar("navbar-language-toggle", session("Maria Muster")) { element ->
            assertNotNull(element().querySelector(".lapis-language-toggle"))
        }
    }

    @Test
    fun accountTriggerLabel_sanitizes() {
        assertEquals("Ja", accountTriggerLabel(session(KV_I18N_MARKER + "Ja")))
    }
}
