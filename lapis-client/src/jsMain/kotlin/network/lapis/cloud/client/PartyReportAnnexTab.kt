package network.lapis.cloud.client

import io.kvision.core.Container
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
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.parseGermanAmount
import org.w3c.dom.HTMLElement

private class AnnexLoad(
    val annex: PartgAnnexDto,
    val findings: List<PartgFindingDto>,
)

/** The element that gets the focus once the next render of the tab is done (the row that was just saved, or the add button). */
private var pendingFocusId: String? = null

private const val FOCUS_RETRY_MS = 120

/** Counts the open donor forms: a late focus call of a form that is closed (or replaced) by now must do nothing. */
private var formGeneration = 0

private fun focusElement(id: String) {
    window.setTimeout({ (document.getElementById(id) as? HTMLElement)?.focus() }, 0)
}

/** `inputmode` belongs on the real `<input>`, not on the wrapper of the KVision control. */
internal fun io.kvision.form.text.AbstractText.inputMode(mode: String) {
    (input as? io.kvision.core.Widget)?.setAttribute("inputmode", mode)
}

private fun deductionLabel(deduction: PartgDeduction): String =
    when (deduction) {
        PartgDeduction.UNASSIGNABLE -> tr("Nicht zuordenbare Spenden")
        PartgDeduction.CASH_OVER_1000 -> tr("Bar-Spenden über 1.000 €")
        PartgDeduction.OVER_3300 -> tr("Spenden über 3.300 € ohne Ausweis")
        PartgDeduction.WRONGLY_REPORTED -> tr("Fälschlich gemeldete Spenden")
    }

private fun amountRule(text: String): FieldCheck =
    if (text.isBlank()) {
        FieldCheck.Ok
    } else {
        when (val parsed = parseGermanAmount(text)) {
            is AmountParse.Ok -> FieldCheck.Ok
            is AmountParse.Error -> FieldCheck.Invalid(resolvedAttributeText(partgAmountErrorText(parsed.reason)))
        }
    }

private fun centsOf(text: String): Long? = (parseGermanAmount(text) as? AmountParse.Ok)?.cents.takeIf { text.isNotBlank() }

private fun memberCountRule(text: String): FieldCheck =
    if (text.isBlank() || text.trim().toIntOrNull()?.let { it in 0..PartgAnnexRules.MEMBER_COUNT_MAX } == true) {
        FieldCheck.Ok
    } else {
        FieldCheck.Invalid(gettext("Bitte eine ganze Zahl eingeben."))
    }

/**
 * V1.9.269 "Rechenschaftsbericht R3" -- the tab "Ausweise": the deductions (A), the member count (E) and the list of persons with more than
 * 10,000 EUR in a year (B) of one unit and year. Only the board and admin reach it; the server checks that on every call. Empty means "not
 * entered", 0 is a value. The member count suggestion is a plain button that fills the field and saves nothing.
 */
internal fun SimplePanel.renderPartyReportAnnex(context: PartyReportContext) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    dataSection<List<PartgUnitDto>>(
        isEmpty = { false },
        load = { guarded { partyReportRpc().listUnits() } },
        render = { body, units -> body.renderAnnexShell(context, units) },
    ).reload()
}

private fun SimplePanel.renderAnnexShell(
    context: PartyReportContext,
    units: List<PartgUnitDto>,
) {
    val chosen = context.unit?.let { u -> units.firstOrNull { it.unitKey == u.unitKey } } ?: units.first()
    context.unit = chosen
    val toolbar = lapisToolbar()
    val unitSelect = toolbar.select(options = units.map { it.unitKey to unitLabel(it) }, value = chosen.unitKey, label = tr("Gliederung"))
    val host = simplePanel()

    fun load() {
        host.removeAll()
        val unit = context.unit ?: return
        host.renderAnnex(context, unit)
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
            confirmDiscardInputs {
                unitSelect.value = key
                proceed()
            }
        } else {
            proceed()
        }
    }
    load()
}

private fun SimplePanel.renderAnnex(
    context: PartyReportContext,
    unit: PartgUnitDto,
) {
    context.hasUnsavedChanges = { false }
    lateinit var section: DataSection
    section =
        dataSection<AnnexLoad>(
            isEmpty = { false },
            load = {
                guarded {
                    val rpc = partyReportRpc()
                    val annex = rpc.getAnnex(context.year, unit.unitKey)
                    // the hints are an extra: without them the lists still work
                    AnnexLoad(annex = annex, findings = runCatching { rpc.reportFindings(context.year) }.getOrDefault(emptyList()))
                }
            },
            render = { body, loaded -> body.renderAnnexBody(context, loaded) { section.reload() } },
        )
    section.reload()
}

