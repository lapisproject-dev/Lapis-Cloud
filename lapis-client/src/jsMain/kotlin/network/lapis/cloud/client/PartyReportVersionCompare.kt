package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgDiffMarker
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import network.lapis.cloud.shared.domain.PartgVersionDto

/**
 * V1.9.291 (R6) -- compares two kept versions. Two selects (older / newer), one button, then a table of the differences only; a switch shows all rows.
 * When nothing differs the page says so in words. A missing or unknown value is "n. b."/"n. e.", never 0. Below 640 px the table turns into stacked
 * cards through the shared table grammar (`standardTable`).
 */
internal fun SimplePanel.renderVersionCompare(
    versions: List<PartgVersionDto>,
    units: List<PartgUnitDto>,
) {
    div(tr("Versionen vergleichen"), className = "fw-bold mt-3") { setAttribute("data-partg-compare", "1") }
    if (versions.size < 2) {
        div(tr("Zum Vergleichen werden mindestens zwei festgehaltene Versionen gebraucht."), className = "small text-muted")
        return
    }
    val options = versions.map { it.id to partgVersionOptionLabel(it, units) }
    val toolbar = lapisToolbar()
    val older = toolbar.select(options = options, value = versions.last().id, label = tr("Version A (älter)"))
    val newer = toolbar.select(options = options, value = versions.first().id, label = tr("Version B (neuer)"))
    val result = simplePanel()
    val live = div { setAttribute("aria-live", "polite") }
    live.addCssClasses("small text-muted")

    toolbar.actionGroup {
        actionButton(ActionIcon.SEARCH, tr("Vergleichen"), ButtonStyle.OUTLINEPRIMARY) {
            id = "partg-compare-run"
            onClick {
                val a = older.value ?: return@onClick
                val b = newer.value ?: return@onClick
                AppScope.launch {
                    val diff = guarded { partyReportRpc().compareVersions(a, b) }
                    result.removeAll()
                    if (diff == null) {
                        live.content = ""
                    } else {
                        live.content = if (partgNoDifferences(diff)) tr("Keine Unterschiede in den Zahlen.") else tr("Vergleich geladen.")
                        result.renderDiff(diff)
                    }
                }
            }
        }
    }
}

private fun SimplePanel.renderDiff(diff: PartgVersionDiffDto) {
    if (!diff.comparable) {
        div(
            tr("Die beiden Versionen gehören zu verschiedenen Jahren oder Umfängen. Verglichen werden nur die Zellen, die beide haben."),
            className = "small text-muted",
        )
    }
    if (partgNoDifferences(diff)) {
        div(tr("Keine Unterschiede in den Zahlen."), className = "mt-2") { setAttribute("data-partg-no-diff", "1") }
    }
    val box = CheckBox(value = false, label = tr("Alle Zeilen zeigen"))
    box.id = "partg-compare-all"
    add(box)
    val host = simplePanel()

    fun paint(showAll: Boolean) {
        host.removeAll()
        val rows = partgVisibleDiffRows(diff, showAll)
        if (rows.isNotEmpty()) {
            val table =
                host.standardTable(
                    listOf(
                        TableHeader(title = tr("Gliederung")),
                        TableHeader(title = tr("Position")),
                        TableHeader(title = tr("Version A"), numeric = true),
                        TableHeader(title = tr("Version B"), numeric = true),
                        TableHeader(title = tr("Unterschied"), numeric = true),
                    ),
                )
            rows.forEach { row ->
                if (row.marker == PartgDiffMarker.SAME) {
                    table.row {
                        textCell(row.label)
                        textCell(partgDiffMarkerWord(row.marker))
                        numCell("")
                        numCell("")
                        numCell("")
                    }
                } else {
                    row.cells.forEach { cell ->
                        table.row {
                            textCell(
                                if (row.marker ==
                                    PartgDiffMarker.CHANGED
                                ) {
                                    row.label
                                } else {
                                    gettext("%1 (%2)", row.label, partgDiffMarkerWord(row.marker))
                                },
                            )
                            textCell(partgDiffCodeLabel(cell.code))
                            numCell(partgDiffValueText(cell.before))
                            numCell(partgDiffValueText(cell.after))
                            numCell(partgDiffDeltaText(cell))
                        }
                    }
                }
            }
        }
        if (diff.truncated) host.div(tr("Die Liste ist gekürzt."), className = "small text-muted")
    }
    paint(false)
    // KVision reports the current value at once on subscription; the repaint is harmless
    box.subscribe { checked -> paint(checked == true) }
}
