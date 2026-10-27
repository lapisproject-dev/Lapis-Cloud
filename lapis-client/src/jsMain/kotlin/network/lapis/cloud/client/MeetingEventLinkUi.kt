package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventManageScope
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.LinkedEventRef
import network.lapis.cloud.shared.domain.LinkedMeetingRef
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingEventDifference
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IGovernanceService

/*
 * V1.9.227 "Sitzung in der Videokonferenz" -- the Sitzung <-> Veranstaltung link in the UI. Two small blocks: one on the Sitzung page (announce,
 * show, dissolve, align) and one on the event page (link a Sitzung, show, dissolve). The server decides everything; the buttons only say what
 * the server would refuse anyway (a cancelled / held / past Sitzung cannot be announced, an in-person Sitzung needs a place).
 * No new route, no table; the dissolve action is an icon-only button with its name as tooltip and aria-label (R58) and no dialog, because
 * nothing is lost -- both records stay.
 */

/** Why the Sitzung cannot be announced right now, or `null`. A static, translated sentence -- the server re-checks all of it (including the past). */
internal fun meetingAnnounceBlockedReason(meeting: MeetingDto): String? =
    when {
        meeting.status == MeetingStatus.CANCELLED -> tr("Eine abgesagte Sitzung kann nicht angekündigt werden.")
        meeting.status == MeetingStatus.HELD -> tr("Eine bereits durchgeführte Sitzung kann nicht mehr angekündigt werden.")
        meeting.format == MeetingFormat.IN_PERSON && meeting.location.isNullOrBlank() ->
            tr("Für die Ankündigung einer Präsenzsitzung fehlt der Ort.")
        else -> null
    }

/** The block on the Sitzung page. Renders nothing when there is no link and the caller may not manage it. */
internal fun renderMeetingEventLink(
    panel: SimplePanel,
    meeting: MeetingDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val linked = meeting.linkedEvent
    if (linked == null && !canManage) return
    val box = panel.vPanel(spacing = 4) { addCssClass("lapis-meeting-event-link") }
    if (linked == null) {
        renderAnnounce(box, meeting, onChanged)
        return
    }
    val line = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    line.span(tr("Angekündigt als:"))
    if (linked.canOpen) {
        line.recordLink(linked.title, Routes.EVENT_MANAGEMENT, linked.eventId)
    } else {
        line.span(sanitizeUntrustedI18nText(linked.title))
    }
    line.eventStatusBadge(linked.status)
    if (canManage) unlinkButton(line, meeting.id, linked.eventId, onChanged)
    if (linked.status == EventStatus.DRAFT) {
        val row = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        row.div(tr("Entwurf, noch nicht veröffentlicht.")) { addCssClasses("text-muted small") }
        if (linked.canPublish) publishButton(row, linked.eventId, onChanged)
    }
    val difference = linked.difference
    if (difference != null) renderDifference(box, difference, canManage, linked, meeting, onChanged)
}

/** V1.9.229: whoever the server lets publish (BOARD/ADMIN, or an officer of this Sitzung's committee) gets the button; the dialog names the audience. */
private fun publishButton(
    row: Container,
    eventId: String,
    onChanged: () -> Unit,
) {
    val button = row.actionButton(ActionIcon.SEND, tr("Veröffentlichen"), style = ButtonStyle.SUCCESS)
    button.onClick {
        button.disabled = true
        AppScope.launch {
            val dto = guarded { rpcService<IEventService>().getEvent(eventId) }
            button.disabled = false
            if (dto != null) publishEventDialog(dto, onChanged)
        }
    }
}

private fun renderAnnounce(
    box: SimplePanel,
    meeting: MeetingDto,
    onChanged: () -> Unit,
) {
    val reason = meetingAnnounceBlockedReason(meeting)
    val button = box.actionButton(ActionIcon.SEND, tr("Als Veranstaltung ankündigen"), style = ButtonStyle.OUTLINEPRIMARY)
    if (reason != null) {
        button.disabled = true
        box.div(reason) { addCssClasses("text-muted small") }
        return
    }
    button.onClick {
        button.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IGovernanceService>().announceMeetingAsEvent(meeting.id) }
            if (result == null) {
                button.disabled = false
            } else {
                // Everybody who may announce may also open and manage the event now (V1.9.229).
                navigateTo(detailRoute(Routes.EVENT_MANAGEMENT, result.eventId))
            }
        }
    }
}

private fun unlinkButton(
    row: Container,
    meetingId: String,
    eventId: String,
    onChanged: () -> Unit,
) {
    val button = row.actionButton(ActionIcon.REMOVE, tr("Verknüpfung lösen"), style = ButtonStyle.OUTLINESECONDARY, iconOnly = true)
    button.onClick {
        button.disabled = true
        AppScope.launch {
            val ok = guarded { rpcService<IGovernanceService>().unlinkMeetingEvent(meetingId, eventId) } != null
            if (ok) {
                notifyInfo(tr("Verknüpfung gelöst. Sitzung und Veranstaltung bleiben erhalten."))
                onChanged()
            } else {
                button.disabled = false
            }
        }
    }
}

