package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgStatementRules
import network.lapis.cloud.shared.domain.PartgYouthOrgDto
import network.lapis.cloud.shared.domain.PartgYouthOrgInputDto
import network.lapis.cloud.shared.domain.parseGermanAmount

/** Result of a youth organisation: income minus expenses, `null` (shown as "nicht berechenbar") when one of the two is missing. */
internal fun partgYouthResultCents(
    income: Long?,
    expenses: Long?,
): Long? = if (income != null && expenses != null) income - expenses else null

private fun optionalMoney(cents: Long?): String = cents?.let { moneyFromCents(it) } ?: gettext("nicht erfasst")

internal fun SimplePanel.renderYouthBlock(env: StatementEnv) {
    div(
        tr("Jugendorganisationen der Einheit mit ihren Einnahmen und Ausgaben. Das Ergebnis wird berechnet."),
        className = "small text-muted",
    )
    val formHost = simplePanel()
    val listHost = simplePanel()
    var renderListRef: () -> Unit = {}
    val blockFindings = env.findings.filter { partgFindingBlock(it) == AnnexBlock.F && it.rowNumbers.isNotEmpty() }

    fun closeForm(focusId: String) {
        formHost.removeAll()
        env.guard.close()
        focusElement(focusId)
    }

    fun openForm(existing: PartgYouthOrgDto?) {
        val open = {
            env.guard.begin { formHost.removeAll() }
            formHost.removeAll()
            formHost.renderYouthForm(
                env = env,
                existing = existing,
                onCancel = { closeForm(existing?.let { "partg-youth-edit-${it.id}" } ?: "partg-youth-add") },
                onSaved = { saved ->
                    formHost.removeAll()
                    env.guard.close()
                    pendingFocusId = existing?.let { "partg-youth-edit-${it.id}" } ?: "partg-youth-add"
                    env.apply(saved)
                    renderListRef()
                    env.reload(AnnexBlock.F)
                },
            )
        }
        if (env.guard.open) confirmDiscardInputs(open) else open()
    }

    // the list follows the latest server answer, so it stays correct when a reload is declined after a save
    fun renderList() {
        listHost.removeAll()
        val current = env.annex()
        if (current.youthOrgs.isEmpty()) {
            listHost.div(tr("Noch keine Einträge."), className = "small text-muted")
        } else {
            val table =
                listHost.standardTable(
                    listOf(
                        TableHeader(title = tr("Zeile"), numeric = true),
                        TableHeader(title = tr("Name")),
                        TableHeader(title = tr("Einnahmen"), numeric = true),
                        TableHeader(title = tr("Ausgaben"), numeric = true),
                        TableHeader(title = tr("Ergebnis"), numeric = true),
                        TableHeader(title = tr("Hinweise")),
                        TableHeader(title = tr("Aktionen")),
                    ),
                )
            current.youthOrgs.forEach { y ->
                table.row {
                    numCell(y.rowNumber.toString())
                    textCell(y.name)
                    numCell(optionalMoney(y.incomeCents))
                    numCell(optionalMoney(y.expensesCents))
                    numCell(
                        partgYouthResultCents(y.incomeCents, y.expensesCents)?.let { moneyFromCents(it) } ?: gettext("nicht berechenbar"),
                    )
                    cell { findingsOfRow(blockFindings, y.rowNumber) }
                    cell {
                        addCssClass(TABLE_ACTIONS_CELL_CLASS)
                        tableActionGroup().apply {
                            tableActionButton(
                                ActionIcon.EDIT,
                                tr("Bearbeiten"),
                            ).also { it.id = "partg-youth-edit-${y.id}" }.onClick { openForm(y) }
                            tableActionButton(ActionIcon.DELETE, tr("Löschen")).onClick {
                                confirmDialog(
                                    title = tr("Zeile löschen?"),
                                    message = tr("Die Zeile wird aus der Liste entfernt. Das lässt sich nicht rückgängig machen."),
                                    confirmLabel = tr("Löschen"),
                                    onConfirm = {
                                        AppScope.launch {
                                            val result =
                                                guarded {
                                                    partyReportRpc().deleteYouthOrg(
                                                        current.year,
                                                        current.unit.unitKey,
                                                        env.revision(),
                                                        y.id,
                                                    )
                                                }
                                            if (result != null) {
                                                pendingFocusId = "partg-youth-add"
                                                env.apply(result)
                                                renderListRef()
                                                env.reload(AnnexBlock.F)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    renderListRef = ::renderList
    renderList()
    actionGroup {
        actionButton(ActionIcon.ADD, tr("Jugendorganisation hinzufügen")) {
            id = "partg-youth-add"
            onClick { openForm(null) }
        }
    }
}

private fun Container.findingsOfRow(
    findings: List<PartgFindingDto>,
    rowNumber: Int,
) {
    findings.filter { rowNumber in it.rowNumbers }.forEach { f ->
        div(className = "small d-flex align-items-center gap-1") {
            val check = f.level == PartgFindingLevel.CHECK
            add(Span(className = (if (check) ActionIcon.WARN else ActionIcon.INFO).css).also { it.setAttribute("aria-hidden", "true") })
            span(partgFindingShortLabel(f))
        }
    }
}

private fun Container.renderYouthForm(
    env: StatementEnv,
    existing: PartgYouthOrgDto?,
    onCancel: () -> Unit,
    onSaved: (PartgAnnexDto) -> Unit,
) {
    val annex = env.annex()
    val guard = env.guard
    val form = lapisForm()
    form.panel.div(
        if (existing == null) tr("Neue Jugendorganisation") else gettext("Zeile %1 bearbeiten", existing.rowNumber),
        className = "fw-semibold",
    )
    val base = PartgYouthOrgInputDto(null, "x", null, null, null)

    fun rule(change: PartgYouthOrgInputDto.(String) -> PartgYouthOrgInputDto): (String) -> FieldCheck =
        { text ->
            PartgStatementRules.validateYouthOrg(base.change(text))?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) }
                ?: FieldCheck.Ok
        }

    fun moneyRule(change: PartgYouthOrgInputDto.(Long) -> PartgYouthOrgInputDto): (String) -> FieldCheck =
        { text ->
            if (text.isBlank()) {
                FieldCheck.Ok
            } else {
                when (val parsed = parseGermanAmount(text)) {
                    is AmountParse.Error -> FieldCheck.Invalid(resolvedAttributeText(partgAmountErrorText(parsed.reason)))
                    is AmountParse.Ok ->
                        PartgStatementRules
                            .validateYouthOrg(
                                base.change(parsed.cents),
                            )?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) }
                            ?: FieldCheck.Ok
                }
            }
        }
    val nameField =
        form.textField(
            label = tr("Name"),
            value = existing?.name,
            required = true,
            requiredMessage = tr("Bitte einen Namen angeben."),
            rule = rule { copy(name = it) },
        )
    val relationshipField =
        form.textField(label = tr("Verhältnis zur Partei"), value = existing?.relationship, rule = rule { copy(relationship = it) })
    val incomeField =
        form.textField(
            label = tr("Einnahmen (€)"),
            value = existing?.incomeCents?.let(::partgInputText),
            rule = moneyRule { copy(incomeCents = it) },
            init = { it.inputMode("decimal") },
        )
    val expensesField =
        form.textField(
            label = tr("Ausgaben (€)"),
            value = existing?.expensesCents?.let(::partgInputText),
            rule = moneyRule { copy(expensesCents = it) },
            init = { it.inputMode("decimal") },
        )
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"))
    form.buttons(primary = save, cancel = cancel)
    cancel.onClick { onCancel() }
    save.onClick {
        form.submit(save) {
            val input =
                PartgYouthOrgInputDto(
                    id = existing?.id,
                    name = nameField.value,
                    relationship = relationshipField.value,
                    incomeCents = centsOf(incomeField.value),
                    expensesCents = centsOf(expensesField.value),
                )
            PartgStatementRules.validateYouthOrg(input)?.let {
                form.showFormError(partgAnnexViolationText(it))
                return@submit
            }
            val result = guarded { partyReportRpc().saveYouthOrg(annex.year, annex.unit.unitKey, env.revision(), input) }
            if (result != null) {
                notifySuccess(tr("Gespeichert."))
                onSaved(result)
            } else {
                form.showFormError(saveFailedText())
            }
        }
    }
    nameField.focusWhenInserted()
    val mine = guard.generation
    window.setTimeout({ if (mine == guard.generation) nameField.focus() }, ANNEX_FOCUS_RETRY_MS)
}
