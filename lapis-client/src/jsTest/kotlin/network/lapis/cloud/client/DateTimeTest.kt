package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * W7 "Zeitstempel-Formatierung app-weit" -- covers the five `format*`/`*Token` forms of `DateTime.kt`,
 * the temporal sibling of [MoneyTest]. Every test goes through the `...In(locale|language, ...)` seam (the browser language is injected, see [noBrowserLanguage]) --
 * `I18n.language` is never set here (its setter restarts the root; that belongs only in the DOM test).
 * Every expectation with a date/time trenner is built from [NBSP] (`Money.kt`), never a literal space.
 */
class DateTimeTest {
    private val sample = LocalDate(2026, 9, 24)
    private val sampleDateTime = LocalDateTime(2026, 9, 24, 14, 30)
    private val sampleTimestamp = LocalDateTime(2026, 9, 24, 14, 30, 7)

    private val savedBrowserLanguage = browserLanguageProvider

    @BeforeTest
    fun noBrowserLanguage() {
        // CI runs Chrome with whatever language the image has: the `en` split must never depend on it.
        browserLanguageProvider = { null }
    }

    @AfterTest
    fun restoreBrowserLanguage() {
        browserLanguageProvider = savedBrowserLanguage
    }

    private fun locale(tag: String): TemporalLocale = TEMPORAL_LOCALES.getValue(tag)

    @Test
    fun nineLocaleMatrix_date() {
        assertEquals("24.09.2026", formatDateIn("de", sample))
        assertEquals("24/09/2026", formatDateIn("en", sample))
        assertEquals("24/09/2026", formatDateIn(locale("en-GB"), sample))
        assertEquals("09/24/2026", formatDateIn(locale("en-US"), sample))
        assertEquals("24/09/2026", formatDateIn("fr", sample))
        assertEquals("24/09/2026", formatDateIn("es", sample))
        assertEquals("24/09/2026", formatDateIn("it", sample))
        assertEquals("24-09-2026", formatDateIn("nl", sample))
        assertEquals("24.09.2026", formatDateIn("pl", sample))
        assertEquals("24.09.2026", formatDateIn("ru", sample))
    }

    @Test
    fun nineLocaleMatrix_dateTime() {
        assertEquals("24.09.2026,${NBSP}14:30", formatDateTimeIn("de", sampleDateTime))
        assertEquals("24/09/2026,${NBSP}14:30", formatDateTimeIn(locale("en-GB"), sampleDateTime))
        assertEquals("09/24/2026,${NBSP}2:30${NBSP}PM", formatDateTimeIn(locale("en-US"), sampleDateTime))
        assertEquals("24/09/2026,${NBSP}14:30", formatDateTimeIn("fr", sampleDateTime))
        assertEquals("24-09-2026,${NBSP}14:30", formatDateTimeIn("nl", sampleDateTime))
        val de = formatDateTimeIn("de", sampleDateTime)
        assertEquals(0x00A0, de[de.indexOf(',') + 1].code)
        assertFalse(de.contains(' '), "no plain space anywhere")
    }

    @Test
    fun nineLocaleMatrix_timestamp() {
        assertEquals("24.09.2026,${NBSP}14:30:07", formatTimestampIn("de", sampleTimestamp))
        assertEquals("24/09/2026,${NBSP}14:30:07", formatTimestampIn(locale("en-GB"), sampleTimestamp))
        assertEquals("09/24/2026,${NBSP}2:30:07${NBSP}PM", formatTimestampIn(locale("en-US"), sampleTimestamp))
        assertEquals("24/09/2026,${NBSP}14:30:07", formatTimestampIn("fr", sampleTimestamp))
    }

    @Test
    fun nineLocaleMatrix_time() {
        listOf("de", "en-GB", "fr", "es", "it", "nl", "pl", "ru").forEach { tag ->
            assertEquals("14:30", formatTimeIn(locale(tag), sampleDateTime))
        }
        assertEquals("2:30${NBSP}PM", formatTimeIn(locale("en-US"), sampleDateTime))
    }

