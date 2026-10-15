package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.132 -- the pure core of the travel expense date fields (`LocaleDate.kt`): parsing, formatting, the visible pattern and the order
 * resolution, with INJECTED orders and probes (no browser, no `Intl` except the one test that asks the real probe for `de`).
 */
class LocaleDateTest {
    private val dmyDot = DateFieldOrder(DateOrder.DMY, '.')
    private val dmySlash = DateFieldOrder(DateOrder.DMY, '/')
    private val dmyDash = DateFieldOrder(DateOrder.DMY, '-')
    private val mdySlash = DateFieldOrder(DateOrder.MDY, '/')
    private val ymdDash = DateFieldOrder(DateOrder.YMD, '-')
    private val ymdSlash = DateFieldOrder(DateOrder.YMD, '/')

    private val all = listOf(dmyDot, dmySlash, dmyDash, mdySlash, ymdDash, ymdSlash)

    @AfterTest
    fun restoreSeams() {
        dateFieldOrderProbe = ::jsDateFieldOrderProbe
        browserLanguageProvider = { kotlinx.browser.window.navigator.language }
    }

    private fun ok(
        text: String,
        spec: DateFieldOrder,
    ): LocalDate? = (parseLocalDate(text, spec) as? DateParse.Ok)?.date

    @Test
    fun format_isTwoDigitDayMonthAndFourDigitYear_inTheOrderOfTheSpec() {
        val date = LocalDate(2033, 11, 2)
        assertEquals("02.11.2033", formatLocalDate(date, dmyDot))
        assertEquals("02/11/2033", formatLocalDate(date, dmySlash))
        assertEquals("02-11-2033", formatLocalDate(date, dmyDash))
        assertEquals("11/02/2033", formatLocalDate(date, mdySlash))
        assertEquals("2033-11-02", formatLocalDate(date, ymdDash))
        assertEquals("0999-01-05".length, formatLocalDate(LocalDate(999, 1, 5), ymdDash).length, "a year below 1000 is still four digits")
    }

    @Test
    fun roundtrip_formatThenParse_givesTheSameDate_forEverySpec() {
        val dates = listOf(LocalDate(2024, 2, 29), LocalDate(2026, 3, 5), LocalDate(1999, 12, 31), LocalDate(2999, 1, 1))
        all.forEach { spec -> dates.forEach { assertEquals(it, ok(formatLocalDate(it, spec), spec), "$spec $it") } }
    }

    @Test
    fun parse_readsTheBlocksInTheOrderOfTheSpec_neverGuessing() {
        assertEquals(LocalDate(2026, 4, 3), ok("03/04/2026", dmySlash), "DMY: 3 April")
        assertEquals(LocalDate(2026, 3, 4), ok("03/04/2026", mdySlash), "MDY: 4 March")
        assertEquals(LocalDate(2026, 3, 5), ok("5.3.2026", dmyDot), "single digits are fine")
        assertEquals(LocalDate(2026, 3, 5), ok("2026/03/05", ymdSlash))
    }

    @Test
    fun parse_trimsTheEdges_butNotTheInside() {
        assertEquals(LocalDate(2026, 3, 5), ok("  05.03.2026\t", dmyDot))
        assertEquals(DateParse.BadFormat, parseLocalDate("05. 03.2026", dmyDot))
        assertEquals(DateParse.BadFormat, parseLocalDate("", dmyDot))
    }

    @Test
    fun parse_acceptsIsoInEveryLocale() {
        all.forEach { spec ->
            assertEquals(LocalDate(2026, 3, 5), ok("2026-03-05", spec), "$spec")
            assertEquals(LocalDate(2026, 3, 5), ok("2026-3-5", spec), "$spec")
        }
    }

    @Test
    fun parse_rejectsMixedSeparators_andWrongShapes() {
        assertEquals(DateParse.BadFormat, parseLocalDate("5.3/2026", dmyDot))
        assertEquals(DateParse.BadFormat, parseLocalDate("5.3.2026.", dmyDot))
        assertEquals(DateParse.BadFormat, parseLocalDate("nicht-ein-datum", dmyDot))
        assertEquals(DateParse.BadFormat, parseLocalDate("2026.03.05", dmyDot), "a four-digit day block is no day")
        assertEquals(DateParse.BadFormat, parseLocalDate("005.03.2026", dmyDot))
        assertEquals(DateParse.BadFormat, parseLocalDate("05.03.20260", dmyDot), "five year digits")
    }

    @Test
    fun parse_aTwoDigitYearIsNeverGuessed() {
        assertEquals(DateParse.TwoDigitYear, parseLocalDate("5.3.26", dmyDot))
        assertEquals(DateParse.TwoDigitYear, parseLocalDate("05.03.99", dmyDot))
        assertEquals(DateParse.TwoDigitYear, parseLocalDate("05.03.026", dmyDot))
    }

    @Test
    fun parse_aYearOutsideTheRange_isReportedAsSuch() {
        assertEquals(DateParse.YearOutOfRange, parseLocalDate("05.03.0099", dmyDot))
        assertEquals(DateParse.YearOutOfRange, parseLocalDate("05.03.1899", dmyDot))
        assertEquals(DateParse.YearOutOfRange, parseLocalDate("05.03.3000", dmyDot))
        assertEquals(LocalDate(1900, 1, 1), ok("01.01.1900", dmyDot))
        assertEquals(LocalDate(2999, 12, 31), ok("31.12.2999", dmyDot))
        assertEquals(DateParse.YearOutOfRange, parseLocalDate("0099-01-01", dmyDot), "ISO is range-checked as well")
    }

