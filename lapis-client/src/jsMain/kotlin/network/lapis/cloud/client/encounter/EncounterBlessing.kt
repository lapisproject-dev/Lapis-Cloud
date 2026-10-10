package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.span
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.addWithLifecycle
import org.w3c.dom.Element

/** How long the cross stays in the picture after a blessing, in milliseconds (quiet and short; a further packet restarts the window). */
internal const val ENCOUNTER_BLESSING_DISPLAY_MS = 7_000

/** Length of the fade-out in milliseconds; matches the CSS transition (the element is hidden after it). */
internal const val ENCOUNTER_BLESSING_FADE_MS = 250

/** How long the fixed sentence stays in the polite region before it is cleared, so that a LATER blessing is announced again. */
internal const val ENCOUNTER_BLESSING_ANNOUNCE_CLEAR_MS = 1_000

/** What a blessing packet does to the display. */
internal enum class EncounterBlessingOutcome {
    /** The display was off: show it and announce the fixed sentence once. */
    SHOW,

    /** The display was on: only restart the window. Nothing is announced and never a second display opens. */
    EXTEND,
}

/**
 * V1.9.95 -- the pure timer logic of the blessing display: a new packet while the display is on restarts the [displayMs] window and never
 * opens a second display; only the first packet of a window is announced. Holds one number (the end of the window): no counter, no history.
 */
internal class EncounterBlessingTimer(
    private val now: () -> Double,
    private val displayMs: Int = ENCOUNTER_BLESSING_DISPLAY_MS,
) {
    private var until = Double.NEGATIVE_INFINITY

    fun onPacket(): EncounterBlessingOutcome {
        val t = now()
        val wasVisible = t < until
        until = t + displayMs
        return if (wasVisible) EncounterBlessingOutcome.EXTEND else EncounterBlessingOutcome.SHOW
    }

    fun isVisibleAt(t: Double): Boolean = t < until
}

/** The timer seam of the display (a `jsTest` hands over a controllable one); `schedule` returns the function that cancels the timer. */
internal fun interface EncounterBlessingScheduler {
    fun schedule(
        ms: Int,
        block: () -> Unit,
    ): () -> Unit
}

internal val browserBlessingScheduler =
    EncounterBlessingScheduler { ms, block ->
        val handle = window.setTimeout({ block() }, ms)
        ({ window.clearTimeout(handle) })
    }

private const val SVG_NS = "http://www.w3.org/2000/svg"

/**
 * V1.9.95 -- the quiet cross over the pulpit area: an own latin cross (outline, drawn with the DOM API, never parsed from text), the
 * word of [EncounterTerms.blessingWord] under it. No accent colour, no scaling, no sound; shown for [ENCOUNTER_BLESSING_DISPLAY_MS] and
 * visible to EVERYBODY in the room (the congregation and the stewards too). The visual part is `aria-hidden`; a persistent polite region
 * ([live], created by the room BEFORE the first blessing -- a region that appears together with its text is not announced) receives the
 * one fixed sentence when the display opens, and is cleared again a moment later.
 *
 * Holds no name, no time of day and no counter; the only state is "is the display on" and the two timers, which [dispose] cancels.
 */
internal class EncounterBlessingDisplay(
    host: Container,
    private val terms: EncounterTerms,
    private val live: Div,
    private val timer: EncounterBlessingTimer,
    private val scheduler: EncounterBlessingScheduler = browserBlessingScheduler,
    private val reducedMotion: () -> Boolean = { window.matchMedia("(prefers-reduced-motion: reduce)").matches },
) {
    private val root: Div =
        host.addWithLifecycle(
            Div(className = "lapis-encounter-blessing"),
            onInsert = { vnode -> (vnode.elm as? Element)?.let { ensureCross(it) } },
        )
    private var cancelHide: (() -> Unit)? = null
    private var cancelFade: (() -> Unit)? = null
    private var cancelClear: (() -> Unit)? = null
    private var disposed = false

    init {
        root.setAttribute("aria-hidden", "true")
        root.setAttribute("hidden", "")
        // the word under the cross is a text node of its own (never markup)
        root.span(terms.blessingWord(), className = "lapis-encounter-blessing-word")
    }

    /** `true` while the display is in the picture (test aid). */
    val visible: Boolean get() = root.getAttribute("hidden") == null

    /** A blessing packet arrived (accepted by the session): show, or extend the window. */
    fun onBlessing() {
        if (disposed) return
        val outcome = timer.onPacket()
        cancelHide?.invoke()
        cancelFade?.invoke()
        cancelFade = null
        if (outcome == EncounterBlessingOutcome.SHOW) {
            root.removeAttribute("hidden")
            root.addCssClass(CLASS_ON)
            announce()
        }
        cancelHide = scheduler.schedule(ENCOUNTER_BLESSING_DISPLAY_MS) { fadeOut() }
    }

    private fun announce() {
        val sentence = terms.blessingAnnouncement()
        live.content = sentence
        cancelClear?.invoke()
        cancelClear = scheduler.schedule(ENCOUNTER_BLESSING_ANNOUNCE_CLEAR_MS) { live.content = "" }
    }

    private fun fadeOut() {
        root.removeCssClass(CLASS_ON)
        if (reducedMotion()) {
            root.setAttribute("hidden", "")
        } else {
            cancelFade = scheduler.schedule(ENCOUNTER_BLESSING_FADE_MS) { root.setAttribute("hidden", "") }
        }
    }

    /** Ends both timers; the room calls this from its own dispose. */
    fun dispose() {
        disposed = true
        cancelHide?.invoke()
        cancelFade?.invoke()
        cancelClear?.invoke()
        cancelHide = null
        cancelFade = null
        cancelClear = null
    }

    /**
     * The cross: latin, outline, 24 x 32. Built with `createElementNS` (never markup from text); inserted once (safe if the widget is
     * re-attached), and `aria-hidden`/not focusable like every decorative drawing.
     */
    private fun ensureCross(element: Element) {
        if (element.querySelector("svg") != null) return
        val svg = document.createElementNS(SVG_NS, "svg")
        svg.setAttribute("viewBox", "0 0 24 32")
        svg.setAttribute("aria-hidden", "true")
        svg.setAttribute("focusable", "false")
        val path = document.createElementNS(SVG_NS, "path")
        path.setAttribute("d", "M9 1H15V9H22V15H15V31H9V15H2V9H9Z")
        path.setAttribute("fill", "none")
        path.setAttribute("stroke", "currentColor")
        path.setAttribute("stroke-width", "1.75")
        path.setAttribute("stroke-linejoin", "round")
        svg.appendChild(path)
        element.insertBefore(svg, element.firstChild)
    }

    private companion object {
        const val CLASS_ON = "lapis-encounter-blessing--visible"
    }
}
