package network.lapis.cloud.client

import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.text
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
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventVolunteerShiftDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftInput
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.EventVolunteerSignupStatus
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IEventVolunteerService

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- Schicht-Management (BOARD/ADMIN).
 * List+creation-form shape mirrors `CateringScreen.kt`'s own precedent (Event-Auswahl + Übersicht +
 * Neue-Schicht-Formular); the per-row "Bearbeiten" toggle -> pre-filled inline edit form ->
 * Save/Cancel mirrors `renderCateringOrderEditForm`'s idiom. Route `/event-volunteers`, gated
 * BOARD/ADMIN in `Routing.kt` -- Schicht-Management ist Management/back-office data, niemals
 * member-public (same tier as `EventRoomsScreen`/`CateringScreen`). Die Selbstbedienungs-Ansicht
 * für Mitglieder (Anmelden/Abmelden) lebt in `MyVolunteerShiftsScreen.kt`, nicht hier.
 *
 * Start/Ende werden als einfache ISO-Text-Eingabe (`YYYY-MM-DDTHH:MM`) erfasst -- dieser Client
 * besitzt bislang keinen etablierten Datum/Zeit-Picker-Widget-Präzedenzfall (verifiziert: keine
 * andere Screen-Datei nutzt `io.kvision.form.time.*`), ein neues Picker-Widget einzuführen wäre für
 * diese Welle unverhältnismäßig.
 */
fun renderEventVolunteerShiftsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Helfer-Schichten"))

    root.h2(tr("Veranstaltung")) { addCssClass("h5") }
    val eventSelectRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val eventSelect = eventSelectRow.select(options = emptyList(), label = tr("Veranstaltung"))

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val listPanel = root.vPanel(spacing = 6)

    root.h2(tr("Neue Schicht anlegen")) { addCssClass("h5") }
    val creationFormHolder = root.vPanel(spacing = 6)

    fun refreshList() {
        val eventId = eventSelect.value
        listPanel.removeAll()
        if (eventId == null) return
        AppScope.launch {
            val shifts =
                guarded {
                    rpcService<IEventVolunteerService>().listShifts(eventId)
                } ?: return@launch
            if (shifts.isEmpty()) {
                listPanel.p(tr("Noch keine Schichten für diese Veranstaltung."))
                return@launch
            }
            shifts.forEach { shift ->
                renderEventVolunteerShiftRow(listPanel, shift, ::refreshList)
            }
        }
    }

    fun loadEvents() {
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IEventService>().listEvents(EventQuery(includePast = true, limit = 200))
                } ?: return@launch
            val options = page.rows.map { it.id to it.title }
            eventSelect.options = options
            if (options.isNotEmpty()) {
                eventSelect.value = options.first().first
            }
            refreshList()
            creationFormHolder.removeAll()
            renderEventVolunteerShiftCreationForm(creationFormHolder, eventSelect, ::refreshList)
        }
    }

    eventSelect.subscribe { refreshList() }

    loadEvents()
}

// ============================================================================================
// List row + roster + creation/edit form
// ============================================================================================

private fun renderEventVolunteerShiftRow(
    panel: SimplePanel,
    shift: EventVolunteerShiftDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val displayHolder = row.vPanel(spacing = 4)
    val editFormHolder = row.vPanel(spacing = 6) { hide() }
    val rosterHolder = row.vPanel(spacing = 4) { hide() }

    fun renderDisplay() {
        displayHolder.removeAll()
        val headerRow = displayHolder.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        headerRow.div(shift.description) { addCssClasses("flex-grow-1 fw-bold") }
        headerRow.eventVolunteerShiftStatusBadge(shift.status)

        displayHolder.div(gettext("%1 – %2", shift.startsAt.toString(), shift.endsAt.toString())) { addCssClasses("text-muted small") }
        displayHolder.div(gettext("Besetzung: %1 von %2", shift.confirmedCount.toString(), shift.neededCount.toString())) {
            addCssClasses("text-muted small")
        }

        val actionRow = displayHolder.hPanel(spacing = 8)
        if (shift.status == EventVolunteerShiftStatus.ACTIVE) {
            val editButton = actionRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
            editButton.onClick {
                displayHolder.hide()
                editFormHolder.removeAll()
                renderEventVolunteerShiftEditForm(
                    editFormHolder,
                    shift,
                    onSaved = onChanged,
                    onCancel = {
                        editFormHolder.removeAll()
                        editFormHolder.hide()
                        displayHolder.show()
                    },
                )
                editFormHolder.show()
            }

            val cancelButton = actionRow.button(tr("Stornieren"), style = ButtonStyle.OUTLINEDANGER)
            cancelButton.onClick {
                confirmDialog(
                    title = tr("Schicht stornieren"),
                    message =
                        gettext(
                            "\"%1\" wirklich stornieren? Bestehende Zusagen bleiben als Historie erhalten.",
                            shift.description,
                        ),
                    confirmLabel = tr("Stornieren"),
                ) {
                    AppScope.launch {
                        val result = guarded { rpcService<IEventVolunteerService>().cancelShift(shift.id) }
                        if (result != null) {
                            notifyInfo(tr("Schicht wurde storniert."))
                            onChanged()
                        }
                    }
                }
            }
        }

        val rosterButton = actionRow.button(tr("Zusagen anzeigen"), style = ButtonStyle.OUTLINEPRIMARY)
        rosterButton.onClick {
            if (rosterHolder.visible) {
                rosterHolder.hide()
                return@onClick
            }
            rosterHolder.removeAll()
            AppScope.launch {
                val roster = guarded { rpcService<IEventVolunteerService>().getShiftRoster(shift.id) } ?: return@launch
                if (roster.signups.isEmpty()) {
                    rosterHolder.p(tr("Noch keine Zusagen für diese Schicht."))
                } else {
                    roster.signups.forEach { signup ->
                        val signupRow = rosterHolder.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                        signupRow.div(signup.memberDisplayName) { addCssClasses("flex-grow-1") }
                        signupRow.eventVolunteerSignupStatusBadge(signup.status)
                    }
                }
                rosterHolder.show()
            }
        }
    }
    renderDisplay()
}

