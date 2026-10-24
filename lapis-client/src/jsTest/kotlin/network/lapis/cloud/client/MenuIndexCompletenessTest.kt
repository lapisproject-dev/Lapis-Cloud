package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.navbar.Nav
import io.kvision.panel.SimplePanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.203 -- the tripwire of the menu search. It builds the real sidebar for every kind of person and checks two things:
 *
 *  1. the menu index holds EXACTLY the links the sidebar offers that person (so the search can neither miss a destination nor show a
 *     hidden one -- role and status gates live only in the sidebar code);
 *  2. every destination has search words in [MenuSearchKeywords.BY_ROUTE] or a written reason in
 *     [MenuSearchKeywords.ALLOWED_WITHOUT_KEYWORD]. A new sidebar link without either turns this test red.
 */
class MenuIndexCompletenessTest {
    private fun session(
        role: AccountRole,
        status: MemberStatus = MemberStatus.ACTIVE,
        mcp: Boolean = false,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Person",
        role = role,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        status = status,
        aiAssistantEnabled = true,
        mcpEnabled = mcp,
    )

    private val sessions =
        listOf(
            session(AccountRole.ADMIN, mcp = true),
            session(AccountRole.BOARD, mcp = true),
            session(AccountRole.TREASURER),
            session(AccountRole.MEMBER),
            session(AccountRole.MEMBER, MemberStatus.GUEST),
            session(AccountRole.MEMBER, MemberStatus.FRIEND),
        )

    @AfterTest
    fun tearDown() {
        AppState.onSessionChange = {}
        AppState.setSession(null)
        MenuIndex.reset()
    }

    /** The links of the sidebar built for [session] that are visible, in order. */
    private fun renderedRoutes(session: SessionInfoDto): List<String> {
        AppState.setSession(session)
        val body = SimplePanel()
        buildSidebar(body, session, activeRoute = null) {}
        val nav = body.getChildren().first() as Nav
        val flat = nav.getChildren().filterIsInstance<Link>()
        val grouped = nav.getChildren().filterIsInstance<Nav>().flatMap { it.getChildren().filterIsInstance<Link>() }
        return (flat + grouped).filter { it.visible }.map { it.url.orEmpty().removePrefix("#") }
    }

    @Test
    fun theIndex_holdsExactlyTheLinksOfTheSidebar_forEveryKindOfPerson() {
        sessions.forEach { session ->
            val rendered = renderedRoutes(session)
            val indexed = MenuIndex.entries().map { it.route }
            assertEquals(rendered.distinct().toSet(), indexed.toSet(), "index vs. sidebar for ${session.role}/${session.status}")
            assertEquals(indexed.size, indexed.toSet().size, "no route twice in the index")
        }
    }

    @Test
    fun theIndex_followsTheRoleOfThePerson() {
        renderedRoutes(session(AccountRole.MEMBER))
        val memberRoutes = MenuIndex.entries().map { it.route }.toSet()
        assertTrue(Routes.CONTRIBUTIONS in memberRoutes)
        assertTrue(Routes.BACKUP !in memberRoutes, "a member cannot find the admin backup screen")
        assertTrue(Routes.LEDGER !in memberRoutes, "a member cannot find the ledger")

        renderedRoutes(session(AccountRole.ADMIN))
        val adminRoutes = MenuIndex.entries().map { it.route }.toSet()
        assertTrue(Routes.BACKUP in adminRoutes)
        assertTrue(Routes.LEDGER in adminRoutes)
    }

    @Test
    fun everyDestination_hasSearchWords_orAWrittenReason() {
        val seen = sessions.flatMap { renderedRoutes(it) }.toSet()
        assertTrue(seen.size > 60, "the scan sees the whole menu (not vacuous): ${seen.size} destinations")
        val flatAndUnlisted =
            setOf(Routes.DASHBOARD, Routes.CONFERENCE, Routes.ENCOUNTER, Routes.CARPOOL, Routes.BANK_ACCOUNTS, Routes.VOLUNTEER_ALLOWANCES)
        val all = seen + GROUP_ROUTES.values.flatten() + flatAndUnlisted
        val missing =
            all.filter { route ->
                MenuSearchKeywords.BY_ROUTE[route].orEmpty().isEmpty() && MenuSearchKeywords.ALLOWED_WITHOUT_KEYWORD[route].isNullOrBlank()
            }
        assertEquals(emptyList(), missing.sorted(), "these destinations have neither search words nor a named reason")
    }

    @Test
    fun theKeywordTable_hasNoStaleOrContradictoryEntries() {
        val seen = sessions.flatMap { renderedRoutes(it) }.toSet()
        val known = seen + GROUP_ROUTES.values.flatten()
        val unknown = MenuSearchKeywords.BY_ROUTE.keys.filter { it !in known }
        assertEquals(emptyList(), unknown, "search words for a route the menu does not have")
        MenuSearchKeywords.BY_ROUTE.forEach { (route, words) ->
            assertTrue(words.isNotEmpty() && words.all { it.isNotBlank() }, "blank search word for $route")
        }
        MenuSearchKeywords.ALLOWED_WITHOUT_KEYWORD.forEach { (route, reason) ->
            assertTrue(reason.isNotBlank(), "the exception for $route needs a reason")
            assertTrue(MenuSearchKeywords.BY_ROUTE[route].isNullOrEmpty(), "$route has search words AND an exception")
            assertTrue(route !in seen, "$route is in the menu: the exception is stale")
        }
    }
}