    @Test
    fun nineLocaleMatrix_dayMonth() {
        assertEquals("24.09.", formatDayMonthIn("de", sample))
        assertEquals("24.09.", formatDayMonthIn("pl", sample))
        assertEquals("24.09.", formatDayMonthIn("ru", sample))
        assertEquals("24/09", formatDayMonthIn(locale("en-GB"), sample))
        assertEquals("09/24", formatDayMonthIn(locale("en-US"), sample))
        assertEquals("24/09", formatDayMonthIn("fr", sample))
        assertEquals("24/09", formatDayMonthIn("es", sample))
        assertEquals("24/09", formatDayMonthIn("it", sample))
        assertEquals("24-09", formatDayMonthIn("nl", sample))
    }

    @Test
    fun twelveHourClock_edgeCases() {
        val us = locale("en-US")
        assertEquals("12:00${NBSP}AM", formatTimeIn(us, LocalDateTime(2026, 1, 1, 0, 0)))
        assertEquals("12:30${NBSP}AM", formatTimeIn(us, LocalDateTime(2026, 1, 1, 0, 30)))
        assertEquals("12:00${NBSP}PM", formatTimeIn(us, LocalDateTime(2026, 1, 1, 12, 0)))
        assertEquals("1:05${NBSP}AM", formatTimeIn(us, LocalDateTime(2026, 1, 1, 1, 5)))
        assertEquals("11:59${NBSP}PM", formatTimeIn(us, LocalDateTime(2026, 1, 1, 23, 59)))
        assertEquals("12:00:00${NBSP}AM", formatTimeComponents(0, 0, HourCycle.H12, 0))
        assertEquals("12:00:09${NBSP}PM", formatTimeComponents(12, 0, HourCycle.H12, 9))
        assertEquals("3:05:09${NBSP}PM", formatTimeComponents(15, 5, HourCycle.H12, 9))
        // the separator before AM/PM is NBSP (U+00A0), never the narrow no-break space U+202F
        assertFalse(formatTimeIn(us, sampleDateTime).contains('\u202F'))
        assertEquals(0x00A0, formatTimeIn(us, sampleDateTime)[formatTimeIn(us, sampleDateTime).indexOf('M') - 2].code)
    }

    @Test
    fun weekdayDateTime() {
        val us = locale("en-US")
        // 24 September 2026 is a Thursday
        withTranslations(mapOf("Do." to "Thu")) {
            assertEquals("Thu,${NBSP}09/24/2026,${NBSP}2:30${NBSP}PM", formatWeekdayDateTimeIn(us, sampleDateTime))
        }
        assertEquals("Do.,${NBSP}24.09.2026,${NBSP}14:30", formatWeekdayDateTimeIn(locale("de"), sampleDateTime))
        assertEquals(
            formatWeekdayDateTimeIn(locale("de"), sampleDateTime),
            formatValueTokenIn("de", weekdayDateTimeToken(sampleDateTime).removePrefix(KV_I18N_MARKER)),
        )
    }

    @Test
    fun resolution_enSplitsByBrowserLanguageOnly() {
        assertEquals("en-US", resolveTemporalLocale("en", "en-US").tag)
        assertEquals("en-US", resolveTemporalLocale("en", "EN-us").tag)
        assertEquals("en-GB", resolveTemporalLocale("en", "en-CA").tag)
        assertEquals("en-GB", resolveTemporalLocale("en", "en-AU").tag)
        assertEquals("en-GB", resolveTemporalLocale("en", "de-DE").tag)
        assertEquals("en-GB", resolveTemporalLocale("en", null).tag)
        assertEquals("de", resolveTemporalLocale("de", "en-US").tag)
        assertEquals("de", resolveTemporalLocale("de-DE", null).tag)
        assertEquals("de", resolveTemporalLocale("xx", null).tag)
        assertEquals("fr", resolveTemporalLocale("fr", "en-US").tag)
        assertEquals(9, TEMPORAL_LOCALES.size)
    }

