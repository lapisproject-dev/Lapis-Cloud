package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.panel.SimplePanel
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

/** V1.9.339 -- the approval counters of the sidebar are a suffix span (like Nachrichten), the label stays plain. */
class SidebarApprovalCountDomTest {
    @AfterTest
    fun reset() = AppState.setSession(null)

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Maria Muster",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun withLink(
        id: String,
        role: AccountRole = AccountRole.BOARD,
        label: String = "Reisekosten-Freigaben",
        bind: (Link) -> Unit,
        block: suspend (() -> HTMLElement, Link) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            mountedForm(id) { root: SimplePanel, element ->
                val link = Link(label, "#/x", icon = "fas fa-route")
                root.add(link)
                bind(link)
                block(element, link)
            }
        }

    private fun HTMLElement.suffix(): HTMLElement? = querySelector(".nav-live-suffix") as? HTMLElement

    private fun HTMLElement.shown(): Boolean {
        val span = suffix() ?: return false
        return span.textContent.orEmpty().isNotBlank() && span.style.display != "none"
    }

    private fun bindCount(load: suspend () -> Int?): (Link) -> Unit =
        { link -> bindSidebarApprovalCount(link = link, label = "Reisekosten-Freigaben", load = load) }

    @Test
    fun aCount_showsTheSuffix_andTheAccessibleName_butTheLabelStaysPlain(): Promise<Unit> =
        withLink("apc-3", bind = bindCount { 3 }) { el, link ->
            awaitUntil("suffix") { el().shown() }
            assertEquals(" (3)", el().suffix()!!.textContent)
            assertEquals("true", el().suffix()!!.getAttribute("aria-hidden"))
            val a = el().querySelector("a")!!
            assertEquals("Reisekosten-Freigaben, 3 offen", a.getAttribute("aria-label"))
            assertEquals("Reisekosten-Freigaben, 3 offen", a.getAttribute("title"))
            assertEquals("Reisekosten-Freigaben", link.label, "the label (menu search index) is not changed")
        }

    @Test
    fun zero_showsNothing_andNoAriaOverride(): Promise<Unit> =
        withLink("apc-0", bind = bindCount { 0 }) { el, _ ->
            delay(150)
            assertFalse(el().shown())
            assertNull(el().querySelector("a")!!.getAttribute("aria-label"))
            assertNull(el().querySelector("a")!!.getAttribute("title"))
        }

    @Test
    fun aLoadFailure_showsNothing_andRaisesNoToast(): Promise<Unit> =
        withLink("apc-fail", bind = bindCount { error("boom") }) { el, _ ->
            delay(150)
            assertFalse(el().shown())
            assertNull(el().querySelector("a")!!.getAttribute("aria-label"))
            assertNull(el().querySelector("a")!!.getAttribute("title"))
        }

    @Test
    fun overTheCap_showsTwoHundredPlus(): Promise<Unit> =
        withLink("apc-250", bind = bindCount { 250 }) { el, _ ->
            awaitUntil("suffix") { el().shown() }
            assertEquals(" (200+)", el().suffix()!!.textContent)
            assertEquals("Reisekosten-Freigaben, 200+ offen", el().querySelector("a")!!.getAttribute("aria-label"))
        }

    @Test
    fun theAccessibleName_containsTheCountExactlyOnce(): Promise<Unit> =
        withLink("apc-once", bind = bindCount { 7 }) { el, _ ->
            awaitUntil("suffix") { el().shown() }
            val name = el().querySelector("a")!!.getAttribute("aria-label")!!
            assertEquals(1, Regex("7").findAll(name).count())
        }

    @Test
    fun articles_withoutBoardOrAdmin_hasNoSuffixAndNoCall(): Promise<Unit> {
        var calls = 0
        return withLink(
            "apc-art-member",
            role = AccountRole.MEMBER,
            label = "Artikel",
            bind = { link ->
                bindArticlesApprovalCount(link = link, label = "Artikel") {
                    calls++
                    5
                }
            },
        ) { el, _ ->
            delay(150)
            assertNull(el().suffix())
            assertEquals(0, calls)
        }
    }

    @Test
    fun articles_forBoardAndAdmin_showTheCount(): Promise<Unit> =
        withLink("apc-art-board", role = AccountRole.BOARD, label = "Artikel", bind = { link ->
            bindArticlesApprovalCount(link = link, label = "Artikel") { 4 }
        }) { el, _ ->
            awaitUntil("suffix") { el().shown() }
            assertEquals(" (4)", el().suffix()!!.textContent)
            assertEquals("Artikel, 4 offen", el().querySelector("a")!!.getAttribute("aria-label"))
        }

    @Test
    fun theThreeApprovalEntries_areNotInTheSidebarOfAMember() {
        val member = session(AccountRole.MEMBER)
        AppState.setSession(member)
        val ctx = NavContext(session = member, memberFinderOffered = false)
        val routes =
            (NavRegistry.flat(ctx) + NavRegistry.visibleGroups(ctx).flatMap { NavRegistry.visibleInGroup(it, ctx) }).map { it.route }
        assertFalse(Routes.CONTRIBUTION_RELIEF in routes)
        assertFalse(Routes.TRAVEL_EXPENSE_APPROVALS in routes)
        assertFalse(Routes.VOLUNTEER_ALLOWANCE_APPROVALS in routes)
        assertTrue(Routes.ARTICLES in routes)
    }

    @Test
    fun theMenuSearchIndexOfTheEntries_carriesNoCount() {
        val board = session(AccountRole.BOARD)
        AppState.setSession(board)
        val ctx = NavContext(session = board, memberFinderOffered = false)
        val labels =
            NavRegistry.visibleGroups(ctx).flatMap { NavRegistry.visibleInGroup(it, ctx) }.map { plainMenuText(it.label) }
        assertTrue("Artikel" in labels || labels.any { it.startsWith("Artikel") })
        assertTrue(labels.none { Regex("\\(\\d|200\\+").containsMatchIn(it) })
    }
}
