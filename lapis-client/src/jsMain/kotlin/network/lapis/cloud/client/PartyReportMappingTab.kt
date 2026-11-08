package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgAccountMappingRowDto
import network.lapis.cloud.shared.domain.PartgAccountMappingViewDto
import network.lapis.cloud.shared.domain.PartgUnitDto

private class MappingSetup(
    val units: List<PartgUnitDto>,
)

private val MAPPING_HEADERS =
    listOf(
        TableHeader(title = tr("Konto")),
        TableHeader(title = tr("Name")),
        TableHeader(title = tr("Saldo"), numeric = true),
        TableHeader(title = tr("Position")),
        TableHeader(title = tr("Herkunft")),
    )

/**
 * V1.9.289 (R4a) -- the tab "Kontenzuordnung": which ledger account feeds which position of the report. Federal roles edit it; a chapter treasurer
 * only reads it. The suggestions ("aus Parteien-Kontenplan") fill only accounts that are not mapped yet and mark nothing until they are ticked and
 * taken over; saving is a separate step. The balances shown belong to the chosen unit and year.
 */
internal fun SimplePanel.renderPartyReportMappingTab(context: PartyReportContext) {
    div(tr("Entwurf, kein amtliches Formular. Die Zuordnung ist eine Arbeitshilfe und ersetzt keine Prüfung durch Ihre Steuerberatung.")) {
        addCssClasses("text-muted small")
    }
    dataSection<MappingSetup>(
        isEmpty = { false },
        load = { guarded { MappingSetup(units = partyReportRpc().listUnits()) } },
        render = { body, setup -> body.renderMappingShell(context, setup) },
    ).reload()
}

