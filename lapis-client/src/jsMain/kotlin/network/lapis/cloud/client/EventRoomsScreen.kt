package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
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
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Räume"))

    // ---- List (Räume-Übersicht) -----------------------------------------------------------
    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeInactiveCheck = filterRow.checkBox(value = true, label = tr("Inaktive Räume anzeigen"))
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
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
            render = { panel, rooms ->
                val listPanel = panel.vPanel(spacing = 6)
                rooms.forEach { room -> renderEventRoomRow(listPanel, room, ::refreshList) }
            },
        )
    refreshButton.onClick { refreshList() }
    refreshList()

    root.h2(tr("Neuen Raum anlegen")) { addCssClass("h5") }
    renderEventRoomCreationForm(root, ::refreshList)
}

// ============================================================================================
// List row + creation form
// ============================================================================================

private fun renderEventRoomRow(
    panel: SimplePanel,
    room: EventRoomDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val displayHolder = row.vPanel(spacing = 4)
    val editFormHolder = row.vPanel(spacing = 6) { hide() }

    fun renderDisplay() {
        displayHolder.removeAll()
        val headerRow = displayHolder.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        headerRow.untrustedCardTitle(room.name)
        headerRow.eventRoomStatusBadge(room.status)

        if (room.capacity != null) {
            displayHolder.div(gettext("Kapazität: %1", room.capacity.toString())) { addCssClasses("text-muted small") }
        }
        if (room.equipmentTags.isNotEmpty()) {
            displayHolder.div(room.equipmentTags.joinToString(", ")) { addCssClasses("text-muted small") }
        }

        val actionRow = displayHolder.hPanel(spacing = 8)
        val editButton = actionRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
        editButton.onClick {
            displayHolder.hide()
            editFormHolder.removeAll()
            renderEventRoomEditForm(
                editFormHolder,
                room,
                onSaved = onChanged,
                onCancel = {
                    editFormHolder.removeAll()
                    editFormHolder.hide()
                    displayHolder.show()
                },
            )
            editFormHolder.show()
        }
        if (room.status == EventRoomStatus.ACTIVE) {
            val deactivateButton = actionRow.button(tr("Deaktivieren"), style = ButtonStyle.OUTLINEDANGER)
            deactivateButton.onClick {
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
            val activateButton = actionRow.button(tr("Aktivieren"), style = ButtonStyle.OUTLINESUCCESS)
            activateButton.onClick {
                AppScope.launch {
                    val result = guarded { rpcService<IEventRoomService>().activateRoom(room.id) }
                    if (result != null) {
                        notifySuccess(tr("Raum wurde aktiviert."))
                        onChanged()
                    }
                }
            }
        }
    }
    renderDisplay()
}

/**
 * Room-edit form -- toggled from [renderEventRoomRow]'s "Bearbeiten" button, pre-filled from the
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
    val saveButton = buttonRow.button(tr("Änderungen speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = buttonRow.button(tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)

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
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val nameInput = panel.text(label = tr("Name"))
    val capacityInput = panel.text(label = tr("Kapazität (optional)"))
    val tagsInput = panel.text(label = tr("Ausstattung (kommagetrennt, optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button(tr("Raum anlegen"), style = ButtonStyle.PRIMARY)
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
                nameInput.value = null
                capacityInput.value = null
                tagsInput.value = null
                onCreated()
            }
        }
    }
}

/**
 * Reuses [activeStatusBadge] (`StatusBadge.kt`) instead of hand-rolling a third badge-CSS-class
 * table for the same ACTIVE/INACTIVE distinction that helper already renders consistently across
 * every other deactivate-able entity in this client (review MINOR fix "erfindet einen Badge-Stil
 * neu").
 */
private fun SimplePanel.eventRoomStatusBadge(status: EventRoomStatus) {
    activeStatusBadge(status == EventRoomStatus.ACTIVE)
}
