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
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService

/*
 * V1.9.204 -- the detail page of one event (`/events/:id`, BOARD/ADMIN like the whole management area): the key facts with the status actions,
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
    root.overviewBackButton(Routes.EVENT_MANAGEMENT, navigate = navigate)
    val header = root.pageHeader(tr("Veranstaltung"), subtitle = "")
    lateinit var section: DataSection
    section =
        root.dataSection<EventDetailData>(
            isEmpty = { false },
            load = { loadEventDetail(id) },
            onSettled = { data -> data?.event?.let { header.setSubtitle(sanitizeUntrustedI18nText(it.title)) } },
            render = { panel, data ->
                val event = data.event
                if (event == null) {
                    // One neutral sentence for every failure; the server's own message (it may echo the id) is never shown.
                    panel.p(tr("Veranstaltung nicht gefunden."))
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
    val rooms = guarded { rpcService<IEventRoomService>().listRooms(includeInactive = true) }.orEmpty()
    return EventDetailData(event = event, rooms = rooms)
}

private fun renderEventDetail(
    panel: SimplePanel,
    event: EventDto,
    rooms: List<EventRoomDto>,
    reload: () -> Unit,
) {
    renderEventFacts(panel, event, reload)

    if (event.status != EventStatus.CANCELLED) {
        panel.h2(tr("Bearbeiten")) { addCssClass("h5") }
        val editHolder = panel.vPanel(spacing = 6)
        // "Abbrechen" throws the typed changes away by loading the stored event again.
        renderEventEditForm(editHolder, event, rooms, onSaved = reload, onCancel = reload)
    }

    renderEventRegistrationsSection(panel, event.id)
}

private fun renderEventFacts(
    panel: SimplePanel,
    event: EventDto,
    reload: () -> Unit,
) {
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
    box.div(gettext("Belegung: %1 belegt, %2 auf Warteliste", event.occupiedSeats.toString(), event.waitlistCount.toString())) {
        addCssClasses("text-muted small")
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
    if (event.status == EventStatus.PUBLISHED) renderEventGuestsPanel(box, event)

    val actionRow = box.hPanel(spacing = 8)
    if (event.status == EventStatus.DRAFT) {
        actionRow.actionButton(ActionIcon.SEND, tr("Veröffentlichen"), style = ButtonStyle.SUCCESS).onClick {
            publishEventDialog(event, reload)
        }
    }
    if (event.status != EventStatus.CANCELLED) {
        actionRow.actionButton(ActionIcon.REVOKE, tr("Absagen"), style = ButtonStyle.OUTLINEDANGER).onClick {
            cancelEventDialog(event, reload)
        }
    }
    if (event.waitlistCount > 0 && event.status == EventStatus.PUBLISHED) {
        actionRow.actionButton(ActionIcon.REFRESH, tr("Warteliste nachrücken"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            sweepEventWaitlist(event, reload)
        }
    }
}
