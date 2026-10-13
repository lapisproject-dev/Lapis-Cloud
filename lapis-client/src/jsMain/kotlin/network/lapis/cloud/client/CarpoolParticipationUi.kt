package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.li
import io.kvision.html.span
import io.kvision.html.ul
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CarpoolMyParticipationDto
import network.lapis.cloud.shared.domain.CarpoolParticipantDto
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.rpc.ICarpoolService

/**
 * Welle V1.9.125 "Mitfahrten" -- the passenger side of the Mitfahrerzentrale: status badges, "Meine Mitfahrten", the driver's passenger
 * management (list, exact-name search, invitation, meeting note) and the one-click actions with their announcements.
 *
 * **No colour alone**: every state is a badge with an icon (decoration, `aria-hidden`) AND a text; nothing is danger-red -- a declined or
 * withdrawn ride is information, not an error. Names, places and notes are free text of other people: always through the `untrusted*` helpers.
 * The server never transmits its own error message (see [guarded]): a refusal shows the generic toast.
 *
 * [CarpoolUi.announce] writes into the screen's polite live region ("Mitfahrt bestätigt."), [CarpoolUi.onChanged] reloads the lists.
 */
internal class CarpoolUi(
    val announce: (String) -> Unit,
    val onChanged: () -> Unit,
)

/** What a badge shows: the [label], the Font Awesome [icon] classes (decoration) and the Bootstrap [color]. */
internal class CarpoolBadgeSpec(
    val label: String,
    val icon: String,
    val color: String,
)

/**
 * The badge of one participation. [viewerIsDriver] tells whose waiting it is: an OPEN row waits for the OTHER side of its initiator, so a
 * passenger's own request waits for "den Fahrer", a driver's invitation waits for "den Mitfahrer", and a row started by the other side waits
 * for YOU. Pure.
 */
internal fun carpoolBadgeSpec(
    status: CarpoolParticipationStatus,
    initiator: CarpoolParticipationInitiator,
    viewerIsDriver: Boolean,
): CarpoolBadgeSpec =
    when (status) {
        CarpoolParticipationStatus.CONFIRMED -> CarpoolBadgeSpec(gettext("Bestätigt"), "fas fa-check", "success")
        CarpoolParticipationStatus.DECLINED -> CarpoolBadgeSpec(gettext("Abgelehnt"), "fas fa-xmark", "secondary")
        CarpoolParticipationStatus.WITHDRAWN -> CarpoolBadgeSpec(gettext("Zurückgezogen"), "fas fa-rotate-left", "secondary")
        CarpoolParticipationStatus.OPEN -> {
            val startedByViewer = (initiator == CarpoolParticipationInitiator.DRIVER) == viewerIsDriver
            when {
                !startedByViewer -> CarpoolBadgeSpec(gettext("Wartet auf Ihre Bestätigung"), "fas fa-clock", "warning")
                viewerIsDriver ->
                    CarpoolBadgeSpec(
                        gettext("Wartet auf Bestätigung durch den Mitfahrer"),
                        "fas fa-hourglass-half",
                        "secondary",
                    )
                else -> CarpoolBadgeSpec(gettext("Wartet auf Bestätigung durch den Fahrer"), "fas fa-hourglass-half", "secondary")
            }
        }
    }

internal fun Container.carpoolStatusBadge(spec: CarpoolBadgeSpec): Span {
    val label = spec.label
    return span {
        addCssClasses("badge rounded-pill text-bg-${spec.color}")
        span {
            addCssClasses("${spec.icon} me-1")
            setAttribute("aria-hidden", "true")
        }
        span(label)
    }
}

/** The "free seats" line of a card. A server older than V1.9.125 sends no [CarpoolPostingDto.seatsFree]: then the old wording stays. */
internal fun carpoolSeatsText(posting: CarpoolPostingDto): String? {
    val offered = posting.seatsOffered ?: return null
    val free = posting.seatsFree ?: return gettext("%1 freie Plätze", offered)
    return if (free <= 0) gettext("Ausgebucht") else gettext("%1 von %2 Plätzen frei", free, offered)
}

/** Whether the card of a foreign offer offers "Mitfahrt anfragen" (the server's 24-hour lock after a driver's decline is mirrored: no button). */
internal fun carpoolMayRequestRide(posting: CarpoolPostingDto): Boolean {
    if (posting.isOwn || posting.isPast || posting.type != network.lapis.cloud.shared.domain.CarpoolPostingType.OFFER) return false
    if ((posting.seatsFree ?: 1) <= 0) return false
    val mine = posting.myParticipation ?: return true
    return when (mine.status) {
        CarpoolParticipationStatus.WITHDRAWN -> true
        // Declined by the PERSON themselves (the driver had invited): asking is allowed. Declined by the driver: final in the UI.
        CarpoolParticipationStatus.DECLINED -> mine.initiator == CarpoolParticipationInitiator.DRIVER
        else -> false
    }
}