/** The "they disagree" hint of the Sitzung page; the align button sits in the hint when the server says the event can follow the Sitzung. */
private fun renderDifference(
    box: SimplePanel,
    difference: MeetingEventDifference,
    canManage: Boolean,
    linked: LinkedEventRef,
    meeting: MeetingDto,
    onChanged: () -> Unit,
) {
    if (!difference.startsAtDiffers && !difference.locationDiffers && !difference.onlineDiffers) return
    val hint = box.vPanel(spacing = 2) { addCssClass("lapis-link-difference") }
    if (difference.startsAtDiffers) {
        hint.div(
            gettext("Sitzung: %1 · Veranstaltung: %2", formatDateTime(meeting.scheduledAt), formatDateTime(linked.startsAt)),
        )
    }
    if (difference.locationDiffers) {
        val noPlace = tr("kein Ort")
        val meetingPlace = sanitizeUntrustedI18nText(meeting.location?.takeIf { it.isNotBlank() } ?: noPlace)
        val eventPlace = sanitizeUntrustedI18nText(linked.locationText?.takeIf { it.isNotBlank() } ?: noPlace)
        hint.div(gettext("Ort der Sitzung: %1 · Ort der Veranstaltung: %2", meetingPlace, eventPlace))
    }
    if (difference.onlineDiffers) {
        hint.div(tr("Online-Teilnahme ist unterschiedlich eingestellt."))
    }
    if (canManage && difference.alignable) {
        val button = hint.actionButton(ActionIcon.REFRESH, tr("Termin der Veranstaltung an die Sitzung angleichen"))
        button.onClick {
            button.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IGovernanceService>().alignEventToMeeting(meeting.id, linked.eventId) }
                if (result != null) {
                    notifySuccess(tr("Die Veranstaltung wurde an die Sitzung angeglichen."))
                    onChanged()
                } else {
                    button.disabled = false
                }
            }
        }
    }
}

/** The block on the event page (BOARD/ADMIN). */
internal fun renderEventMeetingLink(
    panel: SimplePanel,
    event: EventDto,
    reload: () -> Unit,
) {
    val linked = event.linkedMeeting
    if (linked == null) {
        // Linking an existing event is the board's (V1.9.229): the button is for manageScope FULL only.
        if (event.manageScope != EventManageScope.FULL) return
        if (event.status == EventStatus.CANCELLED || (event.seriesId != null && !event.seriesDetached)) return
        val box = panel.vPanel(spacing = 4) { addCssClass("lapis-meeting-event-link") }
        val button = box.actionButton(ActionIcon.LINK, tr("Sitzung verknüpfen"))
        button.onClick { linkMeetingDialog(event.id, reload) }
        return
    }
    val box = panel.vPanel(spacing = 4) { addCssClass("lapis-meeting-event-link") }
    val line = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val label = gettext("Gehört zur Sitzung: %1, %2", linked.committeeName, formatDateTime(linked.scheduledAt))
    val meetingId = linked.meetingId
    if (meetingId != null) {
        line.recordLink(label, Routes.MEETINGS, meetingId)
    } else {
        line.span(sanitizeUntrustedI18nText(label))
    }
    if (linked.canManageLink && meetingId != null) unlinkButton(line, meetingId, event.id, reload)
    renderEventSideDifference(box, linked, event)
}

private fun renderEventSideDifference(
    box: SimplePanel,
    linked: LinkedMeetingRef,
    event: EventDto,
) {
    val difference = linked.difference ?: return
    if (!difference.startsAtDiffers && !difference.locationDiffers && !difference.onlineDiffers) return
    val hint = box.vPanel(spacing = 2) { addCssClass("lapis-link-difference") }
    if (difference.startsAtDiffers) {
        hint.div(gettext("Sitzung: %1 · Veranstaltung: %2", formatDateTime(linked.scheduledAt), formatDateTime(event.startsAt)))
    }
    if (difference.locationDiffers) hint.div(tr("Der Ort der Sitzung weicht vom Ort der Veranstaltung ab."))
    if (difference.onlineDiffers) hint.div(tr("Online-Teilnahme ist unterschiedlich eingestellt."))
    if (difference.alignable && linked.canManageLink) {
        hint.div(tr("Angleichen kann die Sitzungsleitung auf der Seite der Sitzung.")) { addCssClasses("text-muted small") }
    }
}

private fun linkMeetingDialog(
    eventId: String,
    reload: () -> Unit,
) {
    val modal = Modal(caption = tr("Sitzung verknüpfen"))
    val body = modal.vPanel(spacing = 6)
    val loading = body.div(tr("Sitzungen werden geladen…")) { addCssClasses("text-muted small") }
    val form = body.lapisForm()
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val confirm: Button = newActionButton(ActionIcon.LINK, tr("Verknüpfen"), ButtonStyle.PRIMARY).apply { disabled = true }
    modal.addButton(cancel)
    modal.addButton(confirm)
    modal.show()
    AppScope.launch {
        val meetings = guarded { rpcService<IGovernanceService>().listLinkableMeetings(eventId) }
        if (meetings == null) {
            loading.hide()
            body.dataErrorState(
                onRetry = {
                    modal.hide()
                    linkMeetingDialog(eventId, reload)
                },
            )
            return@launch
        }
        if (meetings.isEmpty()) {
            loading.content = tr("Es gibt keine Sitzung, die verknüpft werden kann.")
            return@launch
        }
        loading.hide()
        val options =
            meetings.map { meeting ->
                meeting.id to
                    sanitizeUntrustedI18nText(
                        gettext("%1, %2 – %3", meeting.committeeName, formatDateTime(meeting.scheduledAt), meeting.title),
                    )
            }
        val picker = form.selectField(label = tr("Sitzung"), options = options, value = options.first().first, required = true)
        confirm.disabled = false
        val once = ConfirmOnce()
        confirm.onClick {
            val chosen = picker.value.takeIf { it.isNotBlank() } ?: return@onClick
            once.run(confirm) {
                AppScope.launch {
                    val result = guarded { rpcService<IGovernanceService>().linkMeetingToEvent(chosen, eventId) }
                    modal.hide()
                    if (result != null) {
                        notifySuccess(tr("Sitzung verknüpft."))
                        reload()
                    }
                }
            }
        }
    }
}
