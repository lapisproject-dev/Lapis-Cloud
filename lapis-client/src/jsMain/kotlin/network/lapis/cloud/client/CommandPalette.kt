package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.i18n.gettext
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * V1.9.203 -- the command palette: Ctrl+K (Cmd+K on an Apple device) opens a dialog with the same menu search as the sidebar field.
 *
 * Behaviour:
 *  - one `document` keydown listener in the bubble phase, installed ONCE ([install] is idempotent for the same host; a sidebar rebuild
 *    never touches it). Bubble phase on purpose: the mail editor handles Ctrl+K itself and calls `preventDefault()` on its element first,
 *    which [isMenuSearchShortcut] respects. The key rules are in that function;
 *  - while the focus is in the sidebar field the shortcut keeps the focus there (no jump into the palette);
 *  - a dialog (`role=dialog`, `aria-modal`) with a focus trap, Escape and a click on the backdrop close it, and the focus returns to the
 *    element that had it before (or to the document when that element is gone);
 *  - with nothing in the menu index (nobody signed in) the shortcut does nothing but still takes the key from the browser.
 *
 * The palette is hosted by a KVision container (the root); it is only mounted while open.
 */
object CommandPalette {
    private var host: Container? = null
    private var overlay: Div? = null
    private var box: MenuSearchBox? = null
    private var onNavigate: () -> Unit = {}
    private var returnFocusTo: HTMLElement? = null
    private var listenerInstalled = false
    private val shortcutListener: (Event) -> Unit = { onDocumentKeydown(it) }

    var isOpen: Boolean = false
        private set

    /**
     * Builds the palette into [host] and installs the global shortcut. Calling it again with the same host changes nothing; with another
     * host (a test mounted a new root) the old palette is removed first. [onNavigate] runs after a choice (it closes the off-canvas panel).
     */
    fun install(
        host: Container,
        onNavigate: () -> Unit = {},
    ) {
        if (this.host === host && overlay != null) {
            this.onNavigate = onNavigate
            return
        }
        removeWidgets()
        this.host = host
        this.onNavigate = onNavigate
        val newBox =
            MenuSearchBox(
                idPrefix = "lapis-command-palette",
                listAlwaysOpen = true,
                onChoose = ::choose,
                onEscape = ::close,
            )
        val dialog = Div(className = "lapis-command-palette__dialog")
        dialog.setAttribute("role", "dialog")
        dialog.setAttribute("aria-modal", "true")
        dialog.setAttribute("aria-label", gettext("Befehlspalette"))
        dialog.add(newBox.root)
        dialog.onEvent { keydown = { e -> if (e.key == "Tab") trapTab(e, dialog) } }
        val backdrop = Div(className = "lapis-command-palette")
        backdrop.add(dialog)
        backdrop.onEvent {
            mousedown = { e -> if (e.target === backdrop.getElement()) close() }
        }
        backdrop.hide()
        host.add(backdrop)
        overlay = backdrop
        box = newBox
        if (!listenerInstalled) {
            document.addEventListener("keydown", shortcutListener)
            listenerInstalled = true
        }
    }

    /** Opens the palette. A no-op when it is open already, not installed, or the menu is empty. */
    fun open() {
        val backdrop = overlay ?: return
        val searchBox = box ?: return
        if (isOpen || MenuIndex.entries().isEmpty()) return
        returnFocusTo = document.activeElement as? HTMLElement
        isOpen = true
        searchBox.clear()
        backdrop.show()
        focusSoon(searchBox, attempts = FOCUS_ATTEMPTS)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        box?.dispose()
        overlay?.hide()
        val target = returnFocusTo
        returnFocusTo = null
        // an element a sidebar rebuild has taken out of the document cannot take the focus back
        if (target != null && document.body?.contains(target) == true) target.focus()
    }

    /** Removes the palette and the global listener again (tests; the app installs it for its whole life). */
    internal fun uninstall() {
        close()
        removeWidgets()
        if (listenerInstalled) document.removeEventListener("keydown", shortcutListener)
        listenerInstalled = false
        host = null
    }

    private fun removeWidgets() {
        box?.dispose()
        overlay?.let { old -> host?.remove(old) }
        overlay = null
        box = null
        isOpen = false
        returnFocusTo = null
    }

    private fun choose(entry: MenuEntry) {
        close()
        goToMenuEntry(entry)
        onNavigate()
    }

    private fun onDocumentKeydown(event: Event) {
        val e = event as? KeyboardEvent ?: return
        val target = e.target as? HTMLElement
        val inTextEditor = target != null && (target.isContentEditable || target.tagName == "TEXTAREA")
        val handled =
            isMenuSearchShortcut(
                key = e.key,
                ctrl = e.ctrlKey,
                meta = e.metaKey,
                alt = e.altKey,
                shift = e.shiftKey,
                defaultPrevented = e.defaultPrevented,
                composing = e.isComposing,
                inTextEditor = inTextEditor,
            )
        if (!handled) return
        // always: the browser would move the focus to its address bar
        e.preventDefault()
        if (isOpen) {
            close()
        } else if (target?.id != MenuSearchField.INPUT_ID) {
            open()
        }
    }

    /** The shown dialog needs a moment to exist in the document (KVision mounts it on the next render), so the focus is retried briefly. */
    private fun focusSoon(
        searchBox: MenuSearchBox,
        attempts: Int,
    ) {
        window.setTimeout({
            if (!isOpen) return@setTimeout
            searchBox.focus()
            val focused = document.activeElement?.id == searchBox.inputId
            if (!focused && attempts > 1) focusSoon(searchBox, attempts - 1)
        }, 0)
    }

    /** Keeps Tab and Shift+Tab inside the dialog. */
    private fun trapTab(
        e: KeyboardEvent,
        dialog: Div,
    ) {
        val root = dialog.getElement() ?: return
        val nodes = root.querySelectorAll("input, button, a[href], [tabindex]:not([tabindex='-1'])")
        val focusable = (0 until nodes.length).mapNotNull { nodes.item(it) as? HTMLElement }
        if (focusable.isEmpty()) {
            e.preventDefault()
            return
        }
        val first = focusable.first()
        val last = focusable.last()
        val active = document.activeElement
        when {
            e.shiftKey && (active === first || !root.contains(active)) -> {
                e.preventDefault()
                last.focus()
            }
            !e.shiftKey && (active === last || !root.contains(active)) -> {
                e.preventDefault()
                first.focus()
            }
        }
    }

    private const val FOCUS_ATTEMPTS = 10
}
