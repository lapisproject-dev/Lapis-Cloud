package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.FocusEvent

/**
 * V1.9.242 -- one section of the Sitzung panel that is never rebuilt under a hand. [requestRender] draws now when nobody is using a control of the
 * section, and otherwise remembers that a drawing is waiting ([pending]); the owning [SectionGuards] draws it as soon as the focus left the
 * section and no pointer is down inside it. [hostElement] is resolved lazily because a KVision component has no DOM element before its first paint.
 *
 * "Using a control" means: the focus is inside the section (an open `<select>` or the stand-in search field included), or a pointer went down
 * inside it and has not come up yet (the click that follows is still on its way to the node it started on).
 */
internal class SectionGuard(
    private val hostElement: () -> HTMLElement?,
    private val render: () -> Unit,
) {
    /** A drawing is waiting for the section to become idle. */
    var pending: Boolean = false
        private set

    internal var pointerDown: Boolean = false

    internal fun contains(node: Node?): Boolean = node != null && hostElement()?.contains(node) == true

    fun busy(ignoreFocus: Boolean = false): Boolean {
        val host = hostElement() ?: return false
        if (pointerDown) return true
        if (ignoreFocus) return false
        val active = document.activeElement
        return active != null && active !== document.body && host.contains(active)
    }

    /** Draws now, or remembers to draw later when a control of the section is in use. */
    fun requestRender() {
        if (busy()) {
            pending = true
        } else {
            pending = false
            render()
        }
    }

    /** Draws now whatever the section is doing: a refused change must show the value the server kept, even under the focused control. */
    fun renderNow() {
        pending = false
        render()
    }

    internal fun flushIfIdle(ignoreFocus: Boolean = false) {
        if (pending && !busy(ignoreFocus)) {
            pending = false
            render()
        }
    }

    /** Forgets a waiting drawing (the caller makes sure the next state draws the section again). */
    fun drop() {
        pending = false
    }
}

/**
 * The listeners of all guards of one panel, on the document (capture): a section's DOM node may be replaced at any time, listeners on it would be
 * lost with it. Flushing happens on `focusout` (focus moved to something outside the section) and after `pointerup`/`pointercancel` -- on the next
 * tick, because the `click` that belongs to the pointer comes right after `pointerup` and must still find its node. A lost `pointerup` (the pointer
 * released outside the window) clears itself after [POINTER_SAFETY_MS], so a guard can never block drawing for good.
 */
internal class SectionGuards {
    private val guards = mutableListOf<SectionGuard>()
    private var safetyTimer: Int? = null
    private var attached = false
    private var disposed = false

    private val onPointerDown: (Event) -> Unit = { event ->
        val target = event.target as? Node
        guards.forEach { it.pointerDown = it.contains(target) }
        if (guards.any { it.pointerDown }) {
            safetyTimer?.let { window.clearTimeout(it) }
            safetyTimer =
                window.setTimeout({
                    safetyTimer = null
                    guards.forEach { it.pointerDown = false }
                    flushAll()
                }, POINTER_SAFETY_MS)
        }
    }

    private val onPointerEnd: (Event) -> Unit = {
        if (guards.any { it.pointerDown }) {
            guards.forEach { it.pointerDown = false }
            safetyTimer?.let { window.clearTimeout(it) }
            safetyTimer = null
            window.setTimeout({ flushAll() }, 0)
        }
    }

    private val onFocusOut: (Event) -> Unit = { event ->
        val next = (event as? FocusEvent)?.relatedTarget as? Node
        if (next != null) {
            // the focus went somewhere else for sure: every section it left is idle (unless a pointer is still down in it)
            guards.forEach { if (!it.contains(next)) it.flushIfIdle(ignoreFocus = true) }
        } else {
            // focus lost without a successor (window switch, click on bare page): decide on the next tick, when document.activeElement is settled
            window.setTimeout({ flushAll() }, 0)
        }
    }

    fun add(guard: SectionGuard): SectionGuard {
        guards += guard
        attach()
        return guard
    }

    private fun attach() {
        if (attached || disposed) return
        attached = true
        document.addEventListener("pointerdown", onPointerDown, true)
        document.addEventListener("pointerup", onPointerEnd, true)
        document.addEventListener("pointercancel", onPointerEnd, true)
        document.addEventListener("focusout", onFocusOut, true)
    }

    fun flushAll() {
        if (disposed) return
        guards.forEach { it.flushIfIdle() }
    }

    fun dropAll() {
        guards.forEach { it.drop() }
    }

    fun dispose() {
        disposed = true
        dropAll()
        safetyTimer?.let { window.clearTimeout(it) }
        safetyTimer = null
        if (attached) {
            document.removeEventListener("pointerdown", onPointerDown, true)
            document.removeEventListener("pointerup", onPointerEnd, true)
            document.removeEventListener("pointercancel", onPointerEnd, true)
            document.removeEventListener("focusout", onFocusOut, true)
            attached = false
        }
    }

    companion object {
        const val POINTER_SAFETY_MS = 3000
    }
}
