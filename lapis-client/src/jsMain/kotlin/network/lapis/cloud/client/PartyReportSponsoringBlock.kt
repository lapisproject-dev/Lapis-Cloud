package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgSponsorDto
import network.lapis.cloud.shared.domain.PartgSponsorInputDto
import network.lapis.cloud.shared.domain.PartgSponsorQualification
import network.lapis.cloud.shared.domain.PartgSponsoringEntryInputDto
import network.lapis.cloud.shared.domain.PartgStatementRules
import network.lapis.cloud.shared.domain.parseGermanAmount

/** What the blocks C and F need from the tab: the latest server answer, the revision, the one-form guard and the hand-back after a save. */
internal class StatementEnv(
    val annex: () -> PartgAnnexDto,
    val revision: () -> Long,
    val apply: (PartgAnnexDto) -> Unit,
    val reload: (AnnexBlock) -> Unit,
    val guard: AnnexEditorGuard,
    val findings: List<PartgFindingDto>,
    /** V1.9.287 (R3c): switches to the tab "Erläuterungen" (the block D points there for holdings). */
    val openNotes: () -> Unit = {},
)

private const val STATUS_DEBOUNCE_MS = 400

private fun qualificationText(q: PartgSponsorQualification): String =
    when (q) {
        PartgSponsorQualification.SINGLE -> gettext("Im Bericht: Einzelleistung über 750 €")
        PartgSponsorQualification.YEARLY -> gettext("Im Bericht: Jahressumme über 6.000 €")
        PartgSponsorQualification.BOTH -> gettext("Im Bericht: Einzelleistung und Jahressumme")
        PartgSponsorQualification.NONE -> gettext("Nicht im Bericht: unter beiden Grenzen")
    }

/** A symbol AND a word, never a colour alone: ticked circle = in the report, info circle = not in the report. */
private fun Container.statusLine(q: PartgSponsorQualification): Div {
    val line = div(className = "small d-flex align-items-center gap-1")
    val inReport = q != PartgSponsorQualification.NONE
    line.add(Span(className = (if (inReport) ActionIcon.APPROVE else ActionIcon.INFO).css).also { it.setAttribute("aria-hidden", "true") })
    line.span(qualificationText(q))
    return line
}

internal fun SimplePanel.renderSponsoringBlock(env: StatementEnv) {
    div(
        tr(
            "In den Bericht kommt ein Sponsor mit einer Leistung über 750 € oder mit mehr als 6.000 € im Jahr bei dieser Einheit.",
        ),
        className = "small text-muted",
    )
    val formHost = simplePanel()
    val listHost = simplePanel()
    var renderListRef: () -> Unit = {}
    val blockFindings = env.findings.filter { partgFindingBlock(it) == AnnexBlock.C && it.rowNumbers.isNotEmpty() }

    fun closeForm(focusId: String) {
        formHost.removeAll()
        env.guard.close()
        focusElement(focusId)
    }

    fun openForm(existing: PartgSponsorDto?) {
        val open = {
            env.guard.begin { formHost.removeAll() }
            formHost.removeAll()
            formHost.renderSponsorForm(
                env = env,
                existing = existing,
                onCancel = { closeForm(existing?.let { "partg-sponsor-edit-${it.id}" } ?: "partg-sponsor-add") },
                onSaved = { saved ->
                    formHost.removeAll()
                    env.guard.close()
                    pendingFocusId = existing?.let { "partg-sponsor-edit-${it.id}" } ?: "partg-sponsor-add"
                    env.apply(saved)
                    renderListRef()
                    env.reload(AnnexBlock.C)
                },
            )
        }
        if (env.guard.open) confirmDiscardInputs(open) else open()
    }

    // the list follows the latest server answer, so it stays correct when a reload is declined after a save
    fun renderList() {
        listHost.removeAll()
        val current = env.annex()
        if (current.sponsors.isEmpty()) {
            listHost.div(tr("Noch keine Einträge."), className = "small text-muted")
        } else {
            listHost.renderSponsorTable(
                annex = current,
                findings = blockFindings,
                onEdit = ::openForm,
                onDelete = { sponsor ->
                    confirmDialog(
                        title = tr("Zeile löschen?"),
                        message = tr("Die Zeile wird aus der Liste entfernt. Das lässt sich nicht rückgängig machen."),
                        confirmLabel = tr("Löschen"),
                        onConfirm = {
                            AppScope.launch {
                                val result =
                                    guarded {
                                        partyReportRpc().deleteSponsor(
                                            current.year,
                                            current.unit.unitKey,
                                            env.revision(),
                                            sponsor.id,
                                        )
                                    }
                                if (result != null) {
                                    pendingFocusId = "partg-sponsor-add"
                                    env.apply(result)
                                    renderList()
                                    env.reload(AnnexBlock.C)
                                }
                            }
                        },
                    )
                },
            )
        }
    }
    renderListRef = ::renderList
    renderList()
    actionGroup {
        actionButton(ActionIcon.ADD, tr("Sponsor hinzufügen")) {
            id = "partg-sponsor-add"
            onClick { openForm(null) }
        }
    }
}

