package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgIntraTransferDto
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnitDto

private class ConsolidationLoad(
    val dto: PartgConsolidationDto,
    val units: List<PartgUnitDto>,
)

/**
 * V1.9.290 "Rechenschaftsbericht (PartG), R5" -- the tab "Konsolidierung" (federal roles only). One page, five blocks in a fixed order:
 * (1) which units are part of the consolidated total, (2) the 42 positions: sum of the units, eliminated, consolidated, year before,
 * (3) the matching table of the transfers between units, (4) statement B across all units with the manual combination, (5) the hints.
 *
 * A DRAFT: the banner says so, an unknown value is "n. b." and never 0, a difference between the two sides of a pair stays visible. The
 * status is a symbol AND a word (never colour alone, no red); a polite live region announces what the page now shows.
 */
internal fun SimplePanel.renderPartyReportConsolidation(context: PartyReportContext) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    div(className = "alert alert-warning mb-0") {
        setAttribute("role", "note")
        div(
            tr(
                "Entwurf, nicht geprüft: Das ist kein amtlicher Bericht. Übertragungen zwischen den Gliederungen sind herausgerechnet, " +
                    "soweit beide Seiten erfasst sind. n. b. heißt nicht berechenbar und ist nicht 0.",
            ),
        )
    }
    val live = div { setAttribute("aria-live", "polite") }
    live.addCssClasses("small text-muted")
    dataSection<ConsolidationLoad>(
        isEmpty = { false },
        load = {
            guarded {
                val rpc = partyReportRpc()
                val units = rpc.listUnits()
                ConsolidationLoad(dto = rpc.getConsolidation(context.year), units = units)
            }
        },
        render = { body, loaded -> body.renderConsolidationBody(context = context, loaded = loaded, live = live) },
    ).reload()
}

private fun SimplePanel.renderConsolidationBody(
    context: PartyReportContext,
    loaded: ConsolidationLoad,
    live: Div,
) {
    val host = simplePanel()

    lateinit var paint: (PartgConsolidationDto) -> Unit
    paint = { dto ->
        host.removeAll()
        live.content = partgConsolidationStatusText(dto)
        host.renderCompleteness(dto, loaded.units)
        host.renderPositions(dto)
        host.renderReconciliation(context = context, dto = dto, units = loaded.units, onChanged = { paint(it) })
        host.renderDonorCombineBlock(context = context, dto = dto, units = loaded.units, onChanged = { paint(it) })
        host.renderPartyReportFindings(context = context, units = loaded.units, findings = dto.findings)
    }
    paint(loaded.dto)
}

private fun SimplePanel.renderCompleteness(
    dto: PartgConsolidationDto,
    units: List<PartgUnitDto>,
) {
    div(tr("Einbezogene Gliederungen"), className = "fw-bold mt-2") { setAttribute("data-partg-completeness", "1") }
    if (dto.included.isEmpty()) {
        div(tr("Keine Gliederung hat Werte für dieses Jahr."), className = "small")
    } else {
        div(dto.included.joinToString("; ") { partgUnitLabelOf(units, it) }, className = "small")
    }
    if (dto.excluded.isNotEmpty()) {
        div(tr("Nicht einbezogen (zählen nicht als 0):"), className = "small mt-1")
        dto.excluded.forEach {
            div(gettext("%1: %2", partgUnitLabelOf(units, it.unitKey), partgExclusionText(it.reason)), className = "small text-muted")
        }
    }
    if (dto.priorTransfersUnmatched) {
        div(
            tr("Für das Vorjahr sind keine Übertragungen erfasst. Die Spalte Vorjahr ist deshalb nicht bereinigt."),
            className = "small text-muted",
        )
    }
}

private fun io.kvision.table.Row.consolidatedCell(cell: PartgConsolidatedCell) {
    numCell {
        div(partgConsolidatedCellText(cell))
        partgConsolidatedCellReason(cell)?.let { div(it, className = "small text-muted") }
    }
}

private fun SimplePanel.renderPositions(dto: PartgConsolidationDto) {
    div(tr("Gesamtpartei (konsolidiert, Entwurf)"), className = "fw-bold mt-3") { setAttribute("data-partg-positions", "1") }
    val table =
        standardTable(
            listOf(
                TableHeader(title = tr("Nr.")),
                TableHeader(title = tr("Position")),
                TableHeader(title = tr("Summe der Einheiten"), numeric = true),
                TableHeader(title = tr("Eliminiert"), numeric = true),
                TableHeader(title = tr("Konsolidiert"), numeric = true),
                TableHeader(title = tr("Vorjahr"), numeric = true),
            ),
        )
    dto.rows.forEach { row ->
        table.row {
            textCell(PartgPositions.find(row.code)?.numberLabel.orEmpty())
            textCell(partgLabel(row.code))
            consolidatedCell(row.unitSum)
            numCell { moneySpan(centsToDecimal(row.eliminatedCents)) }
            consolidatedCell(row.consolidated)
            consolidatedCell(row.priorConsolidated)
        }
    }
}

