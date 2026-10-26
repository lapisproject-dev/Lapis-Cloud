package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DashboardModuleKeys

/**
 * V1.9.224 -- where a dashboard card leads. The server never sends a navigation target: it sends a module key and, for exactly one own object,
 * its id. The route is built HERE from a fixed table, so nothing from the network can become a route string. A module with a detail screen
 * leads there for a single object (the id must look like a UUID and is URL-encoded by [detailRoute]); everything else leads to the list.
 */
internal object DashboardSignalRoutes {
    private data class Target(
        val list: String,
        val detail: String?,
    )

    private val UUID = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

    // The detail routes are the "/x/:id" patterns of Routes; only the list route (their prefix) is needed to build one.
    private val targets: Map<String, Target> =
        mapOf(
            DashboardModuleKeys.AUCTION to Target(Routes.AUCTION, null),
            DashboardModuleKeys.TENDER to Target(Routes.TENDERS, Routes.TENDER_DETAIL),
            DashboardModuleKeys.ELECTION to Target(Routes.ELECTIONS, Routes.ELECTION_DETAIL),
            DashboardModuleKeys.CONSENSUS to Target(Routes.CONSENSUS, Routes.CONSENSUS_DETAIL),
            DashboardModuleKeys.SURVEY to Target(Routes.POLLS, Routes.POLL_DETAIL),
            DashboardModuleKeys.MOTION to Target(Routes.MOTIONS, Routes.MOTION_DETAIL),
            DashboardModuleKeys.MEETING to Target(Routes.MEETINGS, Routes.MEETING_DETAIL),
            DashboardModuleKeys.CROWDFUNDING to Target(Routes.CROWDFUNDING, null),
            DashboardModuleKeys.EVENTS to Target(Routes.MY_EVENTS, null),
            DashboardModuleKeys.CONTRIBUTIONS to Target(Routes.CONTRIBUTIONS, null),
            DashboardModuleKeys.TRAVEL to Target(Routes.TRAVEL_EXPENSES, null),
            DashboardModuleKeys.AI to Target(Routes.AI_DRAFTS, null),
            DashboardModuleKeys.APPLICATIONS to Target(Routes.MEMBERS, null),
            DashboardModuleKeys.TREASURY to Target(Routes.DUNNING_CASES, null),
        )

    /** The list route of [moduleKey], `null` for a module this client does not know. */
    fun listRoute(moduleKey: String): String? = targets[moduleKey]?.list

    /** The route to open: the detail screen for one valid object id where there is one, else the list. */
    fun route(
        moduleKey: String,
        objectId: String?,
    ): String? {
        val target = targets[moduleKey] ?: return null
        val id = objectId?.takeIf { UUID.matches(it) }
        return if (id != null && target.detail != null) detailRoute(base = target.list, id = id) else target.list
    }

    /**
     * `true` iff the person's menu shows the list screen of [moduleKey]: the registry entry is visible for them AND its group (if any) is
     * built for them. A signal for a module the menu does not offer would lead nowhere, so such a card is not drawn.
     */
    fun isInMenu(
        moduleKey: String,
        context: NavContext,
    ): Boolean {
        val list = listRoute(moduleKey) ?: return false
        val visibleGroups = NavRegistry.visibleGroups(context)
        return NavRegistry.entries.any { entry ->
            entry.route == list && entry.visible(context) && (entry.group == null || entry.group in visibleGroups)
        }
    }

    /** The registry entry that gives the card its icon and its title. */
    fun entryFor(
        moduleKey: String,
        context: NavContext,
    ): NavEntry? {
        val list = listRoute(moduleKey) ?: return null
        return NavRegistry.entries.firstOrNull { it.route == list && it.visible(context) }
    }
}
