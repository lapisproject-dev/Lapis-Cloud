package network.lapis.cloud.client

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
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Catering"), intro = PageIntros.forRoute(Routes.CATERING))
    val createHost = root.vPanel(spacing = 6) // V1.9.47 (R36B): collapsed create form, directly under the header
    val editHost = root.vPanel(spacing = 6) // V1.9.194: the edit form of one order, above the table

    root.h2(tr("Veranstaltung")) { addCssClass("h5") }
    val eventSelectRow = root.lapisToolbar()
    val eventSelect = eventSelectRow.select(options = emptyList(), label = tr("Veranstaltung"))

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    // W5 (R34): the event list and the order list each have a real error state with retry; the order list is one `dataSection`.
    val eventsErrorHost = root.vPanel(spacing = 4)
    lateinit var ordersSection: DataSection

    // Audit fix M1: without a chosen event there is nothing to list -- see MyVolunteerShiftsScreen (no events / failed event list).
    fun refreshList() {
        if (eventSelect.value != null) ordersSection.reload()
    }
    ordersSection =
        root.dataSection<List<CateringOrderDto>>(
            emptyText = tr("Noch keine Bestellpositionen für diese Veranstaltung."),
            isEmpty = { it.isEmpty() },
            load = {
                // never reached without an event ([refreshList] guards it); a missing event is a failed load, not "no orders"
                val eventId = eventSelect.value
                if (eventId == null) null else guarded { rpcService<ICateringService>().listCateringOrders(eventId) }
            },
            render = { panel, orders -> renderCateringOrderTable(panel, orders, editHost, ::refreshList) },
        )

    // The create button exists only once at least one event loaded; a retry after an error must not build a second one.
    var createController: CollapsibleCreateFormController<Unit>? = null
    val titleById = mutableMapOf<String, String>()

    fun loadEvents() {
        eventsErrorHost.removeAll()
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IEventService>().listEvents(EventQuery(includePast = true, limit = 200))
                }
            if (page == null) {
                eventsErrorHost.dataErrorState(onRetry = ::loadEvents)
                return@launch
            }
            val options = untrustedOptions(page.rows.map { it.id to it.title })
            titleById.clear()
            page.rows.forEach { titleById[it.id] = it.title }
            eventSelect.options = options
            if (options.isEmpty()) {
                eventsErrorHost.p(tr("Es sind derzeit keine Veranstaltungen geplant.")) { addCssClasses("text-muted") }
                return@launch
            }
            eventSelect.value = options.first().first
            refreshList()
            if (createController == null) {
                createController =
                    collapsibleCreateForm<Unit>(
                        actionSlot = header.actionSlot,
                        formHost = createHost,
                        buttonLabel = tr("Neue Bestellposition"),
                        formId = "lapis-create-catering-order",
                        // The form belongs to the event chosen when it was opened: the picker is locked while it is open.
                        onOpenChange = { open -> eventSelect.disabled = open },
                    ) { _, close ->
                        val targetEventId = eventSelect.value.orEmpty()
                        renderCateringOrderCreationForm(this, targetEventId, titleById[targetEventId].orEmpty(), close, ::refreshList)
                    }
            }
        }
    }

    eventSelect.subscribe { refreshList() }

    loadEvents()
}

// ============================================================================================
// List row + creation/edit form
// ============================================================================================

/**
 * V1.9.194: the order positions as a TABLE (description, quantity, notes, status) with the row actions as icons (edit, delete). The
 * status stays a select in its column (a change is saved at once, as before). Every icon carries the position's name; "Löschen" keeps
 * its confirmation dialog; "Bearbeiten" opens the pre-filled edit form above the table.
 */
internal fun renderCateringOrderTable(
    panel: SimplePanel,
    orders: List<CateringOrderDto>,
    editHost: SimplePanel,
    onChanged: () -> Unit,
) {
    panel.dataTable(
        columns =
            listOf(
                textColumn<CateringOrderDto>(title = tr("Beschreibung"), primary = true, cssClasses = "fw-bold") { it.description },
                textColumn<CateringOrderDto>(title = tr("Menge"), numeric = true) { it.quantity.toString() },
                textColumn<CateringOrderDto>(title = tr("Allergene/Hinweise"), cssClasses = "text-muted") { it.allergenNotes.orEmpty() },
                DataColumn<CateringOrderDto>(title = tr("Status")) { cell, order ->
                    val statusSelect =
                        cell.select(
                            options = CateringOrderStatus.entries.map { it.name to it.cateringOrderStatusLabel() },
                            value = order.status.name,
                        )
                    (statusSelect.input as? io.kvision.core.Widget)?.setAttribute(
                        "aria-label",
                        gettext("Status: %1", sanitizeUntrustedI18nText(order.description)),
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
                },
            ),
        rows = orders,
        actions = { actions, order ->
            val name = sanitizeUntrustedI18nText(order.description)
            val group = actions.tableActionGroup()
            group.tableActionButton(ActionIcon.EDIT, gettext("Bearbeiten: %1", name), ButtonStyle.OUTLINEPRIMARY).onClick {
                editHost.removeAll()
                renderCateringOrderEditForm(
                    editHost,
                    order,
                    onSaved = {
                        editHost.removeAll()
                        onChanged()
                    },
                    onCancel = { editHost.removeAll() },
                )
            }
            group.tableActionButton(ActionIcon.DELETE, gettext("Löschen: %1", name), ButtonStyle.OUTLINEDANGER).onClick {
                confirmDialog(
                    title = tr("Bestellposition löschen"),
                    message =
                        gettext(
                            "\"%1\" wirklich endgültig löschen? Dies kann nicht rückgängig gemacht werden.",
                            order.description,
                        ),
                    confirmLabel = tr("Löschen"),
                    confirmIcon = ActionIcon.DELETE,
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
        },
    )
}

/**
 * Bestellpositions-Bearbeitungsformular -- toggled from [renderCateringOrderTable]'s "Bearbeiten"
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
    val saveButton = buttonRow.actionButton(ActionIcon.SAVE, tr("Änderungen speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = buttonRow.actionButton(ActionIcon.CANCEL, tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)

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
    val quantityInput = panel.text(label = tr("Menge"))
    val allergenNotesInput = panel.text(label = tr("Allergene/Hinweise (optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val buttonRow = panel.hPanel(spacing = 8)
    val createButton = buttonRow.actionButton(ActionIcon.ADD, tr("Position anlegen"), style = ButtonStyle.PRIMARY)
    buttonRow.add(collapseCancelButton(collapse))
    createButton.onClick {
        errorBox.hide()
        val eventId = targetEventId
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
                collapse(true)
                onCreated()
            }
        }
    }
    return FormSnapshot { listOf(descriptionInput.value.orEmpty(), quantityInput.value.orEmpty(), allergenNotesInput.value.orEmpty()) }
}

private fun CateringOrderStatus.cateringOrderStatusLabel(): String =
    when (this) {
        CateringOrderStatus.PLANNED -> tr("Geplant")
        CateringOrderStatus.ORDERED -> tr("Bestellt")
        CateringOrderStatus.DELIVERED -> tr("Geliefert")
    }
