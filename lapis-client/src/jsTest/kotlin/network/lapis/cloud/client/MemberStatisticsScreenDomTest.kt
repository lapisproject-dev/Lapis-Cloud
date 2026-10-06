package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberCountHistoryQuery
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.59 -- the member-statistics screen in a REAL mounted root, fed through [MemberStatisticsDeps] (no RPC, a counting fake for
 * Chart.js): the table with every figure, the accuracy note only where it applies, empty and error states, disabled combinations with
 * their explanation, a stale answer never overwriting a newer one, the CSV export, and no identifier anywhere in the page.
 */
class MemberStatisticsScreenDomTest {
    private val today = LocalDate(2026, 10, 6)

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun HTMLElement.button(label: String): HTMLElement? = allOf("button").firstOrNull { it.textContent?.trim() == label }

    private class Recorder(
        val answer: suspend (MemberCountHistoryQuery) -> MemberCountHistoryDto?,
    ) {
        val queries = mutableListOf<MemberCountHistoryQuery>()
        val downloads = mutableListOf<Pair<String, String>>()
        val charts = FakeStatisticsCharts()

        fun deps() =
            MemberStatisticsDeps(
                load = { q ->
                    queries += q
                    answer(q)
                },
                today = { LocalDate(2026, 10, 6) },
                chart = charts.deps(),
                download = { name, content -> downloads += name to content },
            )
    }

