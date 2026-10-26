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
 * V1.9.188 -- the "cannot forget it" test of the page intros, complete since Part 2. It builds the real sidebar for every kind of person
 * (with the agent interface switched on and off) and checks every destination it offers:
 *
 *  - every flat entry and every link of every group MUST have a [PageIntros] entry, or be named in [noIntro] with a written reason;
 *  - a static pass over [GROUP_ROUTES] holds the same for the routes that a group lists, whatever the person's role (a link the sidebar
 *    hides for the sessions below is still checked);
 *  - the pass is not vacuous: it must have SEEN the entries that are only offered under a flag (KI-Entwürfe needs `mcpEnabled`) and the
 *    a sidebar link that used to be missing from [GROUP_ROUTES] (Bankkonten).
 *
 * A new sidebar entry therefore either gets an intro or is named here; it cannot slip through unnoticed.
 */
class SidebarIntroCoverageDomTest {
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
            session(AccountRole.ADMIN),
            session(AccountRole.ADMIN, mcp = true),
            session(AccountRole.BOARD),
            session(AccountRole.BOARD, mcp = true),
            session(AccountRole.TREASURER),
            session(AccountRole.TREASURER, mcp = true),
            session(AccountRole.MEMBER),
            session(AccountRole.MEMBER, mcp = true),
            session(AccountRole.MEMBER, MemberStatus.GUEST),
            session(AccountRole.MEMBER, MemberStatus.FRIEND),
        )

    /** Destinations that deliberately have no intro, each with its reason. */
    private val noIntro: Map<String, String> =
        mapOf(
            Routes.EVENT_IMPORT to "no sidebar link; reachable only from the event management screen (a sub-flow, not a view of its own)",
            Routes.ARTICLE_IMPORT to "no sidebar link; reachable only from the articles screen (a sub-flow, not a view of its own)",
        )

    @AfterTest
    fun tearDown() {
        AppState.onSessionChange = {}
        AppState.setSession(null)
    }

    private fun routesOf(link: Link): String = link.url.orEmpty().removePrefix("#")

    /** (flat routes, group id -> routes) of the sidebar built for [session]. */
    private fun destinations(session: SessionInfoDto): Pair<List<String>, Map<SidebarGroupId, List<String>>> {
        AppState.setSession(session)
        val body = SimplePanel()
        buildSidebar(body, session, activeRoute = null) {}
        val nav = body.getChildren().first() as Nav
        val flat = nav.getChildren().filterIsInstance<Link>().map { routesOf(it) }
        val groups =
            nav
                .getChildren()
                .filterIsInstance<Nav>()
                .associate { groupBody ->
                    val id = SidebarGroupId.entries.single { "lapis-sidebar-group-${it.name.lowercase()}" == groupBody.getAttribute("id") }
                    id to groupBody.getChildren().filterIsInstance<Link>().map { routesOf(it) }
                }
        return flat to groups
    }

    private fun seenDestinations(): Set<String> =
        sessions
            .flatMap { session ->
                val (flat, groups) = destinations(session)
                flat + groups.values.flatten()
            }.toSet()

    @Test
    fun everySidebarDestination_hasAnIntro_orANamedReason() {
        val missing = mutableSetOf<String>()
        var seen = 0
        sessions.forEach { session ->
            val (flat, groups) = destinations(session)
            (flat + groups.values.flatten()).forEach { route ->
                seen++
                if (PageIntros.forRoute(route) == null && route !in noIntro) missing += route
            }
        }
        assertTrue(seen > 200, "the scan sees the sidebar of every kind of person (not vacuous): $seen destinations")
        assertEquals(emptySet(), missing, "these sidebar destinations have no page intro and no named reason")
    }

    @Test
    fun theScan_seesTheFlagGatedAndTheUnlistedDestinations_soItIsNotVacuous() {
        val seen = seenDestinations()
        assertTrue(Routes.AI_DRAFTS in seen, "KI-Entwürfe is offered only with mcpEnabled: a session with the flag must reach it")
        assertTrue(Routes.BANK_ACCOUNTS in seen, "Bankkonten is a sidebar link")
        assertTrue(
            Routes.BANK_ACCOUNTS in GROUP_ROUTES.values.flatten(),
            "since V1.9.220 GROUP_ROUTES is derived from the registry and lists it",
        )
        GROUP_ROUTES.keys.forEach { group ->
            assertTrue(
                sessions.any { destinations(it).second[group].orEmpty().isNotEmpty() },
                "no session reaches the group $group: the scan would not check it",
            )
        }
    }

    @Test
    fun everyRouteOfAGroup_hasAnIntro_orANamedReason_whateverTheRole() {
        val missing = GROUP_ROUTES.values.flatten().filter { PageIntros.forRoute(it) == null && it !in noIntro }
        assertEquals(emptyList(), missing, "these group routes have no page intro and no named reason")
    }

    @Test
    fun noIntroReasons_areWritten_andStillNeeded() {
        noIntro.forEach { (route, reason) ->
            assertTrue(reason.isNotBlank(), "$route needs a reason")
            assertTrue(PageIntros.forRoute(route) == null, "$route has an intro now: remove it from the allow-list")
        }
    }

    @Test
    fun registry_andItsRouteList_agree() {
        PageIntros.routes.forEach { route ->
            val intro = PageIntros.forRoute(route)
            assertTrue(intro != null, "$route is listed but has no intro")
            assertTrue(intro.viewKey.matches(Regex("[a-z]+(-[a-z]+)*")), "view key of $route is a code constant: ${intro.viewKey}")
        }
        assertEquals(PageIntros.routes.size, PageIntros.routes.toSet().size, "no route twice")
        assertEquals(77, PageIntros.routes.size, "23 views of Part 1 plus 54 of Part 2")
        val keys = PageIntros.routes.map { PageIntros.forRoute(it)!!.viewKey }
        assertEquals(keys.size, keys.toSet().size, "every view has its own storage key")
        assertEquals("travel-expenses", PageIntros.forRoute(Routes.TRAVEL_EXPENSES)?.helpTopic)
        PageIntros.routes.filter { it != Routes.TRAVEL_EXPENSES }.forEach { assertEquals(null, PageIntros.forRoute(it)?.helpTopic) }
        PageIntros.routes.mapNotNull { PageIntros.forRoute(it)?.helpTopic }.forEach { assertTrue(it in HelpTopics.available) }
        // "Mehr" is the audit log's alone (the source scan pins the list of allowed views and every text rule)
        assertEquals(listOf(Routes.AUDIT_LOG), PageIntros.routes.filter { PageIntros.forRoute(it)?.more != null })
    }
}
