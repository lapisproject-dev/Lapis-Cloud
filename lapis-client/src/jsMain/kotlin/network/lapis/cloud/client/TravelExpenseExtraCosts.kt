package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.Table
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/*
 * V1.9.191: the "Weitere Kosten" section of the travel-expense draft -- ONE list (Art | Angaben | Betrag | Aktionen), ONE
 * "Kosten hinzufügen" button that opens an inline form with a segmented choice of the cost kind, and editing in place (the kind of an
 * existing line is fixed). Client only: the wire format (`ITravelExpenseService.addLine/updateLine` with a `TravelExpenseLineInput`) is
 * unchanged, the line fields are the very same ones the five add buttons used to open.
 */

/** The raw (untrimmed, unvalidated) content of the fields of one cost kind -- what a member has typed so far. DOM-free, so testable. */
internal class LineDraftValues(
    val description: String? = null,
    val kilometers: String? = null,
    val passengers: String? = null,
    val firstNight: LocalDate? = null,
    val nights: String? = null,
    val amount: String? = null,
    val breakfast: Boolean? = null,
)

/**
 * The fields of the add/edit form of one cost line, built by [buildLineFields]. Only the fields the [kind] needs exist; [toInput] turns
 * them into the wire object (the former submit block, moved here unchanged), [snapshot] reads their raw text for a per-kind draft.
 */
internal class LineFormFields(
    val form: LapisForm,
    val kind: TravelExpenseLineKind,
    private val description: LapisField,
    private val kilometers: LapisField?,
    private val passengers: LapisField?,
    private val firstNight: LapisField?,
    private val nights: LapisField?,
    private val amount: LapisField?,
    private val breakfast: LapisField?,
) {
    fun toInput(): TravelExpenseLineInput {
        val trimmed = description.value.trim()
        return when (kind) {
            TravelExpenseLineKind.MILEAGE ->
                TravelExpenseLineInput(
                    kind = kind,
                    description = trimmed,
                    kilometers =
                        checkNotNull(kilometers)
                            .value
                            .trim()
                            .toInt()
                            .toDouble()
                            .toDecimal(),
                    passengerCount =
                        checkNotNull(passengers)
                            .value
                            .trim()
                            .ifEmpty { "0" }
                            .toInt(),
                )
            TravelExpenseLineKind.ACCOMMODATION ->
                TravelExpenseLineInput(
                    kind = kind,
                    description = trimmed,
                    dayDate = checkNotNull(checkNotNull(firstNight).localDateOrNull()),
                    nights = checkNotNull(nights).value.trim().toInt(),
                    breakfastIncluded = checkNotNull(breakfast).value == "true",
                    amountCents = travelExpenseParseCents(checkNotNull(amount).value),
                )
            else ->
                TravelExpenseLineInput(
                    kind = kind,
                    description = trimmed,
                    amountCents = travelExpenseParseCents(checkNotNull(amount).value),
                )
        }
    }

    /** Puts the cursor into the first field once the form is rendered (opening a form by keyboard must not leave the focus behind). */
    fun focusFirstField() {
        window.setTimeout({ description.focus() }, FOCUS_AFTER_PATCH_DELAY_MS)
    }

    fun snapshot(): LineDraftValues =
        LineDraftValues(
            description = description.value,
            kilometers = kilometers?.value,
            passengers = passengers?.value,
            firstNight = firstNight?.localDateOrNull(),
            nights = nights?.value,
            amount = amount?.value,
            breakfast = breakfast?.let { it.value == "true" },
        )
}

/**
 * Builds the fields of a cost line into [form] (Beschreibung plus, depending on [kind], Kilometer + Mitfahrer, Betrag, or Erste Nacht +
 * Nächte + Betrag + "mit Frühstück"). Values: [initial] (a draft of this kind) wins over [line] (the stored line being edited).
 * Money travels as whole cents; the server computes everything else.
 */
