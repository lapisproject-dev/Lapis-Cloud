package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.Text
import io.kvision.form.text.TextArea
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
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
 * Start/Ende/Anmeldeschluss are a date field plus a time field each ([LocalDateTimeInput], V1.9.137): the person types them in the
 * locale of the UI (`15.10.2026` / `18:00`, en-US `10/15/2026` / `6:00 PM`) and the form reads ISO. The browser's own
 * `datetime-local` picker is gone on purpose -- its display followed the operating system, not the UI language.
 */
fun renderEventsScreen(container: SimplePanel) {
    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Veranstaltungen"), intro = PageIntros.forRoute(Routes.EVENT_MANAGEMENT))
    // V1.9.47 (R36B): the create form is collapsed; its host sits directly under the header, before the navigation row.
    val createHost = root.vPanel(spacing = 6)

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
    // V1.9.82: import of past events -- BOARD/ADMIN only (the whole /events screen is gated the same way in Routing.kt; the explicit check
    // keeps the button hidden should the screen ever be reachable by another role).
    if (AppState.session?.role in setOf(AccountRole.BOARD, AccountRole.ADMIN)) {
        secondaryLinksRow.button(tr("Importieren"), style = ButtonStyle.OUTLINESECONDARY) {
            onClick { navigateTo(Routes.EVENT_IMPORT) }
        }
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

    // The room select of the OPEN create form (null while collapsed); rooms arriving late refill it in place, no rebuild.
    var openRoomSelect: Select? = null

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
                val filtered = status != null || includePastCheck.value
                listPanel.p(if (filtered) tr("Keine Veranstaltungen gefunden.") else tr("Noch keine Veranstaltungen."))
                return@launch
            }
            renderEventTable(listPanel, page.rows, ::refreshList)
        }
    }

    fun loadRoomsThenList() {
        AppScope.launch {
            allRooms = guarded { rpcService<IEventRoomService>().listRooms(includeInactive = true) }.orEmpty()
            openRoomSelect?.let { select ->
                val chosen = select.value
                select.options = eventRoomOptions(allRooms, null)
                select.value = chosen
            }
            refreshList()
        }
    }

    statusFilterSelect.subscribe { refreshList() }
    includePastCheck.subscribe { refreshList() }
    loadRoomsThenList()

    collapsibleCreateForm<Unit>(
        actionSlot = header.actionSlot,
        formHost = createHost,
        buttonLabel = tr("Neue Veranstaltung"),
        formId = "lapis-create-event",
        onOpenChange = { open -> if (!open) openRoomSelect = null },
    ) { _, close ->
        renderEventCreationForm(this, allRooms, close, onRoomSelect = { openRoomSelect = it }, onCreated = ::refreshList)
    }
}

// ============================================================================================
// List table (V1.9.204): one row per event, the title links to the detail page `/events/:id`
// ============================================================================================

/** "23/40" with a capacity, "-" without one (an open event has no seats to count). */
internal fun eventOccupancyText(event: EventDto): String {
    val capacity = event.capacity ?: return "–"
    return "${event.occupiedSeats}/$capacity"
}

private fun renderEventTable(
    host: SimplePanel,
    events: List<EventDto>,
    onChanged: () -> Unit,
) {
    val headers =
        listOf(
            TableHeader(tr("Titel")),
            TableHeader(tr("Datum")),
            TableHeader(tr("Ort")),
            TableHeader(tr("Belegung"), numeric = true),
            TableHeader(tr("Status")),
            TableHeader(tr("Aktionen")),
        )
    val table = host.masterTable(tr("Veranstaltungen"), headers)
    events.forEach { event ->
        table.row {
            masterRow()
            val titleCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(titleCell)
            // An event title is organizer-editable free text: sanitized inside [recordLink].
            titleCell.recordLink(event.title, Routes.EVENT_MANAGEMENT, event.id)
            // The recurrence symbol of the old card (struck through for a detached exception); `seriesRuleSummary` is server-built.
            if (event.seriesId != null) {
                titleCell.span(" ")
                titleCell.span("↻") {
                    addCssClasses(if (event.seriesDetached) "text-muted text-decoration-line-through" else "text-muted")
                    title = event.seriesRuleSummary ?: tr("Teil einer Serie")
                }
            }
            titleCell.span(" ")
            titleCell.eventVisibilityBadge(event.visibility)
            if (event.imported) {
                titleCell.span(" ")
                titleCell.span(tr("Importiert")) { addCssClasses("badge text-bg-secondary") }
            }
            cell { weekdayDateTimeSpan(event.startsAt) }
            val place = event.locationText?.takeIf { it.isNotBlank() } ?: event.roomName
            if (place != null) textCell(place) else textCell(trusted(tr("Online")))
            numCell(eventOccupancyText(event))
            cell { eventStatusBadge(event.status) }
            cell { eventRowActions(event, onChanged) }
        }
    }
}

