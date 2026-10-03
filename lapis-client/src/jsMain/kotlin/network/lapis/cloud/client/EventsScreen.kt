package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import io.kvision.form.check.checkBox
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.Text
import io.kvision.form.text.TextArea
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService

/**
 * Welle V1.4.3.x "Veranstaltungen: BOARD/ADMIN-Verwaltungsoberfläche" -- the admin-facing CRUD
 * screen for `event` this client was missing since V1.4.3.1 (see that welle's own CHANGELOG entry
 * "die BOARD/ADMIN-Verwaltungsoberfläche (KVision-Screen) ... für eine Folgewelle vorgesehen").
 * List+creation-form shape mirrors `EventRoomsScreen.kt`'s own precedent (Übersicht + always-visible
 * "Neue X anlegen" form below it); the per-row "Bearbeiten" toggle -> pre-filled inline edit form ->
 * Save/Cancel mirrors `renderEventRoomEditForm`'s idiom. Route `/events`, gated BOARD/ADMIN in
 * `Routing.kt` -- same tier as `EventRoomsScreen`/`CateringScreen`/`EventCheckInSelectionScreen`.
 *
 * Room ASSIGNMENT has moved here from `EventCheckInSelectionScreen.kt`'s own pragmatic notbehelf
 * (see that screen's updated KDoc) now that a real create/edit screen exists. The nachgelagerten
 * Screens [Routes.EVENT_ROOMS]/[Routes.EVENT_VOLUNTEERS]/[Routes.CATERING]/[Routes.EVENT_CHECKIN]
 * stay their own routes -- linked here only as secondary links, no parametrized sub-routing.
 *
 * Start/Ende/Anmeldeschluss use a native `datetime-local` input ([InputType.DATETIME_LOCAL]) --
 * unlike `EventVolunteerShiftsScreen.kt` (which predates this verification), KVision 9.6.0's
 * `io.kvision.html.InputType` DOES carry `DATETIME_LOCAL` (`"datetime-local"`), and
 * `io.kvision.form.text.Text`'s constructor accepts a `type: InputType` parameter -- confirmed by
 * inspecting the `kvision-js` klib directly (no repo precedent existed yet to copy from).
 */
fun renderEventsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Veranstaltungen"))

    val secondaryLinksRow = root.hPanel(spacing = 8)
    secondaryLinksRow.button(tr("Räume"), style = ButtonStyle.OUTLINESECONDARY) {
        onClick { navigateTo(Routes.EVENT_ROOMS) }
    }
    secondaryLinksRow.button(tr("Helfer-Schichten"), style = ButtonStyle.OUTLINESECONDARY) {
        onClick { navigateTo(Routes.EVENT_VOLUNTEERS) }
    }
    secondaryLinksRow.button(tr("Catering"), style = ButtonStyle.OUTLINESECONDARY) {
        onClick { navigateTo(Routes.CATERING) }
    }
    secondaryLinksRow.button(tr("Check-in"), style = ButtonStyle.OUTLINESECONDARY) {
        onClick { navigateTo(Routes.EVENT_CHECKIN) }
    }

    renderEventRefundsSection(root) // V1.9.35, self-gated BOARD/ADMIN, invisible when empty
    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.lapisToolbar()
    val statusFilterOptions =
        listOf("" to tr("Alle")) + EventStatus.entries.map { it.name to eventStatusLabel(it) }
    val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = "", label = tr("Status"))
    val includePastCheck = filterRow.checkBox(value = false, label = tr("Vergangene anzeigen"))
    val listPanel = root.vPanel(spacing = 6)

    var allRooms: List<EventRoomDto> = emptyList()

    // Several triggers fire refreshList() almost at once (initial subscribe callbacks, room load,
    // post-create). Each run clears the panel synchronously but appends asynchronously, so without
    // this guard every run appends its own copy of the rows (event shown N times). Only the
    // newest run may render.
    var refreshGeneration = 0

    fun refreshList() {
        val generation = ++refreshGeneration
        listPanel.removeAll()
        AppScope.launch {
            val status = statusFilterSelect.value?.takeIf { it.isNotBlank() }?.let { runCatching { EventStatus.valueOf(it) }.getOrNull() }
            val page =
                guarded {
                    rpcService<IEventService>().listEvents(
                        EventQuery(status = status, includePast = includePastCheck.value, limit = 200),
                    )
                } ?: return@launch
            if (generation != refreshGeneration) return@launch
            if (page.rows.isEmpty()) {
                listPanel.p(tr("Keine Veranstaltungen gefunden."))
                return@launch
            }
            page.rows.forEach { event ->
                renderEventListRow(listPanel, event, allRooms, ::refreshList)
            }
        }
    }

    fun loadRoomsThenList() {
        AppScope.launch {
            allRooms = guarded { rpcService<IEventRoomService>().listRooms(includeInactive = true) }.orEmpty()
            refreshList()
        }
    }

    statusFilterSelect.subscribe { refreshList() }
    includePastCheck.subscribe { refreshList() }
    loadRoomsThenList()

    root.h2(tr("Neue Veranstaltung anlegen")) { addCssClass("h5") }
    val creationFormHolder = root.vPanel(spacing = 6)
    renderEventCreationForm(creationFormHolder, emptyList(), ::refreshList)
    // The creation form's room dropdown is rebuilt once rooms are actually loaded -- built once
    // upfront (empty) so the screen never stalls waiting on the room-list round trip.
    AppScope.launch {
        val rooms = guarded { rpcService<IEventRoomService>().listRooms(includeInactive = true) }.orEmpty()
        creationFormHolder.removeAll()
        renderEventCreationForm(creationFormHolder, rooms, ::refreshList)
    }
}

