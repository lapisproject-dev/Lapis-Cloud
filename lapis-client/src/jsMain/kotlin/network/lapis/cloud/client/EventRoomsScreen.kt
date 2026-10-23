package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomInput
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.rpc.IEventRoomService

/**
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- room master-data CRUD (create/edit/
 * (de)activate). The list+creation-form shape mirrors `CostCentersScreen.kt`'s own "Screen 4 of 5"
 * precedent; the per-row "Bearbeiten" toggle -> pre-filled inline edit form -> Save/Cancel
 * ([renderEventRoomEditForm]) instead mirrors `CrmContactsScreen.renderCrmContactEditForm`'s own
 * idiom (review MAJOR fix -- `IEventRoomService.updateRoom` was fully implemented server-side but
 * had no client-side entry point at all, so a room could never be renamed/corrected without direct
 * DB access). Route `/event-rooms`, gated BOARD/ADMIN in `Routing.kt` -- room master data is
 * management/back-office data, never member-public (same tier as `CrmContactsScreen`/
 * `ApiKeysScreen`).
 *
 * Room-to-event ASSIGNMENT is deliberately not done here -- there is no dedicated event-detail
 * admin screen in this client yet (see `39-events.kuml.kts`/`IEventRoomService` KDoc "Scope"), so
 * the assignment affordance lives in `EventCheckInSelectionScreen.kt` instead, as a pragmatic
 * substitute for the missing detail page.
 */
fun renderEventRoomsScreen(container: SimplePanel) {
    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Räume"), intro = PageIntros.forRoute(Routes.EVENT_ROOMS))
    val createHost = root.vPanel(spacing = 6) // V1.9.47 (R36B): collapsed create form, directly under the header
    val editHost = root.vPanel(spacing = 6) // V1.9.194: the edit form of one room, above the table (the table itself stays a table)

    // ---- List (Räume-Übersicht) -----------------------------------------------------------
    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.lapisToolbar()
    val includeInactiveCheck = filterRow.checkBox(value = true, label = tr("Inaktive Räume anzeigen"))
    val refreshButton = filterRow.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    // W5 (R34): loading / error with retry / empty are distinct states of one `dataSection`.
    lateinit var roomsSection: DataSection

    fun refreshList() = roomsSection.reload()
    roomsSection =
        root.dataSection<List<EventRoomDto>>(
            emptyText = tr("Noch keine Räume angelegt."),
            // Audit fix: with "Inaktive Räume anzeigen" OFF an empty list only means "no ACTIVE room" -- inactive ones may exist. That is the
            // filtered-empty state, not "Noch keine Räume angelegt." (a claim that there are no rooms at all).
            filterTerm = { if (includeInactiveCheck.value) null else "aktiv" },
            noMatchText = { _ -> gettext("Keine aktiven Räume. Blenden Sie inaktive Räume ein, um alle Räume zu sehen.") },
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<IEventRoomService>().listRooms(includeInactive = includeInactiveCheck.value) } },
            render = { panel, rooms -> renderEventRoomTable(panel, rooms, editHost, ::refreshList) },
        )
    refreshButton.onClick { refreshList() }
    refreshList()

    collapsibleCreateForm<Unit>(
        actionSlot = header.actionSlot,
        formHost = createHost,
        buttonLabel = tr("Neuer Raum"),
        formId = "lapis-create-event-room",
    ) { _, close -> renderEventRoomCreationForm(this, close, ::refreshList) }
}

// ============================================================================================
// List row + creation form
// ============================================================================================

/**
 * V1.9.194: the rooms as a TABLE (name, status, capacity, equipment) with the row actions as icons (edit, (de)activate), the pattern of
 * Gremien/Sitzungen/Anträge. Every icon carries the room's name in its tooltip and accessible name. "Deaktivieren" keeps its
 * confirmation dialog; "Bearbeiten" opens the pre-filled edit form above the table.
 */
internal fun renderEventRoomTable(
    panel: SimplePanel,
    rooms: List<EventRoomDto>,
    editHost: SimplePanel,
    onChanged: () -> Unit,
) {
    panel.dataTable(
        columns =
            listOf(
                textColumn<EventRoomDto>(title = tr("Name"), primary = true, cssClasses = "fw-bold") { it.name },
                DataColumn<EventRoomDto>(title = tr("Status")) { cell, room -> cell.eventRoomStatusBadge(room.status) },
                textColumn<EventRoomDto>(title = tr("Kapazität"), numeric = true) { it.capacity?.toString().orEmpty() },
                textColumn<EventRoomDto>(title = tr("Ausstattung"), cssClasses = "text-muted") { it.equipmentTags.joinToString(", ") },
            ),
        rows = rooms,
        actions = { actions, room ->
            val name = sanitizeUntrustedI18nText(room.name)
            val group = actions.tableActionGroup()
            group.tableActionButton(ActionIcon.EDIT, gettext("Bearbeiten: %1", name), ButtonStyle.OUTLINEPRIMARY).onClick {
                editHost.removeAll()
                renderEventRoomEditForm(
                    editHost,
                    room,
                    onSaved = {
                        editHost.removeAll()
                        onChanged()
                    },
                    onCancel = { editHost.removeAll() },
                )
            }
            if (room.status == EventRoomStatus.ACTIVE) {
                group.tableActionButton(ActionIcon.REVOKE, gettext("Deaktivieren: %1", name), ButtonStyle.OUTLINEDANGER).onClick {
                    confirmDialog(
                        title = tr("Raum deaktivieren"),
                        message =
                            gettext(
                                "\"%1\" wirklich deaktivieren? Der Raum steht dann für neue Zuordnungen nicht mehr zur Verfügung.",
                                room.name,
                            ),
                        confirmLabel = tr("Deaktivieren"),
                    ) {
                        AppScope.launch {
                            val result = guarded { rpcService<IEventRoomService>().deactivateRoom(room.id) }
                            if (result != null) {
                                notifyInfo(tr("Raum wurde deaktiviert."))
                                onChanged()
                            }
                        }
                    }
                }
            } else {
                group.tableActionButton(ActionIcon.APPROVE, gettext("Aktivieren: %1", name), ButtonStyle.OUTLINESUCCESS).onClick {
                    AppScope.launch {
                        val result = guarded { rpcService<IEventRoomService>().activateRoom(room.id) }
                        if (result != null) {
                            notifySuccess(tr("Raum wurde aktiviert."))
                            onChanged()
                        }
                    }
                }
            }
        },
    )
}

