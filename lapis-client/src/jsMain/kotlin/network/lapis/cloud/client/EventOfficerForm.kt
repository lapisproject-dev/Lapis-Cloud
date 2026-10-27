package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.check.radioGroup
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.rpc.IEventService

/*
 * V1.9.229 -- the edit form of an OFFICER (Vorsitz, Schriftfuehrung, ...) for the event announcing their Sitzung: title, description, start, end,
 * place and the online kind with its link -- nothing else. Every field the server reserves to the board (fee, capacity, room, public link,
 * summary, cover text, visibility, registration deadline, encounter space) is not shown; it is carried over from the stored event, so the input
 * the server receives differs from the stored one only in the fields on screen. Validation is the one of the board form (`validateEventForm`)
 * and the save is the same `updateEvent` call -- the server's allow-list (MeetingEventOfficerAccess) stays authoritative.
 */

/** The online kinds an officer may choose; an encounter space stays as the board set it (it is not offered, and not removed by a save). */
internal val OFFICER_ONLINE_KINDS: List<EventOnlineKind> =
    listOf(EventOnlineKind.NONE, EventOnlineKind.EXTERNAL_LINK, EventOnlineKind.LAPIS_CONFERENCE)

internal fun renderEventOfficerEditForm(
    panel: SimplePanel,
    event: EventDto,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val titleInput = panel.text(label = tr("Titel")).apply { value = event.title }
    val descriptionInput = panel.textArea(label = tr("Beschreibung"), rows = 4).apply { value = event.description }
    val locationInput = panel.text(label = tr("Ort (Adresse, optional)")).apply { value = event.locationText }
    val startsAtInput = panel.localDateTimeInput(label = gettext("Beginn"), value = event.startsAt, stepMinutes = 5)
    val endsAtInput = panel.localDateTimeInput(label = gettext("Ende"), value = event.endsAt, stepMinutes = 5)
    linkDateRange(startsAtInput.date, endsAtInput.date)

    // An encounter space is the board's choice: show it as a fact, keep it as it is.
    val kindLocked = event.onlineKind !in OFFICER_ONLINE_KINDS
    val kindRadio =
        if (kindLocked) {
            panel.div(gettext("Online-Teilnahme: %1", eventOnlineKindLabel(event.onlineKind))) { addCssClasses("text-muted small") }
            null
        } else {
            panel
                .radioGroup(
                    options = OFFICER_ONLINE_KINDS.map { it.name to eventOnlineKindLabel(it) },
                    value = event.onlineKind.name,
                    label = tr("Online-Teilnahme"),
                ).also { it.setAttribute("role", "radiogroup") }
        }
    // A link the board made public stays as it is: swapping a public address is the board's decision.
    val urlInput =
        panel.text(label = tr("Online-Link")).apply {
            value = event.onlineUrl
            if (event.onlineUrlPublic) disabled = true
        }
    if (event.onlineUrlPublic) {
        panel.div(tr("Der Online-Link ist öffentlich. Ändern kann ihn der Vorstand.")) {
            addCssClasses("text-muted small")
        }
    }

    fun chosenKind(): EventOnlineKind =
        if (kindRadio ==
            null
        ) {
            event.onlineKind
        } else {
            EventOnlineKind.entries.firstOrNull { it.name == kindRadio.value } ?: EventOnlineKind.NONE
        }

    fun syncUrlVisibility() {
        urlInput.visible = chosenKind() == EventOnlineKind.EXTERNAL_LINK
    }
    syncUrlVisibility()
    kindRadio?.subscribe { syncUrlVisibility() }

    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val saveButton = buttonRow.actionButton(ActionIcon.SAVE, tr("Änderungen speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = buttonRow.actionButton(ActionIcon.CANCEL, tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)
    saveButton.onClick {
        errorBox.hide()
        val kind = chosenKind()
        // Only the officer's own fields are read from the screen. The board's fields get neutral stand-ins for the shared validation and are
        // replaced by the stored values below, so the server sees exactly what it already holds for them.
        val raw =
            EventFormRawInput(
                title = titleInput.value.orEmpty(),
                description = descriptionInput.value.orEmpty(),
                locationText = locationInput.value.orEmpty(),
                onlineUrl = if (kind == EventOnlineKind.EXTERNAL_LINK) urlInput.value.orEmpty() else "",
                startsAtRaw = startsAtInput.value,
                endsAtRaw = endsAtInput.value,
                registrationClosesAtRaw = "",
                capacityRaw = "",
                feeAmountRaw = "0",
                visibility = event.visibility,
                roomId = null,
                onlineKind = kind,
                encounterSpaceId = event.encounterSpaceId,
            )
        when (val result = validateEventForm(raw, existingStartsAt = event.startsAt)) {
            is EventFormResult.Error -> {
                // Claude-authored tr()/gettext() text, see readEventForm: not untrusted content.
                val message = result.message
                errorBox.content = message
                errorBox.show()
            }
            is EventFormResult.Ok -> {
                val input =
                    result.input.copy(
                        capacity = event.capacity,
                        feeAmount = event.feeAmount,
                        feeCurrency = event.feeCurrency,
                        visibility = event.visibility,
                        registrationClosesAt = event.registrationClosesAt,
                        roomId = event.roomId,
                        summary = event.summary,
                        coverImageAlt = event.coverImageAlt,
                        onlineUrlPublic = event.onlineUrlPublic,
                        encounterSpaceId = event.encounterSpaceId,
                    )
                saveButton.disabled = true
                AppScope.launch {
                    val saved = guarded { rpcService<IEventService>().updateEvent(id = event.id, input = input) }
                    saveButton.disabled = false
                    if (saved != null) {
                        notifySuccess(gettext("Veranstaltung \"%1\" wurde aktualisiert.", input.title))
                        onSaved()
                    }
                }
            }
        }
    }
    cancelButton.onClick { onCancel() }
}
