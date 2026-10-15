package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.i18n.gettext
import network.lapis.cloud.client.livekit.TimerDisplay
import network.lapis.cloud.client.livekit.TimerPhase
import network.lapis.cloud.client.livekit.formatMmSs
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import kotlin.math.roundToInt

/**
 * V1.9.133 -- what the mini bar and the floating window say about the conference timer while the call view is not on screen: the digits
 * only (no room title, no names). [text] is the `MM:SS` of the remaining time; the booleans carry the phase, so the words never depend on colour.
 */
internal data class DockTimer(
    val text: String,
    val final: Boolean,
    val expired: Boolean,
    val paused: Boolean,
    /** The share of the progress bar that is still full (0..1). */
    val fraction: Double,
)

/** The dock's view of [display]; `null` when no timer is set. */
internal fun dockTimerOf(display: TimerDisplay): DockTimer? =
    if (!display.visible) {
        null
    } else {
        DockTimer(
            text = formatMmSs(display.remainingMs),
            final = display.phase == TimerPhase.FINAL,
            expired = display.phase == TimerPhase.EXPIRED,
            paused = display.status == ConferenceTimerStatus.PAUSED && display.phase != TimerPhase.EXPIRED,
            fraction = display.fraction,
        )
    }

/**
 * The timer chip of the mini bar / the floating window: "Timer 02:15" and a 2 px bar below it. In the last 10 s "⚠" (decorative,
 * `aria-hidden`) and the words "Gleich vorbei"; expired: "Zeit abgelaufen"; paused: "Pausiert". Built ONCE, hidden while no timer is set, and
 * only written to where a value changed.
 */
internal class ConferenceTimerDockChip(
    parent: Container,
) {
    val root: Div = Div(className = "lapis-dock-timer")
    private val text = Span(className = "lapis-dock-timer-text")
    private val warn = Span("⚠", className = "lapis-dock-timer-warn")
    private val words = Span(className = "lapis-dock-timer-words")
    private val bar = Div(className = "lapis-dock-timer-bar")
    private val fill = Div(className = "lapis-dock-timer-bar-fill")
    private var shown: DockTimer? = null
    private var shownPercent = -1

    init {
        warn.setAttribute("aria-hidden", "true")
        bar.setAttribute("aria-hidden", "true")
        bar.add(fill)
        root.add(text)
        root.add(warn)
        root.add(words)
        root.add(bar)
        root.hide()
        parent.add(root)
    }

    /** The text of the chip, for tests and the accessible name of the bar. */
    val chipText: String get() = listOfNotNull(text.content, words.content.takeIf { !it.isNullOrEmpty() }).joinToString(" ")

    fun render(timer: DockTimer?) {
        if (timer == shown) return
        shown = timer
        if (timer == null) {
            root.hide()
            return
        }
        root.show()
        text.content = if (timer.expired) gettext("Zeit abgelaufen") else gettext("Timer %1", timer.text)
        words.content =
            when {
                timer.final -> gettext("Gleich vorbei")
                timer.paused -> gettext("Pausiert")
                else -> ""
            }
        if (timer.final) warn.show() else warn.hide()
        if (timer.final) root.addCssClass("is-final") else root.removeCssClass("is-final")
        if (timer.expired) root.addCssClass("is-expired") else root.removeCssClass("is-expired")
        val percent = (timer.fraction * 100.0).roundToInt().coerceIn(0, 100)
        if (percent != shownPercent) {
            shownPercent = percent
            fill.setStyle("width", "$percent%")
        }
    }
}
