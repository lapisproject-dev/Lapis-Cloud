package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.i18n.I18n
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceSnapshotDto
import network.lapis.cloud.shared.domain.PriceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Price-Oracle Kursverlauf-Diagramm -- covers [buildPriceHistoryChartData], the pure DOM-independent
 * surface this wave adds. No rendering harness exists in this module (see [PriceOracleScreenTest]
 * KDoc "No rendering harness"), so `renderPriceHistoryChart` itself is out of scope here, same as
 * every other screen's `*ScreenTest.kt`.
 */
class PriceHistoryChartDataTest {
    private fun row(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        price: Double,
        anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
        currency: String = "EUR",
    ) = PriceSnapshotDto(
        id = "$year-$month-$day-$hour",
        anchorAsset = anchor,
        donationCurrency = currency,
        medianPrice = price.toDecimal(),
        priceStatus = PriceStatus.LIVE,
        sourceCount = 3,
        sourcesUsed = "coinbase,kraken,bitstamp",
        priceTimestamp = LocalDateTime(year, month, day, hour, 0),
        capturedAt = LocalDateTime(year, month, day, hour, 0),
    )

    @Test
    fun buildPriceHistoryChartData_collapsesTwelveCacheRepeatsIntoOnePoint() {
        // Gold/Fiat cache plateau: the poller writes hourly, but the price only refreshes every
        // 12h -- twelve consecutive rows share the exact same priceTimestamp.
        val sharedTimestamp = LocalDateTime(2026, 9, 1, 0, 0)
        val rows =
            (0 until 12).map {
                PriceSnapshotDto(
                    id = "gold-$it",
                    anchorAsset = AnchorAsset.GOLD_XAU,
                    donationCurrency = "EUR",
                    medianPrice = 2000.0.toDecimal(),
                    priceStatus = PriceStatus.CACHED,
                    sourceCount = 2,
                    sourcesUsed = "goldapi,metalpriceapi",
                    priceTimestamp = sharedTimestamp,
                    capturedAt = LocalDateTime(2026, 9, 1, it, 0),
                )
            }
        val data = buildPriceHistoryChartData(rows, AnchorAsset.GOLD_XAU) { it.toString() }
        assertEquals(1, data.pointCount)
        assertEquals(1, data.points.size)
        assertEquals(0, data.gapCount)
    }

    @Test
    fun buildPriceHistoryChartData_gapAboveThresholdInsertsExactlyOneNullPoint() {
        // BITCOIN_BTC has refreshIntervalSeconds == 0, so the gap floor is max(3_600_000, 0) * 2.5
        // == 9_000_000 ms (2.5h). A 6-hour jump between two BTC snapshots must insert a gap.
        val rows =
            listOf(
                row(2026, 9, 1, 0, 60_000.0),
                row(2026, 9, 1, 6, 61_000.0),
                row(2026, 9, 1, 7, 61_500.0),
            )
        val data = buildPriceHistoryChartData(rows, AnchorAsset.BITCOIN_BTC) { it.toString() }
        assertEquals(1, data.gapCount)
        assertEquals(4, data.points.size) // 3 real points + 1 null gap marker
        assertEquals(null, data.points[1])
    }

    @Test
    fun buildPriceHistoryChartData_gapBelowThresholdInsertsNoNullPoint() {
        // Consecutive hourly BTC snapshots (1h apart) stay well under the 2.5h threshold.
        val rows =
            listOf(
                row(2026, 9, 1, 0, 60_000.0),
                row(2026, 9, 1, 1, 60_100.0),
                row(2026, 9, 1, 2, 60_200.0),
            )
        val data = buildPriceHistoryChartData(rows, AnchorAsset.BITCOIN_BTC) { it.toString() }
        assertEquals(0, data.gapCount)
        assertEquals(3, data.points.size)
        assertTrue(data.points.all { it != null })
    }

    @Test
    fun buildPriceHistoryChartData_goldThresholdExceedsBitcoinThreshold() {
        // GOLD_XAU refreshes every 43_200s (12h), so its gap threshold is 43_200_000 * 2.5 ==
        // 108_000_000 ms (30h) -- a 24h jump must NOT be treated as a gap for GOLD_XAU, even though
        // the same 24h jump WOULD be a gap for BITCOIN_BTC (2.5h threshold).
        val goldRows =
            listOf(
                row(2026, 9, 1, 0, 2000.0, anchor = AnchorAsset.GOLD_XAU),
                row(2026, 9, 2, 0, 2001.0, anchor = AnchorAsset.GOLD_XAU),
            )
        val goldData = buildPriceHistoryChartData(goldRows, AnchorAsset.GOLD_XAU) { it.toString() }
        assertEquals(0, goldData.gapCount)

        val btcRows =
            listOf(
                row(2026, 9, 1, 0, 60_000.0),
                row(2026, 9, 2, 0, 61_000.0),
            )
        val btcData = buildPriceHistoryChartData(btcRows, AnchorAsset.BITCOIN_BTC) { it.toString() }
        assertEquals(1, btcData.gapCount)
    }

