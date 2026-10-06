package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.snabbdom.VNode
import io.kvision.utils.perc
import io.kvision.utils.rem
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.chart.Chart
import network.lapis.cloud.client.chart.MutationObserver
import network.lapis.cloud.shared.domain.MemberCountPointDto
import network.lapis.cloud.shared.domain.MemberStatus
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt

/*
 * Welle V1.9.59 -- the stacked bar chart of the member counts. `chart.js/auto` (already a dependency, see chart/Chart.kt) registers the
 * bar controller; no new package. Everything that decides what the chart shows is a pure function ([buildMemberCountChartConfig]) so
 * a test can read the configuration without a canvas; the lifecycle (create on insert, re-colour on a theme switch, destroy on
 * removal) is [mountMemberCountChart], built on the same pattern as the price-history chart (PriceOracleScreen.renderPriceHistoryChart).
 */

/** The colours the chart paints with, read from the `--lapis-*` custom properties (never hard-coded here). */
internal class StatisticsChartColors(
    val status: Map<MemberStatus, String>,
    val surface: String,
    val border: String,
    val muted: String,
    val text: String,
)

/** Alpha of the bars of a reconstructed period: visibly paler, never invisible. */
internal const val RECONSTRUCTED_ALPHA = 0.45

/**
 * A six-digit hex colour with [alpha] appended as two more hex digits (the eight-digit form canvas understands); anything else is
 * returned unchanged (a named colour cannot be made translucent here).
 */
internal fun withAlpha(
    color: String,
    alpha: Double,
): String {
    val hex = color.trim()
    if (hex.length != 7 || hex[0] != '#' || hex.substring(1).any { it.digitToIntOrNull(16) == null }) return hex
    val channel = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt()
    return hex + channel.toString(16).padStart(2, '0').uppercase()
}

/** The bar colour of each point of one status: pale where the figure rests on a reconstruction. */
internal fun statisticsPointColors(
    points: List<MemberCountPointDto>,
    statusColor: String,
): Array<String> = points.map { if (it.reconstructed) withAlpha(statusColor, RECONSTRUCTED_ALPHA) else statusColor }.toTypedArray()

/** `getComputedStyle` values can carry whitespace: `trim()` is required, not cosmetic (Canvas silently ignores an untrimmed colour). */
internal fun readStatisticsChartColors(): StatisticsChartColors {
    val style = window.getComputedStyle(document.documentElement!!)

    fun token(name: String) = style.getPropertyValue(name).trim()
    return StatisticsChartColors(
        status = STATISTICS_STATUS_ORDER.associateWith { token("--lapis-chart-${it.name.lowercase()}") },
        surface = token("--lapis-surface"),
        border = token("--lapis-border"),
        muted = token("--lapis-muted"),
        text = token("--lapis-text"),
    )
}

/**
 * The Chart.js configuration: one stacked bar dataset per status in [STATISTICS_STATUS_ORDER], withdrawn / rejected / deceased
 * [hidden] at first, whole numbers on the axis, a thin surface-coloured border between the stacks, and a tooltip footer for
 * reconstructed periods.
 */
internal fun buildMemberCountChartConfig(
    points: List<MemberCountPointDto>,
    labels: List<String>,
    colors: StatisticsChartColors,
    statusLabel: (MemberStatus) -> String,
    reconstructedText: String,
): dynamic {
    val datasets =
        STATISTICS_STATUS_ORDER
            .map { status ->
                val dataset = js("({})")
                dataset.label = statusLabel(status)
                dataset.data = points.map { it.counts[status] ?: 0 }.toTypedArray()
                dataset.backgroundColor = statisticsPointColors(points, colors.status.getValue(status))
                dataset.borderColor = colors.surface
                dataset.borderWidth = 1
                dataset.stack = "members"
                dataset.hidden = status in STATISTICS_STATUS_HIDDEN_BY_DEFAULT
                dataset
            }.toTypedArray()

    val chartData = js("({})")
    chartData.labels = labels.toTypedArray()
    chartData.datasets = datasets

    val footer: (dynamic) -> String = { items ->
        val first = (items as Array<dynamic>).getOrNull(0)
        val index = first?.dataIndex as? Int
        if (index != null && points.getOrNull(index)?.reconstructed == true) reconstructedText else ""
    }
    val tooltipCallbacks = js("({})")
    tooltipCallbacks.footer = footer
    val tooltip = js("({})")
    tooltip.callbacks = tooltipCallbacks
    val legendLabels = js("({})")
    legendLabels.color = colors.text
    val legend = js("({})")
    legend.display = true
    legend.position = "bottom"
    legend.labels = legendLabels
    val plugins = js("({})")
    plugins.legend = legend
    plugins.tooltip = tooltip

    val xTicks = js("({})")
    xTicks.color = colors.muted
    val xGrid = js("({})")
    xGrid.display = false
    val xBorder = js("({})")
    xBorder.color = colors.border
    val xScale = js("({})")
    xScale.stacked = true
    xScale.ticks = xTicks
    xScale.grid = xGrid
    xScale.border = xBorder

    val yTicks = js("({})")
    yTicks.precision = 0
    yTicks.color = colors.muted
    val yGrid = js("({})")
    yGrid.color = colors.border
    val yBorder = js("({})")
    yBorder.color = colors.border
    val yScale = js("({})")
    yScale.stacked = true
    yScale.beginAtZero = true
    yScale.ticks = yTicks
    yScale.grid = yGrid
    yScale.border = yBorder
    val scales = js("({})")
    scales.x = xScale
    scales.y = yScale

    val options = js("({})")
    options.responsive = true
    options.maintainAspectRatio = false
    options.animation = false
    options.plugins = plugins
    options.scales = scales

    val config = js("({})")
    config.type = "bar"
    config.data = chartData
    config.options = options
    return config
}

