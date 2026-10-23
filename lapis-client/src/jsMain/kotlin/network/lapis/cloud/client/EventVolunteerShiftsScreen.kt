package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
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
 * Start/Ende sind je ein Datums- und ein Uhrzeitfeld ([localDateTimeInput]; seit V1.9.173 mit dem
 * nativen Browser-Picker und 5-Minuten-Raster), gespeichert wird ein ISO-`LocalDateTime`.
 */
fun renderEventVolunteerShiftsScreen(container: SimplePanel) {
    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Helfer-Schichten"), intro = PageIntros.forRoute(Routes.EVENT_VOLUNTEERS))
    val createHost = root.vPanel(spacing = 6) // V1.9.47 (R36B): collapsed create form, directly under the header

    root.h2(tr("Veranstaltung")) { addCssClass("h5") }
    val eventSelectRow = root.lapisToolbar()
    val eventSelect = eventSelectRow.select(options = emptyList(), label = tr("Veranstaltung"))

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val editHost = root.vPanel(spacing = 6) // V1.9.194: the edit form of one shift, above the table
    val rosterHost = root.vPanel(spacing = 6) // V1.9.194: the roster of one shift ("Zusagen anzeigen"), above the table
    val listPanel = root.vPanel(spacing = 6)

    var createController: CollapsibleCreateFormController<Unit>? = null
    val titleById = mutableMapOf<String, String>()

    // V1.9.194: picking the first event fires the picker AND the explicit reload; only the newest answer may fill the list (it showed every shift twice).
    var listGeneration = 0

    fun refreshList() {
        val generation = ++listGeneration
        val eventId = eventSelect.value
        listPanel.removeAll()
        editHost.removeAll()
        rosterHost.removeAll()
        if (eventId == null) return
        AppScope.launch {
            val shifts =
                guarded {
                    rpcService<IEventVolunteerService>().listShifts(eventId)
                } ?: return@launch
            if (generation != listGeneration) return@launch
            if (shifts.isEmpty()) {
                listPanel.p(tr("Noch keine Schichten für diese Veranstaltung."))
                return@launch
            }
            renderEventVolunteerShiftTable(listPanel, shifts, editHost, rosterHost, ::refreshList)
        }
    }

    fun loadEvents() {
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IEventService>().listEvents(EventQuery(includePast = true, limit = 200))
                } ?: return@launch
            val options = untrustedOptions(page.rows.map { it.id to it.title })
            titleById.clear()
            page.rows.forEach { titleById[it.id] = it.title }
            eventSelect.options = options
            if (options.isNotEmpty()) {
                eventSelect.value = options.first().first
            }
            refreshList()
            // No event, no shift to create: the button then does not exist (the list's empty text stays).
            if (options.isNotEmpty() && createController == null) {
                createController =
                    collapsibleCreateForm<Unit>(
                        actionSlot = header.actionSlot,
                        formHost = createHost,
                        buttonLabel = tr("Neue Schicht"),
                        formId = "lapis-create-event-shift",
                        // The form belongs to the event chosen when it was opened: the picker is locked while it is open.
                        onOpenChange = { open -> eventSelect.disabled = open },
                    ) { _, close ->
                        val targetEventId = eventSelect.value.orEmpty()
                        renderEventVolunteerShiftCreationForm(this, targetEventId, titleById[targetEventId].orEmpty(), close, ::refreshList)
                    }
            }
        }
    }

    eventSelect.subscribe { refreshList() }

    loadEvents()
}

// ============================================================================================
// List row + roster + creation/edit form
// ============================================================================================

/**
 * V1.9.194: the shifts as a TABLE (description, period, staffing, status) with the row actions as icons (edit, cancel, show the
 * roster). Every icon carries the shift's name; "Stornieren" keeps its confirmation dialog; edit form and roster open above the table.
 */