internal fun buildLineFields(
    form: LapisForm,
    draft: TravelExpenseReportDto,
    kind: TravelExpenseLineKind,
    line: TravelExpenseLineDto?,
    initial: LineDraftValues?,
): LineFormFields {
    val descriptionField =
        form.textField(
            label = if (kind == TravelExpenseLineKind.ACCOMMODATION) tr("Unterkunft (Hotel, Ort)") else tr("Beschreibung"),
            value = initial?.description ?: line?.description,
            required = true,
            requiredMessage = tr("Bitte eine Beschreibung angeben."),
            rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_DESCRIPTION_LENGTH) },
        )
    val kilometersField =
        if (kind == TravelExpenseLineKind.MILEAGE) {
            form.textField(
                label = tr("Kilometer"),
                value =
                    initial?.kilometers ?: line
                        ?.kilometers
                        ?.toDouble()
                        ?.toLong()
                        ?.toString(),
                required = true,
                hint = tr("Ganze Kilometer, 1 bis 5000."),
                rule = { FormRules.intInRange(it, 1, TravelExpenseAmountRules.MAX_KM_PER_LINE) },
            )
        } else {
            null
        }
    val passengersField =
        if (kind == TravelExpenseLineKind.MILEAGE) {
            form.textField(
                label = tr("Mitfahrer"),
                value = initial?.passengers ?: (line?.passengerCount ?: 0).toString(),
                hint = tr("Anzahl der Mitfahrenden, 0 bis 8."),
                rule = { FormRules.intInRange(it, 0, TravelExpenseAmountRules.MAX_PASSENGERS) },
            )
        } else {
            null
        }
    // V1.9.125: the carpool centre may know how many people rode along -- offered as a SUGGESTION under the field, never set by itself.
    if (passengersField != null) renderPassengerHint(host = form.panel, draft = draft, passengersField = passengersField)
    val firstNightField =
        if (kind == TravelExpenseLineKind.ACCOMMODATION) {
            form.localDateField(
                label = tr("Erste Nacht"),
                value = initial?.firstNight ?: line?.dayDate ?: draft.travelFrom,
                required = true,
                rule = { travelExpenseFirstNightRangeCheck(it, draft.travelFrom, draft.travelTo) },
            )
        } else {
            null
        }
    val nightsField =
        if (kind == TravelExpenseLineKind.ACCOMMODATION) {
            form.textField(
                label = tr("Nächte"),
                value = initial?.nights ?: line?.nights?.toString() ?: "1",
                required = true,
                rule = { FormRules.intInRange(it, 1, TravelExpenseAmountRules.MAX_NIGHTS) },
            )
        } else {
            null
        }
    val amountField =
        if (kind != TravelExpenseLineKind.MILEAGE) {
            form.textField(
                label = tr("Betrag"),
                value = initial?.amount ?: line?.amountCents?.let { travelExpenseCentsText(it) },
                required = true,
                hint = tr("Beispiel: 1234,56."),
                rule = { travelExpenseCentsAmountCheck(it) },
            )
        } else {
            null
        }
    val breakfastField =
        if (kind == TravelExpenseLineKind.ACCOMMODATION) {
            form.checkField(label = tr("mit Frühstück"), value = initial?.breakfast ?: (line?.breakfastIncluded == true))
        } else {
            null
        }
    if (kind == TravelExpenseLineKind.ACCOMMODATION && nightsField != null && firstNightField != null) {
        form.crossFieldRule(field = nightsField) {
            val first = firstNightField.localDateOrNull()
            val nights = nightsField.value.trim().toIntOrNull()
            if (first != null && nights != null && !travelExpenseAccommodationFits(first, nights, draft.travelFrom, draft.travelTo)) {
                FieldCheck.Invalid(gettext("Die Übernachtungen müssen innerhalb der Reise liegen."))
            } else {
                FieldCheck.Ok
            }
        }
    }
    return LineFormFields(
        form = form,
        kind = kind,
        description = descriptionField,
        kilometers = kilometersField,
        passengers = passengersField,
        firstNight = firstNightField,
        nights = nightsField,
        amount = amountField,
        breakfast = breakfastField,
    )
}

/**
 * A complete add/edit form in [host]: the fields of [buildLineFields], a primary button ([primaryLabel]/[primaryIcon]) that sends
 * `addLine` ([line] == null) or `updateLine`, and -- when [onCancel] is given -- "Abbrechen" on the left. A fresh [LapisForm] per call, so
 * validation state and cross-field rules of another kind never survive a switch of the kind.
 */