/** "Meine Mitfahrten" row (the person's own rides, as a passenger). */
internal fun renderMyParticipationRow(
    panel: SimplePanel,
    item: CarpoolMyParticipationDto,
    ui: CarpoolUi,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClass("align-items-center") }
    headerRow.untrustedCardTitle(gettext("%1 → %2", item.fromPlace, item.toPlace))
    headerRow.carpoolStatusBadge(carpoolBadgeSpec(item.status, item.initiator, viewerIsDriver = false))
    if (item.isPast) headerRow.statusBadge(tr("Abgelaufen"), "secondary")
    row.div(formatDate(item.departureDate) + carpoolDepartureTimeSuffix(item.departureTime)) { addCssClasses("text-muted small") }
    row.untrustedDiv(gettext("von %1", item.driverDisplayName)) { addCssClasses("text-muted small") }
    item.meetingNote?.takeIf { it.isNotBlank() }?.let { renderMeetingNoteLine(row, it) }
    if (item.isPast) return
    val actions = row.hPanel(spacing = 8) { addCssClass("mt-1") }
    when (item.status) {
        CarpoolParticipationStatus.OPEN ->
            if (item.awaitingMe) {
                renderConfirmDeclineButtons(actions, item.id, ui)
            } else {
                participationButton(actions, ActionIcon.UNDO, tr("Anfrage zurückziehen"), ui, gettext("Anfrage zurückgezogen.")) {
                    rpcService<ICarpoolService>().withdrawParticipation(item.id)
                }
            }
        CarpoolParticipationStatus.CONFIRMED ->
            confirmedActionButton(
                actions,
                ActionIcon.UNDO,
                tr("Mitfahrt zurückziehen"),
                tr("Wirklich zurückziehen?"),
                tr("Endgültig zurückziehen"),
                ui,
                gettext("Mitfahrt zurückgezogen."),
            ) { rpcService<ICarpoolService>().withdrawParticipation(item.id) }
        CarpoolParticipationStatus.DECLINED, CarpoolParticipationStatus.WITHDRAWN -> Unit
    }
}

/** The line "Treffpunkt: ..." with a pin icon (decoration). The text is a driver's free text. */
internal fun renderMeetingNoteLine(
    panel: SimplePanel,
    note: String,
) {
    val line = panel.hPanel(spacing = 6) { addCssClass("align-items-baseline") }
    line.span {
        addCssClasses("fas fa-location-dot text-muted")
        setAttribute("aria-hidden", "true")
    }
    line.untrustedDiv(gettext("Treffpunkt: %1", note)) { addCssClass("small") }
}

private fun renderConfirmDeclineButtons(
    actions: SimplePanel,
    participationId: String,
    ui: CarpoolUi,
) {
    participationButton(actions, ActionIcon.APPROVE, tr("Bestätigen"), ui, gettext("Mitfahrt bestätigt."), ButtonStyle.PRIMARY) {
        rpcService<ICarpoolService>().confirmParticipation(participationId)
    }
    participationButton(actions, ActionIcon.REJECT, tr("Ablehnen"), ui, gettext("Mitfahrt abgelehnt.")) {
        rpcService<ICarpoolService>().declineParticipation(participationId)
    }
}

/** One write action: guarded, announced in the live region, lists reloaded. A failed call keeps everything as it is (the toast is [guarded]'s). */
private fun participationButton(
    actions: SimplePanel,
    icon: ActionIcon,
    label: String,
    ui: CarpoolUi,
    doneMessage: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
    call: suspend () -> Any?,
): Button {
    val button = actions.actionButton(icon, label, style = style)
    button.onClick {
        runGuardedAction(button) {
            if (guarded { call() ?: Unit } != null) {
                notifySuccess(doneMessage)
                ui.announce(doneMessage)
                ui.onChanged()
            }
        }
    }
    return button
}