private fun SimplePanel.renderAnnexBody(
    context: PartyReportContext,
    loaded: AnnexLoad,
    reloadUnchecked: () -> Unit,
) {
    val annex = loaded.annex
    val unit = annex.unit
    var donorFormOpen = false
    // every save bumps the server revision; the tab stays rendered when the user keeps editing, so it must follow along
    var currentAnnex = annex
    var currentRevision = annex.revision
    var renderDonors: () -> Unit = {}
    lateinit var figureFields: List<LapisField>
    var initialFigures: List<String> = emptyList()
    context.hasUnsavedChanges = { donorFormOpen || (figureFields.map { it.value } != initialFigures) }
    // a reload after one section was saved must not silently drop the other section's unsaved input
    val reload = { if (context.hasUnsavedChanges()) confirmDiscardInputs(reloadUnchecked) else reloadUnchecked() }

    div(unitLabel(unit), className = "fw-semibold")

    // ---- A and E
    div(tr("A: Abzüge bei der Spendenbehandlung"), className = "fw-semibold mt-2")
    val figures = lapisForm()
    figures.panel.div(tr("Leer bedeutet nicht erfasst, 0 ist ein Wert."), className = "small text-muted")
    val deductionFields =
        PartgDeduction.entries.map { d ->
            figures.textField(
                label = deductionLabel(d),
                value = annex.deductions[d]?.let(::partgInputText),
                rule = ::amountRule,
                init = { it.inputMode("decimal") },
            )
        }
    figures.panel.div(tr("Mitgliederzahl zum 31.12."), className = "fw-semibold mt-2")
    val memberField =
        figures.textField(
            label = tr("Mitgliederzahl zum 31.12."),
            value = annex.memberCount?.toString(),
            rule = ::memberCountRule,
            init = { it.inputMode("numeric") },
        )
    val suggestion = annex.memberCountSuggestion
    if (suggestion != null) {
        figures.panel.div(className = "small d-flex flex-wrap align-items-center gap-2") {
            setAttribute("data-partg-suggestion", "1")
            span(
                gettext("Heute im Mitgliederregister: %1 (Stand %2, nicht der 31.12.)", suggestion.count, partgGermanDate(suggestion.asOf)),
            )
            actionButton(ActionIcon.ADD, tr("Vorschlag übernehmen"), small = true) {
                onClick { memberField.setValue(suggestion.count.toString()) }
            }
        }
    } else if (unit.level == network.lapis.cloud.shared.domain.PartgUnitLevel.SUBORDINATE) {
        figures.panel.div(
            tr("Für nachgeordnete Gebietsverbände kennt das Mitgliederregister keine Zahl."),
            className = "small text-muted",
        )
    }
    figureFields = deductionFields + memberField
    initialFigures = figureFields.map { it.value }
    val saveFigures = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    val discardFigures = newActionButton(ActionIcon.UNDO, tr("Verwerfen"))
    figures.buttons(primary = saveFigures, cancel = discardFigures)
    discardFigures.onClick { figureFields.forEachIndexed { i, f -> f.setValue(initialFigures[i]) } }
    saveFigures.onClick {
        figures.submit(saveFigures) {
            val deductions = PartgDeduction.entries.zip(deductionFields).associate { (d, f) -> d to centsOf(f.value) }
            val members =
                memberField.value
                    .trim()
                    .ifEmpty { null }
                    ?.toIntOrNull()
            PartgAnnexRules.validateFigures(deductions, members)?.let {
                figures.showFormError(partgAnnexViolationText(it))
                return@submit
            }
            val result = guarded { partyReportRpc().saveAnnexFigures(annex.year, unit.unitKey, currentRevision, deductions, members) }
            if (result != null) {
                currentRevision = result.revision
                currentAnnex = result
                // the saved values are the new baseline, so later edits are still detected as unsaved
                initialFigures = figureFields.map { it.value }
                renderDonors()
                notifySuccess(tr("Gespeichert."))
                reload()
            } else {
                figures.showFormError(saveFailedText())
            }
        }
    }

    // ---- B
    div(tr("B: Spenden und Beiträge über 10.000 € je Person und Jahr"), className = "fw-semibold mt-3")
    div(
        tr(
            "Nur Vorstand und Admin sehen diese Liste. Aufzunehmen sind Personen mit mehr als 10.000 € pro Jahr an diese Gliederung. " +
                "Beträge an mehrere Gebietsverbände werden noch nicht zusammengeführt.",
        ),
        className = "small text-muted",
    )
    val formHost = simplePanel()
    val donorHost = simplePanel()

    fun closeDonorForm(focusId: String) {
        formGeneration++
        formHost.removeAll()
        donorFormOpen = false
        focusElement(focusId)
    }

    fun openDonorForm(existing: PartgLargeDonorDto?) {
        val open = {
            formGeneration++
            formHost.removeAll()
            donorFormOpen = true
            formHost.renderDonorForm(
                annex = currentAnnex,
                revision = { currentRevision },
                existing = existing,
                onCancel = { closeDonorForm(existing?.let { "partg-donor-edit-${it.id}" } ?: "partg-donor-add") },
                onSaved = { saved ->
                    currentRevision = saved.revision
                    currentAnnex = saved
                    renderDonors()
                    formGeneration++
                    formHost.removeAll()
                    donorFormOpen = false
                    pendingFocusId = existing?.let { "partg-donor-edit-${it.id}" } ?: "partg-donor-add"
                    reload()
                },
            )
        }
        if (donorFormOpen) confirmDiscardInputs(open) else open()
    }

    val unitFindings = loaded.findings.filter { it.unitKey == unit.unitKey && it.rowNumbers.isNotEmpty() }
    // the table follows the latest server answer, so it stays correct when a reload is declined after a save
    renderDonors = {
        donorHost.removeAll()
        if (currentAnnex.donors.isEmpty()) {
            donorHost.div(tr("Noch keine Einträge."), className = "small text-muted")
        } else {
            donorHost.renderDonorTable(
                currentAnnex,
                unitFindings,
                revision = { currentRevision },
                onEdit = ::openDonorForm,
                onDeleted = {
                    currentRevision = it.revision
                    currentAnnex = it
                    renderDonors()
                    reload()
                },
            )
        }
    }
    renderDonors()
    add(donorHost)
    actionGroup {
        actionButton(ActionIcon.ADD, tr("Zeile hinzufügen")) {
            id = "partg-donor-add"
            onClick { openDonorForm(null) }
        }
        actionButton(ActionIcon.REFRESH, tr("Neu laden")) { onClick { reload() } }
    }
    add(formHost)
    pendingFocusId?.let { focusId ->
        pendingFocusId = null
        focusElement(focusId)
    }
}