    @Test
    fun unknownOrRegionalLanguageTags() {
        assertEquals(formatDateIn("de", sample), formatDateIn("xx", sample))
        assertEquals(formatDateIn("de", sample), formatDateIn("de-DE", sample))
        assertEquals(formatDateIn("en", sample), formatDateIn("EN", sample))
        assertEquals(formatDateIn("en", sample), formatDateIn("en-GB", sample))
    }

    @Test
    fun edgeCases() {
        assertEquals("29.02.2028", formatDateIn("de", LocalDate(2028, 2, 29)))
        assertEquals("01.01.2026,${NBSP}00:00", formatDateTimeIn("de", LocalDateTime(2026, 1, 1, 0, 0)))
        assertEquals("31.12.2026,${NBSP}23:59:59", formatTimestampIn("de", LocalDateTime(2026, 12, 31, 23, 59, 59)))
        assertEquals("05.01.2026,${NBSP}09:07", formatDateTimeIn("de", LocalDateTime(2026, 1, 5, 9, 7)))
        assertEquals("01.01.0999", formatDateIn("de", LocalDate(999, 1, 1)))
        assertEquals("01.01.0099", formatDateIn("de", LocalDate(99, 1, 1)))
        assertEquals("01.01.10000", formatDateIn("de", LocalDate(10000, 1, 1)))
        assertEquals("01/01/0999", formatDateIn("en", LocalDate(999, 1, 1)))
    }

    @Test
    fun noMillisecondsEverAppear() {
        val withNanos = LocalDateTime(2026, 9, 24, 14, 30, 7, 123_000_000)
        assertFalse(formatDateTimeIn("de", withNanos).contains(".123"))
        assertFalse(formatTimestampIn("de", withNanos).contains(".123"))
        assertFalse(formatTimeIn("de", withNanos).contains(".123"))
        listOf("de", "en", "fr", "es", "it", "nl", "pl", "ru").forEach { language ->
            // NOT a bare `.contains(".")` check: de/pl/ru's date PATTERN itself uses dots as the day/month/year
            // separator (`24.09.2026`), so that would false-positive on every non-fractional timestamp. A
            // fractional-seconds suffix is unambiguous by shape instead: ":ss." immediately followed by a digit
            // (`:07.123`) -- the date component never has a colon before a dot.
            assertFalse(
                Regex(""":\d{2}\.\d""").containsMatchIn(formatTimestampIn(language, withNanos)),
                "no fractional seconds in $language",
            )
        }
    }

    @Test
    fun tokenRoundTrip() {
        val languages = listOf("de", "en", "fr", "es", "it", "nl", "pl", "ru")
        // the `en` case once as en-GB (the injected browser language is null) and once as en-US
        languages.forEach { language ->
            assertEquals(
                formatDateIn(language, sample),
                formatValueTokenIn(language, dateToken(sample).removePrefix(KV_I18N_MARKER)),
            )
            assertEquals(
                formatDayMonthIn(language, sample),
                formatValueTokenIn(language, dayMonthToken(sample).removePrefix(KV_I18N_MARKER)),
            )
            assertEquals(
                formatDateTimeIn(language, sampleDateTime),
                formatValueTokenIn(language, dateTimeToken(sampleDateTime).removePrefix(KV_I18N_MARKER)),
            )
            assertEquals(
                formatTimestampIn(language, sampleTimestamp),
                formatValueTokenIn(language, timestampToken(sampleTimestamp).removePrefix(KV_I18N_MARKER)),
            )
            assertEquals(
                formatTimeIn(language, sampleDateTime),
                formatValueTokenIn(language, timeToken(sampleDateTime).removePrefix(KV_I18N_MARKER)),
            )
        }
    }

    @Test
    fun unknownKindReturnsRawPayload() {
        assertEquals("2026-09-24", formatValueTokenIn("de", I18N_VALUE_SENTINEL + "Z" + "2026-09-24"))
    }