// ============================================================================================
// List row
// ============================================================================================

private fun renderEventListRow(
    panel: SimplePanel,
    event: EventDto,
    rooms: List<EventRoomDto>,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val displayHolder = row.vPanel(spacing = 4)
    val editFormHolder = row.vPanel(spacing = 6) { hide() }

    fun renderDisplay() {
        displayHolder.removeAll()
        val headerRow = displayHolder.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        // Security audit W6b follow-up round 3 (major finding A): an event title is organizer-editable free
        // text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
        headerRow.div(sanitizeUntrustedI18nText(event.title)) { addCssClasses("flex-grow-1 fw-bold") }
        // Follow-up wave "Wiederkehrende Veranstaltungen: Admin-UI" -- the ↻ recurrence symbol from
        // the implementation plan's "flache, chronologische Terminliste" section. Struck through for
        // an individually detached exception instance (still part of the series historically, but no
        // longer governed by it -- see `EventSeriesEditScope.THIS` KDoc). `event.seriesRuleSummary` is
        // server-built (`RecurrenceSentence.frequencyOnly`), so no client-side sentence logic here.
        if (event.seriesId != null) {
            headerRow.div("↻") {
                addCssClasses(if (event.seriesDetached) "text-muted text-decoration-line-through" else "text-muted")
                title = event.seriesRuleSummary ?: tr("Teil einer Serie")
            }
        }
        headerRow.eventStatusBadge(event.status)
        headerRow.eventVisibilityBadge(event.visibility)

        displayHolder.div(event.startsAt.toString()) { addCssClasses("text-muted small") }
        if (event.roomName != null) {
            displayHolder.div(gettext("Raum: %1", event.roomName)) { addCssClasses("text-muted small") }
        }
        displayHolder.div(
            gettext("Belegung: %1 belegt, %2 auf Warteliste", event.occupiedSeats.toString(), event.waitlistCount.toString()),
        ) { addCssClasses("text-muted small") }
        val publicUrl = event.publicUrl
        if (publicUrl != null) {
            val urlRow = displayHolder.hPanel(spacing = 8) { addCssClasses("align-items-center") }
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

        val actionRow = displayHolder.hPanel(spacing = 8)
        if (event.status != EventStatus.CANCELLED) {
            val editButton = actionRow.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
            editButton.onClick {
                displayHolder.hide()
                editFormHolder.removeAll()
                renderEventEditForm(
                    editFormHolder,
                    event,
                    rooms,
                    onSaved = onChanged,
                    onCancel = {
                        editFormHolder.removeAll()
                        editFormHolder.hide()
                        displayHolder.show()
                    },
                )
                editFormHolder.show()
            }
        }
        if (event.status == EventStatus.DRAFT) {
            val publishButton = actionRow.button(tr("Veröffentlichen"), style = ButtonStyle.SUCCESS)
            publishButton.onClick {
                val message =
                    if (event.visibility == EventVisibility.PUBLIC) {
                        tr(
                            "Diese Veranstaltung wird veröffentlicht und ist danach öffentlich " +
                                "(auch für nicht angemeldete Besucher) sichtbar und anmeldbar.",
                        )
                    } else {
                        tr("Diese Veranstaltung wird veröffentlicht und ist danach für Mitglieder sichtbar und anmeldbar.")
                    }
                confirmDialog(
                    title = tr("Veranstaltung veröffentlichen"),
                    message = message,
                    confirmLabel = tr("Veröffentlichen"),
                ) {
                    AppScope.launch {
                        val result = guarded { rpcService<IEventService>().publishEvent(event.id) }
                        if (result != null) {
                            notifySuccess(tr("Veranstaltung wurde veröffentlicht."))
                            onChanged()
                        }
                    }
                }
            }
        }
        if (event.status != EventStatus.CANCELLED) {
            val cancelButton = actionRow.button(tr("Absagen"), style = ButtonStyle.OUTLINEDANGER)
            cancelButton.onClick { cancelEventDialog(event, onChanged) }
        }
        if (event.waitlistCount > 0 && event.status == EventStatus.PUBLISHED) {
            val sweepButton = actionRow.button(tr("Warteliste nachrücken"), style = ButtonStyle.OUTLINESECONDARY)
            sweepButton.onClick {
                AppScope.launch {
                    val result = guarded { rpcService<IEventService>().sweepEvent(event.id) }
                    if (result != null) {
                        notifySuccess(tr("Warteliste wurde geprüft."))
                        onChanged()
                    }
                }
            }
        }
    }
    renderDisplay()
}

/**
 * Branches on series membership -- an event that still belongs to a non-detached series
 * ([EventDto.seriesId] non-null, [EventDto.seriesDetached] `false`) is cancelled via
 * [seriesEditScopeDialog]/`cancelSeriesEvent` (THIS/FOLLOWING/ALL); a plain event, or an already
 * individually detached series exception, keeps the original single-event [cancelEvent] path
 * unchanged -- see `EventSeriesScopeEngine`/`EventService.computeSeriesImpact` KDoc for why a
 * detached instance is REJECTED by `impactOfSeriesEdit`/`cancelSeriesEvent` ("Dieser Termin wurde
 * bereits aus der Serie gelöst.") and must therefore never reach that path.
 */
private fun cancelEventDialog(
    event: EventDto,
    onCancelled: () -> Unit,
) {
    if (event.seriesId != null && !event.seriesDetached) {
        cancelSeriesEventDialog(event, onCancelled)
        return
    }
    val affectedCount = event.occupiedSeats + event.waitlistCount
    confirmWithReasonDialog(
        title = tr("Veranstaltung absagen"),
        message = tr("Diese Aktion kann nicht rückgängig gemacht werden."),
        dangerNote = gettext("Alle %1 angemeldeten Personen erhalten eine E-Mail mit diesem Grund.", affectedCount.toString()),
        reasonLabel = tr("Grund"),
        reasonRequired = true,
        confirmLabel = tr("Absagen und benachrichtigen"),
    ) { reason ->
        // reasonRequired = true guarantees onConfirm is only ever invoked with a non-blank, trimmed
        // reason -- see confirmWithReasonDialog's own KDoc.
        val nonBlankReason = reason ?: return@confirmWithReasonDialog
        AppScope.launch {
            val result = guarded { rpcService<IEventService>().cancelEvent(event.id, nonBlankReason) }
            if (result != null) {
                notifyInfo(tr("Veranstaltung wurde abgesagt."))
                onCancelled()
            }
        }
    }
}

/**
 * The series-aware cancel path: collects the mandatory cancellation reason FIRST (same
 * `confirmWithReasonDialog` grammar as the plain path), then opens [seriesEditScopeDialog] for the
 * THIS/FOLLOWING/ALL choice -- reason before scope, since the reason is identical regardless of scope
 * and asking twice would be redundant. `cancelSeriesEvent` itself decides per affected instance
 * whether it hard-deletes (zero registrations) or cancels-and-mails (`EventSeriesMaterializer
 * .deleteOrCancel` KDoc "Entscheidung 3") -- this dialog's wording covers both outcomes rather than
 * promising one specific one.
 */
private fun cancelSeriesEventDialog(
    event: EventDto,
    onCancelled: () -> Unit,
) {
    confirmWithReasonDialog(
        title = tr("Serientermin absagen"),
        message =
            tr(
                "Diese Aktion kann nicht rückgängig gemacht werden. Ein Termin ohne jede Anmeldung wird " +
                    "endgültig gelöscht, ein Termin mit Anmeldungen wird abgesagt und alle aktiven " +
                    "Angemeldeten werden per E-Mail benachrichtigt.",
            ),
        reasonLabel = tr("Grund"),
        reasonRequired = true,
        confirmLabel = tr("Weiter"),
    ) { reason ->
        val nonBlankReason = reason ?: return@confirmWithReasonDialog
        seriesEditScopeDialog(
            eventId = event.id,
            title = tr("Serientermin absagen"),
            message = tr("Welche Termine sollen abgesagt bzw. gelöscht werden?"),
            confirmLabel = tr("Absagen und benachrichtigen"),
            confirmStyle = ButtonStyle.DANGER,
        ) { scope ->
            AppScope.launch {
                val result = guarded { rpcService<IEventService>().cancelSeriesEvent(event.id, scope, nonBlankReason) }
                if (result != null) {
                    notifyInfo(
                        gettext(
                            "%1 Termin(e) wurden abgesagt bzw. gelöscht (%2 Angemeldete benachrichtigt).",
                            result.affectedEventCount,
                            result.affectedRegistrationCount,
                        ),
                    )
                    onCancelled()
                }
            }
        }
    }
}

/**
 * D6-Muster (siehe `EmbedIntegrationScreen.copyToClipboard`/`ConferenceScreen
 * .showInviteTextFallback`): kein Toast, kein Dialog -- nur der Label-Wechsel des Aufrufers. Bei
 * fehlender/blockierter Clipboard-API wird [onCopied] trotzdem NICHT aufgerufen (ehrliches
 * Fehlschlagen statt eines irreführenden "Kopiert").
 */
private fun copyEventUrlToClipboard(
    text: String,
    onCopied: () -> Unit,
) {
    val clipboard: dynamic = window.navigator.asDynamic().clipboard
    if (clipboard == null || clipboard == undefined) return
    val promise: dynamic = clipboard.writeText(text)
    promise.then({ onCopied() }, {})
}

// ============================================================================================
// Create/edit form
// ============================================================================================

private class EventFormFieldRefs(
    val titleInput: Text,
    val descriptionInput: TextArea,
    val locationTextInput: Text,
    val onlineUrlInput: Text,
    val startsAtInput: Text,
    val endsAtInput: Text,
    val registrationClosesAtInput: Text,
    val capacityInput: Text,
    val feeAmountInput: Text,
    val visibilitySelect: Select,
    val roomSelect: Select,
    /** Non-null only in the edit form when `!prefill.feeEditable` -- see [readEventForm]'s KDoc "feeEditable-Fallstrick". */
    val lockedFeeAmount: Decimal?,
)

private fun buildEventFormFields(
    panel: SimplePanel,
    prefill: EventDto?,
    rooms: List<EventRoomDto>,
): EventFormFieldRefs {
    val titleInput = panel.text(label = tr("Titel")).apply { value = prefill?.title }
    val descriptionInput = panel.textArea(label = tr("Beschreibung"), rows = 4).apply { value = prefill?.description }
    val locationTextInput = panel.text(label = tr("Ort (Adresse, optional)")).apply { value = prefill?.locationText }
    val onlineUrlInput = panel.text(label = tr("Online-Link (optional)")).apply { value = prefill?.onlineUrl }
    val startsAtInput =
        panel.text(type = InputType.DATETIME_LOCAL, label = tr("Beginn")).apply { value = prefill?.startsAt?.toString() }
    val endsAtInput =
        panel.text(type = InputType.DATETIME_LOCAL, label = tr("Ende")).apply { value = prefill?.endsAt?.toString() }
    val registrationClosesAtInput =
        panel.text(type = InputType.DATETIME_LOCAL, label = tr("Anmeldeschluss (optional)")).apply {
            value = prefill?.registrationClosesAt?.toString()
        }
    val capacityInput = panel.text(label = tr("Kapazität (optional)")).apply { value = prefill?.capacity?.toString() }
    val feeAmountInput =
        panel.text(label = tr("Teilnahmegebühr in EUR (0 für kostenlos)")).apply {
            value = (prefill?.feeAmount ?: 0.0.toDecimal()).toString()
            if (prefill != null && !prefill.feeEditable) {
                disabled = true
            }
        }
    val visibilitySelect =
        panel.select(
            options = EventVisibility.entries.map { it.name to eventVisibilityLabel(it) },
            value = (prefill?.visibility ?: EventVisibility.MEMBERS_ONLY).name,
            label = tr("Sichtbarkeit"),
        )
    val roomSelect =
        panel.select(
            options = eventRoomOptions(rooms, prefill?.roomId),
            value = prefill?.roomId ?: "",
            label = tr("Raum (optional)"),
        )
    val lockedFeeAmount = if (prefill != null && !prefill.feeEditable) prefill.feeAmount else null
    return EventFormFieldRefs(
        titleInput = titleInput,
        descriptionInput = descriptionInput,
        locationTextInput = locationTextInput,
        onlineUrlInput = onlineUrlInput,
        startsAtInput = startsAtInput,
        endsAtInput = endsAtInput,
        registrationClosesAtInput = registrationClosesAtInput,
        capacityInput = capacityInput,
        feeAmountInput = feeAmountInput,
        visibilitySelect = visibilitySelect,
        roomSelect = roomSelect,
        lockedFeeAmount = lockedFeeAmount,
    )
}

/**
 * Sames Options-Aufbau wie das ehemalige `EventCheckInSelectionScreen.renderEventRoomAssignmentRow`
 * (jetzt hierher umgezogen): die selektierbaren Optionen sind auf ACTIVE Räume beschränkt, aber wenn
 * [currentRoomId] aktuell einem inzwischen deaktivierten Raum zugeordnet ist, wird dieser Raum
 * (mit "(inaktiv)"-Suffix) zusätzlich in die Optionen aufgenommen, damit das Dropdown die wahre
 * aktuelle Zuordnung zeigt statt fälschlich auf "Kein Raum" zu fallen.
 */
private fun eventRoomOptions(
    allRooms: List<EventRoomDto>,
    currentRoomId: String?,
): List<Pair<String, String>> {
    val activeRooms = allRooms.filter { it.status == EventRoomStatus.ACTIVE }
    val currentlyAssignedInactiveRoom = allRooms.firstOrNull { it.id == currentRoomId && it.status != EventRoomStatus.ACTIVE }
    return listOf("" to tr("Kein Raum")) +
        untrustedOptions(activeRooms.map { it.id to it.name }) +
        listOfNotNull(currentlyAssignedInactiveRoom?.let { it.id to gettext("%1 (inaktiv)", it.name) })
}

/**
 * **feeEditable-Fallstrick** (siehe Wellen-Plan Stolperfalle 2): wenn [fields.feeAmountInput] deaktiviert
 * ist (`!event.feeEditable` beim Bearbeiten), wird dessen DOM-Wert NIEMALS gelesen -- ein
 * Rundungs-/Formatierungsartefakt könnte sonst einen `ConflictException` auslösen, obwohl die Gebühr
 * gar nicht geändert werden sollte. Stattdessen wird [EventFormFieldRefs.lockedFeeAmount] direkt als
 * Rohtext an [validateEventForm] durchgereicht.
 */
private fun readEventForm(
    fields: EventFormFieldRefs,
    errorBox: Div,
    existingStartsAt: LocalDateTime?,
): EventInput? {
    errorBox.hide()
    val visibility =
        fields.visibilitySelect.value?.let { runCatching { EventVisibility.valueOf(it) }.getOrNull() } ?: EventVisibility.MEMBERS_ONLY
    val feeAmountRaw = fields.lockedFeeAmount?.toString() ?: fields.feeAmountInput.value.orEmpty()
    val raw =
        EventFormRawInput(
            title = fields.titleInput.value.orEmpty(),
            description = fields.descriptionInput.value.orEmpty(),
            locationText = fields.locationTextInput.value.orEmpty(),
            onlineUrl = fields.onlineUrlInput.value.orEmpty(),
            startsAtRaw = fields.startsAtInput.value.orEmpty(),
            endsAtRaw = fields.endsAtInput.value.orEmpty(),
            registrationClosesAtRaw = fields.registrationClosesAtInput.value.orEmpty(),
            capacityRaw = fields.capacityInput.value.orEmpty(),
            feeAmountRaw = feeAmountRaw,
            visibility = visibility,
            roomId = fields.roomSelect.value?.takeIf { it.isNotBlank() },
        )
    return when (val result = validateEventForm(raw, existingStartsAt)) {
        is EventFormResult.Ok -> result.input
        is EventFormResult.Error -> {
            // Round 6 review (major, regression from round 5): `result.message` is always built via
            // `tr(...)`/`gettext(...)` in EventFormValidation.kt -- it already carries the KV_I18N_MARKER prefix
            // that KVision's Widget resolves through I18n.trans/gettext on render. Routing it through
            // `untrustedContent` would run it through `sanitizeUntrustedI18nText`, which strips that exact marker
            // (see I18nCatalogManager.kt) and silently breaks translation resolution -- the raw German msgid would
            // render for every non-German locale instead of the translated message. This is a documented exception
            // (see docs/architecture/ui-ux-guideline.adoc, "Known gaps"): validation messages are Claude-authored
            // `tr()`/`gettext()` calls, not untrusted DTO/server content, so a plain assignment is correct here.
            errorBox.content = result.message
            errorBox.show()
            null
        }
    }
}

private fun renderEventCreationForm(
    root: SimplePanel,
    rooms: List<EventRoomDto>,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    panel.div(tr("Ein Titelbild können Sie nach dem Anlegen hinzufügen.")) { addCssClasses("text-muted small") }
    val fields = buildEventFormFields(panel, prefill = null, rooms = rooms)
    val recurrenceEditor = renderRecurrenceEditor(panel, fields.startsAtInput, fields.endsAtInput)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val createButton = panel.button(tr("Veranstaltung anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        val input = readEventForm(fields, errorBox, existingStartsAt = null) ?: return@onClick
        if (recurrenceEditor.isEnabled) {
            val rule = recurrenceEditor.currentRule()
            if (rule == null) {
                errorBox.content = tr("Bitte die Wiederholungsregel prüfen -- sie kann so nicht angelegt werden.")
                errorBox.show()
                return@onClick
            }
            createButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IEventService>().createEventSeries(input, rule) }
                createButton.disabled = false
                if (result != null) {
                    notifySuccess(
                        gettext(
                            "Serie \"%1\" mit %2 Terminen wurde angelegt.",
                            input.title,
                            result.createdEventIds.size,
                        ),
                    )
                    onCreated()
                }
            }
        } else {
            createButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IEventService>().createEvent(input) }
                createButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Veranstaltung \"%1\" wurde angelegt.", input.title))
                    onCreated()
                }
            }
        }
    }
}

