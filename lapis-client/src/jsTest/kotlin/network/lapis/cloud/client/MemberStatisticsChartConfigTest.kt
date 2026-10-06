package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberCountPointDto
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welle V1.9.59 -- what the member-count chart is configured to show (no canvas needed: the configuration is a pure function). */
class MemberStatisticsChartConfigTest {
    private val colors =
        StatisticsChartColors(
            status = MemberStatus.entries.associateWith { "#1E56C8" },
            surface = "#FFFFFF",
            border = "#E3E6EB",
            muted = "#5A626E",
            text = "#14181E",
        )

    private fun point(
        active: Int,
        reconstructed: Boolean,
    ) = MemberCountPointDto(
        periodStart = LocalDate(2026, 1, 1),
        periodEnd = LocalDate(2026, 2, 1),
        current = false,
        counts = MemberStatus.entries.associateWith { if (it == MemberStatus.ACTIVE) active else 1 },
        reconstructed = reconstructed,
    )

    private val points = listOf(point(10, reconstructed = true), point(12, reconstructed = false))

    private fun config(): dynamic =
        buildMemberCountChartConfig(points, listOf("2026-01", "2026-02"), colors, statusLabel = {
            "L-${it.name}"
        }, reconstructedText = "rekonstruiert")

    @Test
    fun isAStackedBar_withOneDatasetPerStatus_inTheDocumentedOrder() {
        val config = config()
        assertEquals("bar", config.type as String)
        val datasets = config.data.datasets as Array<dynamic>
        assertEquals(STATISTICS_STATUS_ORDER.map { "L-${it.name}" }, datasets.map { it.label as String })
        assertEquals(listOf("2026-01", "2026-02"), (config.data.labels as Array<String>).toList())
        assertTrue(config.options.scales.x.stacked as Boolean)
        assertTrue(config.options.scales.y.stacked as Boolean)
        datasets.forEach { assertEquals("members", it.stack as String) }
        assertEquals(listOf(10, 12), (datasets[0].data as Array<Int>).toList())
    }

    @Test
    fun withdrawnRejectedAndDeceased_startHidden_theOthersVisible() {
        val datasets = config().data.datasets as Array<dynamic>
        val hidden = datasets.filter { it.hidden as Boolean }.map { it.label as String }
        assertEquals(listOf("L-WITHDRAWN", "L-REJECTED", "L-DECEASED"), hidden)
        assertFalse(datasets.first { it.label == "L-ACTIVE" }.hidden as Boolean)
    }

    @Test
    fun reconstructedPoints_areDrawnPale_theOthersInTheFullColour() {
        val colorsOfActive = (config().data.datasets as Array<dynamic>)[0].backgroundColor as Array<String>
        assertEquals("#1E56C873", colorsOfActive[0])
        assertEquals("#1E56C8", colorsOfActive[1])
    }

    @Test
    fun axisShowsWholeNumbers_borderIsTheSurfaceColour_legendIsOn() {
        val config = config()
        assertEquals(0, config.options.scales.y.ticks.precision as Int)
        assertTrue(config.options.scales.y.beginAtZero as Boolean)
        val dataset = (config.data.datasets as Array<dynamic>)[0]
        assertEquals("#FFFFFF", dataset.borderColor as String)
        assertEquals(1, dataset.borderWidth as Int)
        assertTrue(config.options.plugins.legend.display as Boolean)
        assertFalse(config.options.animation as Boolean)
    }

    @Test
    fun tooltipFooter_marksOnlyReconstructedPeriods() {
        val footer =
            config()
                .options.plugins.tooltip.callbacks.footer
        val reconstructedItem = js("({dataIndex: 0})")
        val liveItem = js("({dataIndex: 1})")
        assertEquals("rekonstruiert", footer(arrayOf(reconstructedItem)) as String)
        assertEquals("", footer(arrayOf(liveItem)) as String)
        assertEquals("", footer(arrayOf<dynamic>()) as String)
    }

    @Test
    fun withAlpha_convertsHexOnly() {
        assertEquals("#00000080", withAlpha("#000000", 0.5))
        assertEquals("#FFFFFF73", withAlpha(" #FFFFFF ", 0.45))
        assertEquals("#ABCDEF00", withAlpha("#ABCDEF", 0.0))
        assertEquals("#ABCDEFFF", withAlpha("#ABCDEF", 1.0))
        assertEquals("red", withAlpha("red", 0.5))
        assertEquals("#FFF", withAlpha("#FFF", 0.5))
        assertEquals("#GGGGGG", withAlpha("#GGGGGG", 0.5))
    }
}