internal fun renderLineFormBody(
    host: Container,
    draft: TravelExpenseReportDto,
    kind: TravelExpenseLineKind,
    line: TravelExpenseLineDto?,
    initial: LineDraftValues?,
    primaryLabel: String,
    primaryIcon: ActionIcon,
    onCancel: (() -> Unit)?,
    onSaved: () -> Unit,
    receiptPicker: Boolean = false,
): LineFormFields {
    val form = host.lapisForm()
    val fields = buildLineFields(form, draft, kind, line, initial)
    // V1.9.198: receipts can be chosen right in the add form (receipted kinds only; mileage keeps its optional map proof on the row).
    val picker = if (receiptPicker && line == null && kind.requiresReceipt) ReceiptPicker(form.panel) else null
    val uploadStatus = if (picker != null) UploadStatusRegion(form.panel) else null
    val saveButton = newActionButton(primaryIcon, primaryLabel, ButtonStyle.PRIMARY)
    val cancelButton = onCancel?.let { newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY) }
    form.buttons(primary = saveButton, cancel = cancelButton)
    if (onCancel != null) cancelButton?.onClick { onCancel() }
    saveButton.onClick {
        form.submit(saveButton) {
            val input = fields.toInput()
            val files = picker?.selected.orEmpty()
            val result =
                guarded {
                    if (line == null) {
                        rpcService<ITravelExpenseService>().addLine(draft.id, input)
                    } else {
                        rpcService<ITravelExpenseService>().updateLine(line.id, input)
                    }
                }
            if (result != null) {
                if (line == null && files.isNotEmpty()) {
                    val uploaded = uploadReceiptsOfNewLine(draft, result, files, uploadStatus)
                    if (uploaded == files.size) {
                        notifySuccess(
                            ngettext(
                                "Zeile hinzugefügt, %1 Beleg hochgeladen.",
                                "Zeile hinzugefügt, %1 Belege hochgeladen.",
                                uploaded,
                                uploaded,
                            ),
                        )
                    } else {
                        notifySuccess(tr("Zeile hinzugefügt."))
                        notifyError(tr("Nicht alle Belege wurden hochgeladen."))
                    }
                } else {
                    notifySuccess(if (line == null) tr("Zeile hinzugefügt.") else tr("Zeile gespeichert."))
                }
                onSaved()
            }
        }
    }
    return fields
}

/** Key of [pendingFailedReceiptUploads] for files whose line id could not be determined unambiguously. */
internal const val UNKNOWN_LINE_KEY = "?"

/** The unidentifiable-line key of one report, so that leftovers of a submitted report never show up in a later draft. */
internal fun unknownLineKey(reportId: String): String = "$UNKNOWN_LINE_KEY:$reportId"

/**
 * Uploads [files] one after the other to the line that [after] contains in addition to [before] and reports each file in [status].
 * Never guesses: if the new line cannot be identified (zero or several new receipt-capable lines, e.g. a second open tab), nothing is
 * uploaded and every file is kept in [pendingFailedReceiptUploads] under [UNKNOWN_LINE_KEY]. Returns the number of uploaded files.
 */
internal suspend fun uploadReceiptsOfNewLine(
    before: TravelExpenseReportDto,
    after: TravelExpenseReportDto,
    files: List<org.w3c.files.File>,
    status: UploadStatusRegion?,
): Int {
    val knownIds = before.lines.map { it.id }.toSet()
    val newIds = after.lines.filter { it.id !in knownIds && it.kind != TravelExpenseLineKind.DAY_ALLOWANCE }.map { it.id }
    val lineId = newIds.singleOrNull()
    if (lineId == null) {
        pendingFailedReceiptUploads.getOrPut(unknownLineKey(before.id)) { mutableListOf() }.addAll(files)
        files.forEach { status?.set(it.name, gettext("Fehlgeschlagen: %1", gettext("Hochladen fehlgeschlagen.")), it) }
        return 0
    }
    var uploaded = 0
    files.forEach { file ->
        status?.set(file.name, tr("Wird hochgeladen…"), file)
        when (val outcome = TravelExpenseHttp.uploadReceiptOutcome(lineId, file)) {
            is TravelExpenseUploadOutcome.Created -> {
                uploaded++
                status?.set(file.name, tr("Hochgeladen"), file)
            }
            is TravelExpenseUploadOutcome.Refused -> {
                pendingFailedReceiptUploads.getOrPut(lineId) { mutableListOf() }.add(file)
                status?.set(file.name, gettext("Fehlgeschlagen: %1", travelExpenseUploadRefusalText(outcome.status, file.name)), file)
            }
        }
    }
    return uploaded
}

