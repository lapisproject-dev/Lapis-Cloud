package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Link
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.window
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * V1.9.187 -- the "Hilfe und Feedback" menu at the foot of the sidebar: a popover that opens upward from one trigger button.
 *
 * Items, in order (each only when it applies):
 *  1. "Hilfe zu dieser Ansicht" -- only when the CURRENT view's intro names a help topic that exists ([HelpTopics.available]);
 *  2. "Hilfe-Center" and 3. "Was ist neu" -- always, links to the platform website in the UI language, new tab, `noopener noreferrer`;
 *  4. "Einführungen wieder aufklappen" -- only when at least one intro is collapsed ([PageIntroStorage.anyCollapsed]); it opens them all
 *     again and draws the current view again.
 *
 * There is NO "Problem melden" (no operator contact address reaches the client) and NO shortcut list (the client has no global
 * shortcuts) -- an item that promised either would be a lie.
 *
 * The menu is built when it OPENS, so the current view and the collapsed state are fresh, and every label is created with `tr()` /
 * `gettext()` at that moment (a language switch rebuilds the whole sidebar anyway). Keyboard: Arrow up/down move, Home/End jump,
 * Escape closes and returns the focus to the trigger, Tab closes, a click outside closes. The listener on `window` exists only while the
 * menu is open and [dispose] removes it (the sidebar is rebuilt on every session or language change).
 */
internal class SidebarHelpMenu(
    parent: Container,
    private val currentRoute: () -> String = ::currentPathRoute,
    private val rerender: () -> Unit = ::rerenderCurrentRoute,
) {
    private val wrapper: Div = parent.div(className = "lapis-sidebar-help")
    private val trigger = newActionButton(ActionIcon.HELP, tr("Hilfe und Feedback"), style = ButtonStyle.LINK).also { wrapper.add(it) }
    private val panel: Div = wrapper.div(className = "lapis-sidebar-help__menu")
    private var items: List<SimplePanel> = emptyList()
    private var pendingListener: Int? = null

    var isOpen: Boolean = false
        private set

    private val outsideClick: (Event) -> Unit = { event ->
        val target = event.target as? Node
        val inside = target != null && (wrapper.getElement()?.contains(target) == true)
        if (!inside) close(restoreFocus = false)
    }

    init {
        trigger.addCssClass("lapis-sidebar-help__trigger")
        trigger.setAttribute("aria-haspopup", "menu")
        trigger.setAttribute("aria-expanded", "false")
        trigger.onClick { toggle() }
        trigger.onEvent {
            keydown = { e ->
                val key = e.key
                if (key == "ArrowUp" || key == "ArrowDown") {
                    e.preventDefault()
                    open()
                } else if (key == "Escape" && isOpen) {
                    e.preventDefault()
                    close(restoreFocus = true)
                }
            }
        }
        panel.hide()
        panel.setAttribute("role", "menu")
        panel.setAttribute("aria-label", gettext("Hilfe und Feedback"))
        current = this
    }

    fun toggle() {
        if (isOpen) close(restoreFocus = true) else open()
    }

    fun open() {
        if (isOpen) return
        buildItems()
        isOpen = true
        panel.show()
        trigger.setAttribute("aria-expanded", "true")
        // Registered one task later: the click that opened the menu is still bubbling and must not close it again.
        pendingListener =
            window.setTimeout({
                pendingListener = null
                if (isOpen) window.addEventListener("click", outsideClick)
            }, 0)
        window.setTimeout({ focusItem(0) }, 0)
    }

    fun close(restoreFocus: Boolean) {
        if (!isOpen) return
        isOpen = false
        panel.hide()
        trigger.setAttribute("aria-expanded", "false")
        cancelListener()
        if (restoreFocus) trigger.getElement()?.focus()
    }

    fun dispose() {
        cancelListener()
        if (current === this) current = null
    }

    private fun buildItems() {
        panel.removeAll()
        val built = mutableListOf<SimplePanel>()
        val topic = PageIntros.forRoute(currentRoute())?.helpTopic
        if (HelpTopics.exists(topic)) {
            built += externalItem(tr("Hilfe zu dieser Ansicht"), Branding.helpUrl(checkNotNull(topic)))
        }
        built += externalItem(tr("Hilfe-Center"), Branding.helpIndexUrl())
        built += externalItem(tr("Was ist neu"), Branding.whatsNewUrl())
        if (PageIntroStorage.anyCollapsed()) {
            val reset = Tag(TAG.BUTTON, tr("Einführungen wieder aufklappen"), className = "lapis-sidebar-help__item")
            reset.setAttribute("type", "button")
            reset.setAttribute("role", "menuitem")
            reset.onEvent {
                click = {
                    close(restoreFocus = false)
                    PageIntroStorage.expandAll()
                    rerender()
                }
            }
            built += reset
        }
        items = built
        for ((index, item) in built.withIndex()) {
            item.setAttribute("tabindex", if (index == 0) "0" else "-1")
            item.onEvent {
                keydown = { e -> onItemKey(index, e) }
            }
            panel.add(item)
        }
    }

    private fun externalItem(
        label: String,
        url: String,
    ): SimplePanel {
        val link = Link(label = "", url = url, className = "lapis-sidebar-help__item")
        link.target = "_blank"
        link.setAttribute("rel", "noopener noreferrer")
        link.setAttribute("role", "menuitem")
        link.add(Tag(TAG.SPAN, label))
        val arrow = Tag(TAG.SPAN, " ↗")
        arrow.setAttribute("aria-hidden", "true")
        link.add(arrow)
        return link
    }

    private fun onItemKey(
        index: Int,
        e: Event,
    ) {
        val key = (e as? KeyboardEvent)?.key.orEmpty()
        when (key) {
            "Escape" -> {
                e.preventDefault()
                close(restoreFocus = true)
            }
            // Tab leaves the menu: it closes, the browser moves the focus on.
            "Tab" -> close(restoreFocus = false)
            else ->
                helpMenuTarget(index, key, items.size)?.let { target ->
                    e.preventDefault()
                    focusItem(target)
                }
        }
    }

    private fun focusItem(index: Int) {
        for ((i, item) in items.withIndex()) item.setAttribute("tabindex", if (i == index) "0" else "-1")
        items.getOrNull(index)?.getElement()?.focus()
    }

    private fun cancelListener() {
        pendingListener?.let { window.clearTimeout(it) }
        pendingListener = null
        window.removeEventListener("click", outsideClick)
    }

    internal companion object {
        private var current: SidebarHelpMenu? = null

        /** Removes the window listener of the menu of the sidebar that is about to be rebuilt or cleared. */
        fun disposeCurrent() {
            current?.dispose()
        }
    }
}

/** Pure: the index the key [key] moves to from [from] in a menu of [size] items (arrows wrap); `null` for any other key. */
internal fun helpMenuTarget(
    from: Int,
    key: String,
    size: Int,
): Int? {
    if (size <= 0 || from !in 0 until size) return null
    return when (key) {
        "ArrowDown" -> (from + 1) % size
        "ArrowUp" -> (from - 1 + size) % size
        "Home" -> 0
        "End" -> size - 1
        else -> null
    }
}

/** The path of the current hash route WITHOUT its query (`#/travel-expenses?report=1` gives `/travel-expenses`). */
internal fun currentPathRoute(): String =
    window.location.hash
        .removePrefix("#")
        .substringBefore('?')

/** Mounts the help menu as the foot of the sidebar [body] (a second direct child after the nav, see `buildSidebar`). */
internal fun mountSidebarHelpMenu(body: Container): SidebarHelpMenu = SidebarHelpMenu(body)
