package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.132/V1.9.137 -- the pure core of the date and time input fields (`LocaleDate.kt`): parsing, formatting and the visible pattern, with INJECTED
 * [TemporalLocale]s (no browser, no `Intl`).
 */
class LocaleDateTest {
    private fun spec(
        order: DateOrder,
        separator: Char,
        cycle: HourCycle = HourCycle.H23,
    ) = TemporalLocale("test", order, separator, false, cycle)

    private val dmyDot = spec(DateOrder.DMY, '.')
    private val dmySlash = spec(DateOrder.DMY, '/')
    private val dmyDash = spec(DateOrder.DMY, '-')
    private val mdySlash = spec(DateOrder.MDY, '/', HourCycle.H12)
    private val ymdDash = spec(DateOrder.YMD, '-')
    private val ymdSlash = spec(DateOrder.YMD, '/')

    private val all = listOf(dmyDot, dmySlash, dmyDash, mdySlash, ymdDash, ymdSlash)

    private val savedBrowserLanguage = browserLanguageProvider

    @AfterTest
    fun restoreSeams() {
        browserLanguageProvider = savedBrowserLanguage
    }

    private fun ok(
        text: String,
        spec: TemporalLocale,
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
    fun date_extraCases_leapDayYearEdgesAndIso() {
        assertEquals(LocalDate(2028, 2, 29), ok("29.02.2028", dmyDot))
        assertEquals(DateParse.NoSuchDate, parseLocalDate("29.02.2027", dmyDot))
        assertEquals(DateParse.NoSuchDate, parseLocalDate("31.04.2026", dmyDot))
        assertEquals(DateParse.YearOutOfRange, parseLocalDate("01.01.0099", dmyDot))
        assertEquals(DateParse.BadFormat, parseLocalDate("01.01.10000", dmyDot), "five year digits")
        all.forEach { assertEquals(LocalDate(2026, 10, 15), ok("2026-10-15", it)) }
    }

    @Test
    fun parseLocalDateOrNull_isNullForBlankAndAnyFailure() {
        assertNull(parseLocalDateOrNull("", dmyDot))
        assertNull(parseLocalDateOrNull("15.13.2026", dmyDot))
        assertEquals(LocalDate(2026, 10, 15), parseLocalDateOrNull("15.10.2026", dmyDot))
    }

    @Test
    fun currentLocale_andLocaleDateString_followTheInjectedBrowserLanguage() {
        // UI language is `de` in the test setup: German is German whatever the browser says.
        browserLanguageProvider = { "en-US" }
        assertEquals("de", currentTemporalLocale().tag)
        assertEquals("05.03.2026", localeDateString(LocalDate(2026, 3, 5)))
    }

    @Test
    fun theTokenKinds_resolveInTheLocaleOfTheLanguage_andAForgedPayloadFallsBackToTheRawDigits() {
        browserLanguageProvider = { "en-US" }
        assertEquals("11/22/2033", formatValueTokenIn("en", I18N_VALUE_SENTINEL + DATE_KIND_DATE + "2033-11-22"))
        assertEquals("22.11.2033", formatValueTokenIn("de", I18N_VALUE_SENTINEL + DATE_KIND_DATE + "2033-11-22"))
        assertEquals("<b>x</b>", formatValueTokenIn("de", I18N_VALUE_SENTINEL + DATE_KIND_DATE + "<b>x</b>"))
        assertTrue(formatTemporalTokenDigits(DATE_KIND_DATE, "nicht", TEMPORAL_LOCALES.getValue("de")) == null)
        assertTrue(formatTemporalTokenDigits(DATE_KIND_WEEKDAY_DATE_TIME, "<b>x</b>", TEMPORAL_LOCALES.getValue("de")) == null)
    }

    // ---- time -------------------------------------------------------------------------------------------------------------------

    private fun time(
        text: String,
        spec: TemporalLocale,
    ): LocalTime? = (parseLocalTime(text, spec) as? TimeParse.Ok)?.time

    @Test
    fun time_twentyFourHourFormIsReadInEveryLocale() {
        all.forEach { spec ->
            assertEquals(LocalTime(15, 5), time("15:05", spec), "$spec")
            assertEquals(LocalTime(0, 0), time("00:00", spec), "$spec")
            assertEquals(LocalTime(23, 59), time("23:59", spec), "$spec")
        }
        assertEquals(LocalTime(9, 5), time("9:05", dmyDot))
        assertEquals(LocalTime(9, 5), time("09.05", dmyDot), "a dot is accepted as the separator")
    }

    @Test
    fun time_badFormat() {
        assertEquals(TimeParse.BadFormat, parseLocalTime("9", dmyDot))
        assertEquals(TimeParse.BadFormat, parseLocalTime("0905", dmyDot))
        assertEquals(TimeParse.BadFormat, parseLocalTime("", dmyDot))
        assertEquals(TimeParse.BadFormat, parseLocalTime("9:5", dmyDot))
        assertEquals(TimeParse.BadFormat, parseLocalTime("nicht", dmyDot))
        assertEquals(TimeParse.BadFormat, parseLocalTime("3:05 PM", dmyDot), "a 24-hour locale has no AM/PM form")
    }

    @Test
    fun time_noSuchTime() {
        assertEquals(TimeParse.NoSuchTime, parseLocalTime("24:00", dmyDot))
        assertEquals(TimeParse.NoSuchTime, parseLocalTime("12:60", dmyDot))
        assertEquals(TimeParse.NoSuchTime, parseLocalTime("13:00 PM", mdySlash))
        assertEquals(TimeParse.NoSuchTime, parseLocalTime("0:30 AM", mdySlash))
        assertEquals(TimeParse.NoSuchTime, parseLocalTime("12:60 PM", mdySlash))
    }

    @Test
    fun time_twelveHourLocale_readsAmPm_caseInsensitive_withOrWithoutSpace() {
        assertEquals(LocalTime(15, 5), time("3:05 PM", mdySlash))
        assertEquals(LocalTime(15, 5), time("3:05pm", mdySlash))
        assertEquals(LocalTime(15, 5), time("3:05 Pm", mdySlash))
        assertEquals(LocalTime(15, 5), time("3:05${NBSP}PM", mdySlash))
        assertEquals(LocalTime(0, 30), time("12:30 AM", mdySlash))
        assertEquals(LocalTime(12, 0), time("12:00 PM", mdySlash))
        assertEquals(LocalTime(0, 0), time("12:00 AM", mdySlash))
        assertEquals(LocalTime(11, 59), time("11:59 AM", mdySlash))
        assertEquals(LocalTime(23, 59), time("11:59 PM", mdySlash))
    }

    @Test
    fun time_twelveHourLocale_aBareOneDigitHourNeedsAmPm_butTheTwoDigitFormIsTwentyFourHour() {
        assertEquals(TimeParse.AmPmMissing, parseLocalTime("3:05", mdySlash))
        assertEquals(LocalTime(3, 5), time("03:05", mdySlash))
        assertEquals(LocalTime(15, 5), time("15:05", mdySlash))
    }

    @Test
    fun time_formatThenParse_roundTrips() {
        val times = listOf(LocalTime(0, 0), LocalTime(0, 30), LocalTime(12, 0), LocalTime(15, 5), LocalTime(23, 59))
        all.forEach { spec -> times.forEach { assertEquals(it, time(formatLocalTime(it, spec), spec), "$spec $it") } }
    }

    @Test
    fun timePattern_isTranslatedOnATwentyFourHourClock_andFixedOnATwelveHourClock() {
        assertEquals("h:mm AM/PM", timePatternText(mdySlash))
        withTranslations(mapOf("hh:mm" to "uu:mm")) { assertEquals("uu:mm", timePatternText(dmyDot)) }
    }

    @Test
    fun formRulesLocalTime_namesThePatternOrTheReason() {
        assertEquals(FieldCheck.Ok, FormRules.localTime("15:05", dmyDot))
        assertEquals(FieldCheck.Invalid("Bitte eine Uhrzeit im Format hh:mm eingeben."), FormRules.localTime("9", dmyDot))
        assertEquals(FieldCheck.Invalid("Diese Uhrzeit gibt es nicht."), FormRules.localTime("25:00", dmyDot))
        assertEquals(FieldCheck.Invalid("Bitte AM oder PM angeben."), FormRules.localTime("3:05", mdySlash))
        assertEquals(FieldCheck.Invalid("Bitte eine Uhrzeit im Format h:mm AM/PM eingeben."), FormRules.localTime("x", mdySlash))
    }

    @Test
    fun todayLocaleText_isTheOrganizationDayInTheLocaleForm() {
        assertEquals(formatLocalDate(organizationToday(), currentTemporalLocale()), todayLocaleText())
    }
}
