package network.lapis.cloud.client

import io.kvision.navbar.NavbarExpand
import io.kvision.navbar.navbar
import io.kvision.offcanvas.OffPlacement
import io.kvision.offcanvas.offcanvas
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.9.36 -- the unread envelope in the navbar: visibility, pill grammar, accessible name, target, one display per rebuild. */
class NavbarUnreadIndicatorDomTest {
    @AfterTest
    fun reset() = UnreadMessages.resetForTest()

    private fun session(status: MemberStatus = MemberStatus.ACTIVE) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Maria Muster",
            role = AccountRole.MEMBER,
            status = status,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private inline fun withNavbar(
        id: String,
        session: SessionInfoDto?,
        crossinline unread: suspend () -> Int,
        crossinline block: suspend (() -> HTMLElement, rebuild: () -> Unit) -> Unit,
    ): Promise<Unit> =
        formTest {
            UnreadMessages.rpc = UnreadCountRpc { unread() }
            AppState.setSession(session)
            mountedForm(id) { root, element ->
                val navbar = root.navbar(label = "Lapis", expand = NavbarExpand.ALWAYS, className = "lapis-navbar")
                val sidebar = root.offcanvas(placement = OffPlacement.START, className = "lapis-sidebar")
                val rebuild = { refreshNavbar(navbar, sidebar, onLanguageChange = {}) }
                rebuild()
                block(element, rebuild)
            }
        }

    private fun HTMLElement.envelope(): HTMLElement? = querySelector(".lapis-navbar-unread") as? HTMLElement

    private fun HTMLElement.pill(): HTMLElement = assertNotNull(querySelector(".lapis-unread-pill") as? HTMLElement)

    private fun HTMLElement.pillText(): String? = querySelector(".lapis-unread-pill")?.textContent?.trim()

    private fun HTMLElement.pillVisible(): Boolean = querySelector(".lapis-unread-pill") != null

    @Test
    fun anActiveMember_seesTheEnvelope_linkingToTheMessagesSection_withoutAnId(): Promise<Unit> =
        withNavbar("nbu-visible", session(), { 0 }) { el, _ ->
            val link = assertNotNull(el().envelope())
            assertEquals("#/communication?section=messages", link.getAttribute("href"))
            assertFalse(link.getAttribute("href").orEmpty().contains("m-1"))
        }

    @Test
    fun aFriend_seesNoEnvelope(): Promise<Unit> =
        withNavbar("nbu-friend", session(MemberStatus.FRIEND), { 0 }) { el, _ ->
            assertNull(el().envelope())
        }

    @Test
    fun anAnonymousVisitor_seesNoEnvelope(): Promise<Unit> =
        withNavbar("nbu-anon", null, { 0 }) { el, _ ->
            assertNull(el().envelope())
        }

    @Test
    fun pill_showsTheCount_withAccessibleName_noLiveRegion_noAnimation(): Promise<Unit> =
        withNavbar("nbu-pill-3", session(), { 3 }) { el, _ ->
            awaitUntil("3") { el().pillText() == "3" }
            assertTrue(el().pillVisible())
            assertEquals("Nachrichten, 3 ungelesen", el().envelope()!!.getAttribute("aria-label"))
            assertEquals(el().envelope()!!.getAttribute("aria-label"), el().envelope()!!.getAttribute("title"))
            assertNull(el().envelope()!!.getAttribute("aria-live"))
            assertFalse(el().pill().className.contains("animate") || el().pill().className.contains("pulse"))
        }

    @Test
    fun pill_isHiddenAtZero(): Promise<Unit> =
        withNavbar("nbu-pill-0", session(), { 0 }) { el, _ ->
            awaitUntil("rendered") { el().envelope()!!.getAttribute("aria-label") == "Nachrichten" }
            delay(100)
            assertFalse(el().pillVisible())
        }

    @Test
    fun pill_capsAtNinetyNinePlus(): Promise<Unit> =
        withNavbar("nbu-pill-150", session(), { 150 }) { el, _ ->
            awaitUntil("99+") { el().pillText() == "99+" }
            assertEquals("Nachrichten, 99+ ungelesen", el().envelope()!!.getAttribute("aria-label"))
        }

    @Test
    fun rebuildingTheNavbarTwice_keepsExactlyOneDisplay(): Promise<Unit> =
        withNavbar("nbu-rebuild", session(), { 2 }) { el, rebuild ->
            awaitUntil("first render") { el().pillText() == "2" }
            rebuild()
            rebuild()
            awaitUntil("rendered again") { el().pillText() == "2" }
            assertEquals(1, UnreadMessages.displayCount)
            assertEquals(1, el().allOf(".lapis-navbar-unread").size)
        }

    @Test
    fun anRpcFailure_isSilent_noPill(): Promise<Unit> =
        withNavbar("nbu-fail", session(), { throw ForbiddenException("secret-server-text") }) { el, _ ->
            delay(150)
            assertFalse(el().pillVisible())
            assertFalse(el().textContent.orEmpty().contains("secret-server-text"))
            assertEquals(0, el().allOf(".toast").size)
        }

    @Test
    fun whenTheSessionLosesTheMembershipSection_theOldDisplayIsUnregistered(): Promise<Unit> =
        withNavbar("nbu-status-change", session(), { 2 }) { el, rebuild ->
            awaitUntil("first render") { el().pillText() == "2" }
            assertEquals(1, UnreadMessages.displayCount)
            AppState.setSession(session(MemberStatus.FRIEND))
            rebuild()
            assertNull(el().envelope())
            assertEquals(0, UnreadMessages.displayCount)
        }
}