private fun renderEventEditForm(
    panel: SimplePanel,
    event: EventDto,
    rooms: List<EventRoomDto>,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    if (event.slug.isNotBlank()) {
        panel.div(gettext("Adresse: /veranstaltung/%1", event.slug)) { addCssClasses("text-muted small") }
    }
    if (event.slug.isNotBlank()) renderEventCoverCard(panel, event)
    val fields = buildEventFormFields(panel, prefill = event, rooms = rooms)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val saveButton = buttonRow.actionButton(ActionIcon.SAVE, tr("Änderungen speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = buttonRow.actionButton(ActionIcon.CANCEL, tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)

    saveButton.onClick {
        val input = readEventForm(fields, errorBox, existingStartsAt = event.startsAt) ?: return@onClick
        // Series-aware branch (see `cancelEventDialog`'s own KDoc for the exact same
        // `seriesId != null && !seriesDetached` condition and why a detached instance must NEVER
        // reach `updateSeriesEvent`/`impactOfSeriesEdit`, which reject it server-side).
        if (event.seriesId != null && !event.seriesDetached) {
            seriesEditScopeDialog(
                eventId = event.id,
                title = tr("Serientermin bearbeiten"),
                message = tr("Welche Termine sollen diese Änderung übernehmen?"),
                confirmLabel = tr("Änderungen speichern"),
            ) { scope ->
                saveButton.disabled = true
                AppScope.launch {
                    val result =
                        guarded { rpcService<IEventService>().updateSeriesEvent(eventId = event.id, input = input, scope = scope) }
                    saveButton.disabled = false
                    if (result != null) {
                        notifySuccess(
                            gettext(
                                "%1 Termin(e) wurden aktualisiert (%2 Angemeldete betroffen).",
                                result.affectedEventCount,
                                result.affectedRegistrationCount,
                            ),
                        )
                        onSaved()
                    }
                }
            }
            return@onClick
        }
        saveButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IEventService>().updateEvent(id = event.id, input = input) }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Veranstaltung \"%1\" wurde aktualisiert.", input.title))
                onSaved()
            }
        }
    }
    cancelButton.onClick { onCancel() }
}

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- upload/replace/remove card for
 * [EventsScreen]'s edit form. Deliberately does NOT call `onSaved`/refresh the parent list -- that
 * would close the edit mode the card lives inside; only the local preview updates, the list picks
 * up the new [EventDto.coverImageUrl] on its next regular reload. Client-side pre-check (type/size)
 * mirrors the server's own limits (`EventCoverPolicy`) but the server stays authoritative -- see
 * `EventCoverRoutes` KDoc.
 */
