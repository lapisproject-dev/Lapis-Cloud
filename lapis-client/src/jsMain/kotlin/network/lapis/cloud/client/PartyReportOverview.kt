package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel

private val OVERVIEW_HEADERS =
    listOf(
        TableHeader(title = tr("Gliederung")),
        TableHeader(title = tr("Stand")),
        TableHeader(title = tr("Buchführung")),
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
    /** V1.9.290 (R5): the consolidated whole party; `null` for a chapter treasurer or when it could not be read. */
    val consolidation: PartgConsolidationDto? = null,
)

/** The three bookkeeping modes as the person reads them (V1.9.289, R4a). */
internal fun partgModeLabel(mode: PartgBookkeepingMode): String =
    when (mode) {
        PartgBookkeepingMode.A -> tr("A – Zentral gebucht")
        PartgBookkeepingMode.B -> tr("B – Eigene Kasse in Lapis")
        PartgBookkeepingMode.C -> tr("C – Eigene Kasse außerhalb")
    }

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
    val restricted = partgChapterTreasurerOnly(AppState.session)
    if (!restricted) {
        actionGroup {
            actionButton(ActionIcon.EXPORT, tr("Bericht erzeugen"), ButtonStyle.OUTLINEPRIMARY) { onClick { onReport() } }
        }
    }
    lateinit var section: DataSection
    section =
        dataSection<OverviewLoad>(
            emptyText = tr("Keine Gliederungen vorhanden."),
            isEmpty = { it.rows.isEmpty() },
            load = {
                guarded {
                    val rpc = partyReportRpc()
                    val rows = rpc.overview(context.year)
                    // the hints are an extra: if they cannot be read, the table is still complete (no badge)
                    val checks = partgCheckCounts(runCatching { rpc.reportFindings(context.year) }.getOrDefault(emptyList()))
                    // R5: the consolidated whole party only for the federal view (a chapter treasurer never calls it); an extra like the hints
                    val consolidation = if (restricted) null else runCatching { rpc.getConsolidation(context.year) }.getOrNull()
                    OverviewLoad(rows, checks, consolidation)
                }
            },
            render = { body, loaded ->
                val rows = loaded.rows
                val table = body.standardTable(OVERVIEW_HEADERS)
                loaded.consolidation?.let { table.consolidatedRow(it) { context.openTab(PartyReportTab.KONSOLIDIERUNG) } }
                rows.forEach { row ->
                    table.row {
                        textCell(unitLabel(row.unit))
                        cell {
                            val (label, color) = partgFillBadge(row)
                            statusBadge(label, color)
                        }
                        cell { modeCell(context, row) { section.reload() } }
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
                                if (!restricted) {
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
                }
            },
        )
    section.reload()
}

/**
 * The bookkeeping mode of a unit: a select for the federal roles (a change with existing bookings asks first and names the number; the bookings
 * stay untouched), plain text for a chapter treasurer, and for a summary of sub-units always "C" with its reason.
 */
private fun io.kvision.core.Container.modeCell(
    context: PartyReportContext,
    row: PartgOverviewRowDto,
    reload: () -> Unit,
) {
    if (row.unit.level == PartgUnitLevel.SUBORDINATE) {
        div(partgModeLabel(PartgBookkeepingMode.C))
        div(
            tr("Die Gesamtheit der nachgeordneten Gliederungen wird immer außerhalb von Lapis geführt."),
        ) { addCssClasses("small text-muted") }
        return
    }
    if (!row.modeEditable) {
        div(partgModeLabel(row.mode))
        return
    }
    val picker =
        select(
            options = PartgBookkeepingMode.entries.map { it.name to partgModeLabel(it) },
            value = row.mode.name,
        )
    val pickerLabel = resolvedAttributeText(tr("Buchführung")) + " " + unitLabel(row.unit)
    picker.setAttribute("aria-label", pickerLabel)
    picker.subscribe {
        val chosen = picker.value?.let { name -> PartgBookkeepingMode.entries.firstOrNull { it.name == name } } ?: return@subscribe
        if (chosen == row.mode) return@subscribe

        fun apply() {
            AppScope.launch {
                val saved = guarded { partyReportRpc().setUnitMode(context.year, row.unit.unitKey, chosen, row.bookingCount) }
                if (saved != null) notifySuccess(tr("Buchführung gespeichert."))
                reload()
            }
        }
        if (row.bookingCount > 0) {
            picker.value = row.mode.name
            confirmDialog(
                title = tr("Buchführung ändern?"),
                message =
                    gettext(
                        "Für %1 gibt es im Jahr %2 bereits %3 Buchungen. Die Buchungen bleiben unverändert.",
                        unitLabel(row.unit),
                        context.year,
                        row.bookingCount,
                    ),
                extraLines =
                    if (chosen ==
                        PartgBookkeepingMode.C
                    ) {
                        listOf(tr("Im Modus C werden keine Werte aus Buchungen vorgeschlagen."))
                    } else {
                        emptyList()
                    },
                confirmLabel = tr("Ändern"),
                confirmStyle = ButtonStyle.PRIMARY,
                onConfirm = { apply() },
            )
        } else {
            apply()
        }
    }
}

/** V1.9.290 (R5): the first row of the overview, the whole party consolidated (a draft), with the link to the tab. */
private fun io.kvision.table.Table.consolidatedRow(
    dto: PartgConsolidationDto,
    openTab: () -> Unit,
) {
    fun cellOf(code: String) = dto.rows.firstOrNull { it.code == code }?.consolidated
    row {
        textCell(tr("Gesamtpartei (konsolidiert, Entwurf)"))
        cell { statusBadge(tr("Entwurf"), "secondary") }
        textCell("")
        listOf("E11", "A04", "NET").forEach { code ->
            numCell {
                when (val c = cellOf(code)) {
                    is PartgConsolidatedCell.Value -> moneySpan(centsToDecimal(c.cents), warnIfNegative = true)
                    else -> add(io.kvision.html.Span(tr("n. b."), className = "text-muted"))
                }
            }
        }
        textCell("")
        cell {
            addCssClass(TABLE_ACTIONS_CELL_CLASS)
            tableActionGroup().apply {
                tableActionButton(ActionIcon.NEXT, tr("Zur Konsolidierung")).onClick { openTab() }
            }
        }
    }
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
