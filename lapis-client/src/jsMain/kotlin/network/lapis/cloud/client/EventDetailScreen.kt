package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventManageScope
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService

/*
 * V1.9.204 -- the detail page of one event (`/events/:id`; BOARD/ADMIN manage it fully, V1.9.229: an officer of the announced Sitzung's committee sees the limited view): the key facts with the status actions,
 * the edit form (visible at once, no longer folded behind a card), and the registrations. The server decides everything: the event, the
 * registrations and the messages come from it; a missing or foreign id shows one neutral sentence and never a server message.
 */

/** What the page needs to render: the event (`null` = not found / not readable) and the rooms for the edit form. */
internal class EventDetailData(
    val event: EventDto?,
    val rooms: List<EventRoomDto>,
)

fun renderEventDetailScreen(
    container: SimplePanel,
    id: String,
    navigate: (String) -> Unit = ::navigateTo,
) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    // V1.9.229: an officer comes from the Sitzung page, so the way back leads there; the holder is re-filled once the scope is known.
    val backHolder = root.vPanel(spacing = 0)
    backHolder.overviewBackButton(Routes.EVENT_MANAGEMENT, navigate = navigate)
    val header = root.pageHeader(tr("Veranstaltung"), subtitle = "")
    lateinit var section: DataSection
    section =
        root.dataSection<EventDetailData>(
            isEmpty = { false },
            load = { loadEventDetail(id) },
            onSettled = { data ->
                val event = data?.event
                if (event != null && event.manageScope != EventManageScope.NONE) header.setSubtitle(sanitizeUntrustedI18nText(event.title))
                val meetingId = event?.linkedMeeting?.meetingId
                if (event?.manageScope == EventManageScope.MEETING_OFFICER && meetingId != null) {
                    backHolder.removeAll()
                    backHolder.overviewBackButton(detailRoute(Routes.MEETINGS, meetingId), label = tr("Zur Sitzung"), navigate = navigate)
                }
            },
            render = { panel, data ->
                val event = data.event
                if (event == null) {
                    // One neutral sentence for every failure; the server's own message (it may echo the id) is never shown.
                    panel.p(tr("Veranstaltung nicht gefunden."))
                } else if (event.manageScope == EventManageScope.NONE) {
                    // V1.9.229: /events/:id is open to every signed-in member now, but only the management view lives here. A plain member
                    // (or a server that does not say) gets the member list of events instead -- never a management screen the server refuses.
                    panel.p(tr("Veranstaltung nicht gefunden."))
                    navigate(Routes.MY_EVENTS)
                } else {
                    renderEventDetail(panel, event, data.rooms) { section.reload() }
                }
            },
        )
    section.reload()
}