/** The icon-only row actions: publish (draft), cancel (not cancelled), sweep the waitlist. Editing lives on the detail page. */
private fun Container.eventRowActions(
    event: EventDto,
    onChanged: () -> Unit,
) {
    val name = sanitizeUntrustedI18nText(event.title)
    val group = tableActionGroup()
    if (event.status == EventStatus.DRAFT) {
        group
            .tableActionButton(ActionIcon.SEND, gettext("Veröffentlichen: %1", name), ButtonStyle.OUTLINESUCCESS)
            .onClick { publishEventDialog(event, onChanged) }
    }
    if (event.status != EventStatus.CANCELLED) {
        group
            .tableActionButton(ActionIcon.REVOKE, gettext("Absagen: %1", name), ButtonStyle.OUTLINEDANGER)
            .onClick { cancelEventDialog(event, onChanged) }
    }
    if (event.waitlistCount > 0 && event.status == EventStatus.PUBLISHED) {
        group
            .tableActionButton(ActionIcon.REFRESH, gettext("Warteliste nachrücken: %1", name))
            .onClick { sweepEventWaitlist(event, onChanged) }
    }
}

/** Asks first, then publishes; the wording depends on who will see the event. */
internal fun publishEventDialog(
    event: EventDto,
    onChanged: () -> Unit,
) {
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

/** Lets the waitlist move up into free seats. */
internal fun sweepEventWaitlist(
    event: EventDto,
    onChanged: () -> Unit,
) {
    AppScope.launch {
        val result = guarded { rpcService<IEventService>().sweepEvent(event.id) }
        if (result != null) {
            notifySuccess(tr("Warteliste wurde geprüft."))
            onChanged()
        }
    }
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
internal fun cancelEventDialog(
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
internal fun cancelSeriesEventDialog(
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
internal fun copyEventUrlToClipboard(
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
    /** V1.9.113 -- the "Online-Teilnahme" section: the kind, the external link (only for the external kind) and the encounter space. */
    val online: EventOnlineKindRefs,
    /** V1.9.156 -- the release of recording / live streaming (saved with its own call after the event). */
    val capture: EventCaptureRefs,
    val startsAtInput: LocalDateTimeInput,
    val endsAtInput: LocalDateTimeInput,
    val registrationClosesAtInput: LocalDateTimeInput,
    val capacityInput: Text,
    val feeAmountInput: Text,
    val visibilitySelect: Select,
    val roomSelect: Select,
    /** Non-null only in the edit form when `!prefill.feeEditable` -- see [readEventForm]'s KDoc "feeEditable-Fallstrick". */
    val lockedFeeAmount: Decimal?,
    /** V1.9.82 -- public teaser of the events feed. */
    val summaryInput: Text,
    /** V1.9.82 -- alt text of the cover image; only exists in the edit form (a cover can only be added to a saved event). */
    val coverImageAltInput: Text?,
)

private fun buildEventFormFields(
    panel: SimplePanel,
    prefill: EventDto?,
    rooms: List<EventRoomDto>,
): EventFormFieldRefs {
    val titleInput = panel.text(label = tr("Titel")).apply { value = prefill?.title }
    val summaryInput = panel.text(label = tr("Kurztext (optional)")).apply { value = prefill?.summary }
    panel.div(tr("Erscheint auf der Webseite als Vorschautext. Öffentlich.")) { addCssClasses("text-muted small") }
    val summaryCounter = panel.div("") { addCssClasses("small text-muted") }

    fun updateSummaryCounter() {
        val count =
            summaryInput.value
                .orEmpty()
                .trim()
                .length
        summaryCounter.content = gettext("%1 / %2", count, EVENT_SUMMARY_MAX_LENGTH)
        if (count > EVENT_SUMMARY_MAX_LENGTH) summaryCounter.addCssClass("text-danger") else summaryCounter.removeCssClass("text-danger")
    }
    updateSummaryCounter()
    summaryInput.subscribe { updateSummaryCounter() }
    val descriptionInput = panel.textArea(label = tr("Beschreibung"), rows = 4).apply { value = prefill?.description }
    panel.div(tr("Wird öffentlich angezeigt.")) { addCssClasses("text-muted small") }
    val locationTextInput = panel.text(label = tr("Ort (Adresse, optional)")).apply { value = prefill?.locationText }
    panel.div(tr("Wird öffentlich angezeigt.")) { addCssClasses("text-muted small") }
    // V1.9.113: the form fields the online section's warnings depend on are created further below; the section reads them through these holders.
    var visibilityHolder: Select? = null
    var registrationClosesHolder: LocalDateTimeInput? = null
    var capacityHolder: Text? = null
    val online =
        buildEventOnlineKindSection(
            panel = panel,
            prefill = prefill,
            context =
                EventOnlineFormContext(
                    visibility = {
                        visibilityHolder?.value?.let { runCatching { EventVisibility.valueOf(it) }.getOrNull() }
                            ?: prefill?.visibility ?: EventVisibility.MEMBERS_ONLY
                    },
                    hasRegistrationDeadline = { registrationClosesHolder?.isBlank == false },
                    capacity = { capacityHolder?.value?.trim()?.toIntOrNull() },
                ),
        )
    val capture = buildEventCaptureSection(panel = panel, prefill = prefill, online = online)
    val startsAtInput = panel.localDateTimeInput(label = gettext("Beginn"), value = prefill?.startsAt, stepMinutes = 5)
    val endsAtInput = panel.localDateTimeInput(label = gettext("Ende"), value = prefill?.endsAt, stepMinutes = 5)
    linkDateRange(startsAtInput.date, endsAtInput.date)
    val registrationClosesAtInput =
        panel.localDateTimeInput(label = gettext("Anmeldeschluss (optional)"), value = prefill?.registrationClosesAt, stepMinutes = 5)
    val capacityInput = panel.text(label = tr("Kapazität (optional)")).apply { value = prefill?.capacity?.toString() }
    capacityHolder = capacityInput
    registrationClosesHolder = registrationClosesAtInput
    capacityInput.subscribe { online.refresh() }
    registrationClosesAtInput.subscribe { online.refresh() }
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
    visibilityHolder = visibilitySelect
    visibilitySelect.subscribe { online.refresh() }
    online.refresh()
    val roomSelect =
        panel.select(
            options = eventRoomOptions(rooms, prefill?.roomId),
            value = prefill?.roomId ?: "",
            label = tr("Raum (optional)"),
        )
    val lockedFeeAmount = if (prefill != null && !prefill.feeEditable) prefill.feeAmount else null
    var coverImageAltInput: Text? = null
    if (prefill != null) {
        val altInput = panel.text(label = tr("Bildbeschreibung (Alternativtext)")).apply { value = prefill.coverImageAlt }
        coverImageAltInput = altInput
        panel.div(tr("Beschreibt das Titelbild für Screenreader und Suchmaschinen. Öffentlich.")) { addCssClasses("text-muted small") }
        val altWarning =
            panel.div(tr("Für das Titelbild fehlt eine Bildbeschreibung.")) { addCssClasses("text-warning small") }

        fun syncAltWarning() {
            if (prefill.coverImageUrl != null && altInput.value.orEmpty().isBlank()) altWarning.show() else altWarning.hide()
        }
        syncAltWarning()
        altInput.subscribe { syncAltWarning() }
    }
    return EventFormFieldRefs(
        titleInput = titleInput,
        descriptionInput = descriptionInput,
        locationTextInput = locationTextInput,
        online = online,
        capture = capture,
        startsAtInput = startsAtInput,
        endsAtInput = endsAtInput,
        registrationClosesAtInput = registrationClosesAtInput,
        capacityInput = capacityInput,
        feeAmountInput = feeAmountInput,
        visibilitySelect = visibilitySelect,
        roomSelect = roomSelect,
        lockedFeeAmount = lockedFeeAmount,
        summaryInput = summaryInput,
        coverImageAltInput = coverImageAltInput,
    )
}

/**
 * Sames Options-Aufbau wie das ehemalige `EventCheckInSelectionScreen.renderEventRoomAssignmentRow`
 * (jetzt hierher umgezogen): die selektierbaren Optionen sind auf ACTIVE Räume beschränkt, aber wenn
 * [currentRoomId] aktuell einem inzwischen deaktivierten Raum zugeordnet ist, wird dieser Raum
 * (mit "(inaktiv)"-Suffix) zusätzlich in die Optionen aufgenommen, damit das Dropdown die wahre
 * aktuelle Zuordnung zeigt statt fälschlich auf "Kein Raum" zu fallen.
 */
internal fun eventRoomOptions(
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
            onlineUrl =
                if (fields.online.kind() == EventOnlineKind.EXTERNAL_LINK) {
                    fields.online.onlineUrlInput.value
                        .orEmpty()
                } else {
                    ""
                },
            startsAtRaw = fields.startsAtInput.value,
            endsAtRaw = fields.endsAtInput.value,
            registrationClosesAtRaw = fields.registrationClosesAtInput.value,
            capacityRaw = fields.capacityInput.value.orEmpty(),
            feeAmountRaw = feeAmountRaw,
            visibility = visibility,
            roomId = fields.roomSelect.value?.takeIf { it.isNotBlank() },
            summary = fields.summaryInput.value.orEmpty(),
            coverImageAlt = fields.coverImageAltInput?.value.orEmpty(),
            onlineUrlPublic = fields.online.kind() == EventOnlineKind.EXTERNAL_LINK && fields.online.onlineUrlPublicCheck.value,
            onlineKind = fields.online.kind(),
            encounterSpaceId = fields.online.encounterSpaceId(),
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
    collapse: (saved: Boolean) -> Unit,
    onRoomSelect: (Select) -> Unit,
    onCreated: () -> Unit,
): FormSnapshot {
    val panel = root.vPanel(spacing = 6)
    panel.div(tr("Ein Titelbild können Sie nach dem Anlegen hinzufügen.")) { addCssClasses("text-muted small") }
    val fields = buildEventFormFields(panel, prefill = null, rooms = rooms)
    onRoomSelect(fields.roomSelect)
    val recurrenceEditor = renderRecurrenceEditor(panel, fields.startsAtInput, fields.endsAtInput)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val createButton = buttonRow.actionButton(ActionIcon.ADD, tr("Veranstaltung anlegen"), style = ButtonStyle.PRIMARY)
    buttonRow.add(collapseCancelButton(collapse))
    createButton.onClick {
        val input = readEventForm(fields, errorBox, existingStartsAt = null) ?: return@onClick
        if (recurrenceEditor.isEnabled) {
            // The release belongs to ONE event: an occurrence of a series is released one by one after it was created.
            if (fields.capture.changed()) {
                errorBox.content =
                    tr("Die Freigabe der Aufzeichnung gilt nicht für Terminserien. Geben Sie die einzelnen Termine nach dem Anlegen frei.")
                errorBox.show()
                return@onClick
            }
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
                    collapse(true)
                    onCreated()
                }
            }
        } else {
            createButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IEventService>().createEvent(input) }
                createButton.disabled = false
                if (result != null) {
                    // V1.9.156: the release follows the event (own call); a refusal has its own toast and leaves the event saved.
                    applyEventCaptureRelease(eventId = result.id, refs = fields.capture)
                    notifySuccess(gettext("Veranstaltung \"%1\" wurde angelegt.", input.title))
                    collapse(true)
                    onCreated()
                }
            }
        }
    }
    return FormSnapshot { fields.snapshotValues() + recurrenceEditor.stateFingerprint() }
}