private fun saveFailedText(): String =
    gettext(
        "Das Speichern ist nicht gelungen, oder die Daten wurden inzwischen von jemand anderem geändert. " +
            "Mit „Neu laden“ sehen Sie den aktuellen Stand; Ihre Eingaben gehen dabei verloren.",
    )

private fun SimplePanel.renderDonorTable(
    annex: PartgAnnexDto,
    findings: List<PartgFindingDto>,
    revision: () -> Long,
    onEdit: (PartgLargeDonorDto) -> Unit,
    onDeleted: (PartgAnnexDto) -> Unit,
) {
    val table =
        standardTable(
            listOf(
                TableHeader(title = tr("Zeile"), numeric = true),
                TableHeader(title = tr("Name")),
                TableHeader(title = tr("Ort")),
                TableHeader(title = tr("Betrag"), numeric = true),
                TableHeader(title = tr("Hinweise")),
                TableHeader(title = tr("Aktionen")),
            ),
        )
    annex.donors.forEach { donor ->
        table.row {
            numCell(donor.rowNumber.toString())
            textCell(donor.name)
            textCell(donor.city.orEmpty())
            numCell { moneySpan(centsToDecimal(donor.amountCents)) }
            cell {
                findings.filter { donor.rowNumber in it.rowNumbers }.forEach { f ->
                    div(className = "small d-flex align-items-center gap-1") {
                        val check = f.level == network.lapis.cloud.shared.domain.PartgFindingLevel.CHECK
                        add(
                            Span(
                                className = (if (check) ActionIcon.WARN else ActionIcon.INFO).css,
                            ).also { it.setAttribute("aria-hidden", "true") },
                        )
                        span(partgFindingShortLabel(f))
                    }
                }
            }
            cell {
                addCssClass(TABLE_ACTIONS_CELL_CLASS)
                tableActionGroup().apply {
                    tableActionButton(
                        ActionIcon.EDIT,
                        tr("Bearbeiten"),
                    ).also { it.id = "partg-donor-edit-${donor.id}" }.onClick { onEdit(donor) }
                    tableActionButton(ActionIcon.DELETE, tr("Löschen")).onClick {
                        confirmDialog(
                            title = tr("Zeile löschen?"),
                            message = tr("Die Zeile wird aus der Liste entfernt. Das lässt sich nicht rückgängig machen."),
                            confirmLabel = tr("Löschen"),
                            onConfirm = { deleteDonor(annex, donor, revision, onDeleted) },
                        )
                    }
                }
            }
        }
    }
}

private fun deleteDonor(
    annex: PartgAnnexDto,
    donor: PartgLargeDonorDto,
    revision: () -> Long,
    onDeleted: (PartgAnnexDto) -> Unit,
) {
    AppScope.launch {
        val result = guarded { partyReportRpc().deleteLargeDonor(annex.year, annex.unit.unitKey, revision(), donor.id) }
        if (result != null) {
            pendingFocusId = "partg-donor-add"
            onDeleted(result)
        }
    }
}

