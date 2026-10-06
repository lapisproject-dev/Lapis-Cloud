package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.table.row
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberCountHistoryQuery
import network.lapis.cloud.shared.domain.MemberStatisticsRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.IMemberStatisticsService

/*
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- "Mitgliederentwicklung": how many members per status at the end of each month, quarter or
 * year. BOARD/ADMIN only (route AND server: `MemberStatisticsService` requires BOARD/ADMIN as its first statement).
 *
 * The numbers come from the append-only status log, so a past figure is what the log says for that date. Every number is in the table
 * too (always in the DOM, behind a toggle) -- the chart is a picture of the table, never the only place a figure appears. Withdrawn,
 * rejected and deceased start hidden in the chart (legend click shows them). Periods that rest on a reconstruction of the past are
 * drawn pale, with a note on what that means. The time range and the division are two groups of buttons; a combination the server
 * would refuse (more than 240 periods) is disabled, with a line saying why -- the screen never switches a choice silently.
 */

private const val STATISTICS_TABLE_ID = "lapis-member-statistics-table"

/** The collaborators of the screen, injectable so a DOM test can feed figures without an RPC and count chart instances. */
internal class MemberStatisticsDeps(
    val load: suspend (MemberCountHistoryQuery) -> MemberCountHistoryDto? = { query ->
        guarded { rpcService<IMemberStatisticsService>().getMemberCountHistory(query) }
    },
    val today: () -> LocalDate = { organizationToday() },
    val chart: StatisticsChartDeps = StatisticsChartDeps(),
    val download: (fileName: String, content: String) -> Unit = { name, content -> downloadCsvFile(name, content) },
)

fun renderMemberStatisticsScreen(container: SimplePanel) = renderMemberStatisticsScreen(container, MemberStatisticsDeps())

internal fun renderMemberStatisticsScreen(
    container: SimplePanel,
    deps: MemberStatisticsDeps,
) {
    val root = container.dataScreenRoot()
    val header = root.pageHeader(tr("Mitgliederentwicklung"))
    val exportButton = header.actionSlot.actionButton(ActionIcon.EXPORT, tr("CSV exportieren"))
    exportButton.disabled = true

    var range = StatisticsRange.LAST_12_MONTHS
    var granularity = MemberCountGranularity.MONTH
    var earliest: LocalDate? = null
    var lastDto: MemberCountHistoryDto? = null
    var lastQuery: MemberCountHistoryQuery? = null
    lateinit var section: DataSection

    val toolbar = root.lapisToolbar()
    val hint = root.div(className = "text-muted small")
    val rangeButtons = linkedMapOf<StatisticsRange, Button>()
    val granularityButtons = linkedMapOf<MemberCountGranularity, Button>()

    fun possible(
        r: StatisticsRange,
        g: MemberCountGranularity,
    ) = statisticsCombinationPossible(r, g, deps.today(), earliest)

    fun repaint() {
        rangeButtons.forEach { (r, b) ->
            val active = r == range
            b.style = if (active) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEPRIMARY
            b.setAttribute("aria-pressed", active.toString())
            b.disabled = !possible(r, granularity)
        }
        granularityButtons.forEach { (g, b) ->
            val active = g == granularity
            b.style = if (active) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEPRIMARY
            b.setAttribute("aria-pressed", active.toString())
            b.disabled = !possible(range, g)
        }
        val blocked =
            rangeButtons.keys.any { !possible(it, granularity) } || granularityButtons.keys.any { !possible(range, it) }
        hint.content =
            if (blocked) {
                gettext("Nicht wählbar sind Kombinationen mit mehr als %1 Zeiträumen.", MemberStatisticsRules.MAX_POINTS)
            } else {
                ""
            }
    }

    fun segmentGroup(
        ariaLabel: String,
        build: Div.() -> Unit,
    ) {
        toolbar.div(className = "btn-group btn-group-sm") {
            setAttribute("role", "group")
            setAttribute("aria-label", resolvedAttributeText(ariaLabel))
            build()
        }
    }

    toolbar.toolbarText(tr("Zeitraum"))
    segmentGroup(tr("Zeitraum")) {
        listOf(
            StatisticsRange.LAST_12_MONTHS to tr("Letzte 12 Monate"),
            StatisticsRange.THIS_YEAR to tr("Dieses Jahr"),
            StatisticsRange.LAST_5_YEARS to tr("Letzte 5 Jahre"),
            StatisticsRange.SINCE_START to tr("Seit Beginn"),
        ).forEach { (r, label) ->
            val b = button(label, style = ButtonStyle.OUTLINEPRIMARY)
            rangeButtons[r] = b
            b.onClick {
                if (range != r && possible(r, granularity)) {
                    range = r
                    repaint()
                    section.reload()
                }
            }
        }
    }
    toolbar.toolbarText(tr("Einteilung"))
    segmentGroup(tr("Einteilung")) {
        listOf(
            MemberCountGranularity.MONTH to tr("Monat"),
            MemberCountGranularity.QUARTER to tr("Quartal"),
            MemberCountGranularity.YEAR to tr("Jahr"),
        ).forEach { (g, label) ->
            val b = button(label, style = ButtonStyle.OUTLINEPRIMARY)
            granularityButtons[g] = b
            b.onClick {
                if (granularity != g && possible(range, g)) {
                    granularity = g
                    repaint()
                    section.reload()
                }
            }
        }
    }
    repaint()

    exportButton.onClick {
        val dto = lastDto
        val query = lastQuery
        if (dto != null && query != null) {
            deps.download(
                memberCountCsvFileName(isoDay(query.from), isoDay(query.to), query.granularity),
                memberCountCsv(dto, statisticsCsvLabels()),
            )
        }
    }

    section =
        root.dataSection<MemberCountHistoryDto>(
            emptyText = tr("Noch keine Mitgliederzahlen vorhanden."),
            isEmpty = { it.earliestDate == null },
            onSettled = { dto ->
                lastDto = dto
                if (dto != null) earliest = dto.earliestDate
                exportButton.disabled = dto == null || dto.earliestDate == null
                repaint()
            },
            load = {
                val (from, to) = statisticsBounds(range, deps.today(), earliest)
                val query = MemberCountHistoryQuery(from = from, to = to, granularity = granularity)
                lastQuery = query
                deps.load(query)
            },
            render = { panel, dto -> renderStatisticsContent(panel, dto, deps) },
        )
    section.reload()
}

