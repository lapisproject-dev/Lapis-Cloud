package network.lapis.cloud.client

import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.html.Icon
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement

/** How long the live region waits after the last keystroke before it announces the number of hits. */
internal const val MENU_SEARCH_ANNOUNCE_DELAY_MS = 150

/**
 * V1.9.203 -- one search input with its result list and live region. The sidebar field and the command palette are two instances.
 *
 * The input is an ARIA combobox (`role=combobox`, `aria-controls`, `aria-expanded`, `aria-activedescendant`); the focus never leaves it.
 * Keys: arrow down/up move the active option (wrapping), Enter chooses it, Escape calls [onEscape]. With [listAlwaysOpen] (palette) the
 * list is part of the box; otherwise (sidebar) it is an overlay that [openList] / [closeList] show and hide.
 *
 * The timer of the live region is the only resource besides the widgets; [dispose] cancels it (closing and every sidebar rebuild call it).
 */
internal class MenuSearchBox(
    idPrefix: String,
    private val listAlwaysOpen: Boolean,
    private val onChoose: (MenuEntry) -> Unit,
    private val onEscape: () -> Unit,
    shortcutHint: String? = null,
    ariaShortcut: String? = null,
) {
    val root: Div = Div(className = "lapis-menu-search-box")
    val inputId: String = "$idPrefix-input"
    private val inputTag: Tag = Tag(TAG.INPUT, className = "lapis-menu-search__input")
    private val list = MenuSearchList(listId = "$idPrefix-list", onChoose = onChoose)
    private val live: Tag = Tag(TAG.DIV, className = "visually-hidden")
    private var announceTimer: Int? = null
    private var listOpen = false

    init {
        val label = Tag(TAG.LABEL, tr("Menü durchsuchen"), className = "visually-hidden")
        label.setAttribute("for", inputId)
        root.add(label)

        val field = Div(className = "lapis-menu-search__field")
        val glass = Icon("fas fa-magnifying-glass")
        glass.addCssClass("lapis-menu-search__glass")
        glass.setAttribute("aria-hidden", "true")
        field.add(glass)
        inputTag.id = inputId
        inputTag.setAttribute("type", "text")
        inputTag.setAttribute("role", "combobox")
        inputTag.setAttribute("autocomplete", "off")
        inputTag.setAttribute("autocapitalize", "off")
        inputTag.setAttribute("spellcheck", "false")
        inputTag.setAttribute("enterkeyhint", "go")
        inputTag.setAttribute("aria-autocomplete", "list")
        inputTag.setAttribute("aria-controls", "$idPrefix-list")
        inputTag.setAttribute("aria-expanded", listAlwaysOpen.toString())
        inputTag.setAttribute("placeholder", gettext("Suchen …"))
        if (ariaShortcut != null) inputTag.setAttribute("aria-keyshortcuts", ariaShortcut)
        inputTag.onEvent {
            input = { refresh(announce = true) }
            // the sidebar overlay lives and dies with the focus (the mouse never takes it: see the options' mousedown)
            focus = { openList() }
            blur = { closeList() }
            keydown = { e -> onKey(e) }
        }
        field.add(inputTag)
        if (shortcutHint != null) {
            val hint = Tag(TAG.SPAN, shortcutHint, className = "lapis-menu-search__hint")
            hint.setAttribute("aria-hidden", "true")
            field.add(hint)
        }
        root.add(field)

        live.setAttribute("role", "status")
        live.setAttribute("aria-live", "polite")
        root.add(live)

        if (listAlwaysOpen) {
            root.add(list.view)
        } else {
            list.view.addCssClass("lapis-menu-search__overlay")
            list.view.hide()
            root.add(list.view)
        }
    }

    val isListOpen: Boolean get() = listAlwaysOpen || listOpen

    /** The current text of the input. */
    var query: String
        get() = (inputTag.getElement() as? HTMLInputElement)?.value.orEmpty()
        set(value) {
            (inputTag.getElement() as? HTMLInputElement)?.value = value
        }

    fun focus() {
        (inputTag.getElement() as? HTMLInputElement)?.focus()
    }

    fun blur() {
        (inputTag.getElement() as? HTMLInputElement)?.blur()
    }

    /** Shows the overlay list (sidebar) and draws it for the current text. */
    fun openList() {
        if (listAlwaysOpen) return
        listOpen = true
        list.view.show()
        inputTag.setAttribute("aria-expanded", "true")
        refresh(announce = false)
    }

    fun closeList() {
        if (listAlwaysOpen) return
        listOpen = false
        list.view.hide()
        inputTag.setAttribute("aria-expanded", "false")
        inputTag.removeAttribute("aria-activedescendant")
        cancelAnnounce()
    }

    /** Empties the input and draws the empty-query list again. */
    fun clear() {
        query = ""
        refresh(announce = false)
    }

    /** Draws the list for the current text. [announce] also schedules the live-region text (debounced). */
    fun refresh(announce: Boolean) {
        if (!isListOpen && !announce) return
        if (!listAlwaysOpen && !listOpen) {
            // typing after Escape: the list comes back
            listOpen = true
            list.view.show()
            inputTag.setAttribute("aria-expanded", "true")
        }
        val status = list.render(query)
        updateActiveDescendant()
        if (announce) scheduleAnnounce(status) else cancelAnnounce()
    }

    fun dispose() {
        cancelAnnounce()
    }

    private fun onKey(e: org.w3c.dom.events.KeyboardEvent) {
        when (e.key) {
            "ArrowDown" -> {
                e.preventDefault()
                if (!listAlwaysOpen && !listOpen) openList() else list.moveSelection(1)
                updateActiveDescendant()
            }
            "ArrowUp" -> {
                e.preventDefault()
                if (!listAlwaysOpen && !listOpen) openList() else list.moveSelection(-1)
                updateActiveDescendant()
            }
            "Enter" -> {
                // not while an input method is composing a character
                if (!e.isComposing) {
                    e.preventDefault()
                    list.chooseSelected()
                }
            }
            "Escape" -> {
                e.preventDefault()
                onEscape()
            }
        }
    }

    private fun updateActiveDescendant() {
        val id = list.activeDescendantId
        if (id == null) inputTag.removeAttribute("aria-activedescendant") else inputTag.setAttribute("aria-activedescendant", id)
    }

    private fun scheduleAnnounce(text: String) {
        cancelAnnounce()
        announceTimer =
            window.setTimeout({
                announceTimer = null
                live.content = text
            }, MENU_SEARCH_ANNOUNCE_DELAY_MS)
    }

    private fun cancelAnnounce() {
        announceTimer?.let { window.clearTimeout(it) }
        announceTimer = null
    }
}