/**
 * Room-edit form -- toggled from [renderEventRoomTable]'s "Bearbeiten" button, pre-filled from the
 * currently displayed [EventRoomDto]. Calls [IEventRoomService.updateRoom], which was fully
 * implemented server-side but had no client-side entry point at all (review MAJOR finding "keine
 * Bearbeiten-UI trotz fertigem `updateRoom`") -- structurally the same "toggle button next to the
 * read view -> pre-filled form -> Save/Cancel" idiom `CrmContactsScreen.renderCrmContactEditForm`
 * already establishes.
 */
private fun renderEventRoomEditForm(
    panel: SimplePanel,
    room: EventRoomDto,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val nameInput = panel.text(label = tr("Name")).apply { value = room.name }
    val capacityInput = panel.text(label = tr("Kapazität (optional)")).apply { value = room.capacity?.toString() }
    val tagsInput = panel.text(label = tr("Ausstattung (kommagetrennt, optional)")).apply { value = room.equipmentTags.joinToString(", ") }
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
        val name = nameInput.value.orEmpty().trim()
        val capacityText = capacityInput.value.orEmpty().trim()
        val tags =
            tagsInput.value
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

        if (!Validation.isNonBlank(name)) {
            errorBox.content = tr("Bitte einen Namen angeben.")
            errorBox.show()
            return@onClick
        }
        val capacity = capacityText.takeIf { it.isNotBlank() }?.toIntOrNull()
        if (capacityText.isNotBlank() && (capacity == null || capacity <= 0)) {
            errorBox.content = tr("Die Kapazität muss eine positive ganze Zahl sein.")
            errorBox.show()
            return@onClick
        }

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IEventRoomService>().updateRoom(
                        id = room.id,
                        input = EventRoomInput(name = name, capacity = capacity, equipmentTags = tags),
                    )
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Raum \"%1\" wurde aktualisiert.", name))
                onSaved()
            }
        }
    }
    cancelButton.onClick { onCancel() }
}

private fun renderEventRoomCreationForm(
    root: SimplePanel,
    collapse: (saved: Boolean) -> Unit,
    onCreated: () -> Unit,
): FormSnapshot {
    val panel = root.vPanel(spacing = 6)
    val nameInput = panel.text(label = tr("Name"))
    val capacityInput = panel.text(label = tr("Kapazität (optional)"))
    val tagsInput = panel.text(label = tr("Ausstattung (kommagetrennt, optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val buttonRow = panel.hPanel(spacing = 8)
    val createButton = buttonRow.actionButton(ActionIcon.ADD, tr("Raum anlegen"), style = ButtonStyle.PRIMARY)
    buttonRow.add(collapseCancelButton(collapse))
    createButton.onClick {
        errorBox.hide()
        val name = nameInput.value.orEmpty().trim()
        val capacityText = capacityInput.value.orEmpty().trim()
        val tags =
            tagsInput.value
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

        if (!Validation.isNonBlank(name)) {
            errorBox.content = tr("Bitte einen Namen angeben.")
            errorBox.show()
            return@onClick
        }
        val capacity = capacityText.takeIf { it.isNotBlank() }?.toIntOrNull()
        if (capacityText.isNotBlank() && (capacity == null || capacity <= 0)) {
            errorBox.content = tr("Die Kapazität muss eine positive ganze Zahl sein.")
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IEventRoomService>().createRoom(
                        EventRoomInput(name = name, capacity = capacity, equipmentTags = tags),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Raum \"%1\" wurde angelegt.", name))
                collapse(true)
                onCreated()
            }
        }
    }
    return FormSnapshot { listOf(nameInput.value.orEmpty(), capacityInput.value.orEmpty(), tagsInput.value.orEmpty()) }
}

/**
 * Reuses [activeStatusBadge] (`StatusBadge.kt`) instead of hand-rolling a third badge-CSS-class
 * table for the same ACTIVE/INACTIVE distinction that helper already renders consistently across
 * every other deactivate-able entity in this client (review MINOR fix "erfindet einen Badge-Stil
 * neu").
 */
private fun Container.eventRoomStatusBadge(status: EventRoomStatus) {
    activeStatusBadge(status == EventRoomStatus.ACTIVE)
}
