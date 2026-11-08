package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.table.row
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgUnitStatementDto

private class ChapterReportSetup(
    val units: List<PartgUnitDto>,
)

private val CHAPTER_REPORT_HEADERS =
    listOf(
        TableHeader(title = tr("Nr.")),
        TableHeader(title = tr("Position")),
        TableHeader(title = tr("Berichtsjahr"), numeric = true),
        TableHeader(title = tr("Vorjahr"), numeric = true),
    )

/** The text of one report cell: an amount, an amount marked as incomplete, or "n. e." (never 0). */
internal fun partgCellText(cell: PartgCell?): String =
    when (cell) {
        is PartgCell.Value -> partgInputText(cell.cents) + " €"
        is PartgCell.Partial -> gettext("%1 (unvollst.)", partgInputText(cell.cents) + " €")
        PartgCell.NotEntered, null -> tr("n. e.")
    }

/**
 * V1.9.292 (R4b) -- the tab "Bericht" of a chapter treasurer: ONLY the unit part of their own units. A read-only preview (the same source as the
 * download: `unitStatementPreview`), the three downloads (PDF, Excel, journal CSV) and the plain notice that it is a draft. There is deliberately NO
 * element for the whole party, the consolidation, the statements or the kept versions: the server refuses them anyway, this does not even offer them.
 */
internal fun SimplePanel.renderChapterUnitReport(context: PartyReportContext) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    div(tr("Teilbericht Ihrer Gliederung – Entwurf"), className = "fw-bold") { setAttribute("data-partg-chapter-report", "1") }
    dataSection<ChapterReportSetup>(
        emptyText = tr("Keine Gliederungen vorhanden."),
        isEmpty = { it.units.isEmpty() },
        load = { guarded { ChapterReportSetup(units = partyReportRpc().listUnits().filter { it.level != PartgUnitLevel.FEDERAL }) } },
        render = { body, setup -> body.renderChapterReportBody(context, setup) },
    ).reload()
}

private fun SimplePanel.renderChapterReportBody(
    context: PartyReportContext,
    setup: ChapterReportSetup,
) {
    val units = setup.units
    // the shared scope of the page may still be the whole party (the default): a chapter treasurer starts with their first unit
    var chosen = units.firstOrNull { it.unitKey == context.reportScope } ?: units.first()
    context.reportScope = chosen.unitKey
    val toolbar = lapisToolbar()
    toolbar.span(tr("Gliederung"), className = "fw-semibold me-1")
    val unitSelect = toolbar.select(options = units.map { it.unitKey to unitLabel(it) }, value = chosen.unitKey)
    val unitSelectLabel = resolvedAttributeText(tr("Gliederung"))
    unitSelect.setAttribute("aria-label", unitSelectLabel)
    div(className = "alert alert-warning small mb-0") {
        setAttribute("role", "note")
        div(
            tr(
                "Das ist ein Entwurf und kein amtlicher Bericht. Er enthält nur die Zahlen Ihrer Gliederung, ohne gesonderte Ausweise " +
                    "und ohne Erläuterungen. Nicht erfasste Werte erscheinen als „n. e.“, nicht als 0.",
            ),
        )
    }
    val previewHost = simplePanel()
    val downloads = actionGroup()

    fun paintPreview() {
        previewHost.removeAll()
        val unit = chosen
        previewHost
            .dataSection<PartgUnitStatementDto>(
                isEmpty = { false },
                load = { guarded { partyReportRpc().unitStatementPreview(context.year, unit.unitKey) } },
                render = { body, statement -> body.renderChapterStatement(context, units, statement) },
            ).reload()
    }
    unitSelect.subscribe {
        val key = unitSelect.value ?: return@subscribe
        if (key == chosen.unitKey) return@subscribe
        chosen = units.first { it.unitKey == key }
        context.reportScope = key
        paintPreview()
    }
    downloads.apply {
        actionButton(ActionIcon.DOWNLOAD, tr("PDF herunterladen"), ButtonStyle.PRIMARY, labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { downloadAttachment(PartyReportHttp.exportUrl(PartgReportFormat.PDF, context.year, chosen.unitKey)) }
        }
        actionButton(ActionIcon.DOWNLOAD, tr("Excel herunterladen"), ButtonStyle.OUTLINEPRIMARY, labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { downloadAttachment(PartyReportHttp.exportUrl(PartgReportFormat.XLSX, context.year, chosen.unitKey)) }
        }
        // a subordinate summary has no bookings (Q20 is open): there is nothing to download, so the button is not offered for it
        actionButton(ActionIcon.DOWNLOAD, tr("Buchungsjournal (CSV)"), ButtonStyle.OUTLINEPRIMARY, labelMode = ActionLabelMode.RESPONSIVE) {
            setAttribute("data-partg-journal-csv", "1")
            onClick {
                if (chosen.level == PartgUnitLevel.SUBORDINATE) {
                    notifyInfo(tr("Für nachgeordnete Gliederungen gibt es kein Buchungsjournal."))
                } else {
                    downloadAttachment(PartyReportHttp.journalCsvUrl(context.year, chosen.unitKey))
                }
            }
        }
    }
    paintPreview()
}

private fun SimplePanel.renderChapterStatement(
    context: PartyReportContext,
    units: List<PartgUnitDto>,
    statement: PartgUnitStatementDto,
) {
    val box = div(className = "mb-3") { setAttribute("data-partg-chapter-preview", statement.unit.unitKey) }
    box.div(className = "small mb-1") {
        span(tr("Buchführung"), className = "fw-semibold me-1")
        span(partgModeLabel(statement.mode))
    }
    if (statement.unit.level == PartgUnitLevel.SUBORDINATE) {
        box.div(tr("Für nachgeordnete Gliederungen gibt es kein Buchungsjournal."), className = "small text-muted mb-1")
    } else if (statement.postedEntryCount == 0) {
        box
            .div(
                tr(
                    "Beiträge und Spenden werden auf Bundesebene gebucht. " +
                        "Den Anteil Ihrer Gliederung bucht der Bundesschatzmeister als Zuschuss.",
                ),
                className = "small text-muted mb-1",
            ).setAttribute("data-partg-chapter-empty", "1")
    }
    val table = box.standardTable(CHAPTER_REPORT_HEADERS)
    PartgPositions.ALL.forEach { position ->
        table.row {
            textCell(position.numberLabel)
            textCell(partgLabel(position.code))
            numCell(partgCellText(statement.current[position.code]))
            numCell(partgCellText(statement.prior[position.code]))
        }
    }
    renderPartyReportFindings(context, units, statement.findings)
}
