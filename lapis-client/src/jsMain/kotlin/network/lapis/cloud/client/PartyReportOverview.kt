package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.table.cell
import io.kvision.table.row
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgUnitDto

private val OVERVIEW_HEADERS =
    listOf(
        TableHeader(title = tr("Gliederung")),
        TableHeader(title = tr("Stand")),
        TableHeader(title = tr("Einnahmen"), numeric = true),
        TableHeader(title = tr("Ausgaben"), numeric = true),
        TableHeader(title = tr("Reinvermögen"), numeric = true),
        TableHeader(title = tr("Hinweise")),
        TableHeader(title = tr("Aktionen")),
    )

/** The rows of the overview plus the number of hints that ask for a look, per unit key. */
private class OverviewLoad(
    val rows: List<PartgOverviewRowDto>,
    val checks: Map<String, Int>,
)

/** Label and badge colour of the fill state of one overview row. */
internal fun partgFillBadge(row: PartgOverviewRowDto): Pair<String, String> =
    when (partgFillState(row.filledCount, row.leafCount)) {
        PartgFillState.EMPTY -> tr("Nicht erfasst") to "secondary"
        PartgFillState.PARTIAL -> gettext("Teilweise (%1 von %2)", row.filledCount, row.leafCount) to "warning"
        PartgFillState.COMPLETE -> tr("Vollständig") to "success"
    }

/**
 * The overview tab: one row per reporting unit for the chosen year with its fill state and the three totals, and the three actions of a unit
 * (enter values, download the Excel template, import an Excel file). `null` totals mean nothing was entered and show a dash.
 */
internal fun SimplePanel.renderPartyReportOverview(
    context: PartyReportContext,
    onEdit: (PartgUnitDto) -> Unit,
    onReport: () -> Unit,
) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    actionGroup {
        actionButton(ActionIcon.EXPORT, tr("Bericht erzeugen"), ButtonStyle.OUTLINEPRIMARY) { onClick { onReport() } }
    }
    val section =
        dataSection<OverviewLoad>(
            emptyText = tr("Keine Gliederungen vorhanden."),
            isEmpty = { it.rows.isEmpty() },
            load = {
                guarded {
                    val rpc = partyReportRpc()
                    val rows = rpc.overview(context.year)
                    // the hints are an extra: if they cannot be read, the table is still complete (no badge)
                    val checks = partgCheckCounts(runCatching { rpc.reportFindings(context.year) }.getOrDefault(emptyList()))
                    OverviewLoad(rows, checks)
                }
            },
            render = { body, loaded ->
                val rows = loaded.rows
                val table = body.standardTable(OVERVIEW_HEADERS)
                rows.forEach { row ->
                    table.row {
                        textCell(unitLabel(row.unit))
                        cell {
                            val (label, color) = partgFillBadge(row)
                            statusBadge(label, color)
                        }
                        totalCell(row.incomeTotalCents)
                        totalCell(row.expenseTotalCents)
                        totalCell(row.netAssetsCents)
                        cell {
                            val count = loaded.checks[row.unit.unitKey] ?: 0
                            if (count > 0) statusBadge(gettext("%1 zu prüfen", count), "warning")
                        }
                        cell {
                            addCssClass(TABLE_ACTIONS_CELL_CLASS)
                            tableActionGroup().apply {
                                tableActionButton(ActionIcon.EDIT, tr("Werte erfassen")).onClick { onEdit(row.unit) }
                                tableActionButton(ActionIcon.DOWNLOAD, tr("Vorlage herunterladen")).onClick {
                                    downloadAttachment(PartyReportHttp.templateUrl(context.year, row.unit.unitKey))
                                }
                                tableActionButton(ActionIcon.UPLOAD, tr("Excel-Datei importieren")).onClick {
                                    openPartyReportImportDialog(context.year, row.unit) { onEdit(row.unit) }
                                }
                            }
                        }
                    }
                }
            },
        )
    section.reload()
}

private fun io.kvision.table.Row.totalCell(cents: Long?) {
    numCell {
        if (cents == null) {
            add(io.kvision.html.Span("–", className = "text-muted"))
        } else {
            moneySpan(centsToDecimal(cents), warnIfNegative = true)
        }
    }
}
