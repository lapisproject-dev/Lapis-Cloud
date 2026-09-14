package network.lapis.cloud.client

import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CateringOrderDto
import network.lapis.cloud.shared.domain.CateringOrderInput
import network.lapis.cloud.shared.domain.CateringOrderStatus
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.rpc.ICateringService
import network.lapis.cloud.shared.rpc.IEventService

/**
 * Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- aggregierte, freie
 * Bestellpositionen-CRUD pro Event. List+creation-form shape mirrors `EventRoomsScreen.kt`'s own
 * precedent (Screen "Übersicht" + "Neuen ... anlegen"); the per-row "Bearbeiten" toggle ->
 * pre-filled inline edit form -> Save/Cancel mirrors `renderEventRoomEditForm`'s idiom. Route
 * `/catering`, gated BOARD/ADMIN in `Routing.kt` -- Catering-Planung ist Management/back-office
 * data, niemals member-public (same tier as `EventRoomsScreen`/`CrmContactsScreen`).
 *
 * KEINE Personendaten: [CateringOrderInput.allergenNotes] ist eine reine
 * Bestellpositions-Eigenschaft, niemals einer identifizierbaren Person zugeordnet -- siehe
 * [CateringOrderInput] KDoc für die volle Art.-9-DSGVO-Begründung, warum diese Welle bewusst nicht
 * `EventRegistration` um eine pro-Person-Erfassung erweitert.
 */
fun renderCateringScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1(tr("Catering"))

    root.h2(tr("Veranstaltung"))
    val eventSelectRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val eventSelect = eventSelectRow.select(options = emptyList(), label = tr("Veranstaltung"))

    root.h2(tr("Übersicht"))
    val listPanel = root.vPanel(spacing = 6)

    root.h2(tr("Neue Bestellposition anlegen"))
    val creationFormHolder = root.vPanel(spacing = 6)

    fun refreshList() {
        val eventId = eventSelect.value
        listPanel.removeAll()
        if (eventId == null) return
        AppScope.launch {
            val orders =
                guarded {
                    rpcService<ICateringService>().listCateringOrders(eventId)
                } ?: return@launch
            if (orders.isEmpty()) {
                listPanel.p(tr("Noch keine Bestellpositionen für diese Veranstaltung."))
                return@launch
            }
            orders.forEach { order ->
                renderCateringOrderRow(listPanel, order, ::refreshList)
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
            renderCateringOrderCreationForm(creationFormHolder, eventSelect, ::refreshList)
        }
    }

    eventSelect.subscribe { refreshList() }

    loadEvents()
}

// ============================================================================================
// List row + creation/edit form
// ============================================================================================

