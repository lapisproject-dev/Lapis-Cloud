package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import network.lapis.cloud.client.currentFullscreenElement
import network.lapis.cloud.client.exitBrowserFullscreen
import network.lapis.cloud.client.fullscreenApiAvailable
import network.lapis.cloud.client.requestFullscreenOn
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** CSS class of the fallback when the browser has no (usable) Fullscreen API: the room fills the viewport by CSS alone. */
internal const val ENCOUNTER_PSEUDO_FULLSCREEN_CLASS = "is-pseudo-fullscreen"

/**
 * V1.9.67 -- full screen of the encounter room (stage mode). With the Fullscreen API ([nativeSupported]) the room element goes to the
 * real full screen; without it (Safari on iPhone, many in-app WebViews) the element gets [ENCOUNTER_PSEUDO_FULLSCREEN_CLASS] and fills the
 * viewport by CSS (`position: fixed; inset: 0`), Escape leaves it. A failing `requestFullscreen()` falls back the same way.
 *
 * Every listener is registered in `init` and removed by [dispose] (the room calls it when the person leaves), nothing is stored.
 * [onChange] reports the new state (the owner mirrors it into `aria-pressed` and the label).
 */
internal class EncounterFullscreen(
    private val element: () -> HTMLElement?,
    private val onChange: (Boolean) -> Unit,
    // The shared FullscreenApi (standard + webkit-prefixed); an explicit `fullscreenEnabled == false` (policy, WebView) still means no.
    private val nativeSupported: () -> Boolean = { fullscreenApiAvailable() && document.asDynamic().fullscreenEnabled != false },
) {
    var isActive: Boolean = false
        private set

    private var pseudo = false
    private var disposed = false

    private val onFullscreenChange: (Event) -> Unit = { syncFromDocument() }
    private val onKeyDown: (Event) -> Unit = { event ->
        if (pseudo && (event as? KeyboardEvent)?.key == "Escape") leavePseudo()
    }

    init {
        document.addEventListener("fullscreenchange", onFullscreenChange)
        document.addEventListener("webkitfullscreenchange", onFullscreenChange)
        document.addEventListener("keydown", onKeyDown)
    }

    /** Enters the full screen, or leaves it when it is active. */
    fun toggle() {
        if (disposed) return
        if (isActive) leave() else enter()
    }

    private fun enter() {
        val target = element() ?: return
        if (nativeSupported()) {
            try {
                val result: dynamic = requestFullscreenOn(target)
                // A rejected promise (permission policy, not a user gesture) means the real full screen is not available here: use the fallback.
                result?.catch { _: dynamic -> enterPseudo(target) }
                return
            } catch (e: Throwable) {
                // falls through to the fallback
            }
        }
        enterPseudo(target)
    }

    private fun enterPseudo(target: HTMLElement) {
        if (disposed) return
        pseudo = true
        target.classList.add(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS)
        setActive(true)
    }

    private fun leavePseudo() {
        element()?.classList?.remove(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS)
        pseudo = false
        setActive(false)
    }

    /** Leaves the full screen when it is active (a dialog outside the room element must not stay hidden behind it). */
    fun leaveIfActive() {
        if (isActive) leave()
    }

    private fun leave() {
        if (pseudo) {
            leavePseudo()
        } else {
            try {
                if (currentFullscreenElement() != null) exitBrowserFullscreen()
            } catch (e: Throwable) {
                // nothing to leave
            }
        }
    }

    private fun syncFromDocument() {
        if (pseudo || disposed) return
        val shown = currentFullscreenElement()
        setActive(shown != null && shown == element())
    }

    private fun setActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        onChange(active)
    }

    /** Removes both listeners and leaves the full screen (without reporting: the owner is going away). */
    fun dispose() {
        if (disposed) return
        disposed = true
        document.removeEventListener("fullscreenchange", onFullscreenChange)
        document.removeEventListener("webkitfullscreenchange", onFullscreenChange)
        document.removeEventListener("keydown", onKeyDown)
        if (pseudo) {
            element()?.classList?.remove(ENCOUNTER_PSEUDO_FULLSCREEN_CLASS)
            pseudo = false
        } else if (isActive) {
            try {
                if (currentFullscreenElement() != null) exitBrowserFullscreen()
            } catch (e: Throwable) {
                // already left
            }
        }
        isActive = false
    }
}
