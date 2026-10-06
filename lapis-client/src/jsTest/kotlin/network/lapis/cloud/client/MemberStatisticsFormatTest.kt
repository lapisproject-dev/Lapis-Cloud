package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberCountPointDto
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welle V1.9.59 -- the pure parts of the member-statistics screen: presets, possible combinations, labels, key figures, CSV. */
class MemberStatisticsFormatTest {
    private val today = LocalDate(2026, 10, 6)

    internal fun point(
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

    @Test
    fun presets_resolveToTheDocumentedBounds() {
        assertEquals(LocalDate(2025, 11, 1) to today, statisticsBounds(StatisticsRange.LAST_12_MONTHS, today, null))
        assertEquals(LocalDate(2026, 1, 1) to today, statisticsBounds(StatisticsRange.THIS_YEAR, today, null))
        assertEquals(LocalDate(2022, 1, 1) to today, statisticsBounds(StatisticsRange.LAST_5_YEARS, today, null))
        // "since the start" falls back to five years until the first answer told the earliest day
        assertEquals(LocalDate(2022, 1, 1) to today, statisticsBounds(StatisticsRange.SINCE_START, today, null))
        assertEquals(LocalDate(2019, 3, 15) to today, statisticsBounds(StatisticsRange.SINCE_START, today, LocalDate(2019, 3, 15)))
        // never before 1900
        assertEquals(LocalDate(1900, 1, 1) to today, statisticsBounds(StatisticsRange.SINCE_START, today, LocalDate(1850, 1, 1)))
        // January: twelve months back lands in the previous year
        assertEquals(
            LocalDate(2025, 2, 1) to LocalDate(2026, 1, 20),
            statisticsBounds(StatisticsRange.LAST_12_MONTHS, LocalDate(2026, 1, 20), null),
        )
    }

    @Test
    fun impossibleCombinations_areThoseOverTheServersLimit() {
        val earliest = LocalDate(1990, 1, 1)
        // 1990 .. 2026 is 433 months, 145 quarters, 37 years
        assertFalse(statisticsCombinationPossible(StatisticsRange.SINCE_START, MemberCountGranularity.MONTH, today, earliest))
        assertTrue(statisticsCombinationPossible(StatisticsRange.SINCE_START, MemberCountGranularity.QUARTER, today, earliest))
        assertTrue(statisticsCombinationPossible(StatisticsRange.SINCE_START, MemberCountGranularity.YEAR, today, earliest))
        assertTrue(statisticsCombinationPossible(StatisticsRange.LAST_12_MONTHS, MemberCountGranularity.MONTH, today, earliest))
        // exactly 240 months is fine, 241 is not
        assertTrue(statisticsCombinationPossible(StatisticsRange.SINCE_START, MemberCountGranularity.MONTH, today, LocalDate(2006, 11, 20)))
        assertFalse(statisticsCombinationPossible(StatisticsRange.SINCE_START, MemberCountGranularity.MONTH, today, LocalDate(2006, 10, 1)))
    }

    @Test
    fun periodLabels_areLanguageNeutral_andMarkTheRunningPeriod() {
        assertEquals("2026-03", statisticsPeriodLabel(LocalDate(2026, 3, 1), MemberCountGranularity.MONTH, false, "laufend"))
        assertEquals("2026-10 (laufend)", statisticsPeriodLabel(LocalDate(2026, 10, 1), MemberCountGranularity.MONTH, true, "laufend"))
        assertEquals("Q1 2026", statisticsPeriodLabel(LocalDate(2026, 1, 1), MemberCountGranularity.QUARTER, false, "laufend"))
        assertEquals("Q4 2026 (running)", statisticsPeriodLabel(LocalDate(2026, 10, 1), MemberCountGranularity.QUARTER, true, "running"))
        assertEquals("2026", statisticsPeriodLabel(LocalDate(2026, 1, 1), MemberCountGranularity.YEAR, false, "laufend"))
    }

    @Test
    fun keyFigures_areTheActiveNowAndTheirChangeSinceTheFirstPeriod() {
        fun dto(vararg points: MemberCountPointDto) =
            MemberCountHistoryDto(MemberCountGranularity.MONTH, points.toList(), LocalDate(2025, 1, 1), today, null)
        val two =
            statisticsKeyFigures(
                dto(point("2026-08-01", "2026-09-01", 100), point("2026-09-01", "2026-10-01", 110, current = true)),
            )!!
        assertEquals(110, two.activeNow)
        assertEquals(10, two.activeChange)
        assertEquals(LocalDate(2026, 8, 1), two.firstPeriodStart)
        val one = statisticsKeyFigures(dto(point("2026-09-01", "2026-10-01", 7, current = true)))!!
        assertEquals(7, one.activeNow)
        assertEquals(null, one.activeChange)
        assertEquals(null, statisticsKeyFigures(dto()))
        assertEquals("+5", signedNumber(5))
        assertEquals("-3", signedNumber(-3))
        assertEquals("0", signedNumber(0))
    }

    private val labels =
        StatisticsCsvLabels(
            period = "Zeitraum",
            start = "Beginn",
            endExclusive = "Ende (exklusiv)",
            dataBasis = "Datenbasis",
            recorded = "aufgezeichnet",
            reconstructed = "rekonstruiert",
            running = "laufend",
            status = { it.name },
        )

    @Test
    fun csv_hasBom_semicolons_theTableColumns_andOneRowPerPeriod() {
        val dto =
            MemberCountHistoryDto(
                granularity = MemberCountGranularity.QUARTER,
                points =
                    listOf(
                        point("2026-01-01", "2026-04-01", 5, reconstructed = true),
                        point("2026-04-01", "2026-07-01", 6),
                        point("2026-07-01", "2026-10-01", 8),
                        point("2026-10-01", "2027-01-01", 9, current = true),
                    ),
                earliestDate = LocalDate(2026, 1, 1),
                today = today,
                reconstructedBefore = LocalDateTime(2026, 5, 1, 0, 0),
            )
        val csv = memberCountCsv(dto, labels)
        assertTrue(csv.startsWith(CSV_BOM), "UTF-8 byte order mark")
        val lines = csv.removePrefix(CSV_BOM).trimEnd().split("\r\n")
        assertEquals(5, lines.size)
        assertEquals(
            "Zeitraum;Beginn;Ende (exklusiv);ACTIVE;APPLICATION;FRIEND;DONOR;GUEST;WITHDRAWN;REJECTED;DECEASED;Datenbasis",
            lines.first(),
        )
        assertEquals("Q1 2026;2026-01-01;2026-04-01;5;0;0;0;0;0;0;0;rekonstruiert", lines[1])
        assertEquals("Q2 2026;2026-04-01;2026-07-01;6;0;0;0;0;0;0;0;aufgezeichnet", lines[2])
        assertEquals("Q4 2026 (laufend);2026-10-01;2027-01-01;9;0;0;0;0;0;0;0;laufend", lines[4])
        assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(csv))
    }

    @Test
    fun csvCell_quotesWhatNeedsQuoting_only() {
        assertEquals("plain", csvCell("plain"))
        assertEquals("\"a;b\"", csvCell("a;b"))
        assertEquals("\"say \"\"hi\"\"\"", csvCell("say \"hi\""))
        assertEquals("\"two\nlines\"", csvCell("two\nlines"))
    }

    @Test
    fun csvFileName_namesTheRequestedRange_andTheDivision() {
        assertEquals(
            "mitgliederentwicklung_2025-11-01_2026-10-06_monat.csv",
            memberCountCsvFileName("2025-11-01", "2026-10-06", MemberCountGranularity.MONTH),
        )
        assertEquals(
            "mitgliederentwicklung_2022-01-01_2026-10-06_quartal.csv",
            memberCountCsvFileName("2022-01-01", "2026-10-06", MemberCountGranularity.QUARTER),
        )
        assertEquals(
            "mitgliederentwicklung_2022-01-01_2026-10-06_jahr.csv",
            memberCountCsvFileName("2022-01-01", "2026-10-06", MemberCountGranularity.YEAR),
        )
    }
}