private fun EventFormFieldRefs.snapshotValues(): List<String> =
    listOf(
        titleInput.value,
        descriptionInput.value,
        locationTextInput.value,
        online.onlineUrlInput.value,
        online.kind().name,
        if (capture.wanted()) "1" else "0",
        online.encounterSpaceId(),
        startsAtInput.value,
        endsAtInput.value,
        registrationClosesAtInput.value,
        capacityInput.value,
        feeAmountInput.value,
        visibilitySelect.value,
        roomSelect.value,
        summaryInput.value,
        coverImageAltInput?.value,
        if (online.onlineUrlPublicCheck.value) "1" else "0",
    ).map { it.orEmpty() }

internal fun renderEventEditForm(
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
                        applyEventCaptureRelease(eventId = event.id, refs = fields.capture)
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
                applyEventCaptureRelease(eventId = event.id, refs = fields.capture)
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
internal fun renderEventCoverCard(
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

/**
 * V1.9.113 -- the moderator's list of the people who joined an event's conference WITHOUT an account (identity `evg-...`), read from
 * LiveKit on demand, each with "Entfernen". Shown only for a published Lapis conference whose window is open now; a person with an
 * account is removed in the conference itself. The names are untrusted (they were typed by the guest).
 */
internal fun renderEventGuestsPanel(
    parent: SimplePanel,
    event: EventDto,
) {
    if (event.onlineKind != EventOnlineKind.LAPIS_CONFERENCE) return
    val state = eventOnlineButtonState(event, organizationNow())
    if (state != EventOnlineButtonState.Open) return
    val panel = parent.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    panel.div(tr("Gäste ohne Konto")) { addCssClasses("fw-bold small") }
    val list = panel.vPanel(spacing = 2)

    fun refresh() {
        list.removeAll()
        AppScope.launch {
            val guests = guarded { rpcService<IEventService>().listEventGuests(event.id) } ?: return@launch
            list.removeAll()
            if (guests.isEmpty()) {
                list.div(tr("Derzeit sind keine Gäste ohne Konto im Raum.")) { addCssClasses("text-muted small") }
                return@launch
            }
            guests.forEach { guest ->
                val row = list.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                row.untrustedDiv(guest.displayName, className = "flex-grow-1")
                val remove = row.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
                remove.onClick {
                    runGuardedAction(remove) {
                        guarded { rpcService<IEventService>().removeEventGuest(event.id, guest.identity) }
                        refresh()
                    }
                }
            }
        }
    }
    panel.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY).onClick { refresh() }
    refresh()
}
