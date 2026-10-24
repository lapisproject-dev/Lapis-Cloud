package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import org.w3c.dom.events.KeyboardEvent

/**
 * V1.9.206. One tab of [pageTabs]. [tabId] and [panelId] are DOM ids; they stay stable so tests and deep links can find the parts.
 */
internal data class PageTab<K>(
    val key: K,
    val label: String,
    val tabId: String,
    val panelId: String,
)

/**
 * Handle of a [pageTabs] widget. [select] switches tabs; [panel] returns the (always existing) panel of a tab, so a caller can render
 * its content lazily on first selection.
 */
internal class PageTabsHandle<K>(
    private val tabs: List<PageTab<K>>,
    private val buttons: Map<K, Button>,
    private val panels: Map<K, SimplePanel>,
    initial: K,
    private val onSelect: (K, SimplePanel) -> Unit,
) {
    var selected: K = initial
        private set

    fun panel(key: K): SimplePanel = panels.getValue(key)

    fun setLabel(
        key: K,
        label: String,
    ) {
        buttons.getValue(key).text = label
    }

    /** [focus] moves the keyboard focus to the tab button (arrow keys do; a mouse click does not need to). */
    fun select(
        key: K,
        focus: Boolean = false,
    ) {
        selected = key
        tabs.forEach { tab ->
            val active = tab.key == key
            val button = buttons.getValue(tab.key)
            button.style = if (active) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEPRIMARY
            button.setAttribute("aria-selected", active.toString())
            button.setAttribute("tabindex", if (active) "0" else "-1")
            val panel = panels.getValue(tab.key)
            if (active) panel.removeAttribute("hidden") else panel.setAttribute("hidden", "")
        }
        if (focus) buttons.getValue(key).getElement()?.focus()
        onSelect(key, panels.getValue(key))
    }

    /** The key a keyboard move leads to, wrapping around; `null` for a key that is no navigation key. */
    internal fun keyTarget(
        from: K,
        pressedKey: String,
    ): K? {
        val index = tabs.indexOfFirst { it.key == from }
        return when (pressedKey) {
            "ArrowRight" -> tabs[(index + 1) % tabs.size].key
            "ArrowLeft" -> tabs[(index - 1 + tabs.size) % tabs.size].key
            "Home" -> tabs.first().key
            "End" -> tabs.last().key
            else -> null
        }
    }
}

/**
 * V1.9.206. A page-level tab bar after the WAI-ARIA tabs pattern: `role=tablist`, `role=tab` with `aria-selected`/`aria-controls`, a
 * roving `tabindex` (only the selected tab is in the tab order), ArrowLeft/ArrowRight with wrap-around, Home/End, and the selection
 * follows the focus. Panels are `role=tabpanel`, the inactive ones `hidden`. Deliberately NOT [segmentedControl]: that is a filter
 * with `aria-pressed`, this is navigation between regions of a page.
 *
 * [onSelect] runs on every selection with the tab's panel -- a caller renders the panel's content on first use only.
 */
internal fun <K> Container.pageTabs(
    tabs: List<PageTab<K>>,
    selected: K,
    ariaLabel: String,
    onSelect: (K, SimplePanel) -> Unit,
): PageTabsHandle<K> {
    require(tabs.isNotEmpty()) { "pageTabs needs at least one tab" }
    val list =
        div(className = "lapis-page-tabs") {
            setAttribute("role", "tablist")
            setAttribute("aria-label", resolvedAttributeText(ariaLabel))
        }
    val buttons = linkedMapOf<K, Button>()
    tabs.forEach { tab ->
        val button =
            list.button(tab.label, style = ButtonStyle.OUTLINEPRIMARY) {
                id = tab.tabId
                setAttribute("role", "tab")
                setAttribute("aria-controls", tab.panelId)
                setAttribute("aria-selected", "false")
                setAttribute("tabindex", "-1")
            }
        buttons[tab.key] = button
    }
    val panels = linkedMapOf<K, SimplePanel>()
    tabs.forEach { tab ->
        panels[tab.key] =
            simplePanel {
                id = tab.panelId
                addCssClass("lapis-page-panel")
                setAttribute("role", "tabpanel")
                setAttribute("aria-labelledby", tab.tabId)
                setAttribute("hidden", "")
            }
    }
    val handle = PageTabsHandle(tabs, buttons, panels, selected, onSelect)
    tabs.forEach { tab ->
        val button = buttons.getValue(tab.key)
        button.onClick { handle.select(tab.key) }
        button.onEvent {
            keydown = { e ->
                val target = (e as? KeyboardEvent)?.let { handle.keyTarget(tab.key, it.key) }
                if (target != null) {
                    e.preventDefault()
                    handle.select(target, focus = true)
                }
            }
        }
    }
    handle.select(selected)
    return handle
}
