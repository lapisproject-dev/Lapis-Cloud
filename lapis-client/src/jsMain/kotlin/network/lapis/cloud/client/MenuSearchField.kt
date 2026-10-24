package network.lapis.cloud.client

import io.kvision.core.Container

/**
 * Goes to [entry]: remembers it for "Zuletzt besucht" and navigates through the router (the same programmatic path the nav tiles use).
 * Shared by the sidebar field and the command palette.
 */
internal fun goToMenuEntry(entry: MenuEntry) {
    MenuRecents.remember(entry.route)
    menuEntryNavigator(entry.route)
}

/** The navigation step of [goToMenuEntry]: the router. A variable only so that a test, where no router runs, can observe the route. */
internal var menuEntryNavigator: (String) -> Unit = ::navigateTo

/**
 * V1.9.203 -- the search field at the top of the sidebar. A one-line field with a magnifier and the shortcut hint; the hits open as an
 * overlay right under it (the sidebar itself is NOT filtered, so its groups, their open state and the page scroll stay as they are).
 *
 * The overlay opens when the input gets the focus (with "Zuletzt besucht" while it is empty) and closes when the focus leaves (both are
 * done by [MenuSearchBox]), on Escape and after a choice. [onNavigate] is the same callback the sidebar links run (it closes the off-canvas panel on a phone).
 *
 * There is at most one field; [buildSidebar] and [clearSidebar] call [disposeCurrent] before the old widgets go, so no timer outlives them.
 */
internal class MenuSearchField(
    parent: Container,
    private val onNavigate: () -> Unit,
) {
    private val box: MenuSearchBox

    init {
        box =
            MenuSearchBox(
                idPrefix = ID_PREFIX,
                listAlwaysOpen = false,
                onChoose = ::choose,
                onEscape = ::dismiss,
                shortcutHint = menuSearchShortcutHint(),
                ariaShortcut = menuSearchAriaShortcut(),
            )
        box.root.addCssClass("lapis-menu-search")
        parent.add(box.root)
        current = this
    }

    private fun choose(entry: MenuEntry) {
        dismiss()
        goToMenuEntry(entry)
        onNavigate()
    }

    private fun dismiss() {
        box.closeList()
        box.query = ""
        box.blur()
    }

    fun dispose() {
        box.dispose()
        if (current === this) current = null
    }

    internal companion object {
        const val ID_PREFIX = "lapis-menu-search"

        /** The id of the input, so the global shortcut can tell that the focus is already in this field. */
        const val INPUT_ID = "$ID_PREFIX-input"

        private var current: MenuSearchField? = null

        fun disposeCurrent() {
            current?.dispose()
        }
    }
}

/** Mounts the search field as the first entry of the sidebar [nav] (see `buildSidebar`). */
internal fun mountMenuSearchField(
    nav: Container,
    onNavigate: () -> Unit,
): MenuSearchField = MenuSearchField(nav, onNavigate)
