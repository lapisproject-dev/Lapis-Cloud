package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.untrustedDiv
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * V1.9.167 -- the layer that carries the grid map of the encounter room. The map is no longer a block of the stage: it opens ABOVE the room on
 * request and ends above the dock (`bottom` in theme.css), so the control bar stays operable while it is up.
 *
 * - **A dialog, modal for the keyboard only**: `role="dialog"`, `aria-modal="true"`, named by its heading. Tab and Shift+Tab wrap inside the
 *   panel (a focus trap), the background is NOT made inert -- the dock, the "Mehr" sheet and the chat stay reachable with the mouse and
 *   touch, and Escape closes the layer wherever the focus is (unless the "Mehr" sheet is open: that closes first).
 * - **Closing is never an action on the map**: Escape, the close button and a click on the scrim only hide the layer. Nobody leaves the
 *   map by closing it ("Karte verlassen" is its own control inside the view).
 * - **Privacy**: one short line is always shown ("Ihre Position sehen nur anwesende Mitglieder. Nichts wird gespeichert."); the longer notice
 *   of the room (untrusted text of the operator) sits behind the native disclosure "Mehr" and only for somebody who can walk.
 * - Hidden by the `hidden` attribute (plus a CSS guard), so it is out of the accessibility tree and the tab order while closed.
 * - Nothing here is stored in the browser, and no name is written anywhere.
 */
internal class EncounterMapOverlay(
    parent: Container,
    notice: String?,
    private val canWalk: Boolean,
    /** Puts the focus into the layer after it opened (the board, or "Auf die Karte"); the room knows the view. */
    private val focusInitial: () -> Unit,
    /** Called after every opening. */
    private val onOpened: () -> Unit,
    /** Called after every closing; [returnFocus] says whether the room should give the focus back to the opener. */
    private val onClosed: (returnFocus: Boolean) -> Unit,
    /** `true` while the "Mehr" sheet is open: Escape then belongs to the sheet. */
    private val escapeBlocked: () -> Boolean = { false },
) {
    val root: Div = parent.div(className = "lapis-encounter-map-overlay")
    private val scrim: Div = root.div(className = "lapis-encounter-map-overlay__scrim")

    /** The dialog itself; `id` = [OVERLAY_ID] (the dock button points at it with `aria-controls`). */
    val panel: Div = root.div(className = "lapis-encounter-map-overlay__panel")
    private val header: Div = panel.div(className = "lapis-encounter-map-overlay__header")
    private val heading: Tag = header.tag(TAG.H2, content = tr("Karte"), className = "h6 lapis-encounter-map-overlay__title")
    private val closeButton: Tag = header.tag(TAG.BUTTON, className = "btn-close lapis-encounter-map-overlay__close")
    private val privacyLine: Tag =
        panel.tag(
            TAG.P,
            content = tr("Ihre Position sehen nur anwesende Mitglieder. Nichts wird gespeichert."),
            className = "small lapis-encounter-map__privacy-line",
        )

    /** The longer notice of the room behind a native disclosure; `null` unless there is a notice and the viewer can walk. */
    val disclosure: Tag? =
        notice?.takeIf { canWalk }?.let { text ->
            val details = Tag(TAG.DETAILS, className = "small lapis-encounter-map__privacy-more")
            details.add(Tag(TAG.SUMMARY, content = tr("Mehr")))
            details.untrustedDiv(text, className = "lapis-encounter-map__notice")
            panel.add(details)
            details
        }

    /** Where the [EncounterMapView] builds its board (the view brings no heading of its own). */
    val host: Div = panel.div(className = "lapis-encounter-map-overlay__host")

    var isOpen: Boolean = false
        private set

    private var documentKeys: ((Event) -> Unit)? = null
    private var disposed = false

    init {
        root.setAttribute("hidden", "")
        scrim.setAttribute("aria-hidden", "true")
        panel.setAttribute("id", OVERLAY_ID)
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-modal", "true")
        heading.setAttribute("id", TITLE_ID)
        panel.setAttribute("aria-labelledby", TITLE_ID)
        closeButton.setAttribute("type", "button")
        closeButton.setAttribute("aria-label", gettext("Karte schließen"))
        closeButton.onClick { close() }
        scrim.onClick { close() }
        panel.onEvent { keydown = { event -> onPanelKey(event) } }
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    /** Shows the layer and moves the focus into it. Idempotent. */
    fun open() {
        if (disposed || isOpen) return
        isOpen = true
        root.removeAttribute("hidden")
        val onKey: (Event) -> Unit = { event -> onDocumentKey(event) }
        document.addEventListener("keydown", onKey)
        documentKeys = onKey
        onOpened()
        // KVision patches the DOM asynchronously: the layer is focusable only once its `hidden` attribute is gone from the real element.
        var attempts = 0

        fun attempt() {
            val element = root.getElement()
            if (!isOpen || disposed) return
            if (element != null && !element.hasAttribute("hidden")) {
                focusInitial()
            } else if (attempts++ < FOCUS_RETRIES) {
                window.setTimeout({ attempt() }, FOCUS_RETRY_MS)
            }
        }
        attempt()
    }

    /** Hides the layer. Never leaves the map. [returnFocus] = give the focus back to the opener (the room decides where it goes). */
    fun close(returnFocus: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        root.setAttribute("hidden", "")
        removeDocumentListener()
        onClosed(returnFocus)
    }

    /** The visible, enabled controls of the panel in tab order (for the focus trap and for tests). */
    fun focusables(): List<HTMLElement> {
        val element = panel.getElement() ?: return emptyList()
        val nodes = element.querySelectorAll("button, summary, [tabindex=\"0\"]")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? HTMLElement }
            .filter { it.getClientRects().length > 0 && !it.hasAttribute("disabled") && it.getAttribute("aria-disabled") != "true" }
    }

    fun dispose() {
        disposed = true
        isOpen = false
        removeDocumentListener()
        root.hide()
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun removeDocumentListener() {
        documentKeys?.let { document.removeEventListener("keydown", it) }
        documentKeys = null
    }

    private fun onPanelKey(event: KeyboardEvent) {
        when (event.key) {
            "Escape" -> onEscape(event)
            "Tab" -> trapTab(event)
        }
    }

    private fun onDocumentKey(event: Event) {
        val key = event as? KeyboardEvent ?: return
        if (key.key == "Escape") onEscape(key)
    }

    private fun onEscape(event: KeyboardEvent) {
        if (!isOpen || event.defaultPrevented || escapeBlocked()) return
        event.preventDefault()
        close()
    }

    private fun trapTab(event: KeyboardEvent) {
        if (event.ctrlKey || event.altKey || event.metaKey) return
        val items = focusables()
        if (items.isEmpty()) return
        val active = document.activeElement
        val first = items.first()
        val last = items.last()
        if (event.shiftKey && (active == first || active == panel.getElement())) {
            event.preventDefault()
            last.focus()
        } else if (!event.shiftKey && active == last) {
            event.preventDefault()
            first.focus()
        }
    }

    companion object {
        const val OVERLAY_ID = "lapis-encounter-map-overlay"
        private const val TITLE_ID = "lapis-encounter-map-overlay-title"
        private const val FOCUS_RETRIES = 30
        private const val FOCUS_RETRY_MS = 16
    }
}