/** Re-colours an existing chart after a theme switch (the same custom properties, read again). */
internal fun applyStatisticsChartColors(
    chart: Chart,
    points: List<MemberCountPointDto>,
    colors: StatisticsChartColors,
) {
    val datasets = chart.data.datasets as Array<dynamic>
    STATISTICS_STATUS_ORDER.forEachIndexed { i, status ->
        val dataset = datasets[i]
        dataset.backgroundColor = statisticsPointColors(points, colors.status.getValue(status))
        dataset.borderColor = colors.surface
    }
    val scales = chart.options.scales
    scales.x.border.color = colors.border
    scales.x.ticks.color = colors.muted
    scales.y.ticks.color = colors.muted
    scales.y.grid.color = colors.border
    scales.y.border.color = colors.border
    chart.options.plugins.legend.labels.color = colors.text
}

/** The one side-effecting collaborator, injectable so a test can count creations and destructions without a real Chart.js. */
internal class StatisticsChartDeps(
    val createChart: (HTMLCanvasElement, dynamic) -> Chart = { canvas, config -> Chart(canvas, config) },
)

/**
 * Adds the chart to this container: a host `div` of fixed height (`role="img"`) with the canvas inside. The chart is created when the host is
 * inserted into the document and destroyed when it leaves (including the theme observer), so a reload of the section -- which clears
 * its body -- can never leak an instance. [describedBy] is the id of the table that carries every number (the accessible description).
 */
internal fun Container.mountMemberCountChart(
    points: List<MemberCountPointDto>,
    labels: List<String>,
    ariaLabel: String,
    describedBy: String,
    statusLabel: (MemberStatus) -> String,
    reconstructedText: String,
    deps: StatisticsChartDeps = StatisticsChartDeps(),
): Div {
    var chart: Chart? = null
    var observer: MutationObserver? = null

    fun teardown() {
        chart?.destroy()
        chart = null
        observer?.disconnect()
        observer = null
    }

    fun create(vnode: VNode) {
        teardown()
        val container = (vnode.elm as? HTMLElement) ?: return
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        container.appendChild(canvas)
        val config = buildMemberCountChartConfig(points, labels, readStatisticsChartColors(), statusLabel, reconstructedText)
        val created = deps.createChart(canvas, config)
        chart = created
        val themeObserver =
            MutationObserver { _, _ ->
                applyStatisticsChartColors(created, points, readStatisticsChartColors())
                created.update()
            }
        val observerOptions = js("({})")
        observerOptions.attributes = true
        observerOptions.attributeFilter = arrayOf("data-theme")
        themeObserver.observe(document.documentElement!!, observerOptions)
        observer = themeObserver
    }

    val host = Div(className = "lapis-statistics-chart")
    // The accessible name and description sit on the host widget (a plain Widget.setAttribute, re-applied by KVision on every render), not on
    // the raw canvas the insert hook creates: `role="img"` makes the whole canvas one image, described by the table that carries every number.
    host.setAttribute("role", "img")
    host.setAttribute("aria-label", ariaLabel)
    host.setAttribute("aria-describedby", describedBy)
    host.height = 20.rem
    host.width = 100.perc
    return addWithLifecycle(host, onInsert = { vnode -> create(vnode) }, onDestroy = { teardown() })
}
