package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventDto

/** How often the button re-reads the clock (the state changes only at the minute scale; 30 s keeps the label honest). */
internal const val EVENT_ONLINE_BUTTON_TICK_MS = 30_000

private var eventOnlineButtonCounter = 0

/**
 * Welle V1.9.113 -- the one button with which a member takes part online in an event. It is in the SAME place in all three states
 * ([EventOnlineButtonState]): "Teilnehmen ab 17:45 Uhr" (kept focusable, `aria-disabled`, the reason linked through
 * `aria-describedby`), "Teilnehmen"/"Raum betreten", and "Beendet". The state is derived from the event's window and the organization-zone
 * clock ([now]) and re-evaluated every [EVENT_ONLINE_BUTTON_TICK_MS]; a change is announced through a permanently mounted
 * `role="status"` region. A click in the not-yet and ended states does nothing (the server decides anyway).
 *
 * Returns `null` (and adds nothing) for an event without a Lapis online way of taking part.
 */
internal fun Container.eventOnlineJoinButton(
    event: EventDto,
    now: () -> LocalDateTime,
    // Test seam: how often the clock is re-read (production: [EVENT_ONLINE_BUTTON_TICK_MS]).
    tickMs: Int = EVENT_ONLINE_BUTTON_TICK_MS,
    onJoin: (Button) -> Unit,
): Button? {
    if (!event.onlineKind.isLapisHosted()) return null
    val initial = eventOnlineButtonState(event, now()) ?: return null
    val id = "lapis-event-online-${eventOnlineButtonCounter++}"
    val reasonId = "$id-reason"
    val wrapper = Div()
    var timerId: Int? = null
    var current: EventOnlineButtonState = initial
    val button =
        Button(
            eventOnlineButtonLabel(initial, event.onlineKind),
            style =
                if (initial ==
                    EventOnlineButtonState.Open
                ) {
                    ButtonStyle.PRIMARY
                } else {
                    ButtonStyle.OUTLINESECONDARY
                },
        )
    button.id = id
    val reason = Div(eventOnlineButtonReason(initial) ?: "") { addCssClasses("text-muted small") }
    reason.id = reasonId
    val live = Div("") { addCssClasses("visually-hidden") }
    live.setAttribute("role", "status")
    live.setAttribute("aria-live", "polite")

    fun apply(state: EventOnlineButtonState) {
        val open = state == EventOnlineButtonState.Open
        button.text = eventOnlineButtonLabel(state, event.onlineKind)
        button.style = if (open) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
        // aria-disabled, never `disabled`: the button stays a tab stop so the reason can be read.
        button.setAttribute("aria-disabled", if (open) "false" else "true")
        button.setAttribute("aria-describedby", reasonId)
        val explanation = eventOnlineButtonReason(state)
        reason.content = explanation ?: ""
        reason.visible = explanation != null
    }
    apply(initial)
    button.onClick {
        if (current == EventOnlineButtonState.Open) onJoin(button)
    }
    wrapper.add(button)
    wrapper.add(reason)
    wrapper.add(live)
    // The hooks are registered BEFORE the wrapper is added (see addWithLifecycle): the timer starts when the button really is in the document
    // and stops when it leaves.
    addWithLifecycle(
        wrapper,
        onInsert = {
            if (timerId == null) {
                timerId =
                    window.setInterval({
                        val next = eventOnlineButtonState(event, now()) ?: return@setInterval
                        if (next != current) {
                            current = next
                            apply(next)
                            live.content = eventOnlineButtonLabel(next, event.onlineKind)
                        }
                    }, tickMs)
            }
        },
        onDestroy = {
            timerId?.let { window.clearInterval(it) }
            timerId = null
        },
    )
    return button
}