/**
 * The stand-alone form for ONE kind (add or edit), as before V1.9.191 -- kept for callers that mount a single form directly
 * (`CarpoolPassengerHintDomTest`). The draft editor itself uses [renderExtraCostsSection].
 */
internal fun renderLineForm(
    panel: SimplePanel,
    draft: TravelExpenseReportDto,
    kind: TravelExpenseLineKind,
    line: TravelExpenseLineDto?,
    @Suppress("UNUSED_PARAMETER") rates: TravelExpenseRatesDto,
    onChanged: () -> Unit,
) {
    panel.removeAll()
    val wrapper = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    renderLineFormBody(
        host = wrapper,
        draft = draft,
        kind = kind,
        line = line,
        initial = null,
        primaryLabel = if (line == null) tr("Hinzufügen") else tr("Speichern"),
        primaryIcon = if (line == null) ActionIcon.ADD else ActionIcon.SAVE,
        onCancel = null,
        onSaved = onChanged,
    )
}

/** The second line of a cost row ("120 km × 0,30/km, 1 Mitfahrer", "2 Nächte ab ..."), or `null` when the description says it all. */
internal fun travelExpenseLineDetailText(line: TravelExpenseLineDto): String? =
    when (line.kind) {
        TravelExpenseLineKind.MILEAGE ->
            gettext(
                "%1 km × %2/km, %3 Mitfahrer",
                line.kilometers?.let { formatPlainAmountIn(I18n.language, it) } ?: "?",
                line.rateSnapshot?.let { formatMoney(it) } ?: "?",
                line.passengerCount ?: 0,
            )
        TravelExpenseLineKind.PER_DIEM -> gettext("%1 Tage × %2", line.days, line.rateSnapshot?.let { formatMoney(it) } ?: "?")
        TravelExpenseLineKind.ACCOMMODATION ->
            gettext(
                "%1 Nächte ab %2%3",
                line.nights ?: 0,
                line.dayDate?.let { localeDateString(it) } ?: "?",
                if (line.breakfastIncluded == true) gettext(", mit Frühstück") else "",
            )
        else -> null
    }

/** What gets the keyboard focus once the screen has been rebuilt after a save, a removal or a cancel (the screen reloads on `onChanged`). */
internal sealed interface ExtraCostsFocus {
    data object AddButton : ExtraCostsFocus

    data class EditIcon(
        val lineId: String,
    ) : ExtraCostsFocus
}

/** Set before `onChanged()`, consumed (once) by the next [renderExtraCostsSection]. Module-wide because the whole editor is rebuilt in between. */
internal var pendingExtraCostsFocus: ExtraCostsFocus? = null

private fun focusSoon(widget: Widget) {
    window.setTimeout({ widget.getElement()?.focus() }, FOCUS_AFTER_PATCH_DELAY_MS)
}

/** Whether [line] has a receipt block: mandatory for the receipted kinds, an optional map proof for mileage. Day lines never carry one. */
private fun TravelExpenseLineDto.hasReceiptBlock(): Boolean = kind.requiresReceipt || kind == TravelExpenseLineKind.MILEAGE

private const val EXTRA_COST_COLUMNS = 4

/**
 * The "Weitere Kosten" section. At most one form is open at a time (add OR edit); the other edit icons are disabled meanwhile. The
 * list shows legacy lines (`PER_DIEM`, `RECEIPTED`) too -- removable, but without an edit icon (their kind cannot be added any more).
 */
