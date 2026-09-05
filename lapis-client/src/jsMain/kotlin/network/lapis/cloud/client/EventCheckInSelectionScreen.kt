package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.IEventService

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- the first stop of the check-in flow: pick
 * WHICH upcoming event to open the door for. Deliberately minimal (title, start time, one button) --
 * this screen exists only to hand off an `eventId` to [renderEventCheckInScreen]; all the actual
 * door-scanning UI lives there.
 */
fun renderEventCheckInSelectionScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 700.px
            marginTop = 24.px
        }
    root.h1(tr("Veranstaltungs-Check-in"))
    root.div(
        tr("Veranstaltung auswählen, um Tickets am Einlass zu prüfen."),
    ) { addCssClasses("text-muted small") }

    val listPanel = root.vPanel(spacing = 8)

    AppScope.launch {
        val page =
            guarded {
                // Fix (review MINOR): `status` used to be left at the query default (null), which
                // `EventService.listEvents` only narrows to PUBLISHED for MEMBER callers -- a
                // BOARD/ADMIN caller (the only role that can even reach this screen) got EVERY
                // status back, including DRAFT and CANCELLED events, indistinguishable in this
                // list from the one actually running. Explicit here, not left to that role-based
                // default, so this check-in door list can never show anything but a real,
                // currently-open-for-attendance event.
                rpcService<IEventService>().listEvents(EventQuery(status = EventStatus.PUBLISHED, includePast = false, limit = 100))
            } ?: return@launch
        if (page.rows.isEmpty()) {
            listPanel.p(tr("Keine bevorstehenden Veranstaltungen."))
            return@launch
        }
        page.rows.forEach { event ->
            listPanel.hPanel(spacing = 12) {
                addCssClasses("align-items-center justify-content-between border rounded p-2")
                div {
                    div(event.title) { addCssClasses("fw-bold") }
                    div("${event.startsAt}") { addCssClasses("text-muted small") }
                }
                button(tr("Check-in öffnen"), style = ButtonStyle.PRIMARY) {
                    onClick { navigateTo("${Routes.EVENT_CHECKIN}/${event.id}") }
                }
            }
        }
    }
}