private fun SimplePanel.renderMappingShell(
    context: PartyReportContext,
    setup: MappingSetup,
) {
    val units = setup.units.filter { it.level != network.lapis.cloud.shared.domain.PartgUnitLevel.SUBORDINATE }.ifEmpty { setup.units }
    val chosen = context.unit?.let { u -> units.firstOrNull { it.unitKey == u.unitKey } } ?: units.first()
    context.unit = chosen
    val toolbar = lapisToolbar()
    toolbar.span(tr("Gliederung"), className = "fw-semibold me-1")
    val unitSelect = toolbar.select(options = units.map { it.unitKey to unitLabel(it) }, value = chosen.unitKey)
    val unitSelectLabel = resolvedAttributeText(tr("Gliederung"))
    unitSelect.setAttribute("aria-label", unitSelectLabel)
    val host = simplePanel()

    fun load() {
        host.removeAll()
        val unit = context.unit ?: return
        host.renderMappingView(context, unit)
    }
    unitSelect.subscribe {
        val key = unitSelect.value ?: return@subscribe
        if (key == context.unit?.unitKey) return@subscribe
        val proceed = {
            context.unit = units.first { it.unitKey == key }
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

private fun SimplePanel.renderMappingView(
    context: PartyReportContext,
    unit: PartgUnitDto,
) {
    context.hasUnsavedChanges = { false }
    lateinit var section: DataSection
    section =
        dataSection<PartgAccountMappingViewDto>(
            emptyText = tr("Keine Konten vorhanden. Legen Sie zuerst den Kontenplan an."),
            isEmpty = { it.rows.isEmpty() },
            load = { guarded { partyReportRpc().getAccountMappings(context.year, unit.unitKey) } },
            render = { body, view -> body.renderMappingBody(context, unit, view) { section.reload() } },
        )
    section.reload()
}

private fun mappingChoiceValue(choice: MappingChoice?): String =
    when (choice) {
        null, MappingChoice.Cleared -> MAPPING_NONE
        MappingChoice.Excluded -> MAPPING_EXCLUDED
        is MappingChoice.Position -> choice.code
    }

private fun mappingChoiceText(choice: MappingChoice?): String =
    when (choice) {
        null, MappingChoice.Cleared -> tr("Nicht zugeordnet")
        MappingChoice.Excluded -> tr("Bewusst nicht im Bericht")
        is MappingChoice.Position -> partgPositionOptionLabel(choice.code)
    }

internal fun partgPositionOptionLabel(code: String): String {
    val number =
        network.lapis.cloud.shared.domain.PartgPositions
            .find(code)
            ?.numberLabel
    return listOfNotNull(number?.takeIf { it.isNotBlank() }, partgLabel(code)).joinToString(" ")
}

private fun SimplePanel.renderMappingBody(
    context: PartyReportContext,
    unit: PartgUnitDto,
    view: PartgAccountMappingViewDto,
    reload: () -> Unit,
) {
    val draft = PartgMappingDraft(view.rows)
    lateinit var dirtyBadge: Span
    lateinit var saveButton: io.kvision.html.Button
    lateinit var discardButton: io.kvision.html.Button
    lateinit var tablesHost: SimplePanel

    fun refreshState() {
        val dirty = draft.hasUnsaved
        context.hasUnsavedChanges = { draft.hasUnsaved }
        if (dirty) dirtyBadge.show() else dirtyBadge.hide()
        saveButton.disabled = !dirty
        discardButton.disabled = !dirty
    }

    fun repaintTables() {
        tablesHost.removeAll()
        tablesHost.renderMappingTables(view, draft) { refreshState() }
    }

    p(unitLabel(unit) + " · " + yearHint(view.year)) { addCssClasses("fw-semibold mb-1") }
    val unmapped = partgUnmappedRows(view.rows)
    div(
        gettext("Nicht zugeordnet: %1 Konten", view.unmappedCount) +
            if (view.unmappedWithActivityCount > 0) {
                " · " + gettext("davon %1 mit Buchungen im Jahr %2", view.unmappedWithActivityCount, view.year)
            } else {
                ""
            },
    ) {
        addCssClasses(if (view.unmappedWithActivityCount > 0) "text-warning-emphasis fw-semibold" else "text-muted")
        setAttribute("data-partg-unmapped", unmapped.size.toString())
    }
    if (!view.editable) {
        div(
            tr("Die Zuordnung der Konten pflegt der Vorstand. Sie sehen sie hier nur zur Information."),
        ) { addCssClasses("text-muted small") }
    }

    val bar = actionGroup()
    if (view.editable) {
        bar.apply {
            actionButton(ActionIcon.SEARCH, tr("Vorschläge anzeigen"), ButtonStyle.OUTLINEPRIMARY) {
                onClick {
                    val shown = draft.showSuggestions()
                    if (shown == 0) notifyInfo(tr("Es gibt keine Vorschläge für die offenen Konten.")) else repaintTables()
                }
            }
            actionButton(ActionIcon.APPROVE, tr("Alle Kontenplan-Vorschläge auswählen"), ButtonStyle.OUTLINESECONDARY) {
                onClick {
                    draft.tickAllChart()
                    repaintTables()
                }
            }
            actionButton(ActionIcon.APPROVE, tr("Ausgewählte übernehmen"), ButtonStyle.OUTLINEPRIMARY) {
                onClick {
                    val taken = draft.acceptTicked()
                    if (taken == 0) {
                        notifyInfo(tr("Es ist kein Vorschlag ausgewählt."))
                    } else {
                        repaintTables()
                        refreshState()
                    }
                }
            }
        }
    }
    tablesHost = simplePanel()
    val saveBar = div(className = "lapis-partg-savebar")
    dirtyBadge = saveBar.span(tr("Nicht gespeichert"), className = "badge text-bg-warning me-3")
    dirtyBadge.setAttribute("data-partg-dirty", "true")
    discardButton =
        saveBar.actionButton(ActionIcon.UNDO, tr("Verwerfen")) {
            onClick {
                draft.discard()
                repaintTables()
                refreshState()
            }
        }
    saveButton =
        saveBar.actionButton(ActionIcon.SAVE, tr("Zuordnung speichern"), style = ButtonStyle.PRIMARY) {
            onClick {
                val changes = draft.changes()
                if (changes.isEmpty()) return@onClick
                disabled = true
                AppScope.launch {
                    val saved = guarded { partyReportRpc().saveAccountMappings(view.year, unit.unitKey, changes) }
                    if (saved != null) {
                        context.hasUnsavedChanges = { false }
                        notifySuccess(gettext("%1 Zuordnungen gespeichert.", changes.size))
                        reload()
                    } else {
                        disabled = false
                    }
                }
            }
        }
    if (!view.editable) saveBar.hide()
    repaintTables()
    refreshState()
}

private fun SimplePanel.renderMappingTables(
    view: PartgAccountMappingViewDto,
    draft: PartgMappingDraft,
    onChanged: () -> Unit,
) {
    val unmapped = partgUnmappedRows(view.rows).filter { draft.effective(it.accountId) == null }
    val mapped = view.rows.filter { draft.effective(it.accountId) != null }
    if (draft.suggestionsShown) {
        div(tr("Vorschläge. Bitte prüfen, bevor Sie sie übernehmen.")) { addCssClasses("fw-semibold") }
    }
    if (unmapped.isNotEmpty()) {
        p(tr("Nicht zugeordnet")) { addCssClasses("fw-semibold mb-1 mt-2") }
        mappingTable(unmapped, view, draft, onChanged)
    }
    if (mapped.isNotEmpty()) {
        p(tr("Zugeordnet")) { addCssClasses("fw-semibold mb-1 mt-3") }
        mappingTable(mapped, view, draft, onChanged)
    }
}

private fun SimplePanel.mappingTable(
    rows: List<PartgAccountMappingRowDto>,
    view: PartgAccountMappingViewDto,
    draft: PartgMappingDraft,
    onChanged: () -> Unit,
) {
    val table = standardTable(MAPPING_HEADERS)
    rows.forEach { row ->
        table.row {
            textCell(row.number)
            textCell(row.name + if (row.active) "" else " (" + gettext("inaktiv") + ")")
            numCell {
                val balance = row.balanceCents
                if (balance == null) cellText("–", "text-muted") else moneySpan(centsToDecimal(balance), warnIfNegative = true)
            }
            cell {
                val suggestionShown = draft.isSuggestionShown(row.accountId)
                if (suggestionShown) {
                    addCssClass("lapis-partg-suggestion")
                    setAttribute("data-partg-suggestion", row.accountId)
                    val source = draft.suggestionSourceOf(row)
                    setAttribute("data-partg-suggestion-source", if (source == SuggestionSource.NAME) "name" else "chart")
                    val proposed = draft.suggestionOf(row)
                    cellText(mappingChoiceText(proposed))
                    if (source == SuggestionSource.NAME) {
                        cellText(
                            " · " + gettext("Vermutung nach Kontoname („%1“)", row.nameSuggestionKeyword.orEmpty()),
                            "small text-muted",
                        )
                    } else {
                        cellText(" · " + gettext("aus Parteien-Kontenplan"), "small text-muted")
                    }
                    val box = checkBox(value = draft.isTicked(row.accountId))
                    val pickLabel = resolvedAttributeText(tr("Vorschlag übernehmen")) + " " + row.number
                    box.setAttribute("aria-label", pickLabel)
                    box.setAttribute("data-partg-pick", row.accountId)
                    box.subscribe { checked -> draft.setTick(row.accountId, checked) }
                } else if (view.editable) {
                    val effective = draft.effective(row.accountId)
                    val options =
                        listOf(MAPPING_NONE to tr("Nicht zugeordnet")) +
                            partgMappingTargets(row.type).map { it.code to partgPositionOptionLabel(it.code) } +
                            (MAPPING_EXCLUDED to tr("Bewusst nicht im Bericht"))
                    val picker = select(options = options, value = mappingChoiceValue(effective))
                    val positionLabel = resolvedAttributeText(tr("Position für Konto")) + " " + row.number
                    picker.setAttribute("aria-label", positionLabel)
                    picker.subscribe {
                        val chosen = picker.value ?: return@subscribe
                        draft.choose(
                            row.accountId,
                            when (chosen) {
                                MAPPING_NONE -> MappingChoice.Cleared
                                MAPPING_EXCLUDED -> MappingChoice.Excluded
                                else -> MappingChoice.Position(chosen)
                            },
                        )
                        onChanged()
                    }
                } else {
                    cellText(mappingChoiceText(draft.effective(row.accountId)))
                }
            }
            textCell(
                when {
                    draft.isSuggestionShown(row.accountId) ->
                        if (draft.suggestionSourceOf(row) == SuggestionSource.NAME) tr("Vermutung") else tr("Vorschlag")
                    draft.effective(row.accountId) == null -> ""
                    row.positionCode != null || row.excluded ->
                        if (draft.hasUnsaved &&
                            draft.changes().any { it.accountId == row.accountId }
                        ) {
                            tr("geändert")
                        } else {
                            tr("gespeichert")
                        }
                    else -> tr("Entwurf")
                },
            )
        }
    }
}
