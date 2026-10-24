package network.lapis.cloud.client

import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.html.Icon
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext

/**
 * V1.9.203 -- the result list of the menu search, used twice: under the field in the sidebar and inside the command palette.
 * A listbox of options for the combobox input (`aria-activedescendant` pattern: the focus stays in the input, the list only
 * marks one option as active).
 *
 * Every text goes into the DOM as a text node (KVision `Tag` content is escaped, never rich), so a query or a menu title with markup
 * is shown as text. The mark inside a title is its own `<mark>` element built from the plain pieces.
 *
 * [render] returns the text for the live region (empty when there is nothing to announce).
 */
internal class MenuSearchList(
    private val listId: String,
    private val onChoose: (MenuEntry) -> Unit,
) {
    val view: Div = Div(className = "lapis-menu-search__results")

    private val list: Tag = Tag(TAG.UL, className = "lapis-menu-search__list")
    private var entries: List<MenuEntry> = emptyList()
    private var options: List<Tag> = emptyList()
    private var selected: Int = -1

    init {
        list.id = listId
        list.setAttribute("role", "listbox")
        list.setAttribute("aria-label", gettext("Menü durchsuchen"))
    }

    val count: Int get() = entries.size

    /** The id of the active option, `null` when the list is empty. */
    val activeDescendantId: String? get() = if (selected in entries.indices) optionId(selected) else null

    val selectedEntry: MenuEntry? get() = entries.getOrNull(selected)

    /** Draws the list for [query]; the first option is active. Returns the text for the live region. */
    fun render(query: String): String {
        view.removeAll()
        list.removeAll()
        val trimmed = query.trim()
        var heading: String? = null
        var footer: String? = null
        var status = ""
        if (trimmed.isEmpty()) {
            val all = MenuIndex.entries()
            val recent = MenuRecents.load().mapNotNull { route -> all.firstOrNull { it.route == route } }
            if (recent.isNotEmpty()) heading = gettext("Zuletzt besucht")
            entries = recent.ifEmpty { all.take(MenuSearchMatcher.MAX_RESULTS) }
        } else {
            val result = MenuSearchMatcher.search(trimmed, MenuIndex.entries(), MenuSearchKeywords.BY_ROUTE)
            entries = result.shown.map { it.entry }
            when {
                result.total == 0 -> status = gettext("Keine Treffer")
                result.total > MenuSearchMatcher.MAX_RESULTS -> {
                    footer = gettext("%1 Treffer, %2 angezeigt. Suche verfeinern", result.total, MenuSearchMatcher.MAX_RESULTS)
                    status = footer
                }
                else -> status = gettext("%1 Treffer", result.total)
            }
        }
        if (heading != null) view.add(Tag(TAG.DIV, heading, className = "lapis-menu-search__heading"))
        options = entries.mapIndexed { index, entry -> optionFor(index, entry, trimmed) }
        options.forEach { list.add(it) }
        if (entries.isNotEmpty()) view.add(list)
        if (trimmed.isNotEmpty() && entries.isEmpty()) view.add(Tag(TAG.DIV, status, className = "lapis-menu-search__empty"))
        if (footer != null) view.add(Tag(TAG.DIV, footer, className = "lapis-menu-search__more"))
        selected = if (entries.isEmpty()) -1 else 0
        applySelection(scroll = false)
        return status
    }

    /** Moves the active option by [delta], wrapping around at both ends. */
    fun moveSelection(delta: Int) {
        if (entries.isEmpty()) return
        selected = (selected + delta + entries.size) % entries.size
        applySelection(scroll = true)
    }

    fun chooseSelected() {
        selectedEntry?.let(onChoose)
    }

    private fun optionId(index: Int): String = "$listId-opt-$index"

    private fun optionFor(
        index: Int,
        entry: MenuEntry,
        query: String,
    ): Tag {
        val option = Tag(TAG.LI, className = "lapis-menu-search__option")
        option.id = optionId(index)
        option.setAttribute("role", "option")
        option.setAttribute("aria-selected", "false")
        val icon = Icon(entry.icon)
        icon.setAttribute("aria-hidden", "true")
        icon.addCssClass("lapis-menu-search__icon")
        option.add(icon)
        val title = Tag(TAG.SPAN, className = "lapis-menu-search__title")
        var at = 0
        for (range in MenuSearchMatcher.highlightRanges(entry.title, query)) {
            if (range.first > at) title.add(Tag(TAG.SPAN, entry.title.substring(at, range.first)))
            title.add(Tag(TAG.MARK, entry.title.substring(range.first, range.last + 1), className = "lapis-menu-search-mark"))
            at = range.last + 1
        }
        if (at < entry.title.length) title.add(Tag(TAG.SPAN, entry.title.substring(at)))
        option.add(title)
        entry.groupLabel?.let { option.add(Tag(TAG.SPAN, it, className = "lapis-menu-search__path")) }
        // The mouse must not take the focus from the input: it would close the overlay before the click lands.
        option.onEvent {
            mousedown = { it.preventDefault() }
            click = { onChoose(entry) }
        }
        return option
    }

    private fun applySelection(scroll: Boolean) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            option.setAttribute("aria-selected", active.toString())
            if (active) {
                option.addCssClass(
                    "lapis-menu-search__option--active",
                )
            } else {
                option.removeCssClass("lapis-menu-search__option--active")
            }
        }
        if (scroll) {
            runCatching {
                options
                    .getOrNull(selected)
                    ?.getElement()
                    ?.asDynamic()
                    .scrollIntoView(js("({ block: 'nearest' })"))
            }
        }
    }
}