private suspend fun loadEventDetail(id: String): EventDetailData {
    val event =
        try {
            rpcService<IEventService>().getEvent(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return EventDetailData(event = null, rooms = emptyList())
    // The room list is a board function (an officer cannot change the room, and listRooms would be refused).
    val rooms =
        if (event.manageScope == EventManageScope.FULL) {
            guarded { rpcService<IEventRoomService>().listRooms(includeInactive = true) }.orEmpty()
        } else {
            emptyList()
        }
    return EventDetailData(event = event, rooms = rooms)
}

private fun renderEventDetail(
    panel: SimplePanel,
    event: EventDto,
    rooms: List<EventRoomDto>,
    reload: () -> Unit,
) {
    val officer = event.manageScope == EventManageScope.MEETING_OFFICER
    renderEventFacts(panel, event, reload)
    renderEventMeetingLink(panel, event, reload)

    if (event.status != EventStatus.CANCELLED) {
        panel.h2(tr("Bearbeiten")) { addCssClass("h5") }
        val editHolder = panel.vPanel(spacing = 6)
        // "Abbrechen" throws the typed changes away by loading the stored event again.
        if (officer) {
            renderEventOfficerEditForm(editHolder, event, onSaved = reload, onCancel = reload)
        } else {
            renderEventEditForm(editHolder, event, rooms, onSaved = reload, onCancel = reload)
        }
    }

    // An officer sees neither the registrations nor the guests: that is the board's view.
    if (!officer) renderEventRegistrationsSection(panel, event.id)
}

private fun renderEventFacts(
    panel: SimplePanel,
    event: EventDto,
    reload: () -> Unit,
) {
    val officer = event.manageScope == EventManageScope.MEETING_OFFICER
    val box = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val statusRow = box.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.eventStatusBadge(event.status)
    statusRow.eventVisibilityBadge(event.visibility)
    if (event.seriesId != null) {
        statusRow.div("↻") {
            addCssClasses(if (event.seriesDetached) "text-muted text-decoration-line-through" else "text-muted")
            title = event.seriesRuleSummary ?: tr("Teil einer Serie")
        }
    }
    box.weekdayDateTimeSpan(event.startsAt).apply { addCssClasses("text-muted small") }
    if (event.roomName != null) box.div(gettext("Raum: %1", event.roomName)) { addCssClasses("text-muted small") }
    if (!officer) {
        box.div(gettext("Belegung: %1 belegt, %2 auf Warteliste", event.occupiedSeats.toString(), event.waitlistCount.toString())) {
            addCssClasses("text-muted small")
        }
    }
    val publicUrl = event.publicUrl
    if (publicUrl != null) {
        val urlRow = box.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        urlRow.div(publicUrl) { addCssClasses("text-muted small text-truncate") }
        val copyLabel = tr("Kopieren")
        val copiedLabel = tr("Kopiert")
        lateinit var copyButton: Button
        copyButton =
            urlRow.button(copyLabel, style = ButtonStyle.OUTLINESECONDARY) {
                onClick {
                    copyEventUrlToClipboard(publicUrl) {
                        copyButton.text = copiedLabel
                        AppScope.launch {
                            delay(2000)
                            copyButton.text = copyLabel
                        }
                    }
                }
            }
    }
    // How the event can be attended online (a word); the moderator's view of the people who joined without an account.
    if (event.onlineKind != EventOnlineKind.NONE) {
        box.div(gettext("Online-Teilnahme: %1", eventOnlineKindLabel(event.onlineKind))) { addCssClasses("text-muted small") }
    }
    if (event.captureAllowed) box.div(tr("Aufzeichnung und Übertragung: erlaubt")) { addCssClasses("text-muted small") }
    if (event.status == EventStatus.PUBLISHED && !officer) renderEventGuestsPanel(box, event)
    if (officer && event.visibility == EventVisibility.MEMBERS_ONLY) {
        box.div(tr("Sichtbar nur für Mitglieder. Öffentlich machen kann der Vorstand.")) { addCssClasses("text-muted small") }
    }

    val actionRow = box.hPanel(spacing = 8)
    // An officer publishes a members-only draft; a public one stays with the board (the server refuses it too).
    if (event.status == EventStatus.DRAFT && (!officer || event.visibility == EventVisibility.MEMBERS_ONLY)) {
        actionRow.actionButton(ActionIcon.SEND, tr("Veröffentlichen"), style = ButtonStyle.SUCCESS).onClick {
            publishEventDialog(event, reload)
        }
    }
    // An officer cancels a free members-only event; a paid or public one is the board's decision (the server refuses it too).
    val officerMayCancel = displayAmountValue(event.feeAmount) == 0.0 && event.visibility == EventVisibility.MEMBERS_ONLY
    if (event.status != EventStatus.CANCELLED) {
        if (!officer || officerMayCancel) {
            // Cancelling dissolves the Sitzung link, so an officer has nothing left to look at here: back to the Sitzung.
            val meetingId = event.linkedMeeting?.meetingId
            val afterCancel: () -> Unit =
                if (officer && meetingId != null) ({ navigateTo(detailRoute(Routes.MEETINGS, meetingId)) }) else reload
            actionRow.actionButton(ActionIcon.REVOKE, tr("Absagen"), style = ButtonStyle.OUTLINEDANGER).onClick {
                cancelEventDialog(event, afterCancel)
            }
        } else {
            box.div(tr("Absagen kann bei einer kostenpflichtigen oder öffentlichen Veranstaltung nur der Vorstand.")) {
                addCssClasses("text-muted small")
            }
        }
    }
    if (!officer && event.waitlistCount > 0 && event.status == EventStatus.PUBLISHED) {
        actionRow.actionButton(ActionIcon.REFRESH, tr("Warteliste nachrücken"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            sweepEventWaitlist(event, reload)
        }
    }
}
