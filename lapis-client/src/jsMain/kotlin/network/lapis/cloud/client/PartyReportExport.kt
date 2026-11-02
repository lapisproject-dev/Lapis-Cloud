package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnitDto

private class ExportSetup(
    val units: List<PartgUnitDto>,
    val completeness: PartgCompletenessDto,
)

/**
 * V1.9.256 "Rechenschaftsbericht R2" -- the "Bericht" tab: pick the scope (whole party or one unit), read the checklist of what is still missing
 * and download the DRAFT as PDF or Excel. The checklist never blocks: both buttons stay enabled (a draft with gaps is a draft with "n. e."
 * markers, which is what the finance board wants to see). The notice says plainly that the draft is no official report.
 */
internal fun SimplePanel.renderPartyReportExport(context: PartyReportContext) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    dataSection<ExportSetup>(
        isEmpty = { false },
        load = {
            guarded {
                val rpc = partyReportRpc()
                ExportSetup(units = rpc.listUnits(), completeness = rpc.reportCompleteness(context.year))
            }
        },
        render = { body, setup -> body.renderExportBody(context, setup) },
    ).reload()
}

private fun SimplePanel.renderExportBody(
    context: PartyReportContext,
    setup: ExportSetup,
) {
    val options = listOf(PartgReportScope.ALL_KEY to tr("Gesamtpartei (alle Einheiten)")) + setup.units.map { it.unitKey to unitLabel(it) }
    if (options.none { it.first == context.reportScope }) context.reportScope = PartgReportScope.ALL_KEY
    val toolbar = lapisToolbar()
    val scopeSelect = toolbar.select(options = options, value = context.reportScope, label = tr("Umfang"))
    val checklistHost = simplePanel()

    fun paint() {
        checklistHost.removeAll()
        checklistHost.renderChecklist(context, setup)
    }
    scopeSelect.subscribe {
        val key = scopeSelect.value ?: return@subscribe
        if (key == context.reportScope) return@subscribe
        context.reportScope = key
        paint()
    }
    paint()

    div(className = "alert alert-warning small mb-0") {
        setAttribute("role", "note")
        div(
            tr(
                "Das ist ein Entwurf und kein amtlicher Bericht. Er ist nicht geprüft und ersetzt weder den geprüften " +
                    "Rechenschaftsbericht " +
                    "noch eine Rechtsberatung. Nicht erfasste Werte erscheinen als „n. e.“, nicht als 0.",
            ),
        )
    }
    val downloads = actionGroup()
    downloads.apply {
        actionButton(ActionIcon.DOWNLOAD, tr("PDF herunterladen"), ButtonStyle.PRIMARY, labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { downloadAttachment(PartyReportHttp.exportUrl(PartgReportFormat.PDF, context.year, context.reportScope)) }
        }
        actionButton(ActionIcon.DOWNLOAD, tr("Excel herunterladen"), ButtonStyle.OUTLINEPRIMARY, labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { downloadAttachment(PartyReportHttp.exportUrl(PartgReportFormat.XLSX, context.year, context.reportScope)) }
        }
    }
}

private fun SimplePanel.renderChecklist(
    context: PartyReportContext,
    setup: ExportSetup,
) {
    val lines = completenessLines(setup.completeness, context.reportScope)
    val missingLines = lines.filter { it.kind == PartgChecklistKind.MISSING }
    val box = div(className = "mb-3") { setAttribute("data-partg-checklist", "1") }
    box.div(tr("Vollständigkeit"), className = "fw-bold mb-1")
    if (missingLines.isEmpty()) box.div(tr("Alle Positionen sind erfasst."), className = "small mb-1")
    lines.forEach { line ->
        val unit = line.unit
        when (line.kind) {
            PartgChecklistKind.COMPLETE_SUMMARY ->
                box.div(gettext("Einheiten vollständig: %1", line.count), className = "small text-success mb-1")
            PartgChecklistKind.PRIOR_NONE ->
                box
                    .div(
                        gettext("%1: Vorjahr nicht erfasst – Spalte bleibt leer", unitLabel(checkNotNull(unit))),
                        className = "small text-muted mb-1",
                    ).also { it.setAttribute("data-prior", "none") }
            PartgChecklistKind.PRIOR_PARTIAL ->
                box
                    .div(
                        gettext(
                            "%1: Vorjahr: %2 von %3 Positionen nicht erfasst",
                            unitLabel(checkNotNull(unit)),
                            line.count,
                            line.leafCount,
                        ),
                        className = "small text-muted mb-1",
                    ).also { it.setAttribute("data-prior", "partial") }
            PartgChecklistKind.MISSING -> box.missingDetails(context, checkNotNull(unit), line)
        }
    }
}

private fun Container.missingDetails(
    context: PartyReportContext,
    unit: PartgUnitDto,
    line: PartgChecklistLine,
) {
    val details = Tag(TAG.DETAILS, className = "small border rounded p-2 mb-2 text-warning-emphasis")
    details.setAttribute("data-missing", unit.unitKey)
    details.add(
        Tag(
            TAG.SUMMARY,
            content = gettext("%1: %2 von %3 Positionen nicht erfasst", unitLabel(unit), line.count, line.leafCount),
            className = "fw-bold",
        ),
    )
    val list = Div(className = "mt-1")
    line.missing.forEach { code -> list.div(partgLabel(code), className = "small") }
    details.add(list)
    details.actionGroup {
        actionButton(
            ActionIcon.EDIT,
            tr("Werte erfassen"),
            ButtonStyle.OUTLINEPRIMARY,
            small = true,
        ) { onClick { context.openSheetFor(unit) } }
    }
    add(details)
}
