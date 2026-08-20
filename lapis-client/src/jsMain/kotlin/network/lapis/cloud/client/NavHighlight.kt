package network.lapis.cloud.client

import io.kvision.dropdown.DropDownButton
import io.kvision.html.Link

/**
 * Registry mapping route -> nav link (+ optional enclosing dropdown-toggle), rebuilt on every
 * `refreshNavbar` call (KVision throws the whole navbar away and reconstructs it -- see that
 * function's own `navbar.removeAll()`), applied on every routing transition via [setActiveRoute].
 * Derived state only: [apply] always clears every tracked link's `active` class/`aria-current`
 * first, then re-adds it to whatever currently matches -- no incremental bookkeeping, so a stale
 * registration can never leave a phantom highlight behind.
 */
object NavHighlight {
    private class Entry(
        val route: String,
        val link: Link,
        val toggle: DropDownButton?,
    )

    private val entries = mutableListOf<Entry>()
    private var activeRoute: String? = null

    /** Call as the first statement after `navbar.removeAll()` in `refreshNavbar`. */
    fun reset() {
        entries.clear()
    }

    /** [toggle] is the enclosing dropdown's header button, non-null only for `ddLink` entries. */
    fun register(
        route: String,
        link: Link,
        toggle: DropDownButton? = null,
    ) {
        entries += Entry(route, link, toggle)
    }

    /** Call from `Routing.kt`'s `show(route, render)`, before the route actually renders. */
    fun setActiveRoute(route: String) {
        activeRoute = route
        apply()
    }

    /**
     * Call as the last statement of `refreshNavbar` -- reapplies [activeRoute] to the freshly
     * rebuilt link set (e.g. after a language switch, which also calls `refreshNavbar`).
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
        }
    }
}