private fun statisticsCsvLabels() =
    StatisticsCsvLabels(
        period = gettext("Zeitraum"),
        start = gettext("Beginn"),
        endExclusive = gettext("Ende (exklusiv)"),
        dataBasis = gettext("Datenbasis"),
        recorded = gettext("aufgezeichnet"),
        reconstructed = gettext("rekonstruiert"),
        running = gettext("laufend"),
        status = { memberStatusLabel(it) },
    )

private fun renderStatisticsContent(
    panel: SimplePanel,
    dto: MemberCountHistoryDto,
    deps: MemberStatisticsDeps,
) {
    val running = gettext("laufend")
    val labels = dto.points.map { statisticsPeriodLabel(it.periodStart, dto.granularity, it.current, running) }

    statisticsKeyFigures(dto)?.let { figures ->
        val line =
            if (figures.activeChange == null) {
                gettext("Aktive Mitglieder: %1", figures.activeNow)
            } else {
                gettext(
                    "Aktive Mitglieder: %1 (%2 seit Ende von %3)",
                    figures.activeNow,
                    signedNumber(figures.activeChange),
                    labels.first(),
                )
            }
        panel.div(line, className = "fw-bold")
    }

    panel.mountMemberCountChart(
        points = dto.points,
        labels = labels,
        ariaLabel = gettext("Gestapeltes Säulendiagramm der Mitglieder je Status im Zeitverlauf. Alle Werte stehen auch in der Tabelle."),
        describedBy = STATISTICS_TABLE_ID,
        statusLabel = { memberStatusLabel(it) },
        reconstructedText = gettext("rekonstruiert"),
        deps = deps.chart,
    )

    if (dto.points.any { it.reconstructed }) {
        val upTo = dto.reconstructedBefore?.let { formatDate(systemDate(it)) } ?: ""
        panel.div(
            gettext(
                "Hinweis zur Genauigkeit: Bis zum %1 wurde der Verlauf aus vorhandenen Aufzeichnungen rekonstruiert (blasse Balken). " +
                    "Dabei zählt ein Mitglied erst ab dem frühesten Datum, das seinen heutigen Status belegt (meist das Eintrittsdatum). " +
                    "Ein Austritt ohne Datum kann zu früh oder zu spät erscheinen.",
                upTo,
            ),
            className = "text-muted small",
        )
    }

    val tableBox = panel.div()
    tableBox.setAttribute("id", STATISTICS_TABLE_ID)
    // `d-none`, NOT `hide()`: a KVision widget that is not visible is not rendered at all, and the table must stay in the DOM (the
    // chart is described by it, and assistive technology can read it while it is collapsed).
    tableBox.addCssClass("d-none")
    val toggle = panel.actionButton(ActionIcon.VIEW, gettext("Tabelle anzeigen"), small = true)
    toggle.setAttribute("aria-expanded", "false")
    toggle.setAttribute("aria-controls", STATISTICS_TABLE_ID)
    var open = false
    toggle.onClick {
        open = !open
        if (open) tableBox.removeCssClass("d-none") else tableBox.addCssClass("d-none")
        toggle.text = if (open) gettext("Tabelle ausblenden") else gettext("Tabelle anzeigen")
        toggle.setAttribute("aria-expanded", open.toString())
    }
    statisticsTable(tableBox, dto, labels)
}

private fun statisticsTable(
    host: Div,
    dto: MemberCountHistoryDto,
    labels: List<String>,
) {
    val headers =
        listOf(TableHeader(title = gettext("Zeitraum"))) +
            STATISTICS_STATUS_ORDER.map { TableHeader(title = memberStatusLabel(it), numeric = true) } +
            TableHeader(title = gettext("Datenbasis"))
    val table = host.reportTable(caption = gettext("Mitglieder je Status am Ende des jeweiligen Zeitraums"), headers = headers)
    dto.points.forEachIndexed { i, point ->
        table.row {
            textCell(labels[i])
            STATISTICS_STATUS_ORDER.forEach { status: MemberStatus -> numCell((point.counts[status] ?: 0).toString()) }
            val basis =
                when {
                    point.current -> gettext("laufend")
                    point.reconstructed -> gettext("rekonstruiert")
                    else -> gettext("aufgezeichnet")
                }
            textCell(basis)
        }
    }
}
