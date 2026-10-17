package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.EventCaptureLiveDto

/**
 * V1.9.156 -- the line that tells EVERYBODY in an event room that the room is being recorded and / or streamed. The honest side of the capture: as
 * long as something runs, the line is in the picture of the member call and of the call of a person without an account.
 *
 * - Quiet: a plain line with `role="status"` and `aria-live="polite"`, no close button, no blinking, no animation at all (so nothing needs switching off
 *   for `prefers-reduced-motion`). The state is never colour alone: the dot AND the words.
 * - The live region ([root]) is mounted ONCE and stays in the document; only its content changes (a screen reader announces a text change in an existing
 *   live region, not a freshly inserted one). With nothing running the region is empty and takes no room.
 * - Never "on suspicion": [render] shows exactly what the server said. `null` (a state that could not be learned) leaves the last one in place.
 * - Only the server's packets reach [render] (the session drops a packet that carries a sending participant): a guest may publish data and could
 *   otherwise forge "nothing is recorded".
 */
internal class EventCaptureBanner(
    host: Container,
) {
    val root: Div =
        host.div(className = "lapis-event-capture") {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    private val line: Div = root.div(className = "lapis-event-capture-line d-flex flex-wrap align-items-center gap-2")
    private val dot: Span = line.span("●", className = "lapis-event-capture-dot")
    private val text: Span = line.span("", className = "lapis-event-capture-text fw-semibold")

    private var last: EventCaptureLiveDto? = null

    init {
        dot.setAttribute("aria-hidden", "true")
        line.hide()
    }

    /** `true` while the line is in the picture (test aid). */
    val visible: Boolean get() = line.visible

    /** The words shown right now; empty while nothing runs (test aid). */
    val shownText: String get() = if (line.visible) (text.content ?: "") else ""

    /** Shows exactly [live]; `null` leaves the last state in place (nothing was learned). Idempotent. */
    fun render(live: EventCaptureLiveDto?) {
        if (live == null || live == last) return
        last = live
        val words = eventCaptureBannerText(live)
        val shown = words ?: ""
        text.content = shown
        if (words == null) line.hide() else line.show()
    }
}

/** The words of the banner for [live], or `null` while nothing is recorded or streamed. */
internal fun eventCaptureBannerText(live: EventCaptureLiveDto): String? =
    when {
        live.recording && live.streaming -> gettext("Diese Besprechung wird aufgezeichnet und live übertragen.")
        live.recording -> gettext("Diese Besprechung wird aufgezeichnet.")
        live.streaming -> gettext("Diese Besprechung wird live übertragen.")
        else -> null
    }
