package network.lapis.cloud.client

import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.document
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgUnitDto

private val OVERVIEW_HEADERS =
    listOf(
        TableHeader(title = tr("Gliederung")),
        TableHeader(title = tr("Stand")),
        TableHeader(title = tr("Einnahmen"), numeric = true),
        TableHeader(title = tr("Ausgaben"), numeric = true),
        TableHeader(title = tr("Reinvermögen"), numeric = true),
        TableHeader(title = tr("Aktionen")),
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
) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    val section =
        dataSection<List<PartgOverviewRowDto>>(
            emptyText = tr("Keine Gliederungen vorhanden."),
            isEmpty = { it.isEmpty() },
            load = { guarded { partyReportRpc().overview(context.year) } },
            render = { body, rows ->
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
                            addCssClass(TABLE_ACTIONS_CELL_CLASS)
                            tableActionGroup().apply {
                                tableActionButton(ActionIcon.EDIT, tr("Werte erfassen")).onClick { onEdit(row.unit) }
                                tableActionButton(ActionIcon.DOWNLOAD, tr("Vorlage herunterladen")).onClick {
                                    downloadTemplate(PartyReportHttp.templateUrl(context.year, row.unit.unitKey))
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

/** Starts the download of the template through a temporary anchor (no page navigation, the response is an attachment). */
private fun downloadTemplate(url: String) {
    val anchor = document.createElement("a")
    anchor.setAttribute("href", url)
    anchor.setAttribute("download", "")
    anchor.setAttribute("rel", "noopener")
    document.body?.appendChild(anchor)
    (anchor as org.w3c.dom.HTMLElement).click()
    document.body?.removeChild(anchor)
}
