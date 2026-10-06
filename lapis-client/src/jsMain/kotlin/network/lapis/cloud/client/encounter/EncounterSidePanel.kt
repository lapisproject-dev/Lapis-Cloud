package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.actionButton
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** The tabs of the side panel. */
internal enum class EncounterSideTab { CHAT, PRESENT, STREAM }

/**
 * V1.9.62 Begegnungsraum (B2) -- the right-hand panel of the room with the tabs "Chat", "Anwesende" and (for people who moderate)
 * "Übertragung". On a wide screen it sits beside the stage and does not take the focus away from anything; below 768 px it is a full
 * screen SHEET: `role="dialog"`, `aria-modal`, Escape closes it, Tab and Shift+Tab cycle inside it, and the focus returns to the
 * element that opened it. The panel is closed by default (a congregation that only listens needs no chrome), and every tab body is
 * built by the caller into [hostOf] so the logic of chat, presence and stream stays in its own classes.
 */
internal class EncounterSidePanel(
    parent: Container,
    private val tabs: List<EncounterSideTab>,
    private val onTabShown: (EncounterSideTab) -> Unit = {},
) {
    val root: Div = parent.div(className = "lapis-encounter-side")
    private val tabList: Div = root.div(className = "lapis-encounter-tabs")
    private val bodies = tabs.associateWith { root.div(className = "lapis-encounter-tabpanel") }
    private val tabButtons = mutableMapOf<EncounterSideTab, Button>()
    private var opener: HTMLElement? = null

    var activeTab: EncounterSideTab = tabs.first()
        private set

    var isOpen: Boolean = false
        private set

    /** True while the panel is shown as a full-screen sheet (a narrow screen). */
    var isSheet: Boolean = false
        private set

    init {
        tabList.setAttribute("role", "tablist")
        tabList.setAttribute("aria-label", gettext("Raum-Bereiche"))
        tabs.forEach { tab ->
            val button = tabList.actionButton(tabIcon(tab), tabLabel(tab), style = ButtonStyle.OUTLINESECONDARY)
            button.setAttribute("role", "tab")
            button.onClick { show(tab) }
            button.onEvent {
                keydown = { event ->
                    val step =
                        when (event.key) {
                            "ArrowRight" -> 1
                            "ArrowLeft" -> -1
                            else -> 0
                        }
                    if (step != 0) {
                        event.preventDefault()
                        val next = tabs[(tabs.indexOf(tab) + step + tabs.size) % tabs.size]
                        show(next)
                        tabButtons[next]?.getElement()?.focus()
                    }
                }
            }
            tabButtons[tab] = button
        }
        tabs.forEach { tab -> bodies.getValue(tab).setAttribute("role", "tabpanel") }
        tabList.actionButton(ActionIcon.CLOSE, tr("Schließen"), style = ButtonStyle.OUTLINESECONDARY).onClick { close() }
        root.onEvent {
            keydown = { event ->
                if (isOpen && event.key == "Escape") {
                    event.preventDefault()
                    close()
                } else if (isOpen && isSheet && event.key == "Tab") {
                    trapFocus(event)
                }
            }
        }
        root.hide()
        syncTabState()
    }

    fun hostOf(tab: EncounterSideTab): Div = bodies.getValue(tab)

    /** Opens the panel on [tab] (or switches to it). */
    fun open(tab: EncounterSideTab) {
        if (!isOpen) {
            opener = document.activeElement as? HTMLElement
            isSheet = window.matchMedia("(max-width: 767.98px)").matches
            if (isSheet) {
                root.addCssClass("is-sheet")
                root.setAttribute("role", "dialog")
                root.setAttribute("aria-modal", "true")
                root.setAttribute("aria-label", gettext("Chat und Anwesende"))
            }
            root.show()
            isOpen = true
        }
        show(tab)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        isSheet = false
        root.hide()
        root.removeCssClass("is-sheet")
        root.removeAttribute("role")
        root.removeAttribute("aria-modal")
        root.removeAttribute("aria-label")
        opener?.focus()
        opener = null
    }

    fun toggle(tab: EncounterSideTab) {
        if (isOpen && activeTab == tab) close() else open(tab)
    }

    private fun show(tab: EncounterSideTab) {
        activeTab = tab
        syncTabState()
        onTabShown(tab)
    }

    private fun syncTabState() {
        tabs.forEach { tab ->
            val selected = tab == activeTab
            tabButtons[tab]?.setAttribute("aria-selected", selected.toString())
            tabButtons[tab]?.setAttribute("tabindex", if (selected) "0" else "-1")
            tabButtons[tab]?.style = if (selected) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
            if (selected) bodies.getValue(tab).show() else bodies.getValue(tab).hide()
        }
    }

    private fun trapFocus(event: org.w3c.dom.events.KeyboardEvent) {
        val host = root.getElement() ?: return
        val focusable =
            host
                .querySelectorAll("button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex='-1'])")
                .asList()
                .filterIsInstance<HTMLElement>()
        if (focusable.isEmpty()) return
        val first = focusable.first()
        val last = focusable.last()
        val active = document.activeElement
        if (event.shiftKey && active === first) {
            event.preventDefault()
            last.focus()
        } else if (!event.shiftKey && active === last) {
            event.preventDefault()
            first.focus()
        }
    }

    private fun tabLabel(tab: EncounterSideTab): String =
        when (tab) {
            EncounterSideTab.CHAT -> tr("Chat")
            EncounterSideTab.PRESENT -> tr("Anwesende")
            EncounterSideTab.STREAM -> tr("Übertragung")
        }

    private fun tabIcon(tab: EncounterSideTab): ActionIcon =
        when (tab) {
            EncounterSideTab.CHAT -> ActionIcon.CHAT
            EncounterSideTab.PRESENT -> ActionIcon.PEOPLE
            EncounterSideTab.STREAM -> ActionIcon.BROADCAST
        }
}
