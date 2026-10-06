package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.client.chart.Chart
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberCountPointDto
import network.lapis.cloud.shared.domain.MemberStatus
import org.w3c.dom.HTMLCanvasElement

/** A counting stand-in for Chart.js: the DOM, snabbdom, KVision and the theme observer stay real. */
internal class FakeStatisticsCharts {
    var created = 0
    var destroyed = 0
    val canvases = mutableListOf<HTMLCanvasElement>()
    val configs = mutableListOf<dynamic>()

    fun create(
        canvas: HTMLCanvasElement,
        config: dynamic,
    ): Chart {
        created++
        canvases.add(canvas)
        configs.add(config)
        val counter = this
        val fake: dynamic = js("({})")
        fake.update = { }
        fake.destroy = { counter.destroyed++ }
        fake.data = config.data
        fake.options = config.options
        return fake.unsafeCast<Chart>()
    }

    fun deps() = StatisticsChartDeps(createChart = ::create)
}

internal fun statisticsPoint(
    start: String,
    end: String,
    active: Int,
    current: Boolean = false,
    reconstructed: Boolean = false,
) = MemberCountPointDto(
    periodStart = LocalDate.parse(start),
    periodEnd = LocalDate.parse(end),
    current = current,
    counts = MemberStatus.entries.associateWith { if (it == MemberStatus.ACTIVE) active else 0 },
    reconstructed = reconstructed,
)

/** Three monthly points, the last one running, optionally with a reconstructed first point. */
internal fun statisticsDto(
    reconstructed: Boolean = false,
    earliest: LocalDate? = LocalDate(2025, 1, 1),
    granularity: MemberCountGranularity = MemberCountGranularity.MONTH,
    points: List<MemberCountPointDto> =
        listOf(
            statisticsPoint("2026-08-01", "2026-09-01", 100, reconstructed = reconstructed),
            statisticsPoint("2026-09-01", "2026-10-01", 104),
            statisticsPoint("2026-10-01", "2026-11-01", 109, current = true),
        ),
) = MemberCountHistoryDto(
    granularity = granularity,
    points = points,
    earliestDate = earliest,
    today = LocalDate(2026, 10, 6),
    reconstructedBefore = if (reconstructed) LocalDateTime(2026, 8, 20, 0, 0) else null,
)
