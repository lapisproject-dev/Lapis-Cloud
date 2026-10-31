package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.ActivismActionSummaryDto
import network.lapis.cloud.shared.domain.ActivismListFilter
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.rpc.IActivismService

/**
 * V1.9.246 "Aktionen A1" -- `/activism`, the list of actions. Every participant may open it; the server decides what each person sees.
 * The status chips start at "Geplant" and "Läuft". Titles are texts of other people: they are set ONLY through the `untrusted*` helpers.
 */
fun renderActivismScreen(container: SimplePanel) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.pageHeader(
        tr("Aktionen"),
        intro = PageIntros.forRoute(Routes.ACTIVISM),
        primaryAction = {
            actionButton(ActionIcon.ADD, tr("Neue Aktion"), ButtonStyle.PRIMARY) { onClick { navigateTo(Routes.ACTIVISM_NEW) } }
        },
    )
    var selected: Set<ActivismStatus> = ActivismLogic.DEFAULT_STATUSES.toSet()
    lateinit var data: DataSection
    val chips = root.div(className = "d-flex flex-wrap gap-3") { setAttribute("role", "group") }
    chips.setAttribute("aria-label", gettext("Status"))
    ActivismStatus.entries.forEach { status ->
        val box = chips.checkBox(value = status in selected, label = ActivismTexts.status(status))
        box.onClick {
            selected = if (box.value == true) selected + status else selected - status
            data.reload()
        }
    }
    data =
        root.dataSection<List<ActivismActionSummaryDto>>(
            emptyText = gettext("Noch keine Aktionen. Legen Sie eine Idee an."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<IActivismService>().listActions(ActivismListFilter(statuses = selected.toList())) } },
            render = { panel, rows -> renderActivismRows(panel, rows) },
        )
    data.reload()
    root.div(className = "mt-2") {
        actionButton(ActionIcon.NEXT, tr("Einstellungen und Meldungen"), small = true) { onClick { navigateTo(Routes.ACTIVISM_SETTINGS) } }
    }
}

private fun renderActivismRows(
    panel: SimplePanel,
    rows: List<ActivismActionSummaryDto>,
) {
    val list = panel.div { setAttribute("role", "list") }
    rows.forEach { row -> renderActivismRow(list, row) }
}

private fun renderActivismRow(
    list: Container,
    row: ActivismActionSummaryDto,
) {
    val item = list.div(className = "border-bottom py-2 d-flex flex-wrap align-items-start gap-2") { setAttribute("role", "listitem") }
    val info = item.div(className = "flex-grow-1")
    info.untrustedDiv(row.title, className = "fw-bold")
    val meta = info.div(className = "small text-muted d-flex flex-wrap gap-2")
    meta.span(ActivismTexts.kind(row.kind))
    meta.span(ActivismTexts.status(row.status), className = "badge text-bg-secondary")
    if (row.formAppointment) {
        meta.span(
            ActivismTexts.form(network.lapis.cloud.shared.domain.ActivismForm.APPOINTMENT),
            className = "badge text-bg-light",
        )
    }
    if (row.formProgress) {
        meta.span(
            ActivismTexts.form(network.lapis.cloud.shared.domain.ActivismForm.PROGRESS),
            className = "badge text-bg-light",
        )
    }
    if (row.formSignal) {
        meta.span(
            ActivismTexts.form(network.lapis.cloud.shared.domain.ActivismForm.SIGNAL),
            className = "badge text-bg-light",
        )
    }
    row.startsAt?.let { meta.span(formatDateTime(it)) }
    ActivismLogic.summaryProgress(row)?.let { info.div(it, className = "small") }
    item.actionButton(ActionIcon.VIEW, tr("Öffnen"), small = true) {
        onClick { navigateTo("${Routes.ACTIVISM}/${row.id}") }
    }
}
