package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.livekit.formatMmSs

/**
 * V1.9.138 -- the band that shows the temporary floor to EVERYBODY in the room. It is built once and only filled afterwards (the
 * countdown changes its text every second; nothing is rebuilt, nothing re-focused).
 *
 * - The viewer who HAS the floor sees "Sie haben das Wort · noch 1:58" with two buttons: "Mikrofon einschalten" (the microphone is never
 *   switched on by itself: the browser's permission prompt belongs to a click) and "Wort zurückgeben".
 * - Everybody else sees "Gerade spricht jemand aus der Gemeinde" (or "... Versammlung") with the time left -- NO name, no seat.
 * - In the last ten seconds the band carries `is-final` (a thicker border and "Gleich zu Ende"); there is no animation, and under
 *   `prefers-reduced-motion` no transition either (theme.css).
 * - The countdown is `role="timer"` with `aria-live="off"`: a screen reader is told only the few fixed sentences of the room's polite
 *   region, never the seconds.
 */
internal class EncounterFloorBanner(
    host: Container,
    private val terms: EncounterTerms,
    private val onMicrophone: () -> Unit,
    private val onHandBack: () -> Unit,
) {
    val root: Div = host.div(className = "lapis-encounter-band lapis-encounter-floor d-flex flex-wrap align-items-center gap-2")
    private val countdown: Span = root.span(className = "lapis-encounter-floor-text")
    private val finalNote: Span = root.span(terms.floorFinalNote(), className = "lapis-encounter-floor-final fw-semibold")
    private val microphoneButton: Button =
        root.actionButton(ActionIcon.MICROPHONE, terms.floorMicrophoneOnLabel(), style = ButtonStyle.OUTLINEPRIMARY, small = true)
    private val microphoneState: Span = root.span(terms.floorMicrophoneOnState(), className = "lapis-encounter-floor-mic text-muted small")
    private val handBackButton: Button =
        root.actionButton(ActionIcon.UNDO, terms.floorHandBackLabel(), style = ButtonStyle.OUTLINESECONDARY, small = true)
    private var lastText = ""
    private var lastFinal = false

    init {
        countdown.setAttribute("role", "timer")
        countdown.setAttribute("aria-live", "off")
        microphoneButton.onClick { onMicrophone() }
        handBackButton.onClick { onHandBack() }
        root.setAttribute("hidden", "")
        finalNote.hide()
        microphoneButton.hide()
        microphoneState.hide()
        handBackButton.hide()
    }

    /** `true` while the band is in the picture (test aid). */
    val visible: Boolean get() = root.getAttribute("hidden") == null

    /** The text of the countdown as shown (test aid). */
    val text: String get() = lastText

    /** `true` while the last-seconds emphasis is on (test aid). */
    val emphasised: Boolean get() = lastFinal

    fun render(view: EncounterFloorView) {
        if (!view.active) {
            if (visible) root.setAttribute("hidden", "")
            lastText = ""
            lastFinal = false
            return
        }
        val time = formatMmSs(view.remainingSeconds * MS_PER_SECOND)
        val text = if (view.self) terms.floorSelfBanner(time) else terms.floorCommunityBanner(time)
        if (text != lastText) {
            countdown.content = text
            lastText = text
        }
        if (view.final != lastFinal) {
            lastFinal = view.final
            if (view.final) {
                root.addCssClass(FINAL_CLASS)
                finalNote.show()
            } else {
                root.removeCssClass(FINAL_CLASS)
                finalNote.hide()
            }
        }
        if (view.self) {
            microphoneButton.show()
            handBackButton.show()
            // the button is the way IN; once the microphone is on, the band says so instead
            if (view.microphoneOn) {
                microphoneButton.hide()
                microphoneState.show()
            } else {
                microphoneButton.show()
                microphoneState.hide()
            }
        } else {
            microphoneButton.hide()
            handBackButton.hide()
            microphoneState.hide()
        }
        if (!visible) root.removeAttribute("hidden")
    }

    private companion object {
        const val FINAL_CLASS = "is-final"
        const val MS_PER_SECOND = 1000L
    }
}