private fun SimplePanel.renderSponsorTable(
    annex: PartgAnnexDto,
    findings: List<PartgFindingDto>,
    onEdit: (PartgSponsorDto) -> Unit,
    onDelete: (PartgSponsorDto) -> Unit,
) {
    val table =
        standardTable(
            listOf(
                TableHeader(title = tr("Zeile"), numeric = true),
                TableHeader(title = tr("Name")),
                TableHeader(title = tr("Ort")),
                TableHeader(title = tr("Jahressumme"), numeric = true),
                TableHeader(title = tr("Status")),
                TableHeader(title = tr("Hinweise")),
                TableHeader(title = tr("Aktionen")),
            ),
        )
    annex.sponsors.forEach { sponsor ->
        table.row {
            numCell(sponsor.rowNumber.toString())
            textCell(sponsor.name)
            textCell(sponsor.city.orEmpty())
            numCell { moneySpan(centsToDecimal(sponsor.yearlySumCents)) }
            cell { statusLine(sponsor.qualification) }
            cell {
                findings.filter { sponsor.rowNumber in it.rowNumbers }.forEach { f ->
                    div(className = "small d-flex align-items-center gap-1") {
                        val check = f.level == PartgFindingLevel.CHECK
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
                    tableActionButton(ActionIcon.EDIT, tr("Bearbeiten")).also { it.id = "partg-sponsor-edit-${sponsor.id}" }.onClick {
                        onEdit(sponsor)
                    }
                    tableActionButton(ActionIcon.DELETE, tr("Löschen")).onClick { onDelete(sponsor) }
                }
            }
        }
    }
}

/** The fields of one service of the sponsor; [id] is `null` for a service that is not stored yet. */
private class EntryFields(
    val id: String?,
    val panel: Div,
    val date: LapisField,
    val amount: LapisField,
    val purpose: LapisField,
)

private fun Container.renderSponsorForm(
    env: StatementEnv,
    existing: PartgSponsorDto?,
    onCancel: () -> Unit,
    onSaved: (PartgAnnexDto) -> Unit,
) {
    val annex = env.annex()
    val year = annex.year
    val guard = env.guard
    val form = lapisForm()
    form.panel.div(
        if (existing == null) tr("Neuer Sponsor") else gettext("Zeile %1 bearbeiten", existing.rowNumber),
        className = "fw-semibold",
    )
    val kindField =
        form.selectField(
            label = tr("Art der Person"),
            options = PartgPersonKind.entries.map { it.name to partgPersonKindLabel(it) },
            value = (existing?.personKind ?: PartgPersonKind.NATURAL).name,
        )
    val base =
        PartgSponsorInputDto(
            id = null,
            personKind = PartgPersonKind.NATURAL,
            name = "x",
            street = null,
            postalCode = null,
            city = null,
            country = "DE",
            entries = listOf(PartgSponsoringEntryInputDto(null, LocalDate(year, 1, 1), 1L, null)),
        )

    fun fieldRule(change: PartgSponsorInputDto.(String) -> PartgSponsorInputDto): (String) -> FieldCheck =
        { text ->
            PartgStatementRules.validateSponsor(base.change(text), year)?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) }
                ?: FieldCheck.Ok
        }

    fun entryRule(change: PartgSponsoringEntryInputDto.(String) -> PartgSponsoringEntryInputDto): (String) -> FieldCheck =
        { text ->
            val entry = base.entries.single().change(text)
            PartgStatementRules
                .validateSponsor(
                    base.copy(entries = listOf(entry)),
                    year,
                )?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) }
                ?: FieldCheck.Ok
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

    form.panel.div(tr("Leistungen"), className = "fw-semibold mt-2")
    val rowsHost = form.panel.vPanel(spacing = 8)
    val rows = mutableListOf<EntryFields>()
    val sumLine = form.panel.div(className = "small")
    val statusHost =
        form.panel.div(className = "small") {
            setAttribute("aria-live", "polite")
            setAttribute("data-partg-status", "1")
        }
    var timer: Int? = null

    fun amounts(): List<Long> = rows.mapNotNull { (parseGermanAmount(it.amount.value) as? AmountParse.Ok)?.cents?.takeIf { c -> c > 0L } }

    fun refreshStatus() {
        val list = amounts()
        sumLine.content = gettext("Jahressumme %1 (Grenze: mehr als 6.000,00 €)", moneyFromCents(PartgStatementRules.yearlySum(list)))
        statusHost.removeAll()
        statusHost.statusLine(PartgStatementRules.qualification(list))
    }

    fun scheduleStatus() {
        timer?.let { window.clearTimeout(it) }
        val mine = guard.generation
        timer = window.setTimeout({ if (mine == guard.generation) refreshStatus() }, STATUS_DEBOUNCE_MS)
    }
    guard.dispose = { timer?.let { window.clearTimeout(it) } }

    fun addRow(entry: network.lapis.cloud.shared.domain.PartgSponsoringEntryDto?) {
        val panel = rowsHost.div(className = "d-flex flex-wrap align-items-end gap-2")
        val date =
            form.localDateField(
                label = tr("Datum"),
                value = entry?.date,
                required = true,
                host = panel,
                rule = { d -> entryRule { copy(date = d) }("") },
            )
        val amount =
            form.textField(
                label = tr("Betrag (€)"),
                value = entry?.amountCents?.let(::partgInputText),
                required = true,
                requiredMessage = tr("Bitte einen Betrag eingeben."),
                host = panel,
                rule = { text ->
                    when (val parsed = parseGermanAmount(text)) {
                        is AmountParse.Error -> FieldCheck.Invalid(resolvedAttributeText(partgAmountErrorText(parsed.reason)))
                        is AmountParse.Ok -> entryRule { copy(amountCents = parsed.cents) }("")
                    }
                },
                init = { it.inputMode("decimal") },
            )
        val purpose =
            form.textField(
                label = tr("Zweck"),
                value = entry?.purpose,
                host = panel,
                rule = entryRule { copy(purpose = it) },
            )
        val fields = EntryFields(entry?.id, panel, date, amount, purpose)
        rows += fields
        amount.subscribe { scheduleStatus() }
        panel.actionButton(ActionIcon.REMOVE, tr("Entfernen"), small = true) {
            onClick {
                rows.remove(fields)
                listOf(date, amount, purpose).forEach { form.unregister(it) }
                rowsHost.remove(panel)
                scheduleStatus()
            }
        }
    }
    existing?.entries?.forEach { addRow(it) }
    if (existing == null) addRow(null)
    refreshStatus()
    form.panel.actionGroup {
        actionButton(ActionIcon.ADD, tr("Leistung hinzufügen")) {
            onClick {
                addRow(null)
                scheduleStatus()
            }
        }
    }

    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"))
    form.buttons(primary = save, cancel = cancel)
    cancel.onClick { onCancel() }
    save.onClick {
        form.submit(save) {
            val input =
                PartgSponsorInputDto(
                    id = existing?.id,
                    personKind = PartgPersonKind.valueOf(kindField.value),
                    name = nameField.value,
                    street = streetField.value,
                    postalCode = postalField.value,
                    city = cityField.value,
                    country = countryField.value.trim(),
                    entries =
                        rows.map {
                            PartgSponsoringEntryInputDto(
                                id = it.id,
                                date = it.date.localDateOrNull() ?: LocalDate(year, 1, 1),
                                amountCents = centsOf(it.amount.value) ?: 0L,
                                purpose = it.purpose.value,
                            )
                        },
                )
            PartgStatementRules.validateSponsor(input, year)?.let {
                form.showFormError(partgAnnexViolationText(it))
                return@submit
            }
            val result = guarded { partyReportRpc().saveSponsor(year, annex.unit.unitKey, env.revision(), input) }
            if (result != null) {
                notifySuccess(tr("Gespeichert."))
                onSaved(result)
            } else {
                form.showFormError(saveFailedText())
            }
        }
    }
    kindField.focusWhenInserted()
    val mine = guard.generation
    window.setTimeout({ if (mine == guard.generation) kindField.focus() }, ANNEX_FOCUS_RETRY_MS)
}

internal fun partgPersonKindLabel(kind: PartgPersonKind): String =
    if (kind == PartgPersonKind.LEGAL) tr("Juristische Person") else tr("Natürliche Person")