    @Test
    fun buildPriceHistoryChartData_truncatedOnlyWhenExactlyFiveThousandRows() {
        val fiveThousand = (0 until PRICE_HISTORY_MAX_LIMIT).map { row(2026, 1, 1, it % 24, 60_000.0 + it) }
        val truncatedData = buildPriceHistoryChartData(fiveThousand, AnchorAsset.BITCOIN_BTC) { it.toString() }
        assertTrue(truncatedData.truncated)

        val fewer = fiveThousand.take(PRICE_HISTORY_MAX_LIMIT - 1)
        val notTruncatedData = buildPriceHistoryChartData(fewer, AnchorAsset.BITCOIN_BTC) { it.toString() }
        assertFalse(notTruncatedData.truncated)
    }

    @Test
    fun buildPriceHistoryChartData_emptyListReturnsZeroPointsWithoutException() {
        val data = buildPriceHistoryChartData(emptyList(), AnchorAsset.BITCOIN_BTC) { it.toString() }
        assertEquals(0, data.pointCount)
        assertTrue(data.points.isEmpty())
        assertEquals(0, data.gapCount)
        assertFalse(data.truncated)
    }

    @Test
    fun buildPriceHistoryChartData_tooltipLabelIsFormatDonationAmountVerbatim() {
        val rows = listOf(row(2026, 9, 1, 0, 60_000.5, currency = "USD"))
        val data =
            buildPriceHistoryChartData(rows, AnchorAsset.BITCOIN_BTC) { medianPrice ->
                formatDonationAmount(medianPrice, "USD")
            }
        val point = data.points.single()
        assertEquals(formatDonationAmount(60_000.5.toDecimal(), "USD"), point?.tooltipLabel)
    }

    @Test
    fun buildPriceHistoryChartData_dateLabelIsFormattedFromRawComponentsNotReinterpretedInstant() {
        // Review-Befund 2026-09-17: dateLabel darf NIE ueber toInstant(TimeZone.currentSystemDefault())
        // neu berechnet werden (Browser- vs. Server-Zone-Verwechslung) -- es muss exakt die
        // Wanduhr-Komponenten des server-gelieferten LocalDateTime widerspiegeln, unabhaengig davon,
        // in welcher Zeitzone der Test (bzw. spaeter der Browser) laeuft.
        //
        // Review-Befund 2026-09-24 (round 3): round 2's fix here only reworded the SOLL-side literal
        // (a string -> `formatDateTimeIn("de", ...)`) -- the IST side (`point?.dateLabel`) is produced by
        // `buildPriceHistoryChartData` calling `formatPriceTimestampLabel` internally, which still reads the
        // global `I18n.language`, exactly as before. Round 2's own comment claimed the explicit-language
        // literal alone "keeps this test from breaking on a future language switch that forgets its own
        // finally" -- untrue, since the IST side never went through that seam. Pinning `I18n.language`
        // for the duration of THIS test (restored in `finally`, not relying on `TestI18nSetup.kt`'s
        // `@EagerInitialization` default) is what actually makes both sides deterministic regardless of
        // test execution order.
        val originalLanguage = I18n.language
        I18n.language = "de"
        try {
            val rows = listOf(row(2026, 9, 1, 14, 60_000.0))
            val data = buildPriceHistoryChartData(rows, AnchorAsset.BITCOIN_BTC) { it.toString() }
            val point = data.points.single()
            assertEquals(formatDateTimeIn("de", LocalDateTime(2026, 9, 1, 14, 0)), point?.dateLabel)
        } finally {
            I18n.language = originalLanguage
        }
    }

    @Test
    fun formatPriceTimestampLabel_delegatesToTheAppWideDateTimeConvention() {
        // Review-Befund 2026-09-24: vorher eine eigene, handgeschriebene Zero-Padding-Implementierung
        // (`dd.MM.yyyy HH:mm`, ASCII-Leerzeichen) parallel zu ConferenceScreen.kt/
        // MemberAnniversariesScreen.kt -- jetzt eine duenne Delegation an formatDateTimeIn, deren
        // Komma+NBSP-Form und Sprachabhaengigkeit hier mitgeprueft wird, nicht nur das Zero-Padding.
        val at = LocalDateTime(2026, 3, 5, 9, 7)
        // The explicit-language seam (round 3): deterministic regardless of the global I18n.language,
        // no pin/finally needed.
        assertEquals(formatDateTimeIn("de", at), formatPriceTimestampLabelIn("de", at))
        assertEquals(formatDateTimeIn("en", at), formatPriceTimestampLabelIn("en", at))
        // The single-argument convenience delegates to the global I18n.language -- round 2's version of
        // this assertion (`assertEquals(formatDateTimeIn(I18n.language, at), formatPriceTimestampLabel(at))`)
        // was a tautology (both sides read `I18n.language` at the same instant, so it can never fail); this
        // pins the language explicitly so the assertion actually exercises the delegation.
        val originalLanguage = I18n.language
        I18n.language = "de"
        try {
            assertEquals(formatDateTimeIn("de", at), formatPriceTimestampLabel(at))
        } finally {
            I18n.language = originalLanguage
        }
    }
}
