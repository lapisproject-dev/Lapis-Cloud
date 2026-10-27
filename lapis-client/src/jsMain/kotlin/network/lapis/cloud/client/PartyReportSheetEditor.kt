package network.lapis.cloud.client

import io.kvision.core.onEvent
import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Input
import io.kvision.html.InputType
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgSheetDto
import network.lapis.cloud.shared.domain.PartgUnitDto

private class EditorSetup(
    val units: List<PartgUnitDto>,
    val positions: List<PartgPositionDto>,
)

/**
 * The entry tab: pick a unit, type amounts for the report year and the year before. Empty means "not entered", 0 is a value ("Leere Felder mit
 * 0 füllen"). The sums are computed live from the typed-in values with the same function the server uses ([PartgPositions.sums]). The year
 * before is stored in ITS OWN year (own revision); the save bar says how many changes are open.
 */
internal fun SimplePanel.renderPartyReportSheetEditor(context: PartyReportContext) {
    dataSection<EditorSetup>(
        isEmpty = { false },
        load = {
            guarded {
                EditorSetup(units = partyReportRpc().listUnits(), positions = partyReportRpc().listPositions())
            }
        },
        render = { body, setup -> body.renderEditorShell(context, setup) },
    ).reload()
}

private fun SimplePanel.renderEditorShell(
    context: PartyReportContext,
    setup: EditorSetup,
) {
    val unitOptions = setup.units.map { it.unitKey to unitLabel(it) }
    val chosen = context.unit?.let { u -> setup.units.firstOrNull { it.unitKey == u.unitKey } } ?: setup.units.first()
    context.unit = chosen
    val toolbar = lapisToolbar()
    val unitSelect = toolbar.select(options = unitOptions, value = chosen.unitKey, label = tr("Gliederung"))
    val host = simplePanel()

    fun load() {
        host.removeAll()
        val unit = context.unit ?: return
        host.renderSheet(context, unit, setup.positions)
    }
    unitSelect.subscribe {
        val key = unitSelect.value ?: return@subscribe
        if (key == context.unit?.unitKey) return@subscribe
        val proceed = {
            context.unit = setup.units.first { it.unitKey == key }
            load()
        }
        if (context.hasUnsavedChanges()) {
            unitSelect.value = context.unit?.unitKey
            confirmDialog(
                title = tr("Eingaben verwerfen?"),
                message = tr("Sie haben Werte eingegeben, die noch nicht gespeichert sind. Wenn Sie jetzt wechseln, gehen sie verloren."),
                confirmLabel = tr("Verwerfen"),
                cancelLabel = tr("Weiter bearbeiten"),
                onConfirm = {
                    unitSelect.value = key
                    proceed()
                },
            )
        } else {
            proceed()
        }
    }
    load()
}

private fun SimplePanel.renderSheet(
    context: PartyReportContext,
    unit: PartgUnitDto,
    positions: List<PartgPositionDto>,
) {
    context.hasUnsavedChanges = { false }
    lateinit var section: DataSection
    section =
        dataSection<PartgSheetDto>(
            isEmpty = { false },
            load = { guarded { partyReportRpc().getSheet(context.year, unit.unitKey) } },
            render = { body, sheet -> body.renderSheetTable(context, unit, positions, sheet) { section.reload() } },
        )
    section.reload()
}

private fun cellId(
    column: PartgColumn,
    code: String,
) = "partg-${column.name.lowercase()}-$code"

