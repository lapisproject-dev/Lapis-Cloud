package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import network.lapis.cloud.shared.domain.PartgUnitDto

/**
 * V1.9.269 "Rechenschaftsbericht R3" -- the plausibility hints above the download buttons. A hint is a pointer, never a verdict: it never blocks
 * the download, carries no name, no address and no amount (only unit, row numbers and a position) and is shown with a symbol AND a word, never by
 * colour alone (amber for "Bitte prüfen", neutral for "Zur Info", no red).
 */
internal fun SimplePanel.renderPartyReportFindings(
    context: PartyReportContext,
    units: List<PartgUnitDto>,
    findings: List<PartgFindingDto>,
) {
    val box = div(className = "mb-3") { setAttribute("data-partg-findings", "1") }
    box.div(tr("Hinweise zur Plausibilität"), className = "fw-bold mb-1")
    if (findings.isEmpty()) {
        box.div(tr("Keine Hinweise."), className = "small mb-1")
        return
    }
    val byKey = units.associateBy { it.unitKey }
    findings.forEach { finding -> box.findingLine(context, finding, finding.unitKey?.let { byKey[it] }) }
}

private fun Div.findingLine(
    context: PartyReportContext,
    finding: PartgFindingDto,
    unit: PartgUnitDto?,
) {
    val check = finding.level == PartgFindingLevel.CHECK
    val line =
        div(className = "small mb-1 d-flex flex-wrap align-items-center gap-2") { setAttribute("data-partg-finding", finding.code.name) }
    val icon =
        Span(
            className =
                (if (check) ActionIcon.WARN else ActionIcon.INFO).css + if (check) " text-warning-emphasis" else " text-muted",
        )
    icon.setAttribute("aria-hidden", "true")
    line.add(icon)
    line.add(Span(if (check) tr("Bitte prüfen") else tr("Zur Info"), className = "fw-semibold"))
    line.add(Span(partgFindingLine(finding, unit?.let { unitLabel(it) } ?: if (finding.unitKey == null) tr("Organisation") else null)))
    line.actionButton(ActionIcon.NEXT, tr("Anzeigen"), small = true) {
        onClick { context.openFinding(finding, unit) }
    }
}

/** Where the link of a hint leads: the tab and (for a unit) the unit. */
internal fun PartyReportContext.openFinding(
    finding: PartgFindingDto,
    unit: PartgUnitDto?,
) {
    if (unit != null) this.unit = unit
    if (finding.target == PartgFindingTarget.ANNEX) annexFocusBlock = partgFindingBlock(finding)
    openTab(
        when (finding.target) {
            PartgFindingTarget.ANNEX -> PartyReportTab.AUSWEISE
            PartgFindingTarget.NOTES -> PartyReportTab.ERLAEUTERUNGEN
            PartgFindingTarget.SHEET -> PartyReportTab.SHEET
        },
    )
}
