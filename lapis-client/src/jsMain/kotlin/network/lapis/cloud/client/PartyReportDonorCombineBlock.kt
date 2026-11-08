package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.CheckBox
import io.kvision.html.ButtonStyle
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgConsolidatedDonorDto
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgConsolidationRules
import network.lapis.cloud.shared.domain.PartgUnitDto

/**
 * V1.9.290 (R5) -- statement B over all units and the manual combination of rows of several units to ONE person. The server only SUGGESTS
 * candidates (same kind, name, postal code and country in different units); nothing is combined without a person ticking the rows and
 * confirming the units. The notice about the open legal question (Q1) is always shown.
 */
internal fun SimplePanel.renderDonorCombineBlock(
    context: PartyReportContext,
    dto: PartgConsolidationDto,
    units: List<PartgUnitDto>,
    onChanged: (PartgConsolidationDto) -> Unit,
) {
    div(tr("Ausweis B (konsolidiert)"), className = "fw-bold mt-3") { setAttribute("data-partg-donors", "1") }
    div(partgDonorLegalNote(), className = "small text-muted") { setAttribute("data-partg-q1", "1") }
    if (dto.donors.isEmpty()) {
        div(tr("Noch keine Einträge."), className = "small text-muted")
        return
    }
    val selected = LinkedHashSet<String>()
    val checkBoxes = mutableMapOf<String, CheckBox>()
    var combineButton: io.kvision.html.Button? = null

    fun refreshButton() {
        val enough = selected.size >= PartgConsolidationRules.MIN_ROWS
        // KVision reports the current value of a check box at once on subscription, before the button exists
        val button = combineButton ?: return
        button.disabled = !enough
        button.setAttribute("aria-disabled", (!enough).toString())
    }

    val table =
        standardTable(
            listOf(
                TableHeader(title = tr("Auswahl")),
                TableHeader(title = tr("Name")),
                TableHeader(title = tr("Gliederungen")),
                TableHeader(title = tr("Betrag"), numeric = true),
                TableHeader(title = tr("Aktionen")),
            ),
        )
    dto.donors.forEach { donor ->
        table.row {
            cell {
                if (donor.combinationId == null) {
                    val rowId = donor.donorIds.single()
                    val box = CheckBox(value = false, label = gettext("Zeile %1 auswählen", sanitizeUntrustedI18nText(donor.name)))
                    box.subscribe { checked ->
                        if (checked == true) selected += rowId else selected -= rowId
                        refreshButton()
                    }
                    checkBoxes[rowId] = box
                    add(box)
                } else {
                    add(Span(tr("zusammengeführt")))
                }
            }
            textCell(donor.name)
            textCell(donor.unitKeys.joinToString("; ") { partgUnitLabelOf(units, it) })
            numCell { moneySpan(centsToDecimal(donor.totalCents)) }
            cell {
                addCssClass(TABLE_ACTIONS_CELL_CLASS)
                val combinationId = donor.combinationId
                if (combinationId != null) {
                    tableActionGroup().apply {
                        tableActionButton(ActionIcon.UNDO, tr("Zusammenführung auflösen")).onClick {
                            confirmDialog(
                                title = tr("Zusammenführung auflösen?"),
                                message = tr("Die Zeilen erscheinen danach wieder einzeln in der Liste."),
                                confirmLabel = tr("Auflösen"),
                                confirmStyle = ButtonStyle.PRIMARY,
                                onConfirm = {
                                    AppScope.launch {
                                        val result =
                                            guarded { partyReportRpc().dissolveDonorCombination(context.year, dto.revision, combinationId) }
                                        if (result != null) {
                                            notifySuccess(tr("Zusammenführung aufgelöst."))
                                            onChanged(result)
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
    // the candidates: ids only come from the server, the NAME is resolved here from the donor list
    if (dto.candidates.isNotEmpty()) {
        div(
            gettext(
                "Vorschläge (%1): Zeilen mit gleichem Namen, gleicher Postleitzahl und gleichem Land in verschiedenen Gliederungen.",
                dto.candidateTotal,
            ),
            className = "small mt-2",
        )
        dto.candidates.forEach { candidate ->
            div(className = "small d-flex flex-wrap align-items-center gap-2") {
                span(partgCandidateLabel(candidate, dto.donors, units))
                actionButton(ActionIcon.COMBINE, tr("Vorschlag auswählen"), small = true) {
                    onClick {
                        selected.clear()
                        checkBoxes.forEach { (id, box) ->
                            val take = id in candidate.donorIds
                            box.value = take
                            if (take) selected += id
                        }
                        refreshButton()
                    }
                }
            }
        }
    }
    combineButton =
        actionButton(ActionIcon.COMBINE, tr("Zusammenführen"), ButtonStyle.OUTLINEPRIMARY) {
            onClick {
                val ids = selected.toList()
                val rows = dto.donors.filter { d -> d.combinationId == null && d.donorIds.single() in ids }
                combineAfterConfirmation(context = context, dto = dto, units = units, rows = rows, onChanged = onChanged)
            }
        }.also { it.id = "partg-donor-combine" }
    refreshButton()
}

/** The refusal code for a selection that cannot be combined: too few rows, else two rows of the same unit. */
private fun rowsCode(count: Int): String =
    when {
        count < PartgConsolidationRules.MIN_ROWS -> CODE_ROW_COUNT
        else -> CODE_SAME_UNIT
    }

private const val CODE_ROW_COUNT = "ROW_COUNT"
private const val CODE_SAME_UNIT = "SAME_UNIT"

private fun Container.combineAfterConfirmation(
    context: PartyReportContext,
    dto: PartgConsolidationDto,
    units: List<PartgUnitDto>,
    rows: List<PartgConsolidatedDonorDto>,
    onChanged: (PartgConsolidationDto) -> Unit,
) {
    val unitKeys = rows.flatMap { it.unitKeys }
    if (unitKeys.toSet().size != unitKeys.size || rows.size < PartgConsolidationRules.MIN_ROWS) {
        val code = rowsCode(rows.size)
        notifyError(partgConsolidationCodeText(code))
        return
    }
    confirmDialog(
        title = tr("Zeilen zusammenführen?"),
        message =
            gettext(
                "Die ausgewählten Zeilen werden als eine Person gezählt: %1.",
                unitKeys.joinToString("; ") {
                    partgUnitLabelOf(units, it)
                },
            ),
        extraLines = listOf(partgDonorLegalNote()),
        confirmLabel = tr("Zusammenführen"),
        confirmIcon = ActionIcon.COMBINE,
        confirmStyle = ButtonStyle.PRIMARY,
        onConfirm = {
            AppScope.launch {
                val result =
                    guarded {
                        partyReportRpc().createDonorCombination(
                            year = context.year,
                            expectedRevision = dto.revision,
                            expectedAnnexRevisions = dto.annexRevisions,
                            largeDonorIds = rows.map { it.donorIds.single() },
                        )
                    }
                if (result != null) {
                    notifySuccess(tr("Zeilen zusammengeführt."))
                    onChanged(result)
                } else {
                    notifyError(saveFailedText())
                }
            }
        },
    )
}
