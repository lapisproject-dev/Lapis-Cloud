package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.220 -- invariants of [NavRegistry] that hold for every kind of person (pure, no DOM). */
class NavRegistryTest {
    private fun context(
        role: AccountRole,
        status: MemberStatus,
        offered: Boolean?,
        mcp: Boolean,
    ) = NavContext(
        session =
            SessionInfoDto(
                memberId = "m-1",
                displayName = "Person",
                role = role,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                status = status,
                aiAssistantEnabled = true,
                mcpEnabled = mcp,
            ),
        memberFinderOffered = offered,
    )

    private val contexts: List<NavContext> =
        AccountRole.entries.flatMap { role ->
            MemberStatus.entries.flatMap { status ->
                listOf(true, false).flatMap { mcp -> listOf(null, true, false).map { context(role, status, it, mcp) } }
            }
        }

    @Test
    fun noRoute_isListedTwiceInTheSameGroup() {
        val pairs = NavRegistry.entries.map { it.route to it.group }
        assertEquals(pairs.size, pairs.toSet().size, "a (route, group) pair appears twice")
    }

    @Test
    fun everyRoute_isAbsolute_andEveryGroupHasMeta() {
        NavRegistry.entries.forEach { assertTrue(it.route.startsWith("/"), "route ${it.route}") }
        assertEquals(SidebarGroupId.entries.toSet(), NavRegistry.groupMeta.keys)
        assertEquals(SidebarGroupId.entries.toSet(), NavRegistry.groupVisible.keys)
    }

    @Test
    fun forEveryPerson_aRoute_isOfferedAtMostOnce_flatOrInAGroup() {
        contexts.forEach { ctx ->
            val flat = NavRegistry.flat(ctx).map { it.route }
            val grouped = NavRegistry.visibleGroups(ctx).flatMap { g -> NavRegistry.visibleInGroup(g, ctx).map { it.route } }
            val all = flat + grouped
            assertEquals(all.size, all.toSet().size, "a route is offered twice to ${ctx.session.role}/${ctx.session.status}: $all")
        }
    }

    @Test
    fun groupRoutes_comeFromTheRegistry_andKeepTheDeepLinkOnlyRoutes() {
        val listed = GROUP_ROUTES.values.flatten().toSet()
        NavRegistry.entries.filter { it.group != null }.forEach { assertTrue(it.route in listed, "${it.route} missing in GROUP_ROUTES") }
        assertTrue(Routes.BANK_ACCOUNTS in GROUP_ROUTES.getValue(SidebarGroupId.FINANCE))
        assertTrue(Routes.VOLUNTEER_ALLOWANCES in GROUP_ROUTES.getValue(SidebarGroupId.MEMBERSHIP))
        assertTrue(Routes.EVENT_IMPORT in GROUP_ROUTES.getValue(SidebarGroupId.ADMINISTRATION))
        assertTrue(Routes.MEMBER_LUPABB_SYNC in GROUP_ROUTES.getValue(SidebarGroupId.ADMINISTRATION))
        assertEquals(Routes.CONTRIBUTIONS, GROUP_ROUTES.getValue(SidebarGroupId.MEMBERSHIP).first())
        assertEquals(Routes.LEDGER, GROUP_ROUTES.getValue(SidebarGroupId.FINANCE).first())
    }

    @Test
    fun theLiveSuffix_isSetOnTheConferenceAndTheEncounterEntryOnly() {
        val withSuffix = NavRegistry.entries.filter { it.liveSuffix != null }
        assertEquals(listOf(Routes.CONFERENCE, Routes.ENCOUNTER), withSuffix.map { it.route })
        assertEquals(NavLiveSuffix.CONFERENCE, withSuffix.first { it.route == Routes.CONFERENCE }.liveSuffix)
        assertEquals(NavLiveSuffix.ENCOUNTER, withSuffix.first { it.route == Routes.ENCOUNTER }.liveSuffix)
    }
}
