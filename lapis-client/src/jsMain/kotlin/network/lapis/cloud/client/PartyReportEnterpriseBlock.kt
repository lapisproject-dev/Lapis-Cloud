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
import network.lapis.cloud.shared.domain.PartgEnterpriseCheckDto
import network.lapis.cloud.shared.domain.PartgEnterpriseCheckStatus
import network.lapis.cloud.shared.domain.PartgEnterpriseDto
import network.lapis.cloud.shared.domain.PartgEnterpriseInputDto
import network.lapis.cloud.shared.domain.PartgEnterpriseRules
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.parseGermanAmount

/**
 * The share in hundredths of a percent: "25" -> 2500, "25,5" -> 2550, "25,05" -> 2505. Blank and everything outside 0..100 is `null`.
 * Pinned by `PartyReportEnterpriseDomTest`: the number must never be read as a bare percent value.
 */
internal fun partgBasisPoints(text: String): Int? {
    if (text.isBlank()) return null
    val parsed = parseGermanAmount(text) as? AmountParse.Ok ?: return null
    return parsed.cents.takeIf { it in 0..PartgEnterpriseRules.SHARE_MAX_BASIS_POINTS.toLong() }?.toInt()
}

/** `25,00 %` (the stored value is hundredths of a percent). */
internal fun partgShareText(basisPoints: Int?): String = basisPoints?.let { partgInputText(it.toLong()) + " %" } ?: gettext("nicht erfasst")

private fun optionalMoney(cents: Long?): String = cents?.let { moneyFromCents(it) } ?: gettext("nicht erfasst")

/** The word of an adjustment status. The symbol is a separate, decorative element; the word carries the meaning. */
internal fun partgEnterpriseCheckText(check: PartgEnterpriseCheckDto): String =
    when (check.status) {
        PartgEnterpriseCheckStatus.MATCHES -> gettext("%1: stimmt überein", check.positionCode)
        PartgEnterpriseCheckStatus.DIFFERS -> gettext("%1: weicht ab (mehr als 1,00 EUR)", check.positionCode)
        PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED -> gettext("%1: weicht ab (Position im Bericht nicht erfasst)", check.positionCode)
        PartgEnterpriseCheckStatus.NOT_COMPARABLE -> gettext("%1: nicht vergleichbar, Angaben fehlen", check.positionCode)
    }

private fun Container.checkLine(check: PartgEnterpriseCheckDto) {
    val line = div(className = "small d-flex align-items-center gap-1")
    line.setAttribute("data-partg-enterprise-check", check.positionCode)
    val glyph =
        when (check.status) {
            PartgEnterpriseCheckStatus.MATCHES -> "✓"
            PartgEnterpriseCheckStatus.DIFFERS, PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED -> "≠"
            PartgEnterpriseCheckStatus.NOT_COMPARABLE -> "?"
        }
    line.add(Span(glyph).also { it.setAttribute("aria-hidden", "true") })
    line.span(partgEnterpriseCheckText(check))
}

