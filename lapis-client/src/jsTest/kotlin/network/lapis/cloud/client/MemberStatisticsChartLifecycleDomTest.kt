package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.9.59 -- the Chart.js lifecycle of the member-statistics screen in a REAL mounted root (same questions as
 * [PriceOracleChartLifecycleDomTest]): created once, not destroyed by an unrelated patch, destroyed exactly once when the screen
 * goes, rebuilt (old one destroyed) when the data reloads, and a theme switch re-colours the live chart without recreating it.
 */
class MemberStatisticsChartLifecycleDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun deps(charts: FakeStatisticsCharts) =
        MemberStatisticsDeps(
            load = { q -> statisticsDto(granularity = q.granularity) },
            today = { LocalDate(2026, 10, 6) },
            chart = charts.deps(),
            download = { _, _ -> },
        )

    @Test
    fun chart_isCreatedOnce_andNotDestroyedByAnUnrelatedPatch(): Promise<Unit> =
        test {
            withMountedRoot("member-statistics-lifecycle-1") { root, element ->
                val charts = FakeStatisticsCharts()
                renderMemberStatisticsScreen(root, deps(charts))
                awaitUntil("chart") { charts.created == 1 }
                root.add(io.kvision.html.Span("an unrelated sibling"))
                delay(60)
                assertEquals(1, charts.created)
                assertEquals(0, charts.destroyed)
                assertTrue(charts.canvases.single().isConnected)
                assertEquals(1, element().querySelectorAll("canvas").length)
            }
        }

    @Test
    fun chart_isDestroyedExactlyOnce_whenTheScreenLeaves(): Promise<Unit> =
        test {
            withMountedRoot("member-statistics-lifecycle-2") { root, _ ->
                val charts = FakeStatisticsCharts()
                renderMemberStatisticsScreen(root, deps(charts))
                awaitUntil("chart") { charts.created == 1 }
                root.removeAll() // what Routing.show does on a route change
                assertEquals(1, charts.destroyed)
                assertEquals(1, charts.created)
            }
        }

    @Test
    fun reload_destroysTheOldChart_andBuildsExactlyOneNew(): Promise<Unit> =
        test {
            withMountedRoot("member-statistics-lifecycle-3") { root, element ->
                val charts = FakeStatisticsCharts()
                renderMemberStatisticsScreen(root, deps(charts))
                awaitUntil("chart") { charts.created == 1 }
                element().allOf("button").first { it.textContent?.trim() == "Quartal" }.click()
                awaitUntil("second chart") { charts.created == 2 }
                assertEquals(1, charts.destroyed, "the first instance is gone")
                assertEquals(1, element().querySelectorAll("canvas").length, "one canvas in the page")
            }
        }

    @Test
    fun themeSwitch_recoloursTheLiveChart_withoutRecreatingIt(): Promise<Unit> =
        test {
            withMountedRoot("member-statistics-lifecycle-4") { root, _ ->
                val html = document.documentElement!!
                val previous = html.getAttribute("data-theme")
                // Self-contained: the test brings its OWN theme tokens instead of relying on whether theme.css happens to be part of the Karma page
                // (it is not in a fresh page, but another spec of the same run may have loaded it -- the CI failed once on exactly that order dependence).
                // The rules are added last, so they win over any theme.css rule of the same specificity.
                val style = document.createElement("style")
                style.textContent =
                    ":root { --lapis-surface: $LIGHT_TOKEN; } :root[data-theme=\"dark\"] { --lapis-surface: $DARK_TOKEN; }"
                document.head!!.appendChild(style)
                html.setAttribute("data-theme", "light")
                try {
                    val charts = FakeStatisticsCharts()
                    renderMemberStatisticsScreen(root, deps(charts))
                    awaitUntil("chart") { charts.created == 1 }

                    fun borderColor() =
                        (
                            charts.configs
                                .single()
                                .data.datasets as Array<dynamic>
                        )[0].borderColor as String
                    assertEquals(LIGHT_TOKEN, borderColor(), "the bar outline starts with the light surface token")
                    // The observer reads the computed custom properties again when the attribute changes.
                    html.setAttribute("data-theme", "dark")
                    awaitUntil("re-coloured to the dark surface token", detail = { "borderColor is '${borderColor()}'" }) {
                        borderColor() ==
                            DARK_TOKEN
                    }
                    assertEquals(1, charts.created, "no second chart for a theme switch")
                    assertEquals(0, charts.destroyed)
                } finally {
                    if (previous == null) html.removeAttribute("data-theme") else html.setAttribute("data-theme", previous)
                    style.parentNode?.removeChild(style)
                }
            }
        }
}

private const val LIGHT_TOKEN = "#112233"
private const val DARK_TOKEN = "#445566"