private fun Container.renderDonorForm(
    annex: PartgAnnexDto,
    revision: () -> Long,
    existing: PartgLargeDonorDto?,
    onCancel: () -> Unit,
    onSaved: (PartgAnnexDto) -> Unit,
) {
    val form = lapisForm()
    form.panel.div(
        if (existing == null) tr("Neue Zeile") else gettext("Zeile %1 bearbeiten", existing.rowNumber),
        className = "fw-semibold",
    )
    val kindField =
        form.selectField(
            label = tr("Art der Person"),
            options = PartgPersonKind.entries.map { it.name to personKindLabel(it) },
            value = (existing?.personKind ?: PartgPersonKind.NATURAL).name,
        )
    val valid =
        PartgLargeDonorInputDto(null, PartgPersonKind.NATURAL, "x", null, null, null, "DE", PartgContributionKind.DONATION, 1_000_001L)

    fun fieldRule(change: PartgLargeDonorInputDto.(String) -> PartgLargeDonorInputDto): (String) -> FieldCheck =
        { text ->
            PartgAnnexRules.validateDonor(valid.change(text))?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) } ?: FieldCheck.Ok
        }
    val nameField =
        form.textField(
            label = tr("Name"),
            value = existing?.name,
            required = true,
            requiredMessage = tr("Bitte einen Namen angeben."),
            rule = fieldRule { copy(name = it) },
        )
    val streetField = form.textField(label = tr("Straße und Hausnummer"), value = existing?.street, rule = fieldRule { copy(street = it) })
    val postalField = form.textField(label = tr("Postleitzahl"), value = existing?.postalCode, rule = fieldRule { copy(postalCode = it) })
    val cityField = form.textField(label = tr("Ort"), value = existing?.city, rule = fieldRule { copy(city = it) })
    val countryField =
        form.textField(label = tr("Land (zwei Großbuchstaben)"), value = existing?.country ?: "DE", rule = fieldRule { copy(country = it) })
    val contributionField =
        form.selectField(
            label = tr("Art der Zuwendung"),
            options = PartgContributionKind.entries.map { it.name to contributionLabel(it) },
            value = (existing?.contributionKind ?: PartgContributionKind.DONATION).name,
        )
    val amountField =
        form.textField(
            label = tr("Betrag (€)"),
            value = existing?.amountCents?.let(::partgInputText),
            required = true,
            requiredMessage = tr("Bitte einen Betrag eingeben."),
            rule = { text ->
                when (val parsed = parseGermanAmount(text)) {
                    is AmountParse.Error -> FieldCheck.Invalid(resolvedAttributeText(partgAmountErrorText(parsed.reason)))
                    is AmountParse.Ok ->
                        PartgAnnexRules
                            .validateDonor(
                                valid.copy(amountCents = parsed.cents),
                            )?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) }
                            ?: FieldCheck.Ok
                }
            },
            init = { it.inputMode("decimal") },
        )
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"))
    form.buttons(primary = save, cancel = cancel)
    cancel.onClick { onCancel() }
    save.onClick {
        form.submit(save) {
            val input =
                PartgLargeDonorInputDto(
                    id = existing?.id,
                    personKind = PartgPersonKind.valueOf(kindField.value),
                    name = nameField.value,
                    street = streetField.value,
                    postalCode = postalField.value,
                    city = cityField.value,
                    country = countryField.value.trim(),
                    contributionKind = PartgContributionKind.valueOf(contributionField.value),
                    amountCents = centsOf(amountField.value) ?: 0L,
                )
            PartgAnnexRules.validateDonor(input)?.let {
                form.showFormError(partgAnnexViolationText(it))
                return@submit
            }
            val result = guarded { partyReportRpc().saveLargeDonor(annex.year, annex.unit.unitKey, revision(), input) }
            if (result != null) {
                notifySuccess(tr("Gespeichert."))
                onSaved(result)
            } else {
                form.showFormError(saveFailedText())
            }
        }
    }
    kindField.focusWhenInserted()
    // the insert hook alone is not reliable for a select that is added to a page which is already on screen: ask again a moment later,
    // but only while this very form is still the open one
    val mine = formGeneration
    window.setTimeout({ if (mine == formGeneration) kindField.focus() }, FOCUS_RETRY_MS)
}

private fun personKindLabel(kind: PartgPersonKind): String =
    if (kind == PartgPersonKind.LEGAL) tr("Juristische Person") else tr("Natürliche Person")

private fun contributionLabel(kind: PartgContributionKind): String =
    when (kind) {
        PartgContributionKind.DONATION -> tr("Spende")
        PartgContributionKind.FEE -> tr("Beitrag")
        PartgContributionKind.BOTH -> tr("Spende und Beitrag")
    }
