package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.livekit.TimerDisplay
import network.lapis.cloud.client.livekit.TimerPhase
import network.lapis.cloud.client.livekit.formatMmSs
import network.lapis.cloud.client.livekit.timerAccessibleName
import network.lapis.cloud.client.livekit.timerSpokenDuration
import network.lapis.cloud.client.livekit.timerWholeSeconds
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/*
 * V1.9.133 -- the display of the conference timer above the video area: the digits (24 px, bold, tabular), a progress ring, and -- never
 * by colour alone -- the words "Gleich vorbei" (last 10 s, thicker outline, a 1 Hz pulse unless the person asked for reduced motion),
 * "Zeit abgelaufen" (static) and "Pausiert". Built ONCE as raw DOM and only filled afterwards (like the raised-hand badge): the 4 Hz
 * countdown must not go through a vnode patch. The element is NOT a video overlay and nothing here ever touches a media track -- it is a
 * page element, so it is not in a recording or a stream (see `ConferenceTimerRecordingTripwireTest`).
 *
 * A moderator's pill is a `<button>` ("Timer steuern, noch ...") that opens the control dialog; for everybody else it is a `<div>`.
 * The words for a screen reader come from the timer's OWN polite live region (a sibling of the timer element, never inside it).
 */

private const val SVG_NS = "http://www.w3.org/2000/svg"
private const val RING_RADIUS = 10.0
private const val RING_CIRCUMFERENCE = 2.0 * 3.141592653589793 * RING_RADIUS
private const val LABEL_REFRESH_SECONDS = 5L
private const val LIVE_CLEAR_MS = 6_000

/** `true` if the person asked the system for less motion. */
internal fun systemPrefersReducedMotion(): Boolean =
    runCatching {
        window.matchMedia("(prefers-reduced-motion: reduce)").matches
    }.getOrDefault(false)