/** A destructive-ish write that first asks inline ("Wirklich ...?"): the same [InlineConfirmGate] grammar as the delete control of a card. */
private fun confirmedActionButton(
    actions: SimplePanel,
    icon: ActionIcon,
    label: String,
    question: String,
    finalLabel: String,
    ui: CarpoolUi,
    doneMessage: String,
    call: suspend () -> Any?,
) {
    val gate = InlineConfirmGate()
    val outer = actions.actionButton(icon, label, style = ButtonStyle.OUTLINESECONDARY)
    val confirmBox = actions.hPanel(spacing = 8) { hide() }

    fun syncButton() {
        outer.disabled = gate.blocked
    }
    outer.onClick {
        if (!gate.openConfirmation()) return@onClick
        syncButton()
        confirmBox.removeAll()
        confirmBox.div(question) { addCssClasses("align-self-center") }
        confirmBox.actionButton(ActionIcon.BACK, tr("Zurück"), style = ButtonStyle.SECONDARY).onClick {
            if (!gate.cancelConfirmation()) return@onClick
            confirmBox.hide()
            syncButton()
        }
        val finalButton = confirmBox.actionButton(icon, finalLabel, style = ButtonStyle.PRIMARY)
        finalButton.onClick {
            if (!gate.beginRequest()) return@onClick
            syncButton()
            runGuardedAction(finalButton) {
                var succeeded = false
                try {
                    if (guarded { call() ?: Unit } != null) {
                        succeeded = true
                        notifySuccess(doneMessage)
                        ui.announce(doneMessage)
                        ui.onChanged()
                    } else {
                        confirmBox.show()
                    }
                } finally {
                    gate.endRequest(succeeded)
                    syncButton()
                }
            }
        }
        confirmBox.show()
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// The driver's side of an own offer
// ---------------------------------------------------------------------------------------------------------------------------------------

/** Folded away by default: the passenger list is loaded on the FIRST unfold. Hangs under the card of an own, current OFFER. */
internal fun renderOwnerPanel(
    row: SimplePanel,
    posting: CarpoolPostingDto,
    ui: CarpoolUi,
) {
    val toggle = row.actionButton(ActionIcon.PASSENGERS, tr("Mitfahrer verwalten"), style = ButtonStyle.OUTLINESECONDARY)
    toggle.setAttribute("aria-expanded", "false")
    val body = row.vPanel(spacing = 8) { addCssClasses("border-top pt-2 mt-1") }
    body.hide()
    var loaded = false
    toggle.onClick {
        val open = !body.visible
        if (open) body.show() else body.hide()
        toggle.setAttribute("aria-expanded", open.toString())
        if (open && !loaded) {
            loaded = true
            buildOwnerBody(body, posting, ui)
        }
    }
}

private fun buildOwnerBody(
    body: SimplePanel,
    posting: CarpoolPostingDto,
    ui: CarpoolUi,
) {
    body.div(tr("Mitfahrer")) { addCssClass("fw-bold") }
    // A decision inside the panel reloads only the passenger list: reloading the whole screen would fold this panel away under the user's hands.
    lateinit var participants: DataSection
    val participantUi = CarpoolUi(announce = ui.announce, onChanged = { participants.reload() })
    participants =
        body.dataSection<List<CarpoolParticipantDto>>(
            emptyText = tr("Noch keine Mitfahrer."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<ICarpoolService>().listParticipants(posting.id) } },
            render = { panel, items -> items.forEach { renderParticipantRow(panel, it, participantUi) } },
        )
    participants.reload()
    renderInviteSection(body, posting, participantUi)
    renderMeetingNoteForm(body, posting, ui)
}

private fun renderParticipantRow(
    panel: SimplePanel,
    item: CarpoolParticipantDto,
    ui: CarpoolUi,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClass("align-items-center") }
    headerRow.untrustedCardTitle(item.displayName)
    item.memberStatus?.let { headerRow.div(memberStatusLabel(it)) { addCssClasses("text-muted small") } }
    headerRow.carpoolStatusBadge(carpoolBadgeSpec(item.status, item.initiator, viewerIsDriver = true))
    val actions = row.hPanel(spacing = 8)
    when (item.status) {
        CarpoolParticipationStatus.OPEN ->
            if (item.awaitingMe) {
                renderConfirmDeclineButtons(actions, item.id, ui)
            } else {
                participationButton(actions, ActionIcon.UNDO, tr("Einladung zurückziehen"), ui, gettext("Einladung zurückgezogen.")) {
                    rpcService<ICarpoolService>().withdrawParticipation(item.id)
                }
            }
        CarpoolParticipationStatus.CONFIRMED ->
            confirmedActionButton(
                actions,
                ActionIcon.REMOVE,
                tr("Entfernen"),
                tr("Mitfahrer wirklich entfernen?"),
                tr("Endgültig entfernen"),
                ui,
                gettext("Mitfahrer entfernt."),
            ) { rpcService<ICarpoolService>().withdrawParticipation(item.id) }
        CarpoolParticipationStatus.DECLINED, CarpoolParticipationStatus.WITHDRAWN -> Unit
    }
}

