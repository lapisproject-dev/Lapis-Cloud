package network.lapis.cloud.client

import io.kvision.html.Link
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.231 -- the unread pill on the "Nachrichten" sidebar entry: same count source as the navbar envelope, label untouched. */
class SidebarUnreadBadgeDomTest {
    @AfterTest
    fun reset() = UnreadMessages.resetForTest()

    private inline fun withLink(
        id: String,
        crossinline unread: suspend () -> Int,
        crossinline block: suspend (() -> HTMLElement, Link) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(
                SessionInfoDto(
                    memberId = "m-1",
                    displayName = "Maria Muster",
                    role = AccountRole.MEMBER,
                    status = MemberStatus.ACTIVE,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                ),
            )
            UnreadMessages.rpc = UnreadCountRpc { unread() }
            mountedForm(id) { root, element ->
                val link = Link("Nachrichten", "#/messages", icon = "fas fa-envelope")
                root.add(link)
                bindSidebarUnread(link)
                block(element, link)
            }
        }

    private fun HTMLElement.pill(): HTMLElement? = querySelector(".nav-live-suffix") as? HTMLElement

    private fun HTMLElement.pillShown(): Boolean {
        val span = pill() ?: return false
        return span.textContent.orEmpty().isNotBlank() && span.style.display != "none"
    }

    @Test
    fun aCount_showsThePill_andTheAccessibleName_butTheLabelStaysPlain(): Promise<Unit> =
        withLink("sub-3", { 3 }) { el, link ->
            awaitUntil("pill") { el().pillShown() }
            assertEquals(" (3)", el().pill()!!.textContent)
            assertEquals("true", el().pill()!!.getAttribute("aria-hidden"))
            assertEquals("Nachrichten, 3 ungelesen", el().querySelector("a")!!.getAttribute("aria-label"))
            assertEquals("Nachrichten", link.label, "the label (menu search index) is not changed")
        }

    @Test
    fun overNinetyNine_capsAtNinetyNinePlus(): Promise<Unit> =
        withLink("sub-150", { 150 }) { el, _ ->
            awaitUntil("pill") { el().pillShown() }
            assertEquals(" (99+)", el().pill()!!.textContent)
        }

    @Test
    fun zero_showsNoPill_andNoAriaOverride(): Promise<Unit> =
        withLink("sub-0", { 0 }) { el, _ ->
            delay(150)
            assertFalse(el().pillShown())
            assertNull(el().querySelector("a")!!.getAttribute("aria-label"))
        }

    @Test
    fun navbarAndSidebar_areTwoDisplaysOfOneCount(): Promise<Unit> =
        withLink("sub-two", { 2 }) { el, _ ->
            UnreadMessages.register(NAVBAR_UNREAD_DISPLAY_KEY) {}
            awaitUntil("pill") { el().pillShown() }
            assertEquals(2, UnreadMessages.displayCount)
            // registering the sidebar display again replaces it, never adds one
            val second = Link("Nachrichten", "#/messages")
            bindSidebarUnread(second)
            assertEquals(2, UnreadMessages.displayCount)
            assertTrue(UnreadMessages.count == 2 || UnreadMessages.count == null)
        }
}