internal class ConferenceTimerPill(
    private val isModerator: Boolean,
    private val onActivate: () -> Unit,
    private val reducedMotion: () -> Boolean = ::systemPrefersReducedMotion,
) {
    /** The wrapper to mount: the timer element, the reset note and the live region. */
    val element: HTMLElement = div("lapis-conference-timer-wrap")

    /** The timer element itself (`<button>` for a moderator, `<div>` otherwise). */
    val timerElement: HTMLElement

    private val digits: HTMLElement
    private val stateText: HTMLElement
    private val ring: Element
    private val note: HTMLElement
    private val liveRegion: HTMLElement
    private var lastLabelKey: String? = null
    private var lastLabelSeconds = Long.MIN_VALUE
    private var liveClear: Int? = null

    init {
        timerElement =
            if (isModerator) {
                (document.createElement("button") as HTMLElement).also {
                    it.setAttribute("type", "button")
                    it.addEventListener("click", { onActivate() })
                }
            } else {
                document.createElement("div") as HTMLElement
            }
        timerElement.className = "lapis-conference-timer"
        ring = buildRing()
        timerElement.appendChild(ring)
        digits = span("lapis-conference-timer-digits")
        // a moderator's button keeps its own role (its children are presentational); everybody else's div is the timer
        if (isModerator) digits.setAttribute("role", "timer")
        timerElement.appendChild(digits)
        stateText = span("lapis-conference-timer-state")
        timerElement.appendChild(stateText)
        if (!isModerator) timerElement.setAttribute("role", "timer")
        note = div("lapis-conference-timer-note")
        note.textContent = gettext("Timer wurde zurückgesetzt")
        liveRegion = div("visually-hidden lapis-conference-timer-live")
        liveRegion.setAttribute("aria-live", "polite")
        liveRegion.setAttribute("aria-atomic", "true")
        element.appendChild(timerElement)
        element.appendChild(note)
        element.appendChild(liveRegion)
        render(TimerDisplay.OFF)
    }

    private fun buildRing(): Element {
        val svg = document.createElementNS(SVG_NS, "svg")
        svg.setAttribute("class", "lapis-conference-timer-ring")
        svg.setAttribute("viewBox", "0 0 24 24")
        svg.setAttribute("width", "20")
        svg.setAttribute("height", "20")
        svg.setAttribute("aria-hidden", "true")
        svg.setAttribute("focusable", "false")
        val track = document.createElementNS(SVG_NS, "circle")
        track.setAttribute("class", "lapis-conference-timer-ring-track")
        val progress = document.createElementNS(SVG_NS, "circle")
        progress.setAttribute("class", "lapis-conference-timer-ring-progress")
        for (circle in listOf(track, progress)) {
            circle.setAttribute("cx", "12")
            circle.setAttribute("cy", "12")
            circle.setAttribute("r", RING_RADIUS.toString())
            circle.setAttribute("fill", "none")
            circle.setAttribute("stroke-width", "3")
        }
        progress.setAttribute("stroke-dasharray", RING_CIRCUMFERENCE.toString())
        progress.setAttribute("stroke-dashoffset", "0")
        progress.setAttribute("transform", "rotate(-90 12 12)")
        svg.appendChild(track)
        svg.appendChild(progress)
        return svg
    }

    /** Fills the element from [display]; touches the DOM only where something changed. */
    fun render(display: TimerDisplay) {
        element.style.display = if (display.visible || display.resetNotice) "" else "none"
        note.style.display = if (display.resetNotice) "" else "none"
        timerElement.style.display = if (display.visible) "" else "none"
        if (!display.visible) {
            lastLabelKey = null
            return
        }
        val expired = display.phase == TimerPhase.EXPIRED
        val final = display.phase == TimerPhase.FINAL
        val paused = display.status == ConferenceTimerStatus.PAUSED
        setText(digits, formatMmSs(display.remainingMs))
        timerElement.classList.toggle("is-final", final)
        timerElement.classList.toggle("is-expired", expired)
        timerElement.classList.toggle("is-paused", paused && !expired)
        // the pulse is decoration: not at all when the person asked for reduced motion (theme.css repeats the rule for the media query)
        timerElement.classList.toggle("is-pulsing", final && !reducedMotion())
        setText(
            stateText,
            when {
                expired -> gettext("Zeit abgelaufen")
                final -> gettext("Gleich vorbei")
                paused -> gettext("Pausiert")
                else -> ""
            },
        )
        stateText.style.display = if (stateText.textContent.isNullOrEmpty()) "none" else ""
        ring
            .querySelector(".lapis-conference-timer-ring-progress")
            ?.setAttribute("stroke-dashoffset", (RING_CIRCUMFERENCE * (1.0 - display.fraction)).toString())
        refreshLabel(display, expired, paused)
    }

    private fun refreshLabel(
        display: TimerDisplay,
        expired: Boolean,
        paused: Boolean,
    ) {
        val key = "${display.phase}|${display.status}"
        val seconds = timerWholeSeconds(display.remainingMs)
        // the name changes at most every 5 s while a state stays the same: a focused button must not chatter once a second
        if (key == lastLabelKey && kotlin.math.abs(seconds - lastLabelSeconds) < LABEL_REFRESH_SECONDS && !expired) return
        lastLabelKey = key
        lastLabelSeconds = seconds
        val spoken = timerSpokenDuration(display.remainingMs)
        val label =
            when {
                expired -> if (isModerator) gettext("Timer steuern, Zeit abgelaufen") else gettext("Timer: Zeit abgelaufen")
                paused ->
                    if (isModerator) gettext("Timer steuern, pausiert, noch %1", spoken) else gettext("Timer pausiert: noch %1", spoken)
                isModerator -> gettext("Timer steuern, noch %1", spoken)
                else -> timerAccessibleName(display.remainingMs)
            }
        timerElement.setAttribute("aria-label", label)
    }

    /** Says [text] once, politely, in the timer's own live region (cleared again after a few seconds). */
    fun announce(text: String) {
        liveClear?.let { window.clearTimeout(it) }
        liveRegion.textContent = text
        liveClear = window.setTimeout({ liveRegion.textContent = "" }, LIVE_CLEAR_MS)
    }

    /** The text of the live region right now (read by tests). */
    val liveText: String get() = liveRegion.textContent.orEmpty()

    private fun setText(
        target: HTMLElement,
        text: String,
    ) {
        if (target.textContent != text) target.textContent = text
    }

    private fun div(cssClass: String): HTMLElement = (document.createElement("div") as HTMLElement).also { it.className = cssClass }

    private fun span(cssClass: String): HTMLElement = (document.createElement("span") as HTMLElement).also { it.className = cssClass }
}
