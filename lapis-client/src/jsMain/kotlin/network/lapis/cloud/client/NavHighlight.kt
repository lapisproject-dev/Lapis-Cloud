package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.html.Link

/**
 * Registry mapping route -> nav link (+ optional enclosing sidebar-group-toggle), rebuilt on every
 * `buildSidebar` call (`Sidebar.kt` throws the whole sidebar away and reconstructs it -- see that
 * function's own `body.removeAll()`), applied on every routing transition via [setActiveRoute].
 * Derived state only: [apply] always clears every tracked link's `active` class/`aria-current`
 * first, then re-adds it to whatever currently matches -- no incremental bookkeeping, so a stale
 * registration can never leave a phantom highlight behind.
 *
 * Vertical-Sidebar-Umbau (2026-09-08): this registry is now populated EXCLUSIVELY by `Sidebar.kt`.
 * The navbar's own account dropdown ("Mein Konto"/"Meine Daten"/"Abmelden") deliberately does NOT
 * register here -- Jobs' review call: the navbar header is identity/session chrome, the sidebar is
 * "where am I", and only the latter gets a persistent active-route highlight.
 */
object NavHighlight {
    private class Entry(
        val route: String,
        val link: Link,
        val toggle: Widget?,
        val openGroup: (() -> Unit)?,
    )

    private val entries = mutableListOf<Entry>()
    private var activeRoute: String? = null

    /** Call as the first statement after `body.removeAll()` in `buildSidebar`. */
    fun reset() {
        entries.clear()
    }

    /**
     * [toggle] is the enclosing sidebar group's header button, non-null only for entries inside a
     * group (see `Sidebar.kt`'s `sidebarGroup`). `Widget`, not the narrower `DropDownButton` this
     * used to be pinned to before the vertical-sidebar-umbau (2026-09-08) -- `Sidebar.kt`'s group
     * headers are plain `Button`s, not `DropDown`s, and `Widget` is the common supertype both
     * share `addCssClass`/`removeCssClass` through, so this widening changes no behavior for the
     * `DropDownButton` case, it only additionally accepts the new header type.
     *
     * [openGroup] (Review-Fund 2026-09-08, Finding 4) force-opens [toggle]'s enclosing group --
     * `Sidebar.kt`'s `sidebarGroup` passes a closure that shows the group's body, flips
     * `aria-expanded`, and persists the new open-set, mirroring its own header-click handler
     * exactly. `null` for every top-level (ungrouped) entry, same as [toggle].
     */
    fun register(
        route: String,
        link: Link,
        toggle: Widget? = null,
        openGroup: (() -> Unit)? = null,
    ) {
        entries += Entry(route, link, toggle, openGroup)
    }

    /** Call from `Routing.kt`'s `show(route, render)`, before the route actually renders. */
    fun setActiveRoute(route: String) {
        activeRoute = route
        apply()
    }

    /**
     * Call as the last statement of `buildSidebar` -- reapplies [activeRoute] to the freshly
     * rebuilt link set (e.g. after a language switch, which also calls `buildSidebar`).
     */
    fun apply() {
        entries.forEach {
            it.link.removeCssClass("active")
            it.link.removeAttribute("aria-current")
            it.toggle?.removeCssClass("active")
        }
        entries.filter { NavRouteMatch.isActive(activeRoute, it.route) }.forEach {
            it.link.addCssClass("active")
            it.link.setAttribute("aria-current", "page")
            it.toggle?.addCssClass("active")
            // Review-Fund 2026-09-08 (Finding 4, MINOR/UX): expands the active link's enclosing
            // group if it happens to be collapsed -- e.g. a Dashboard tile navigating straight into
            // a route inside a currently-closed group. `openGroup` itself no-ops if already open
            // (see `Sidebar.kt`'s `groupOpeners` entry), so this is safe to call unconditionally on
            // every `apply()`, including the redundant call `buildSidebar` already makes as its own
            // last statement.
            it.openGroup?.invoke()
        }
    }
}
