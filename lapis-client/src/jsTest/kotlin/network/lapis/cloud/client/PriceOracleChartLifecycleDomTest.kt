package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.html.span
import io.kvision.panel.vPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.client.chart.Chart
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceSnapshotDto
import network.lapis.cloud.shared.domain.PriceStatus
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Chart.js lifecycle of the price-oracle "Kursverlauf" section in a REAL, mounted `Root` (the
 * "late hooks" audit): the chart must live exactly as long as the screen and be torn down exactly when the
 * screen really goes away -- not when an unrelated widget of the same page happens to trigger a patch.
 *
 * `Chart` is replaced by a counting fake through [PriceHistoryDeps]; everything else (KVision, snabbdom,
 * the DOM, the theme observer) is real.
 */
class PriceOracleChartLifecycleDomTest {
    private class FakeCharts {
        var created = 0
        var destroyed = 0
        val canvases = mutableListOf<HTMLCanvasElement>()

        fun create(
            canvas: HTMLCanvasElement,
            config: dynamic,
        ): Chart {
            created++
            canvases += canvas
            val counter = this
            val fake: dynamic = js("({})")
            fake.update = { }
            fake.destroy = { counter.destroyed++ }
            fake.data = js("({datasets: [{}]})")
            fake.options = js("({scales: {x: {border: {}}, y: {ticks: {}, grid: {}, border: {}}}})")
            return fake.unsafeCast<Chart>()
        }
    }

    private fun snapshot(hour: Int) =
        PriceSnapshotDto(
            id = "p-$hour",
            anchorAsset = AnchorAsset.BITCOIN_BTC,
            donationCurrency = "EUR",
            medianPrice = (60000.0 + hour).toDecimal(),
            priceStatus = PriceStatus.LIVE,
            sourceCount = 3,
            sourcesUsed = "a,b,c",
            priceTimestamp = LocalDateTime(2026, 9, 1, hour, 0),
            capturedAt = LocalDateTime(2026, 9, 1, hour, 0),
        )

    private val history = (0 until 6).map { snapshot(it) }

    private fun FakeCharts.deps() = PriceHistoryDeps(loadHistory = { _, _ -> history }, createChart = ::create)

    // NOT `AppScope.promise`: AppScope has no SupervisorJob, one failing test would cancel it for the whole run.
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private suspend fun settle() = delay(60)

    @Test
    fun chart_isCreatedOnceAndSurvivesAnUnrelatedPatchOfTheScreen(): Promise<Unit> =
        test {
            withMountedRoot("price-oracle-chart-test") { root, element ->
                val charts = FakeCharts()
                val screen = root.vPanel(spacing = 14)
                renderPriceHistoryChart(screen, canManage = false, charts.deps())
                settle()
                assertEquals(1, charts.created, "the chart exists after the history loaded")
                val canvas = assertNotNull(charts.canvases.single().takeIf { it.isConnected }, "and its canvas is in the document")

                // Anything else on the page finishing (the config panel after its RPC, the member list of the
                // convert form) patches the whole screen root.
                screen.span("an unrelated sibling finished loading")
                screen.span("and another one")
                settle()

                assertEquals(0, charts.destroyed, "an unrelated patch must not destroy the chart")
                assertEquals(1, charts.created, "and must not build a second one")
                assertSame(canvas, charts.canvases.single())
                assertTrue(canvas.isConnected)
                assertEquals(1, element().querySelectorAll("canvas").length)
            }
        }

    @Test
    fun chart_isCreatedWithoutAnyFurtherPatch(): Promise<Unit> =
        test {
            // The canvas host's insert hook was registered after its element was rendered; the chart used to
            // exist only because `footerLine.content = ...` right below happened to patch the tree again.
            withMountedRoot("price-oracle-chart-test") { root, _ ->
                val charts = FakeCharts()
                val screen = root.vPanel(spacing = 14)
                renderPriceHistoryChart(screen, canManage = false, charts.deps())
                settle()
                assertEquals(1, charts.created)
                assertEquals(0, charts.destroyed)
            }
        }

    @Test
    fun chart_isDestroyedExactlyOnce_whenTheScreenReallyLeaves(): Promise<Unit> =
        test {
            withMountedRoot("price-oracle-chart-test") { root, _ ->
                val charts = FakeCharts()
                val screen = root.vPanel(spacing = 14)
                renderPriceHistoryChart(screen, canManage = false, charts.deps())
                settle()
                screen.span("unrelated")

                root.removeAll() // what `Routing.show` does on a route change
                assertEquals(1, charts.destroyed, "leaving the screen destroys the chart")
                assertEquals(1, charts.created)
            }
        }

    @Test
    fun anchorSwitch_destroysTheOldChartAndBuildsExactlyOneNew(): Promise<Unit> =
        test {
            withMountedRoot("price-oracle-chart-test") { root, _ ->
                val charts = FakeCharts()
                val screen = root.vPanel(spacing = 14)
                val setAnchor = renderPriceHistoryChart(screen, canManage = false, charts.deps())
                settle()
                setAnchor(AnchorAsset.GOLD_XAU) // config arrives with another anchor: reload
                settle()
                assertEquals(2, charts.created)
                assertEquals(1, charts.destroyed, "only the superseded chart was destroyed")
            }
        }
}