internal fun renderEventVolunteerShiftTable(
    panel: SimplePanel,
    shifts: List<EventVolunteerShiftDto>,
    editHost: SimplePanel,
    rosterHost: SimplePanel,
    onChanged: () -> Unit,
) {
    var rosterShiftId: String? = null
    panel.dataTable(
        columns =
            listOf(
                textColumn<EventVolunteerShiftDto>(title = tr("Beschreibung"), primary = true, cssClasses = "fw-bold") { it.description },
                textColumn<EventVolunteerShiftDto>(title = tr("Zeitraum")) {
                    gettext("%1 – %2", formatDateTime(it.startsAt), formatDateTime(it.endsAt))
                },
                textColumn<EventVolunteerShiftDto>(title = tr("Besetzung"), numeric = true) {
                    gettext("%1 von %2", it.confirmedCount.toString(), it.neededCount.toString())
                },
                DataColumn<EventVolunteerShiftDto>(
                    title = tr("Status"),
                ) { cell, shift -> cell.eventVolunteerShiftStatusBadge(shift.status) },
            ),
        rows = shifts,
        actions = { actions, shift ->
            val name = sanitizeUntrustedI18nText(shift.description)
            val group = actions.tableActionGroup()
            if (shift.status == EventVolunteerShiftStatus.ACTIVE) {
                group.tableActionButton(ActionIcon.EDIT, gettext("Bearbeiten: %1", name), ButtonStyle.OUTLINEPRIMARY).onClick {
                    editHost.removeAll()
                    renderEventVolunteerShiftEditForm(
                        editHost,
                        shift,
                        onSaved = {
                            editHost.removeAll()
                            onChanged()
                        },
                        onCancel = { editHost.removeAll() },
                    )
                }
                group.tableActionButton(ActionIcon.UNDO, gettext("Stornieren: %1", name), ButtonStyle.OUTLINEDANGER).onClick {
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
            group.tableActionButton(ActionIcon.VIEW, gettext("Zusagen anzeigen: %1", name)).onClick {
                if (rosterShiftId == shift.id) {
                    rosterShiftId = null
                    rosterHost.removeAll()
                    return@onClick
                }
                AppScope.launch {
                    val roster = guarded { rpcService<IEventVolunteerService>().getShiftRoster(shift.id) } ?: return@launch
                    rosterShiftId = shift.id
                    rosterHost.removeAll()
                    rosterHost.h2(gettext("Zusagen: %1", name)) { addCssClass("h5") }
                    if (roster.signups.isEmpty()) {
                        rosterHost.p(tr("Noch keine Zusagen für diese Schicht."))
                    } else {
                        roster.signups.forEach { signup ->
                            val signupRow = rosterHost.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                            signupRow.untrustedDiv(signup.memberDisplayName, className = "flex-grow-1")
                            signupRow.eventVolunteerSignupStatusBadge(signup.status)
                        }
                    }
                }
            }
        },
    )
}

private fun renderEventVolunteerShiftEditForm(
    panel: SimplePanel,
    shift: EventVolunteerShiftDto,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val descriptionInput = panel.text(label = tr("Beschreibung")).apply { value = shift.description }
    val startsAtInput = panel.localDateTimeInput(label = gettext("Beginn"), value = shift.startsAt, stepMinutes = 5)
    val endsAtInput = panel.localDateTimeInput(label = gettext("Ende"), value = shift.endsAt, stepMinutes = 5)
    linkDateRange(startsAtInput.date, endsAtInput.date)
    val neededCountInput = panel.text(label = tr("Benötigte Anzahl")).apply { value = shift.neededCount.toString() }
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val saveButton = buttonRow.actionButton(ActionIcon.SAVE, tr("Änderungen speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = buttonRow.actionButton(ActionIcon.CANCEL, tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)

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
    targetEventId: String,
    targetEventTitle: String,
    collapse: (saved: Boolean) -> Unit,
    onCreated: () -> Unit,
): FormSnapshot {
    val panel = root.vPanel(spacing = 6)
    // Two widgets, never one concatenated string: the title is organizer-editable free text (sanitized), the label a `tr()` constant.
    val targetRow = panel.hPanel(spacing = 6)
    targetRow.span(tr("Für Veranstaltung:")) { addCssClass("text-muted") }
    targetRow.untrustedSpan(targetEventTitle, className = "fw-bold")
    val descriptionInput = panel.text(label = tr("Beschreibung"))
    val startsAtInput = panel.localDateTimeInput(label = gettext("Beginn"), stepMinutes = 5)
    val endsAtInput = panel.localDateTimeInput(label = gettext("Ende"), stepMinutes = 5)
    linkDateRange(startsAtInput.date, endsAtInput.date)
    val neededCountInput = panel.text(label = tr("Benötigte Anzahl"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val buttonRow = panel.hPanel(spacing = 8)
    val createButton = buttonRow.actionButton(ActionIcon.ADD, tr("Schicht anlegen"), style = ButtonStyle.PRIMARY)
    buttonRow.add(collapseCancelButton(collapse))
    createButton.onClick {
        errorBox.hide()
        val eventId = targetEventId
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
                collapse(true)
                onCreated()
            }
        }
    }
    return FormSnapshot {
        listOf(descriptionInput.value.orEmpty(), startsAtInput.value, endsAtInput.value, neededCountInput.value.orEmpty())
    }
}

/** Shared read+validate für Create- und Edit-Formular -- vermeidet doppelte Validierungslogik. */
private fun readAndValidateShiftForm(
    descriptionInput: io.kvision.form.text.Text,
    startsAtInput: LocalDateTimeInput,
    endsAtInput: LocalDateTimeInput,
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
    val startsAt = startsAtInput.dateTimeOrNull()
    if (startsAt == null) {
        errorBox.content = tr("Bitte einen gültigen Beginn angeben.")
        errorBox.show()
        return null
    }
    val endsAt = endsAtInput.dateTimeOrNull()
    if (endsAt == null) {
        errorBox.content = tr("Bitte ein gültiges Ende angeben.")
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

private fun Container.eventVolunteerShiftStatusBadge(status: EventVolunteerShiftStatus) {
    activeStatusBadge(status == EventVolunteerShiftStatus.ACTIVE)
}

fun SimplePanel.eventVolunteerSignupStatusBadge(status: EventVolunteerSignupStatus) {
    when (status) {
        EventVolunteerSignupStatus.CONFIRMED -> statusBadge(tr("Zugesagt"), "success")
        EventVolunteerSignupStatus.CANCELLED -> statusBadge(tr("Zurückgezogen"), "secondary")
    }
}