private fun renderCateringOrderRow(
    panel: SimplePanel,
    order: CateringOrderDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val displayHolder = row.vPanel(spacing = 4)
    val editFormHolder = row.vPanel(spacing = 6) { hide() }

    fun renderDisplay() {
        displayHolder.removeAll()
        val headerRow = displayHolder.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        headerRow.div(order.description) { addCssClasses("flex-grow-1 fw-bold") }
        headerRow.cateringOrderStatusBadge(order.status)

        displayHolder.div(gettext("Menge: %1", order.quantity.toString())) { addCssClasses("text-muted small") }
        if (!order.allergenNotes.isNullOrBlank()) {
            displayHolder.div(gettext("Allergene/Hinweise: %1", order.allergenNotes)) { addCssClasses("text-muted small") }
        }

        val actionRow = displayHolder.hPanel(spacing = 8)
        val editButton = actionRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
        editButton.onClick {
            displayHolder.hide()
            editFormHolder.removeAll()
            renderCateringOrderEditForm(
                editFormHolder,
                order,
                onSaved = onChanged,
                onCancel = {
                    editFormHolder.removeAll()
                    editFormHolder.hide()
                    displayHolder.show()
                },
            )
            editFormHolder.show()
        }

        val statusSelect =
            actionRow.select(
                options = CateringOrderStatus.entries.map { it.name to it.cateringOrderStatusLabel() },
                value = order.status.name,
            )
        statusSelect.subscribe { newStatus ->
            val status = newStatus?.let { runCatching { CateringOrderStatus.valueOf(it) }.getOrNull() } ?: return@subscribe
            if (status == order.status) return@subscribe
            AppScope.launch {
                val result = guarded { rpcService<ICateringService>().setCateringOrderStatus(order.id, status) }
                if (result != null) {
                    notifySuccess(tr("Status wurde aktualisiert."))
                    onChanged()
                }
            }
        }

        val deleteButton = actionRow.button(tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
        deleteButton.onClick {
            confirmDialog(
                title = tr("Bestellposition löschen"),
                message =
                    gettext(
                        "\"%1\" wirklich endgültig löschen? Dies kann nicht rückgängig gemacht werden.",
                        order.description,
                    ),
                confirmLabel = tr("Löschen"),
            ) {
                AppScope.launch {
                    val result = guarded { rpcService<ICateringService>().deleteCateringOrder(order.id) }
                    if (result != null) {
                        notifyInfo(tr("Bestellposition wurde gelöscht."))
                        onChanged()
                    }
                }
            }
        }
    }
    renderDisplay()
}

/**
 * Bestellpositions-Bearbeitungsformular -- toggled from [renderCateringOrderRow]'s "Bearbeiten"
 * button, pre-filled from the currently displayed [CateringOrderDto]. Mirrors
 * `EventRoomsScreen.renderEventRoomEditForm`'s idiom exactly.
 */
private fun renderCateringOrderEditForm(
    panel: SimplePanel,
    order: CateringOrderDto,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val descriptionInput = panel.text(label = tr("Beschreibung")).apply { value = order.description }
    val quantityInput = panel.text(label = tr("Menge")).apply { value = order.quantity.toString() }
    val allergenNotesInput = panel.text(label = tr("Allergene/Hinweise (optional)")).apply { value = order.allergenNotes }
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
        val description = descriptionInput.value.orEmpty().trim()
        val quantityText = quantityInput.value.orEmpty().trim()
        val allergenNotes = allergenNotesInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (!Validation.isNonBlank(description)) {
            errorBox.content = tr("Bitte eine Beschreibung angeben.")
            errorBox.show()
            return@onClick
        }
        val quantity = quantityText.toIntOrNull()
        if (quantity == null || quantity <= 0) {
            errorBox.content = tr("Die Menge muss eine positive ganze Zahl sein.")
            errorBox.show()
            return@onClick
        }

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ICateringService>().updateCateringOrder(
                        id = order.id,
                        input =
                            CateringOrderInput(
                                eventId = order.eventId,
                                description = description,
                                quantity = quantity,
                                allergenNotes = allergenNotes,
                            ),
                    )
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Bestellposition \"%1\" wurde aktualisiert.", description))
                onSaved()
            }
        }
    }
    cancelButton.onClick { onCancel() }
}

private fun renderCateringOrderCreationForm(
    root: SimplePanel,
    eventSelect: Select,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val descriptionInput = panel.text(label = tr("Beschreibung"))
    val quantityInput = panel.text(label = tr("Menge"))
    val allergenNotesInput = panel.text(label = tr("Allergene/Hinweise (optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button(tr("Position anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val eventId = eventSelect.value
        val description = descriptionInput.value.orEmpty().trim()
        val quantityText = quantityInput.value.orEmpty().trim()
        val allergenNotes = allergenNotesInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (eventId.isNullOrBlank()) {
            errorBox.content = tr("Bitte eine Veranstaltung auswählen.")
            errorBox.show()
            return@onClick
        }
        if (!Validation.isNonBlank(description)) {
            errorBox.content = tr("Bitte eine Beschreibung angeben.")
            errorBox.show()
            return@onClick
        }
        val quantity = quantityText.toIntOrNull()
        if (quantity == null || quantity <= 0) {
            errorBox.content = tr("Die Menge muss eine positive ganze Zahl sein.")
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ICateringService>().createCateringOrder(
                        CateringOrderInput(
                            eventId = eventId,
                            description = description,
                            quantity = quantity,
                            allergenNotes = allergenNotes,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Bestellposition \"%1\" wurde angelegt.", description))
                descriptionInput.value = null
                quantityInput.value = null
                allergenNotesInput.value = null
                onCreated()
            }
        }
    }
}

private fun CateringOrderStatus.cateringOrderStatusLabel(): String =
    when (this) {
        CateringOrderStatus.PLANNED -> tr("Geplant")
        CateringOrderStatus.ORDERED -> tr("Bestellt")
        CateringOrderStatus.DELIVERED -> tr("Geliefert")
    }

/**
 * Reuses [statusBadge] (`StatusBadge.kt`) instead of hand-rolling a fourth badge-CSS-class table
 * for a THIRD lifecycle status this client renders -- same "one shared grammar" posture
 * `EventRoomsScreen.eventRoomStatusBadge` already establishes for the two-value ACTIVE/INACTIVE
 * case.
 */
private fun SimplePanel.cateringOrderStatusBadge(status: CateringOrderStatus) {
    val color =
        when (status) {
            CateringOrderStatus.PLANNED -> "secondary"
            CateringOrderStatus.ORDERED -> "info"
            CateringOrderStatus.DELIVERED -> "success"
        }
    statusBadge(status.cateringOrderStatusLabel(), color)
}
