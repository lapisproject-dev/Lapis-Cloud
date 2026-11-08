package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto

/**
 * V1.9.291 (R6) -- the line beside the year, on every tab (BOARD/ADMIN only; the chapter treasurer never gets it): where the report of the year
 * stands (symbol AND word), the deadline, and -- in a polite live region that is mounted from the start -- the warning when the working state
 * deviates from the newest kept version. It loads on construction, on a change of the year, on a change of the tab and after every successful save
 * (`PartyReportContext.onSaved`). A failed load leaves the line empty: it is an aid, not a gate.
 */
internal class PartyReportStatusLine(
    host: SimplePanel,
    private val context: PartyReportContext,
) {
    private val line: Div =
        host.div(
            className = "d-flex flex-wrap align-items-center gap-2 small",
        ) { setAttribute("data-partg-yearstatus", "1") }
    private val warning: Div =
        host.div(className = "small text-warning-emphasis") {
            // mounted empty, so a screen reader announces the change of the text and not the insertion of a region
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
            setAttribute("data-partg-deviation", "1")
        }
    private var generation = 0

    fun refresh() {
        val mine = ++generation
        val year = context.year
        AppScope.launch {
            // quiet on purpose: the line is an aid, a failed load must not raise a toast on every tab
            val overview =
                try {
                    partyReportRpc().versionOverview(year)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            // an answer of an older year or an older call must not overwrite a newer one
            if (mine == generation && overview != null) paint(overview)
        }
    }

    private fun paint(overview: PartgVersionOverviewDto) {
        line.removeAll()
        val look = partgVersionStatusLook(overview.yearStatus)
        line.span(className = "fw-semibold") { content = gettext("Rechnungsjahr %1:", overview.year.toString()) }
        line.add(statusPill(look))
        line.span(partgDeadlineText(overview.deadline))
        partgDeadlineLook(overview.deadline.level)?.let { loud ->
            line.add(statusPill(loud))
        }
        warning.content = partgDeviationText(overview.draftDeviation).orEmpty()
    }

    private fun statusPill(look: PartgStatusLook): Span =
        Span(className = "badge rounded-pill text-bg-${look.color}").apply {
            icon(look.icon).setAttribute("aria-hidden", "true")
            span(" " + look.word)
        }
}