internal fun SimplePanel.renderEnterpriseBlock(env: StatementEnv) {
    div(
        tr(
            "Hier tragen Sie eigene Geschäftsbetriebe ein (Positionen E05 und A02F). Beteiligungen an Unternehmen (BP03, E05A) " +
                "gehören in die Erläuterungen. Bitte keine Namen von Personen eintragen.",
        ),
        className = "small text-muted",
    )
    val formHost = simplePanel()
    val listHost = simplePanel()
    var renderListRef: () -> Unit = {}
    val blockFindings = env.findings.filter { partgFindingBlock(it) == AnnexBlock.D && it.rowNumbers.isNotEmpty() }

    fun closeForm(focusId: String) {
        formHost.removeAll()
        env.guard.close()
        focusElement(focusId)
    }

    fun openForm(existing: PartgEnterpriseDto?) {
        val open = {
            env.guard.begin { formHost.removeAll() }
            formHost.removeAll()
            formHost.renderEnterpriseForm(
                env = env,
                existing = existing,
                onCancel = { closeForm(existing?.let { "partg-enterprise-edit-${it.id}" } ?: "partg-enterprise-add") },
                onSaved = { saved ->
                    formHost.removeAll()
                    env.guard.close()
                    pendingFocusId = existing?.let { "partg-enterprise-edit-${it.id}" } ?: "partg-enterprise-add"
                    env.apply(saved)
                    renderListRef()
                    env.reload(AnnexBlock.D)
                },
            )
        }
        if (env.guard.open) confirmDiscardInputs(open) else open()
    }

    // the list follows the latest server answer, so it stays correct when a reload is declined after a save
    fun renderList() {
        listHost.removeAll()
        val current = env.annex()
        if (current.enterprises.isEmpty()) {
            listHost.div(tr("Keine Unternehmen erfasst."), className = "small text-muted")
        } else {
            val table =
                listHost.standardTable(
                    listOf(
                        TableHeader(title = tr("Zeile"), numeric = true),
                        TableHeader(title = tr("Bezeichnung")),
                        TableHeader(title = tr("Rechtsform")),
                        TableHeader(title = tr("Sitz")),
                        TableHeader(title = tr("Anteil"), numeric = true),
                        TableHeader(title = tr("Einnahmen"), numeric = true),
                        TableHeader(title = tr("Ausgaben"), numeric = true),
                        TableHeader(title = tr("Ergebnis"), numeric = true),
                        TableHeader(title = tr("Hinweise")),
                        TableHeader(title = tr("Aktionen")),
                    ),
                )
            current.enterprises.forEach { e ->
                table.row {
                    numCell(e.rowNumber.toString())
                    textCell(e.name)
                    textCell(e.legalForm.orEmpty())
                    textCell(e.seat.orEmpty())
                    numCell(partgShareText(e.shareBasisPoints))
                    numCell(optionalMoney(e.incomeCents))
                    numCell(optionalMoney(e.expensesCents))
                    numCell(
                        PartgEnterpriseRules.resultCents(e.incomeCents, e.expensesCents)?.let { moneyFromCents(it) }
                            ?: gettext("nicht berechenbar"),
                    )
                    cell { findingsOfEnterpriseRow(blockFindings, e.rowNumber) }
                    cell {
                        addCssClass(TABLE_ACTIONS_CELL_CLASS)
                        tableActionGroup().apply {
                            tableActionButton(
                                ActionIcon.EDIT,
                                tr("Bearbeiten"),
                            ).also { it.id = "partg-enterprise-edit-${e.id}" }.onClick { openForm(e) }
                            tableActionButton(
                                ActionIcon.DELETE,
                                tr("Löschen"),
                            ).also { it.id = "partg-enterprise-delete-${e.id}" }.onClick {
                                confirmDialog(
                                    title = tr("Zeile löschen?"),
                                    message = tr("Die Zeile wird aus der Liste entfernt. Das lässt sich nicht rückgängig machen."),
                                    confirmLabel = tr("Löschen"),
                                    onConfirm = {
                                        AppScope.launch {
                                            val result =
                                                guarded {
                                                    partyReportRpc().deleteEnterprise(
                                                        current.year,
                                                        current.unit.unitKey,
                                                        env.revision(),
                                                        e.id,
                                                    )
                                                }
                                            if (result != null) {
                                                pendingFocusId = "partg-enterprise-add"
                                                env.apply(result)
                                                renderListRef()
                                                env.reload(AnnexBlock.D)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            // the sum of the unit: only when every row has the figure, else "nicht berechenbar"
            val income = PartgEnterpriseRules.completeSum(current.enterprises.map { it.incomeCents })
            val expenses = PartgEnterpriseRules.completeSum(current.enterprises.map { it.expensesCents })
            val result = PartgEnterpriseRules.resultCents(income, expenses)
            listHost.div(className = "small fw-semibold mt-1") {
                setAttribute("data-partg-enterprise-total", "1")
                span(tr("Summe"))
                span(": ")
                span(gettext("Einnahmen %1", income?.let { moneyFromCents(it) } ?: gettext("nicht berechenbar")))
                span("; ")
                span(gettext("Ausgaben %1", expenses?.let { moneyFromCents(it) } ?: gettext("nicht berechenbar")))
                span("; ")
                span(gettext("Ergebnis %1", result?.let { moneyFromCents(it) } ?: gettext("nicht berechenbar")))
            }
        }
        // the adjustment against the report positions: a status in words, never an amount
        current.enterpriseChecks.forEach { listHost.checkLine(it) }
    }
    renderListRef = ::renderList
    renderList()
    actionGroup {
        val atLimit = env.annex().enterprises.size >= PartgEnterpriseRules.MAX_ENTERPRISES_PER_UNIT
        actionButton(ActionIcon.ADD, tr("Unternehmen hinzufügen")) {
            id = "partg-enterprise-add"
            if (atLimit) disabled = true
            onClick { openForm(null) }
        }
        actionButton(ActionIcon.NEXT, tr("Zu den Erläuterungen")) {
            id = "partg-enterprise-to-notes"
            onClick { env.openNotes() }
        }
    }
    if (env.annex().enterprises.size >= PartgEnterpriseRules.MAX_ENTERPRISES_PER_UNIT) {
        div(tr("Es sind höchstens 50 Unternehmen je Gliederung und Jahr möglich."), className = "small text-muted")
    }
}

private fun Container.findingsOfEnterpriseRow(
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

private fun Container.renderEnterpriseForm(
    env: StatementEnv,
    existing: PartgEnterpriseDto?,
    onCancel: () -> Unit,
    onSaved: (PartgAnnexDto) -> Unit,
) {
    val annex = env.annex()
    val guard = env.guard
    val form = lapisForm()
    form.panel.div(
        if (existing == null) tr("Neues Unternehmen") else gettext("Zeile %1 bearbeiten", existing.rowNumber),
        className = "fw-semibold",
    )
    val base = PartgEnterpriseInputDto(null, "x", null, null, null, null, null)

    fun rule(change: PartgEnterpriseInputDto.(String) -> PartgEnterpriseInputDto): (String) -> FieldCheck =
        { text ->
            PartgEnterpriseRules.validate(base.change(text))?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) } ?: FieldCheck.Ok
        }

    fun moneyRule(change: PartgEnterpriseInputDto.(Long) -> PartgEnterpriseInputDto): (String) -> FieldCheck =
        { text ->
            if (text.isBlank()) {
                FieldCheck.Ok
            } else {
                when (val parsed = parseGermanAmount(text)) {
                    is AmountParse.Error -> FieldCheck.Invalid(resolvedAttributeText(partgAmountErrorText(parsed.reason)))
                    is AmountParse.Ok ->
                        PartgEnterpriseRules
                            .validate(base.change(parsed.cents))
                            ?.let { FieldCheck.Invalid(partgAnnexViolationText(it)) } ?: FieldCheck.Ok
                }
            }
        }

    val shareRule: (String) -> FieldCheck = { text ->
        if (text.isBlank() || partgBasisPoints(text) != null) {
            FieldCheck.Ok
        } else {
            FieldCheck.Invalid(gettext("Bitte einen Anteil zwischen 0 und 100 eingeben."))
        }
    }
    val nameField =
        form.textField(
            label = tr("Bezeichnung"),
            value = existing?.name,
            required = true,
            requiredMessage = tr("Bitte eine Bezeichnung angeben."),
            rule = rule { copy(name = it) },
        )
    val legalFormField =
        form.textField(label = tr("Rechtsform (optional)"), value = existing?.legalForm, rule = rule { copy(legalForm = it) })
    val seatField = form.textField(label = tr("Sitz (optional)"), value = existing?.seat, rule = rule { copy(seat = it) })
    val shareField =
        form.textField(
            label = tr("Anteil der Partei in % (optional)"),
            value = existing?.shareBasisPoints?.let { partgInputText(it.toLong()) },
            rule = shareRule,
            init = { it.inputMode("decimal") },
        )
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
                PartgEnterpriseInputDto(
                    id = existing?.id,
                    name = nameField.value,
                    legalForm = legalFormField.value.takeIf { it.isNotBlank() },
                    seat = seatField.value.takeIf { it.isNotBlank() },
                    shareBasisPoints = partgBasisPoints(shareField.value),
                    incomeCents = centsOf(incomeField.value),
                    expensesCents = centsOf(expensesField.value),
                )
            PartgEnterpriseRules.validate(input)?.let {
                form.showFormError(partgAnnexViolationText(it))
                return@submit
            }
            val result = guarded { partyReportRpc().saveEnterprise(annex.year, annex.unit.unitKey, env.revision(), input) }
            if (result != null) {
                notifySuccess(tr("Gespeichert."))
                PartyReportSaveSignal.saved()
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