private fun SimplePanel.renderReconciliation(
    context: PartyReportContext,
    dto: PartgConsolidationDto,
    units: List<PartgUnitDto>,
    onChanged: (PartgConsolidationDto) -> Unit,
) {
    div(tr("Abgleich der Übertragungen"), className = "fw-bold mt-3") { setAttribute("data-partg-reconciliation", "1") }
    div(
        tr(
            "Tragen Sie ein, was eine Gliederung an eine andere gezahlt hat (Zuschuss) oder von ihr fordert. " +
                "Eliminiert wird der kleinere der beiden Beträge, und nur, wenn beide Seiten erfasst sind.",
        ),
        className = "small text-muted",
    )
    if (dto.transfers.isEmpty()) {
        div(tr("Noch keine Übertragungen erfasst."), className = "small")
    } else {
        val table =
            standardTable(
                listOf(
                    TableHeader(title = tr("Art")),
                    TableHeader(title = tr("Zahler")),
                    TableHeader(title = tr("Empfänger")),
                    TableHeader(title = tr("Betrag laut Zahler"), numeric = true),
                    TableHeader(title = tr("Betrag laut Empfänger"), numeric = true),
                    TableHeader(title = tr("Stand")),
                    TableHeader(title = tr("Aktionen")),
                ),
            )
        dto.transfers.forEach { pair ->
            table.row {
                textCell(partgTransferKindLabel(pair.kind))
                textCell(partgUnitLabelOf(units, pair.payerUnitKey))
                textCell(partgUnitLabelOf(units, pair.payeeUnitKey))
                numCell(pair.payerAmountCents?.let { moneyFromCents(it) } ?: tr("nicht erfasst"))
                numCell(pair.payeeAmountCents?.let { moneyFromCents(it) } ?: tr("nicht erfasst"))
                cell {
                    val look = partgMatchLook(pair.match)
                    val icon = Span(className = look.icon.css)
                    icon.setAttribute("aria-hidden", "true")
                    add(icon)
                    statusBadge(look.word, look.color)
                }
                cell {
                    addCssClass(TABLE_ACTIONS_CELL_CLASS)
                    tableActionGroup().apply {
                        tableActionButton(ActionIcon.EDIT, tr("Bearbeiten")).onClick {
                            openPartyReportTransferDialog(context, units, dto.revision, pair, onChanged)
                        }
                        tableActionButton(ActionIcon.DELETE, tr("Löschen")).onClick { confirmDeleteTransfer(context, dto, pair, onChanged) }
                    }
                }
            }
        }
    }
    if (dto.unmatched.isNotEmpty()) {
        div(tr("Nicht abgeglichen"), className = "small fw-semibold mt-2")
        dto.unmatched.forEach {
            div(
                gettext(
                    "%1, %2: %3",
                    partgUnitLabelOf(units, it.unitKey),
                    partgLabel(it.positionCode),
                    partgUnmatchedText(it.differenceCents),
                ),
                className = "small",
            )
        }
    }
    actionGroup {
        actionButton(ActionIcon.ADD, tr("Übertragung erfassen"), ButtonStyle.OUTLINEPRIMARY) {
            id = "partg-transfer-add"
            onClick { openPartyReportTransferDialog(context, units, dto.revision, null, onChanged) }
        }
    }
}

private fun confirmDeleteTransfer(
    context: PartyReportContext,
    dto: PartgConsolidationDto,
    pair: PartgIntraTransferDto,
    onChanged: (PartgConsolidationDto) -> Unit,
) {
    confirmDialog(
        title = tr("Übertragung löschen?"),
        message = tr("Der Eintrag wird aus der Liste entfernt. Danach wird dieser Betrag nicht mehr herausgerechnet."),
        confirmLabel = tr("Löschen"),
        onConfirm = {
            AppScope.launch {
                val result = guarded { partyReportRpc().deleteIntraTransfer(context.year, dto.revision, pair.id) }
                if (result != null) onChanged(result)
            }
        },
    )
}