    @Test
    fun forgedDigitsFailClosed_neverThrow() {
        // Not a parsable date at all -- formatTemporalTokenDigits must fall back to the raw digits, never throw.
        assertEquals("not-a-date", formatValueTokenIn("de", I18N_VALUE_SENTINEL + DATE_KIND_DATE + "not-a-date"))
        assertEquals("", formatValueTokenIn("de", I18N_VALUE_SENTINEL + DATE_KIND_DATE_TIME + ""))
    }

    @Test
    fun payloadCarriesNoMarkerOrSeparator() {
        val tokens =
            listOf(
                dateToken(sample),
                dayMonthToken(sample),
                dateTimeToken(sampleDateTime),
                timestampToken(sampleTimestamp),
                timeToken(sampleDateTime),
            )
        tokens.forEach { token ->
            val payload = token.removePrefix(KV_I18N_MARKER)
            assertFalse(payload.contains(KV_I18N_MARKER))
            assertFalse(payload.contains(I18N_ARG_SEPARATOR))
            assertEquals(1, payload.count { it.toString() == I18N_VALUE_SENTINEL }, "exactly one sentinel in $payload")
        }
    }

    @Test
    fun machineFormsAreExactlyIso() {
        assertEquals(sample.toString(), machineDate(sample))
        assertEquals(sampleDateTime.toString(), machineDateTime(sampleDateTime))
        assertEquals(sampleTimestamp.toString(), machineDateTime(sampleTimestamp))
        val withNanos = LocalDateTime(2026, 9, 24, 14, 30, 7, 123_000_000)
        assertEquals(withNanos.toString(), machineDateTime(withNanos))
    }

    @Test
    fun temporalLocale_everyRealEntryConstructs() {
        TEMPORAL_LOCALES.values.forEach { l ->
            TemporalLocale(l.tag, l.order, l.separator, l.dayMonthTrailingDot, l.hourCycle)
        }
    }

    @Test
    fun temporalLocale_trailingDotOutsideDotSeparatorFailsClosed() {
        assertFailsWith<IllegalArgumentException> { TemporalLocale("x", DateOrder.DMY, '/', true, HourCycle.H23) }
        assertFailsWith<IllegalArgumentException> { TemporalLocale("x", DateOrder.MDY, '-', true, HourCycle.H12) }
        // the one separator the flag is meaningful for must NOT throw
        TemporalLocale("x", DateOrder.DMY, '.', true, HourCycle.H23)
    }

    @Test
    fun enUsTokenRoundTripIsTwelveHour() {
        browserLanguageProvider = { "en-US" }
        assertEquals(
            "09/24/2026,${NBSP}2:30${NBSP}PM",
            formatValueTokenIn("en", dateTimeToken(sampleDateTime).removePrefix(KV_I18N_MARKER)),
        )
    }

    // A prior `noZoneSuffixAnywhere` test asserted the five `format*In` outputs above never contain
    // "UTC"/"MESZ"/"Z"/"+" -- a dead assertion (Review-Befund 2026-09-24): every character these
    // functions can EVER emit is a digit or one of `.`/`/`/`-`/`:`/`,`/NBSP (see formatDateComponents/
    // formatDayMonthComponents/formatTimeComponents), so none of those four substrings is reachable no
    // matter what a future edit does -- the test could never go red, same anti-pattern this repo's own
    // VoteSectionRenderingDomTest KDoc already names ("checking for '99,99' here is a dead assertion
    // that cannot fail even without the fix"). The invariant this was meant to guard -- formatting never
    // touches `toInstant`/`TimeZone`/`Clock`, so a zone can never even be COMPUTED here, let alone
    // rendered -- is now enforced at the source level instead, where a regression is actually
    // detectable: `ClientTemporalFormatTripwireTest`'s T4 rule scans `DateTime.kt` itself for those
    // three identifiers.
}