private fun renderEventVolunteerShiftEditForm(
    panel: SimplePanel,
    shift: EventVolunteerShiftDto,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val descriptionInput = panel.text(label = tr("Beschreibung")).apply { value = shift.description }
    val startsAtInput = panel.text(label = tr("Beginn (JJJJ-MM-TTThh:mm)")).apply { value = shift.startsAt.toString() }
    val endsAtInput = panel.text(label = tr("Ende (JJJJ-MM-TTThh:mm)")).apply { value = shift.endsAt.toString() }
    val neededCountInput = panel.text(label = tr("Benötigte Anzahl")).apply { value = shift.neededCount.toString() }
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val saveButton = buttonRow.button(tr("Änderungen speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = buttonRow.button(tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)

    saveButton.onClick {
        val input =
            readAndValidateShiftForm(descriptionInput, startsAtInput, endsAtInput, neededCountInput, errorBox, shift.eventId)
                ?: return@onClick

        saveButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IEventVolunteerService>().updateShift(id = shift.id, input = input) }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Schicht \"%1\" wurde aktualisiert.", input.description))
                onSaved()
            }
        }
    }
    cancelButton.onClick { onCancel() }
}

private fun renderEventVolunteerShiftCreationForm(
    root: SimplePanel,
    eventSelect: Select,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val descriptionInput = panel.text(label = tr("Beschreibung"))
    val startsAtInput = panel.text(label = tr("Beginn (JJJJ-MM-TTThh:mm)"))
    val endsAtInput = panel.text(label = tr("Ende (JJJJ-MM-TTThh:mm)"))
    val neededCountInput = panel.text(label = tr("Benötigte Anzahl"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button(tr("Schicht anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val eventId = eventSelect.value
        if (eventId.isNullOrBlank()) {
            errorBox.content = tr("Bitte eine Veranstaltung auswählen.")
            errorBox.show()
            return@onClick
        }
        val input =
            readAndValidateShiftForm(descriptionInput, startsAtInput, endsAtInput, neededCountInput, errorBox, eventId) ?: return@onClick

        createButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IEventVolunteerService>().createShift(input) }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Schicht \"%1\" wurde angelegt.", input.description))
                descriptionInput.value = null
                startsAtInput.value = null
                endsAtInput.value = null
                neededCountInput.value = null
                onCreated()
            }
        }
    }
}

/** Shared read+validate für Create- und Edit-Formular -- vermeidet doppelte Validierungslogik. */
private fun readAndValidateShiftForm(
    descriptionInput: io.kvision.form.text.Text,
    startsAtInput: io.kvision.form.text.Text,
    endsAtInput: io.kvision.form.text.Text,
    neededCountInput: io.kvision.form.text.Text,
    errorBox: io.kvision.html.Div,
    eventId: String,
): EventVolunteerShiftInput? {
    errorBox.hide()
    val description = descriptionInput.value.orEmpty().trim()
    val neededCountText = neededCountInput.value.orEmpty().trim()

    if (!Validation.isNonBlank(description)) {
        errorBox.content = tr("Bitte eine Beschreibung angeben.")
        errorBox.show()
        return null
    }
    val startsAt = runCatching { LocalDateTime.parse(startsAtInput.value.orEmpty().trim()) }.getOrNull()
    if (startsAt == null) {
        errorBox.content = tr("Bitte einen gültigen Beginn im Format JJJJ-MM-TTThh:mm angeben.")
        errorBox.show()
        return null
    }
    val endsAt = runCatching { LocalDateTime.parse(endsAtInput.value.orEmpty().trim()) }.getOrNull()
    if (endsAt == null) {
        errorBox.content = tr("Bitte ein gültiges Ende im Format JJJJ-MM-TTThh:mm angeben.")
        errorBox.show()
        return null
    }
    if (endsAt <= startsAt) {
        errorBox.content = tr("Das Ende der Schicht muss nach ihrem Beginn liegen.")
        errorBox.show()
        return null
    }
    val neededCount = neededCountText.toIntOrNull()
    if (neededCount == null || neededCount <= 0) {
        errorBox.content = tr("Die benötigte Anzahl muss eine positive ganze Zahl sein.")
        errorBox.show()
        return null
    }

    return EventVolunteerShiftInput(
        eventId = eventId,
        description = description,
        startsAt = startsAt,
        endsAt = endsAt,
        neededCount = neededCount,
    )
}

private fun SimplePanel.eventVolunteerShiftStatusBadge(status: EventVolunteerShiftStatus) {
    activeStatusBadge(status == EventVolunteerShiftStatus.ACTIVE)
}

fun SimplePanel.eventVolunteerSignupStatusBadge(status: EventVolunteerSignupStatus) {
    when (status) {
        EventVolunteerSignupStatus.CONFIRMED -> statusBadge(tr("Zugesagt"), "success")
        EventVolunteerSignupStatus.CANCELLED -> statusBadge(tr("Zurückgezogen"), "secondary")
    }
}
