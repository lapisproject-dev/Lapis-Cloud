package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * W7 "Zeitstempel-Formatierung app-weit" -- covers the five `format*`/`*Token` forms of `DateTime.kt`,
 * the temporal sibling of [MoneyTest]. Every test goes through the `...In(language, ...)` seam --
 * `I18n.language` is never set here (its setter restarts the root; that belongs only in the DOM test).
 * Every expectation with a date/time trenner is built from [NBSP] (`Money.kt`), never a literal space.
 */
class DateTimeTest {
    private val sample = LocalDate(2026, 9, 24)
    private val sampleDateTime = LocalDateTime(2026, 9, 24, 14, 30)
    private val sampleTimestamp = LocalDateTime(2026, 9, 24, 14, 30, 7)

    @Test
    fun eightLanguageMatrix_date() {
        assertEquals("24.09.2026", formatDateIn("de", sample))
        assertEquals("2026-09-24", formatDateIn("en", sample))
        assertEquals("24/09/2026", formatDateIn("fr", sample))
        assertEquals("24/09/2026", formatDateIn("es", sample))
        assertEquals("24/09/2026", formatDateIn("it", sample))
        assertEquals("24-09-2026", formatDateIn("nl", sample))
        assertEquals("24.09.2026", formatDateIn("pl", sample))
        assertEquals("24.09.2026", formatDateIn("ru", sample))
    }

    @Test
    fun eightLanguageMatrix_dateTime() {
        assertEquals("24.09.2026,${NBSP}14:30", formatDateTimeIn("de", sampleDateTime))
        assertEquals("2026-09-24,${NBSP}14:30", formatDateTimeIn("en", sampleDateTime))
        assertEquals("24/09/2026,${NBSP}14:30", formatDateTimeIn("fr", sampleDateTime))
        assertEquals("24-09-2026,${NBSP}14:30", formatDateTimeIn("nl", sampleDateTime))
        val de = formatDateTimeIn("de", sampleDateTime)
        assertEquals(0x00A0, de[de.indexOf(',') + 1].code)
        assertFalse(de.contains(' '), "no plain space anywhere")
    }

    @Test
    fun eightLanguageMatrix_timestamp() {
        assertEquals("24.09.2026,${NBSP}14:30:07", formatTimestampIn("de", sampleTimestamp))
        assertEquals("2026-09-24,${NBSP}14:30:07", formatTimestampIn("en", sampleTimestamp))
        assertEquals("24/09/2026,${NBSP}14:30:07", formatTimestampIn("fr", sampleTimestamp))
    }

    @Test
    fun eightLanguageMatrix_time() {
        listOf("de", "en", "fr", "es", "it", "nl", "pl", "ru").forEach { language ->
            assertEquals("14:30", formatTimeIn(language, sampleDateTime))
        }
    }

    @Test
    fun eightLanguageMatrix_dayMonth() {
        assertEquals("24.09.", formatDayMonthIn("de", sample))
        assertEquals("24.09.", formatDayMonthIn("pl", sample))
        assertEquals("24.09.", formatDayMonthIn("ru", sample))
        assertEquals("09-24", formatDayMonthIn("en", sample))
        assertEquals("24/09", formatDayMonthIn("fr", sample))
        assertEquals("24/09", formatDayMonthIn("es", sample))
        assertEquals("24/09", formatDayMonthIn("it", sample))
        assertEquals("24-09", formatDayMonthIn("nl", sample))
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
        assertEquals("0999-01-01", formatDateIn("en", LocalDate(999, 1, 1)))
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

    // Review-Befund 2026-09-24 (round 2): the `DateLocale.init` `require` (round 1's fix for the
    // KDoc/`formatDayMonthComponents` mismatch, see the class KDoc) shipped with zero coverage --
    // `grep -rn "DateLocale(" lapis-client/src/jsTest lapis-server/src/test` found no call anywhere.
    // Two cases close that: every real `DATE_LOCALES` entry still constructs (positive), and the
    // combination the class KDoc says is meaningless -- `dayMonthTrailingDot = true` on a pattern other
    // than `DatePattern.DMY_DOT` -- fails closed instead of silently constructing (negative).
    @Test
    fun dateLocale_everyRealEntryConstructs() {
        DATE_LOCALES.values.forEach { locale ->
            DateLocale(datePattern = locale.datePattern, dayMonthTrailingDot = locale.dayMonthTrailingDot)
        }
    }

    @Test
    fun dateLocale_trailingDotOutsideDmyDotPatternFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            DateLocale(datePattern = DatePattern.ISO, dayMonthTrailingDot = true)
        }
        assertFailsWith<IllegalArgumentException> {
            DateLocale(datePattern = DatePattern.DMY_SLASH, dayMonthTrailingDot = true)
        }
        assertFailsWith<IllegalArgumentException> {
            DateLocale(datePattern = DatePattern.DMY_DASH, dayMonthTrailingDot = true)
        }
        // the one pattern the flag is meaningful for must NOT throw
        DateLocale(datePattern = DatePattern.DMY_DOT, dayMonthTrailingDot = true)
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