private fun renderEventCoverCard(
    panel: SimplePanel,
    event: EventDto,
) {
    val card = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2 mb-2") }
    card.div(tr("Titelbild")) { addCssClasses("fw-bold") }
    card.div(tr("Änderungen am Titelbild werden sofort gespeichert.")) { addCssClasses("text-muted small") }

    var currentUrl = event.coverImageUrl
    val preview =
        card.div {
            addCssClasses("mb-2 border rounded")
            setStyle("max-width", "320px")
            setStyle("aspect-ratio", "4 / 3")
            setStyle("overflow", "hidden")
        }

    fun renderPreview() {
        preview.removeAll()
        val url = currentUrl
        if (url != null) {
            preview.image(url, event.title) {
                setStyle("width", "100%")
                setStyle("height", "100%")
                setStyle("object-fit", "cover")
            }
        } else {
            preview.div(tr("Kein Titelbild – JPEG oder PNG, mind. 800x600, max. 5 MB")) {
                addCssClasses("text-muted small p-2")
            }
        }
    }
    renderPreview()

    val statusBox =
        card.div().apply {
            addCssClasses("text-muted small")
            hide()
        }
    val fileUpload = card.upload(label = tr("Bild auswählen…"), multiple = false)
    fileUpload.setAttribute("accept", "image/jpeg,image/png")
    val buttonRow = card.hPanel(spacing = 8)
    val uploadButton = buttonRow.actionButton(ActionIcon.UPLOAD, tr("Hochladen"), style = ButtonStyle.PRIMARY)
    val removeButton = buttonRow.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
    removeButton.visible = currentUrl != null

    uploadButton.onClick {
        val nativeFile = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) }
        if (nativeFile == null) {
            statusBox.content = tr("Bitte zuerst eine Datei auswählen.")
            statusBox.show()
            return@onClick
        }
        uploadButton.disabled = true
        removeButton.disabled = true
        statusBox.content = tr("Wird hochgeladen…")
        statusBox.show()
        AppScope.launch {
            when (val result = EventCoverHttp.upload(event.slug, nativeFile)) {
                is EventCoverHttp.Result.Ok -> {
                    currentUrl = result.coverImageUrl
                    renderPreview()
                    removeButton.visible = currentUrl != null
                    statusBox.hide()
                    notifySuccess(tr("Titelbild gespeichert."))
                }
                is EventCoverHttp.Result.Error -> {
                    untrustedContent(statusBox, result.message)
                    statusBox.show()
                }
            }
            uploadButton.disabled = false
            removeButton.disabled = false
        }
    }

    var removeArmed = false
    removeButton.onClick {
        if (!removeArmed) {
            removeArmed = true
            removeButton.text = tr("Wirklich entfernen?")
            AppScope.launch {
                delay(4000)
                if (removeArmed) {
                    removeArmed = false
                    removeButton.text = tr("Entfernen")
                }
            }
            return@onClick
        }
        removeArmed = false
        removeButton.text = tr("Entfernen")
        uploadButton.disabled = true
        removeButton.disabled = true
        AppScope.launch {
            when (val result = EventCoverHttp.remove(event.slug)) {
                is EventCoverHttp.Result.Ok -> {
                    currentUrl = null
                    renderPreview()
                    removeButton.visible = false
                    notifySuccess(tr("Titelbild entfernt."))
                }
                is EventCoverHttp.Result.Error -> {
                    untrustedContent(statusBox, result.message)
                    statusBox.show()
                }
            }
            uploadButton.disabled = false
            removeButton.disabled = false
        }
    }
}