/** Exact-name search: both parts at least three characters. A hit never shows more than a display name; "Einladen" sends an opaque token. */
private fun renderInviteSection(
    body: SimplePanel,
    posting: CarpoolPostingDto,
    ui: CarpoolUi,
) {
    body.div(tr("Mitfahrer hinzufügen")) { addCssClass("fw-bold") }
    val form = body.lapisForm()

    fun minThree(value: String): FieldCheck =
        if (value.trim().length >=
            MIN_NAME_PART_LENGTH
        ) {
            FieldCheck.Ok
        } else {
            FieldCheck.Invalid(gettext("Bitte mindestens 3 Zeichen angeben."))
        }
    val firstField = form.textField(label = tr("Vorname"), required = true, rule = ::minThree)
    val lastField = form.textField(label = tr("Nachname"), required = true, rule = ::minThree)
    val searchButton = newActionButton(ActionIcon.SEARCH, tr("Suchen"), ButtonStyle.PRIMARY)
    form.buttons(primary = searchButton)
    val results = body.vPanel(spacing = 4)
    searchButton.onClick {
        form.submit(searchButton) submit@{
            val hits =
                guarded { rpcService<ICarpoolService>().findInvitee(firstField.value.trim(), lastField.value.trim()) } ?: return@submit
            results.removeAll()
            if (hits.isEmpty()) {
                // The SAME text for "nobody", "too short" and "rate limited" -- the answer must not reveal which.
                results.div(tr("Keine Person mit diesem Namen gefunden. Schicken Sie der Person den Link zu dieser Fahrt."))
                return@submit
            }
            results.div(tr("Gefundene Personen")) { addCssClass("fw-bold") }
            val list = results.ul()
            hits.forEach { hit ->
                list.li {
                    hPanel(spacing = 8) {
                        addCssClass("align-items-center")
                        untrustedCardTitle(hit.displayName)
                        hit.memberStatus?.let { div(memberStatusLabel(it)) { addCssClasses("text-muted small") } }
                        val invite = actionButton(ActionIcon.ADD_PERSON, tr("Einladen"), style = ButtonStyle.OUTLINESECONDARY)
                        invite.onClick {
                            runGuardedAction(invite) {
                                if (guarded { rpcService<ICarpoolService>().inviteParticipant(posting.id, hit.personRef) } != null) {
                                    val message = gettext("Einladung gesendet.")
                                    notifySuccess(message)
                                    ui.announce(message)
                                    results.removeAll()
                                    ui.onChanged()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MIN_NAME_PART_LENGTH = 3
private const val MAX_MEETING_NOTE_LENGTH = 200

private fun renderMeetingNoteForm(
    body: SimplePanel,
    posting: CarpoolPostingDto,
    ui: CarpoolUi,
) {
    val form = body.lapisForm()
    val noteField =
        form.textAreaField(
            label = tr("Treffpunkt und Uhrzeit"),
            rows = 2,
            value = posting.meetingNote,
            hint = tr("Nur für bestätigte Mitfahrer sichtbar. Höchstens 200 Zeichen."),
            rule = { value ->
                if (value.trim().length <= MAX_MEETING_NOTE_LENGTH) FieldCheck.Ok else FieldCheck.Invalid(gettext("Höchstens 200 Zeichen."))
            },
        )
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Treffpunkt speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val saved =
                guarded { rpcService<ICarpoolService>().setMeetingNote(posting.id, noteField.value.trim().takeIf { it.isNotEmpty() }) }
            if (saved != null) {
                val message = gettext("Treffpunkt wurde gespeichert.")
                notifySuccess(message)
                ui.announce(message)
            }
        }
    }
}

/**
 * The sidebar label of the carpool centre with the number of things waiting for the person: invitations to answer, plus -- for a member --
 * requests waiting on the own offers. `0`/`null` is the plain label. Pure, see `SidebarLabelsTest`.
 */
internal fun carpoolSidebarLabel(waiting: Int?): String =
    when {
        waiting == null || waiting <= 0 -> tr("Mitfahrerzentrale")
        waiting >= MAX_SIDEBAR_COUNT -> gettext("Mitfahrerzentrale (%1)", "$MAX_SIDEBAR_COUNT+")
        else -> gettext("Mitfahrerzentrale (%1)", waiting)
    }

private const val MAX_SIDEBAR_COUNT = 99

/** Loads the waiting count once, quietly (a failure leaves the plain label) and writes it into [link]'s label. */
internal fun updateCarpoolSidebarCount(
    link: io.kvision.html.Link,
    isMember: Boolean,
) {
    AppScope.launch {
        val invitations =
            runCatching { rpcService<ICarpoolService>().listMyParticipations().count { it.awaitingMe } }.getOrNull() ?: return@launch
        val requests =
            if (isMember) {
                runCatching { rpcService<ICarpoolService>().listMyPostings().sumOf { it.openRequests } }.getOrNull() ?: 0
            } else {
                0
            }
        if (invitations + requests > 0) link.label = carpoolSidebarLabel(invitations + requests)
    }
}
