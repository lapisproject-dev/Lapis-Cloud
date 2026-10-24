package network.lapis.cloud.client

/**
 * V1.9.203 -- the searchable list of menu destinations. It is filled by [buildSidebar] itself, link by link, so it can only ever contain
 * what the sidebar of the signed-in person really shows: the role and status gates stay in ONE place (the sidebar code) and the search
 * cannot offer a destination the menu hides. The `MenuSearchDomTest` pins that the index equals the rendered links.
 *
 * [buildSidebar] runs synchronously from [reset] to its last link (the sidebar counters only change a label LATER, never add a link), and
 * the browser is single-threaded, so a query never sees a half-built index.
 */
object MenuIndex {
    private class Registered(
        val entry: MenuEntry,
        val isVisible: () -> Boolean,
    )

    private val byRoute = LinkedHashMap<String, Registered>()
    private var nextOrder = 0

    /** Forgets everything; the start of every sidebar build and of every cleared sidebar. */
    fun reset() {
        byRoute.clear()
        nextOrder = 0
    }

    /**
     * Adds a destination. A route offered twice (the carpool centre is a flat link for a guest and a group link for a member, never both
     * in one session) keeps its first entry. [isVisible] is asked at query time: the member finder link can appear or vanish after the build.
     */
    fun register(
        route: String,
        title: String,
        icon: String,
        groupLabel: String?,
        isVisible: () -> Boolean = { true },
    ) {
        if (route in byRoute) return
        byRoute[route] =
            Registered(MenuEntry(route = route, title = title, icon = icon, groupLabel = groupLabel, order = nextOrder++), isVisible)
    }

    /** The destinations currently offered, in sidebar order. */
    fun entries(): List<MenuEntry> = byRoute.values.filter { it.isVisible() }.map { it.entry }
}