internal fun renderExtraCostsSection(
    main: SimplePanel,
    draft: TravelExpenseReportDto,
    rates: TravelExpenseRatesDto,
    onChanged: () -> Unit,
    missingReceiptLineId: String? = null,
    missingReceiptHintId: String? = null,
) {
    main.h2(tr("Weitere Kosten")) { addCssClass("h6") }
    val host = main.vPanel(spacing = 8)
    val lines = draft.lines.filter { it.kind != TravelExpenseLineKind.DAY_ALLOWANCE }
    var adding = false
    var editingLineId: String? = null
    var render: () -> Unit = {}
    // Forget failed uploads of lines that no longer exist (the entry of an unidentifiable line stays until it is discarded).
    pendingFailedReceiptUploads.keys.retainAll(lines.map { it.id }.toSet() + unknownLineKey(draft.id))
    var pendingFocus = pendingExtraCostsFocus
    pendingExtraCostsFocus = null

    fun finish(focus: ExtraCostsFocus) {
        pendingExtraCostsFocus = focus
        onChanged()
    }

    fun renderRow(
        table: Table,
        line: TravelExpenseLineDto,
    ) {
        val kindLabel = travelExpenseLineKindLabel(line.kind)
        val resolvedKindLabel = resolvedAttributeText(kindLabel)
        val busy = adding || editingLineId != null
        table.row {
            textCell(trusted(kindLabel))
            cell {
                untrustedDiv(line.description, className = "text-truncate") { title = sanitizeUntrustedI18nText(line.description) }
                travelExpenseLineDetailText(line)?.let { untrustedDiv(it, className = "text-muted small") }
            }
            numCell { moneySpan(line.amount) }
            cell {
                val actions = tableActionGroup()
                if (line.kind in TRAVEL_EXPENSE_ADDABLE_KINDS) {
                    val editButton = actions.tableActionButton(ActionIcon.EDIT, gettext("%1 bearbeiten", resolvedKindLabel))
                    editButton.disabled = busy
                    if (pendingFocus == ExtraCostsFocus.EditIcon(line.id)) {
                        focusSoon(editButton)
                        pendingFocus = null
                    }
                    editButton.onClick {
                        editingLineId = line.id
                        render()
                    }
                }
                val removeButton =
                    actions.tableActionButton(ActionIcon.REMOVE, gettext("%1 entfernen", resolvedKindLabel), ButtonStyle.OUTLINEDANGER)
                removeButton.disabled = editingLineId == line.id
                removeButton.onClick {
                    runGuardedAction(removeButton) {
                        val result = guarded { rpcService<ITravelExpenseService>().removeLine(line.id) }
                        if (result != null) finish(ExtraCostsFocus.AddButton)
                    }
                }
            }
        }
        if (editingLineId == line.id) {
            table.row {
                cell {
                    setAttribute("colspan", EXTRA_COST_COLUMNS.toString())
                    val editPanel = vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
                    editPanel.h3(gettext("%1 bearbeiten", resolvedKindLabel)) { addCssClasses("h6 mb-0") }
                    val fields =
                        renderLineFormBody(
                            host = editPanel,
                            draft = draft,
                            kind = line.kind,
                            line = line,
                            initial = null,
                            primaryLabel = tr("Speichern"),
                            primaryIcon = ActionIcon.SAVE,
                            onCancel = {
                                editingLineId = null
                                pendingFocus = ExtraCostsFocus.EditIcon(line.id)
                                render()
                            },
                            onSaved = { finish(ExtraCostsFocus.EditIcon(line.id)) },
                        )
                    fields.focusFirstField()
                }
            }
        }
        if (line.hasReceiptBlock()) {
            table.row {
                cell {
                    setAttribute("colspan", EXTRA_COST_COLUMNS.toString())
                    renderLineReceipts(
                        this,
                        line,
                        onChanged,
                        describedById =
                            if (line.id ==
                                missingReceiptLineId
                            ) {
                                missingReceiptHintId
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }

    fun renderAddForm(container: Container) {
        val available = TRAVEL_EXPENSE_ADDABLE_KINDS.filter { travelExpenseLineKindEnabled(it, rates) }
        val addPanel = container.vPanel(spacing = 8) { addCssClasses("border rounded p-2") }
        addPanel.h3(tr("Kosten hinzufügen")) { addCssClasses("h6 mb-0") }
        var kind = available.first()
        val drafts = mutableMapOf<TravelExpenseLineKind, LineDraftValues>()
        var fields: LineFormFields? = null
        var rebuild: () -> Unit = {}
        val group =
            addPanel.segmentedRadioGroup(
                options =
                    TRAVEL_EXPENSE_ADDABLE_KINDS.map { option ->
                        val enabled = travelExpenseLineKindEnabled(option, rates)
                        SegmentOption(
                            value = option,
                            label = travelExpenseLineKindLabel(option),
                            enabled = enabled,
                            disabledHint = if (enabled) null else tr("Kilometersatz ist noch nicht konfiguriert."),
                        )
                    },
                selected = kind,
                ariaLabel = tr("Kostenart"),
            ) { chosen ->
                // The typed text of the kind being left is kept; the form of the new kind is built from scratch.
                fields?.let { drafts[kind] = it.snapshot() }
                kind = chosen
                rebuild()
            }
        val formHost = addPanel.vPanel(spacing = 4)
        rebuild = {
            formHost.removeAll()
            fields =
                renderLineFormBody(
                    host = formHost,
                    draft = draft,
                    kind = kind,
                    line = null,
                    initial = drafts[kind],
                    primaryLabel = tr("Hinzufügen"),
                    primaryIcon = ActionIcon.ADD,
                    onCancel = {
                        adding = false
                        pendingFocus = ExtraCostsFocus.AddButton
                        render()
                    },
                    onSaved = { finish(ExtraCostsFocus.AddButton) },
                    receiptPicker = true,
                )
        }
        rebuild()
        group.focusSelectedSegment()
    }

    render = {
        host.removeAll()
        if (lines.isEmpty()) {
            host.p(tr("Noch keine weiteren Kosten erfasst.")) { addCssClasses("text-muted small") }
        } else {
            val table = host.standardTable(emptyList())
            table.addCssClass("lapis-te-extra-table")
            table.addHeaderCell(plainHeaderCell(TableHeader(tr("Art"))))
            table.addHeaderCell(plainHeaderCell(TableHeader(tr("Angaben"))))
            table.addHeaderCell(plainHeaderCell(TableHeader(tr("Betrag"), numeric = true)))
            table.addHeaderCell(HeaderCell(content = tr("Aktionen"), scope = Scope.COL) { addCssClass("visually-hidden") })
            lines.forEach { renderRow(table, it) }
        }
        renderFailedReceiptUploads(host, unknownLineKey(draft.id), onChanged)
        if (adding) {
            renderAddForm(host)
        } else if (TRAVEL_EXPENSE_ADDABLE_KINDS.any { travelExpenseLineKindEnabled(it, rates) }) {
            val addButton = host.actionButton(ActionIcon.ADD, tr("Kosten hinzufügen"), ButtonStyle.OUTLINEPRIMARY, small = true)
            addButton.disabled = editingLineId != null
            if (pendingFocus == ExtraCostsFocus.AddButton) {
                focusSoon(addButton)
                pendingFocus = null
            }
            addButton.onClick {
                adding = true
                render()
            }
        }
    }
    render()
}

/**
 * Receipts of one line: the list (download link, size, remove) and the upload form. Mandatory for the receipted kinds, an optional map
 * proof for mileage. Receipt bytes travel over a dedicated HTTP route (see `TravelExpenseHttp`), not Kilua RPC.
 */
internal fun renderLineReceipts(
    host: Container,
    line: TravelExpenseLineDto,
    onChanged: () -> Unit,
    describedById: String? = null,
) {
    val block = host.vPanel(spacing = 4)
    if (describedById != null) {
        // The block that holds the file chooser of the first affected line: marked for the focus jump, and described by the hint.
        block.setAttribute("data-lapis-te-first-missing", "true")
        block.setAttribute("role", "group")
        block.setAttribute("aria-describedby", describedById)
    }
    renderFailedReceiptUploads(block, line.id, onChanged)
    line.receipts.forEach { receipt ->
        val receiptRow = block.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        receiptRow.icon(receiptIcon(receipt.mimeType))
        receiptRow.untrustedLink(
            receipt.originalFilename,
            url = TravelExpenseHttp.receiptDownloadUrl(receipt.id),
            target = "_blank",
        )
        receiptRow.div(receiptSizeLabel(receipt.sizeBytes)) { addCssClasses("text-muted small") }
        val deleteReceiptButton = receiptRow.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
        deleteReceiptButton.onClick {
            runGuardedAction(deleteReceiptButton) {
                val error = TravelExpenseHttp.deleteReceipt(receipt.id)
                if (error != null) notifyError(error) else onChanged()
            }
        }
    }
    // The raw Upload control registered via `register` (pattern `DocumentsScreen.kt`'s `renderVersionUpload`).
    val uploadLabel =
        if (line.kind == TravelExpenseLineKind.MILEAGE) {
            tr("Kartennachweis hochladen (optional)")
        } else {
            tr("Beleg hochladen")
        }
    val uploadForm = block.lapisForm()
    val fileUpload = uploadForm.panel.upload(label = uploadLabel)

    fun selectedNativeFile() = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) }
    val fileField =
        uploadForm.register(
            fileUpload,
            label = uploadLabel,
            required = true,
            requiredMessage = tr("Bitte eine Datei auswählen."),
        )
    val uploadButton = newActionButton(ActionIcon.UPLOAD, tr("Hochladen"), ButtonStyle.PRIMARY)
    uploadForm.buttons(primary = uploadButton)
    uploadButton.onClick {
        uploadForm.submit(uploadButton) {
            val nativeFile = selectedNativeFile() ?: return@submit
            val error = TravelExpenseHttp.uploadReceipt(line.id, nativeFile)
            if (error != null) {
                uploadForm.showFormError(error)
            } else {
                notifySuccess(tr("Beleg hochgeladen."))
                fileField.reset()
                onChanged()
            }
        }
    }
}

/**
 * V1.9.198: receipt files of this line that were chosen in the add form but could not be uploaded -- kept in memory ONLY (a reload loses
 * them; the missing-receipt hint then still tells the member what to do). "Erneut hochladen" re-sends the very same file, "Verwerfen"
 * forgets it. Both carry the file name in their accessible name.
 */
internal fun renderFailedReceiptUploads(
    host: Container,
    lineId: String,
    onChanged: () -> Unit,
) {
    val failed = pendingFailedReceiptUploads[lineId]?.toList().orEmpty()
    if (failed.isEmpty()) return
    val block = host.vPanel(spacing = 2) { addCssClasses("lapis-te-failed-uploads") }
    block.div(tr("Nicht hochgeladen:"), className = "fw-bold small")
    failed.forEach { file ->
        val row = block.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        row.untrustedDiv(file.name, className = "small")
        val name = sanitizeUntrustedI18nText(file.name)
        if (!lineId.startsWith("$UNKNOWN_LINE_KEY:")) {
            val retry = row.actionButton(ActionIcon.UPLOAD, tr("Erneut hochladen"), style = ButtonStyle.OUTLINEPRIMARY, small = true)
            retry.setAttribute("aria-label", resolvedAttributeText(gettext("„%1“ erneut hochladen", name)))
            retry.onClick {
                runGuardedAction(retry) {
                    when (val outcome = TravelExpenseHttp.uploadReceiptOutcome(lineId, file)) {
                        is TravelExpenseUploadOutcome.Created -> {
                            pendingFailedReceiptUploads[lineId]?.remove(file)
                            if (pendingFailedReceiptUploads[lineId].isNullOrEmpty()) pendingFailedReceiptUploads.remove(lineId)
                            notifySuccess(tr("Beleg hochgeladen."))
                            onChanged()
                        }
                        is TravelExpenseUploadOutcome.Refused -> notifyError(travelExpenseUploadRefusalText(outcome.status, file.name))
                    }
                }
            }
        }
        val discard = row.actionButton(ActionIcon.REMOVE, tr("Verwerfen"), style = ButtonStyle.OUTLINEDANGER, small = true)
        discard.setAttribute("aria-label", resolvedAttributeText(gettext("„%1“ verwerfen", name)))
        discard.onClick {
            pendingFailedReceiptUploads[lineId]?.remove(file)
            if (pendingFailedReceiptUploads[lineId].isNullOrEmpty()) pendingFailedReceiptUploads.remove(lineId)
            onChanged()
        }
    }
}