    @Test
    fun parse_aDateTheCalendarDoesNotHave_isNoSuchDate() {
        assertEquals(LocalDate(2024, 2, 29), ok("29.02.2024", dmyDot), "leap day exists")
        assertEquals(DateParse.NoSuchDate, parseLocalDate("29.02.2025", dmyDot))
        assertEquals(DateParse.NoSuchDate, parseLocalDate("31.04.2026", dmyDot))
        assertEquals(DateParse.NoSuchDate, parseLocalDate("32.01.2026", dmyDot), "a day above 31 is NoSuchDate, not BadFormat")
        assertEquals(DateParse.NoSuchDate, parseLocalDate("01.13.2026", dmyDot))
        assertEquals(DateParse.NoSuchDate, parseLocalDate("00.01.2026", dmyDot))
        assertEquals(DateParse.NoSuchDate, parseLocalDate("13/01/2026", mdySlash), "month 13 in MDY")
    }

    @Test
    fun patternText_putsTheWordsInTheOrderOfTheSpec() {
        assertEquals("TT.MM.JJJJ", patternText(dmyDot, "TT", "MM", "JJJJ"))
        assertEquals("MM/DD/YYYY", patternText(mdySlash, "DD", "MM", "YYYY"))
        assertEquals("YYYY-MM-DD", patternText(ymdDash, "DD", "MM", "YYYY"))
    }

    @Test
    fun resolve_germanIsAlwaysDayMonthYearWithDots_withoutAskingTheBrowser() {
        var asked = 0
        val probe: (String) -> DateFieldOrder? = {
            asked++
            mdySlash
        }
        assertEquals(dmyDot, resolveDateFieldOrder("de", "en-US", probe))
        assertEquals(dmyDot, resolveDateFieldOrder("de-DE", null, probe))
        assertEquals(0, asked)
    }

    @Test
    fun resolve_english_asksWithTheBrowsersEnglishTag_elseWithEnGb() {
        val asked = mutableListOf<String>()
        val probe: (String) -> DateFieldOrder? = { tag ->
            asked += tag
            if (tag == "en-US") mdySlash else dmySlash
        }
        assertEquals(mdySlash, resolveDateFieldOrder("en", "en-US", probe))
        assertEquals(dmySlash, resolveDateFieldOrder("en", "de-DE", probe))
        assertEquals(dmySlash, resolveDateFieldOrder("en", null, probe))
        assertEquals(listOf("en-US", "en-GB", "en-GB"), asked)
    }

    @Test
    fun resolve_otherLanguages_askWithTheirOwnTag() {
        val asked = mutableListOf<String>()
        val probe: (String) -> DateFieldOrder? = { tag ->
            asked += tag
            ymdDash
        }
        assertEquals(ymdDash, resolveDateFieldOrder("fr", "en-US", probe))
        assertEquals(listOf("fr"), asked)
    }

    @Test
    fun resolve_aProbeThatDoesNotKnow_fallsBackToTheFixedTable() {
        val unknown: (String) -> DateFieldOrder? = { null }
        assertEquals(dmySlash, resolveDateFieldOrder("fr", null, unknown))
        assertEquals(dmySlash, resolveDateFieldOrder("es", null, unknown))
        assertEquals(dmySlash, resolveDateFieldOrder("it", null, unknown))
        assertEquals(dmySlash, resolveDateFieldOrder("en", "en-US", unknown))
        assertEquals(dmyDash, resolveDateFieldOrder("nl", null, unknown))
        assertEquals(dmyDot, resolveDateFieldOrder("pl", null, unknown))
        assertEquals(dmyDot, resolveDateFieldOrder("ru", null, unknown))
        assertEquals(dmyDot, resolveDateFieldOrder("xx", null, unknown), "an unknown language reads like German")
    }

    @Test
    fun theRealProbe_knowsGerman_andNeverThrowsForNonsense() {
        assertEquals(dmyDot, jsDateFieldOrderProbe("de"))
        assertNull(jsDateFieldOrderProbe("this is not a language tag"))
    }

    @Test
    fun currentDateFieldOrder_usesTheInjectedProbeAndBrowserLanguage() {
        // UI language is `de` in the test setup: German never asks.
        dateFieldOrderProbe = { mdySlash }
        browserLanguageProvider = { "en-US" }
        assertEquals(dmyDot, currentDateFieldOrder())
        assertEquals("05.03.2026", localeDateString(LocalDate(2026, 3, 5)))
    }

    @Test
    fun theLocaleToken_resolvesInTheOrderOfTheLanguage_andAForgedPayloadFallsBackToTheRawDigits() {
        dateFieldOrderProbe = { mdySlash }
        browserLanguageProvider = { "en-US" }
        assertEquals("11/22/2033", formatValueTokenIn("en", I18N_VALUE_SENTINEL + DATE_KIND_LOCALE_FIELD + "2033-11-22"))
        assertEquals("22.11.2033", formatValueTokenIn("de", I18N_VALUE_SENTINEL + DATE_KIND_LOCALE_FIELD + "2033-11-22"))
        assertEquals("<b>x</b>", formatValueTokenIn("de", I18N_VALUE_SENTINEL + DATE_KIND_LOCALE_FIELD + "<b>x</b>"))
        assertTrue(formatTemporalTokenDigits(DATE_KIND_LOCALE_FIELD, "nicht", "de") == null)
    }
}