    @Test
    fun content_showsEveryFigureInATableWithCaptionAndColumnHeaders_chartIsDescribedByIt(): Promise<Unit> =
        test {
            val rec = Recorder { statisticsDto() }
            withMountedRoot("member-statistics-content") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("the table") { element().querySelector("table") != null }
                val table = element().querySelector("table")!!
                assertEquals("Mitglieder je Status am Ende des jeweiligen Zeitraums", table.querySelector("caption")?.textContent?.trim())
                val headers = table.querySelectorAll("th[scope=col]")
                assertEquals(10, headers.length, "Zeitraum, eight statuses, Datenbasis")
                assertEquals("Datenbasis", (headers.item(9) as HTMLElement).text().trim())
                assertEquals(3, table.querySelectorAll("tbody tr").length)
                val firstRow = (table.querySelectorAll("tbody tr").item(0) as HTMLElement)
                assertTrue(firstRow.text().contains("2026-08"))
                assertTrue(firstRow.text().contains("100"))
                // the key figure line
                assertTrue(element().text().contains("Aktive Mitglieder: 109 (+9 seit Ende von 2026-08)"), element().text())
                // chart: exactly one, its canvas described by the table
                awaitUntil("the chart") { rec.charts.created == 1 }
                val canvasHost =
                    assertNotNull(
                        rec.charts.canvases
                            .single()
                            .parentElement,
                        "the canvas sits in its host",
                    )
                assertEquals("img", canvasHost.getAttribute("role"))
                val describedBy = assertNotNull(canvasHost.getAttribute("aria-describedby"))
                assertNotNull(element().querySelector("#$describedBy"), "the described table exists in the DOM")
                assertTrue(canvasHost.getAttribute("aria-label").orEmpty().contains("Alle Werte stehen auch in der Tabelle"))
                // the request of the first load: last 12 months, monthly
                assertEquals(MemberCountHistoryQuery(LocalDate(2025, 11, 1), today, MemberCountGranularity.MONTH), rec.queries.first())
            }
        }

    @Test
    fun table_isBehindAToggle_butAlwaysInTheDom(): Promise<Unit> =
        test {
            val rec = Recorder { statisticsDto() }
            withMountedRoot("member-statistics-toggle") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("content") { element().querySelector("table") != null }
                val box = element().querySelector("#lapis-member-statistics-table") as HTMLElement
                assertTrue(box.classList.contains("d-none"), "collapsed at first")
                val toggle = assertNotNull(element().button("Tabelle anzeigen"))
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                assertEquals("lapis-member-statistics-table", toggle.getAttribute("aria-controls"))
                toggle.click()
                awaitUntil(
                    "expanded",
                ) { (element().querySelector("#lapis-member-statistics-table") as HTMLElement).style.display != "none" }
                assertEquals("true", element().button("Tabelle ausblenden")?.getAttribute("aria-expanded"))
            }
        }

    @Test
    fun noHistoryAtAll_showsTheEmptyText_andTheExportIsDisabled(): Promise<Unit> =
        test {
            val rec = Recorder { statisticsDto(earliest = null, points = emptyList()) }
            withMountedRoot("member-statistics-empty") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("empty text") { element().text().contains("Noch keine Mitgliederzahlen vorhanden.") }
                assertNull(element().querySelector("table"))
                val export = assertNotNull(element().button("CSV exportieren"))
                assertTrue(export.asDynamic().disabled as Boolean, "nothing to export")
                assertEquals(0, rec.charts.created)
            }
        }

    @Test
    fun failedLoad_showsOneFixedSentenceWithRetry_noServerDetail(): Promise<Unit> =
        test {
            var failing = true
            val rec = Recorder { if (failing) null else statisticsDto() }
            withMountedRoot("member-statistics-error") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("error box") { element().querySelector(".alert-danger[role=alert]") != null }
                assertEquals(
                    "Die Daten konnten nicht geladen werden. Erneut versuchen",
                    element().querySelector(".alert-danger")!!.textContent.orEmpty(),
                )
                assertNull(element().querySelector("table"))
                failing = false
                element().button("Erneut versuchen")!!.click()
                awaitUntil("content after the retry") { element().querySelector("table") != null }
                assertEquals(2, rec.queries.size)
            }
        }

    @Test
    fun accuracyNote_appearsOnlyWhenAPointIsReconstructed(): Promise<Unit> =
        test {
            val plain = Recorder { statisticsDto(reconstructed = false) }
            withMountedRoot("member-statistics-note-plain") { root, element ->
                renderMemberStatisticsScreen(root, plain.deps())
                awaitUntil("content") { element().querySelector("table") != null }
                assertFalse(element().text().contains("Hinweis zur Genauigkeit"))
            }
            val reconstructed = Recorder { statisticsDto(reconstructed = true) }
            // theme.css is not part of the Karma page: give the chart tokens a value, or every colour reads back empty
            val rootStyle = document.documentElement!!.asDynamic().style
            STATISTICS_STATUS_ORDER.forEach { rootStyle.setProperty("--lapis-chart-${it.name.lowercase()}", "#1E56C8") }
            withMountedRoot("member-statistics-note-reconstructed") { root, element ->
                renderMemberStatisticsScreen(root, reconstructed.deps())
                awaitUntil("note") { element().text().contains("Hinweis zur Genauigkeit") }
                assertTrue(element().text().contains("20.08.2026"), "the day up to which it was reconstructed, in the display format")
                assertTrue(element().text().contains("frühesten Datum, das seinen heutigen Status belegt"))
                // the pale bars: the first point of the config carries the reduced alpha
                awaitUntil("chart") { reconstructed.charts.created == 1 }
                val colors =
                    (
                        reconstructed.charts.configs
                            .single()
                            .data.datasets as Array<dynamic>
                    )[0].backgroundColor as Array<String>
                assertEquals("#1E56C873", colors[0], "the reconstructed point carries the reduced alpha")
                assertEquals("#1E56C8", colors[1])
                assertTrue(element().text().contains("rekonstruiert"), "the table's data-basis column")
            }
            STATISTICS_STATUS_ORDER.forEach { rootStyle.removeProperty("--lapis-chart-${it.name.lowercase()}") }
        }

    @Test
    fun impossibleCombination_isDisabledWithAnExplanation_andNeverSwitchesSilently(): Promise<Unit> =
        test {
            val rec = Recorder { statisticsDto(earliest = LocalDate(1990, 1, 1)) }
            withMountedRoot("member-statistics-disabled") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("content") { element().querySelector("table") != null }
                // monthly + "since the start" (1990..2026 = 433 months) is over the limit
                val sinceStart = assertNotNull(element().button("Seit Beginn"))
                assertTrue(sinceStart.asDynamic().disabled as Boolean)
                assertTrue(element().text().contains("Nicht wählbar sind Kombinationen mit mehr als 240 Zeiträumen."))
                val loadsBefore = rec.queries.size
                sinceStart.click()
                awaitAppScopeIdle("no reload for a disabled choice")
                assertEquals(loadsBefore, rec.queries.size, "a disabled choice does nothing")
                assertEquals(
                    "true",
                    element().button("Monat")!!.getAttribute("aria-pressed"),
                    "the division was not switched behind the back",
                )
                // quarterly makes "since the start" possible
                element().button("Quartal")!!.click()
                awaitUntil("the quarterly load") { rec.queries.size == loadsBefore + 1 }
                awaitUntil("repainted") { !(element().button("Seit Beginn")!!.asDynamic().disabled as Boolean) }
                element().button("Seit Beginn")!!.click()
                awaitUntil("the since-start load") { rec.queries.size == loadsBefore + 2 }
                assertEquals(LocalDate(1990, 1, 1), rec.queries.last().from)
                assertEquals(MemberCountGranularity.QUARTER, rec.queries.last().granularity)
            }
        }

    @Test
    fun aStaleAnswer_neverOverwritesANewerOne(): Promise<Unit> =
        test {
            val slowFirst = CompletableDeferred<MemberCountHistoryDto?>()
            var call = 0
            val rec =
                Recorder { q ->
                    call++
                    if (call == 1) {
                        slowFirst.await()
                    } else {
                        statisticsDto(
                            granularity = q.granularity,
                            points =
                                listOf(
                                    statisticsPoint("2026-07-01", "2026-10-01", 42),
                                    statisticsPoint("2026-10-01", "2027-01-01", 43, current = true),
                                ),
                        )
                    }
                }
            withMountedRoot("member-statistics-stale") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("first request issued") { rec.queries.size == 1 }
                element().button("Quartal")!!.click()
                awaitUntil("second request answered") { element().text().contains("Q3 2026") }
                // the OLD answer arrives last
                slowFirst.complete(statisticsDto())
                awaitAppScopeIdle("the late answer was handled")
                val firstColumn =
                    element().allOf("tbody tr").map {
                        it
                            .allOf("td")
                            .first()
                            .text()
                            .trim()
                    }
                assertEquals(listOf("Q3 2026", "Q4 2026 (laufend)"), firstColumn, "still the newer, quarterly data")
            }
        }

    @Test
    fun csvExport_offersTheShownFigures_underTheNameOfTheRequestedRange(): Promise<Unit> =
        test {
            val rec = Recorder { statisticsDto() }
            withMountedRoot("member-statistics-csv") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("content") { element().querySelector("table") != null }
                val export = element().button("CSV exportieren")!!
                assertFalse(export.asDynamic().disabled as Boolean)
                export.click()
                val (name, content) = rec.downloads.single()
                assertEquals("mitgliederentwicklung_2025-11-01_2026-10-06_monat.csv", name)
                assertTrue(content.startsWith(CSV_BOM))
                assertTrue(content.contains("2026-10 (laufend);2026-10-01;2026-11-01;109;"))
            }
        }

    @Test
    fun pageCarriesNoIdentifier(): Promise<Unit> =
        test {
            val rec = Recorder { statisticsDto(reconstructed = true) }
            withMountedRoot("member-statistics-no-uuid") { root, element ->
                renderMemberStatisticsScreen(root, rec.deps())
                awaitUntil("content") { element().querySelector("table") != null }
                val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                assertFalse(uuid.containsMatchIn(element().innerHTML))
            }
        }
}
