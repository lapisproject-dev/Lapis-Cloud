package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- the first stop of the check-in flow: pick
 * WHICH upcoming event to open the door for. Deliberately minimal (title, start time, one button) --
 * this screen exists only to hand off an `eventId` to [renderEventCheckInScreen]; all the actual
 * door-scanning UI lives there.
 *
 * **Welle V1.4.3.4 "Raumverwaltung" addendum.** There is no dedicated event-create/edit admin
 * screen in this client (only this selection screen and the door-scanning `EventCheckInScreen`) --
 * see `IEventRoomService` KDoc "Scope". Room ASSIGNMENT (which room, if any, an event uses) is
 * therefore pragmatically surfaced right here, on each event's row: the current room name (or
 * "Kein Raum zugeordnet") plus a dropdown of ACTIVE rooms that calls `IEventService.updateEvent`
 * with the full [EventDto] mapped back into an [EventInput] (the server itself re-validates/
 * re-checks the room assignment for a collision -- see `EventRoomCollisionGuard`).
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

    fun refresh() {
        listPanel.removeAll()
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
            // Fix (review MINOR): fetch ALL rooms (`includeInactive = true`), not just ACTIVE ones --
            // `listRooms(includeInactive = false)` already returns only ACTIVE rooms server-side (see
            // `EventRoomStore.listRooms`), so the previous `.filter { it.status == ACTIVE }` here was
            // redundant AND meant an event's own currently-assigned room could never be resolved once
            // it was deactivated (dropped entirely from the list this screen builds its options from),
            // making the assignment dropdown show "Kein Raum" for a room that is actually still
            // assigned -- misleadingly implying the event had lost its room, when only the room's own
            // status had changed. [renderEventRoomAssignmentRow] below adds the event's own room back
            // into its options if it is not already ACTIVE.
            val allRooms = guarded { rpcService<IEventRoomService>().listRooms(includeInactive = true) }.orEmpty()
            page.rows.forEach { event ->
                val row =
                    listPanel.vPanel(spacing = 4) {
                        addCssClasses("border rounded p-2")
                    }
                val headerRow = row.hPanel(spacing = 12) { addCssClasses("align-items-center justify-content-between") }
                headerRow.div {
                    div(event.title) { addCssClasses("fw-bold") }
                    div("${event.startsAt}") { addCssClasses("text-muted small") }
                }
                headerRow.button(tr("Check-in öffnen"), style = ButtonStyle.PRIMARY) {
                    onClick { navigateTo("${Routes.EVENT_CHECKIN}/${event.id}") }
                }
                renderEventRoomAssignmentRow(row, event, allRooms, ::refresh)
            }
        }
    }
    refresh()
}

/**
 * See [renderEventCheckInSelectionScreen] KDoc "Raumverwaltung addendum" for why this lives here.
 *
 * [allRooms] is the FULL room list (ACTIVE + INACTIVE) -- the dropdown's own selectable options
 * are still restricted to ACTIVE rooms (an INACTIVE room may never be picked as a NEW assignment,
 * `EventRoomCollisionGuard` would reject it anyway), but if [event] is CURRENTLY assigned to a room
 * that has since been deactivated, that room is added back into the options (labeled "(inaktiv)")
 * so the dropdown shows the true current assignment instead of falling back to "Kein Raum" (review
 * MINOR fix -- see [renderEventCheckInSelectionScreen]'s own call-site comment).
 */
private fun renderEventRoomAssignmentRow(
    panel: SimplePanel,
    event: EventDto,
    allRooms: List<EventRoomDto>,
    onChanged: () -> Unit,
) {
    val assignmentRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    assignmentRow.div(gettext("Raum: %1", event.roomName ?: tr("Kein Raum zugeordnet"))) {
        addCssClasses("text-muted small flex-grow-1")
    }
    val activeRooms = allRooms.filter { it.status == EventRoomStatus.ACTIVE }
    val currentlyAssignedInactiveRoom = allRooms.firstOrNull { it.id == event.roomId && it.status != EventRoomStatus.ACTIVE }
    val options =
        listOf("" to tr("Kein Raum")) +
            activeRooms.map { it.id to it.name } +
            listOfNotNull(currentlyAssignedInactiveRoom?.let { it.id to gettext("%1 (inaktiv)", it.name) })
    val roomSelect = assignmentRow.select(options = options, value = event.roomId ?: "")
    roomSelect.subscribe {
        val newRoomId = roomSelect.value?.takeIf { it.isNotBlank() }
        if (newRoomId == event.roomId) return@subscribe
        AppScope.launch {
            val updated =
                guarded {
                    rpcService<IEventService>().updateEvent(
                        id = event.id,
                        input =
                            EventInput(
                                title = event.title,
                                description = event.description,
                                locationText = event.locationText,
                                onlineUrl = event.onlineUrl,
                                startsAt = event.startsAt,
                                endsAt = event.endsAt,
                                capacity = event.capacity,
                                feeAmount = event.feeAmount,
                                feeCurrency = event.feeCurrency,
                                visibility = event.visibility,
                                registrationClosesAt = event.registrationClosesAt,
                                roomId = newRoomId,
                            ),
                    )
                }
            if (updated != null) {
                notifySuccess(tr("Raumzuordnung wurde aktualisiert."))
                onChanged()
            } else {
                // Fix (review MINOR): on a rejected update (e.g. `ConflictException` from a room
                // collision), the `<select>` used to stay on the just-picked, REJECTED value instead
                // of reverting to the event's real, unchanged room -- `guarded()`'s error toast fired,
                // but the dropdown kept silently showing a room assignment that was never actually
                // saved. Revert it explicitly rather than relying on a full `onChanged()`-triggered
                // re-render (which only happens on success).
                roomSelect.value = event.roomId ?: ""
            }
        }
    }
}