private fun SimplePanel.renderSheetTable(
    context: PartyReportContext,
    unit: PartgUnitDto,
    positions: List<PartgPositionDto>,
    sheet: PartgSheetDto,
    reload: () -> Unit,
) {
    val draft = PartgSheetDraft(year = sheet.year, saved = sheet.values, savedPrior = sheet.priorValues)
    val sumSpans = mutableMapOf<Pair<PartgColumn, String>, Span>()
    val errorBoxes = mutableMapOf<Pair<PartgColumn, String>, Div>()
    lateinit var saveBar: Div
    lateinit var countText: Span
    lateinit var saveButton: io.kvision.html.Button
    lateinit var discardButton: io.kvision.html.Button
    lateinit var failureBox: Div

    div(unitLabel(unit)) { addCssClasses("fw-semibold") }
    val zeroGroup = actionGroup()
    zeroGroup.apply {
        actionButton(ActionIcon.EDIT, tr("Leere Felder im Rechnungsjahr mit 0 füllen"), labelMode = ActionLabelMode.RESPONSIVE) {
            onClick {
                confirmDialog(
                    title = tr("Leere Felder mit 0 füllen?"),
                    message =
                        gettext(
                            "Alle leeren Felder des Rechnungsjahres %1 werden mit 0,00 € gefüllt. Das Vorjahr bleibt unverändert.",
                            sheet.year,
                        ),
                    confirmLabel = tr("Mit 0 füllen"),
                    confirmStyle = ButtonStyle.PRIMARY,
                    onConfirm = {
                        draft.fillEmptyWithZero()
                        refreshAll(draft, errorBoxes, sumSpans, countText, saveButton, discardButton, context)
                        PartgPositions.LEAF_CODES.forEach { code ->
                            (
                                kotlinx.browser.document.getElementById(
                                    cellId(PartgColumn.CURRENT, code),
                                ) as? org.w3c.dom.HTMLInputElement
                            )?.value =
                                draft.text(PartgColumn.CURRENT, code)
                        }
                    },
                )
            }
        }
    }

    val headers =
        listOf(
            TableHeader(title = tr("Nr.")),
            TableHeader(title = tr("Position")),
            TableHeader(title = gettext("Vorjahr %1", sheet.priorYear), numeric = true),
            TableHeader(title = gettext("Rechnungsjahr %1", sheet.year), numeric = true),
        )
    val table = standardTable(headers)
    val placeholder = resolvedAttributeText(tr("nicht erfasst"))
    positions.sortedBy { it.sortOrder }.forEach { position ->
        val isLeaf = position.kind == PartgKind.LEAF
        table.row {
            textCell(position.numberLabel)
            cell {
                cellText(partgLabel(position.code), if (isLeaf) null else "fw-semibold d-block").also { if (isLeaf) addCssClass("d-block") }
                cellText(position.legalRef, "small text-muted d-block")
                position.orgHint?.let { hint -> cellText(hint, "small fst-italic d-block") }
            }
            for (column in listOf(PartgColumn.PRIOR, PartgColumn.CURRENT)) {
                numCell {
                    if (isLeaf) {
                        val input = Input(type = InputType.TEXT, className = "form-control form-control-sm lapis-num")
                        input.value = draft.text(column, position.code)
                        input.id = cellId(column, position.code)
                        input.setAttribute("inputmode", "decimal")
                        input.setAttribute("autocomplete", "off")
                        input.setAttribute("placeholder", placeholder)
                        input.setAttribute(
                            "aria-label",
                            resolvedAttributeText(partgLabel(position.code)) + " – " +
                                (if (column == PartgColumn.PRIOR) sheet.priorYear else sheet.year),
                        )
                        add(input)
                        val error = div(className = "small text-danger")
                        errorBoxes[column to position.code] = error
                        input.onEvent {
                            this.input = {
                                // the DOM value, not the widget property: KVision syncs the property on change, the page needs every keystroke
                                val typed = (input.getElement() as? org.w3c.dom.HTMLInputElement)?.value ?: input.value.orEmpty()
                                draft.edit(column, position.code, typed)
                                refreshAll(draft, errorBoxes, sumSpans, countText, saveButton, discardButton, context)
                            }
                        }
                    } else {
                        sumSpans[column to position.code] = span("", className = "fw-semibold lapis-num")
                    }
                }
            }
        }
    }

    failureBox = div(className = "alert alert-warning") { hide() }
    saveBar = div(className = "lapis-partg-savebar")
    countText = saveBar.span("", className = "me-3")
    discardButton =
        saveBar.actionButton(ActionIcon.UNDO, tr("Verwerfen"), small = false) {
            onClick {
                draft.discard()
                reload()
            }
        }
    saveButton =
        saveBar.actionButton(ActionIcon.SAVE, tr("Speichern"), style = ButtonStyle.PRIMARY) {
            onClick {
                if (draft.hasInvalid) {
                    notifyError(tr("Bitte korrigieren Sie zuerst die markierten Felder."))
                    return@onClick
                }
                val changes = draft.changes()
                if (changes.isEmpty()) return@onClick
                disabled = true
                AppScope.launch {
                    val result =
                        guarded {
                            partyReportRpc().saveSheet(sheet.year, unit.unitKey, sheet.revision, sheet.priorRevision, changes)
                        }
                    if (result != null) {
                        context.hasUnsavedChanges = { false }
                        notifySuccess(gettext("%1 Werte gespeichert.", result.changed))
                        reload()
                    } else {
                        disabled = false
                        failureBox.content =
                            tr(
                                "Das Speichern ist nicht gelungen, oder das Blatt wurde inzwischen von jemand anderem geändert. " +
                                    "Mit \"Neu laden\" sehen Sie den aktuellen Stand; Ihre Eingaben gehen dabei verloren.",
                            )
                        failureBox.show()
                        failureBox.actionButton(ActionIcon.REFRESH, tr("Neu laden")) { onClick { reload() } }
                    }
                }
            }
        }
    refreshAll(draft, errorBoxes, sumSpans, countText, saveButton, discardButton, context)
}

private fun refreshAll(
    draft: PartgSheetDraft,
    errorBoxes: Map<Pair<PartgColumn, String>, Div>,
    sumSpans: Map<Pair<PartgColumn, String>, Span>,
    countText: Span,
    saveButton: io.kvision.html.Button,
    discardButton: io.kvision.html.Button,
    context: PartyReportContext,
) {
    for ((key, box) in errorBoxes) {
        val parsed = draft.parsed(key.first, key.second)
        val input = kotlinx.browser.document.getElementById(cellId(key.first, key.second))
        if (parsed is PartgCellValue.Invalid) {
            box.content = partgAmountErrorText(parsed.reason)
            input?.setAttribute("aria-invalid", "true")
        } else {
            box.content = ""
            input?.removeAttribute("aria-invalid")
        }
    }
    for (column in PartgColumn.entries) {
        val sums = PartgPositions.sums(draft.valuesFor(column))
        for ((key, span) in sumSpans) {
            if (key.first == column) span.content = formatMoney(centsToDecimal(sums.getValue(key.second)))
        }
    }
    val count = draft.changeCount
    countText.content = gettext("%1 Änderungen", count)
    saveButton.disabled = count == 0 || draft.hasInvalid
    discardButton.disabled = count == 0
    context.hasUnsavedChanges = { draft.changeCount > 0 }
}
