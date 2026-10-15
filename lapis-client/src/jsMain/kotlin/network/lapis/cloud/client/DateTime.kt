package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.span
import io.kvision.i18n.gettext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number

/**
 * W7 "Zeitstempel-Formatierung app-weit" -- the single, shared date/time display convention, the
 * temporal sibling of [Money.kt]'s amount convention. Every screen calls one of the five `format*`/
 * `*Span` functions below for every displayed [LocalDate]/[LocalDateTime]; no screen interpolates one
 * into a string itself, and no screen calls `.toString()` on a time field for DISPLAY (see [machineDate]/
 * [machineDateTime] for the legitimate exception: prefill and a sort key).
 *
 * **Raw ISO can reach the DOM by three routes; two of the three are now an enforced invariant, not just
 * a convention** (V1.4.32 W7 Teilwelle B, 2026-09-27):
 *   1. `<field>.toString()` -- the only form a plain field-list grep sees. Converted app-wide at Teilwelle A and, since V1.9.137, closed for
 *      good: the 31 sites of 20 files Teilwelle A deferred (including the three GoBD report rows of `ReportRows.kt`) moved onto the
 *      formatters and the `*Span`/`*Column`/`*Token` forms. `T5A_LEDGER` is `emptyMap()` and `ClientTemporalFormatTripwireTest`'s `T5a`
 *      fails the build on any new raw site (T6 does the same for `today`/`now`).
 *   2. `gettext("... %1", <LocalDate|LocalDateTime>)` -- no `.toString()` appears in the source at all;
 *      `gettext(key: String, vararg args: Any?)` stringifies every non-String argument in
 *      `I18nCatalogManager.kt` (`args[index - 1]?.toString()`), directly or behind a
 *      `field?.let { ... it ... }` rename. **Closed app-wide by Teilwelle B** (86 direct sites + 14 behind
 *      a lambda rename, across 35 files) and now enforced by `T5b`/`T5b-λ` -- `T5B_LEDGER`/
 *      `T5B_LAMBDA_LEDGER` are both `emptyMap()`, and the build fails the moment either goes non-empty again.
 *   3. String interpolation of a temporal field (`"${field}"`, no wrapping `format*`/`*Token` call). **Closed
 *      app-wide by Teilwelle B** (6 sites, `ApiKeysScreen.kt`, `EventCheckInSelectionScreen.kt`,
 *      `EventCheckInScreen.kt`, `PoliticianScreen.kt`) and enforced by `T5c` (`T5C_LEDGER` is `emptyMap()`).
 *
 * A bare local `val` that renames a field before the call (`val decidedAt = request.decidedAt ?: return
 * null`, no lambda involved) has the same blind-spot shape as the lambda-rename case above and is NOT
 * covered by `T5b`/`T5b-λ`. Its confirmed instance, `DsgvoRightsScreen.kt`'s `erasureDecidedCaption`, formats through
 * [formatSystemDateTime] since V1.9.137 (together with `periodRangeCaption`, `postingConfirmDialog` and the AI draft deadline, which
 * the scan could not see either); a screen-by-screen review is the only guard for this shape.
 *
 * **No `Instant`, no zone conversion, no zone suffix -- in THIS file.** Every persisted time field in `lapis-shared` is
 * [LocalDate] or [LocalDateTime]; there is not a single `Instant` field on the wire. Since V1.9.38 a `LocalDateTime` is one of
 * two kinds (see `docs/architecture/time-and-timezones.adoc` and `OrganizationTime.kt`):
 *   - **class B**, a wall-clock typed in by a person in the organization's zone (`startsAt`, `closesAt`, `scheduledAt`, ...):
 *     rendered by THIS file exactly as it arrived.
 *   - **class A**, a system timestamp the server stamped, stored and sent as UTC (`createdAt`, `postedAt`, `expiresAt`, ...):
 *     converted to the organization's zone by `OrganizationTime.kt` (`formatSystemDateTime`, `systemDateTimeToken`, ...) and
 *     THEN rendered by this file. This file still never converts anything itself (enforced by the `T4` rule of
 *     `ClientTemporalFormatTripwireTest`, which scans this very file's source for `toInstant`, `TimeZone` and `Clock`);
 *     which of the two a field is lives in `lapis-server/src/test/resources/time-fields.tsv`, and
 *     `ClientSystemTimestampTripwireTest` fails the build when a class-A field is rendered here without the conversion.
 *
 * **Display vs. machine-readable**, exactly as in [Money.kt]: everything below is DISPLAY. What prefills
 * a field, is compared against an entered value, or is used as a lexicographic SORT KEY stays the
 * canonical ISO form ([machineDate]/[machineDateTime]) -- ISO-8601 is lexicographically ordered, so it is
 * also the correct chronological sort key, not only a correct round-trip value (see `LedgerScreen.kt`'s
 * `entryDate` comparator and `TravelExpenseScreen.kt`'s `createdAt` sort).
 *
 * **Widget content follows a language switch**, exactly as in [Money.kt]: `I18n.language`'s setter
 * restarts the root and re-renders the EXISTING widget tree; a plain `String` keeps its text. A
 * date/time that is widget content is therefore a token ([dateToken]/[dateTimeToken]/[timestampToken]/
 * [timeToken]/[dayMonthToken] -- a `###KvI18nS###` marker string [I18nCatalogManager.gettext] resolves on
 * every render). A date/time inside a `gettext(...)` message is a plain `format*` string and freezes at
 * the language of the moment, like the rest of that message (documented gap, same as [Money.kt]'s W4a).
 *
 * **Since V1.9.137** every date and time in the app follows the locale table of `TemporalLocale.kt` (nine locales, no `Intl`): `15.10.2026, 15:05`
 * (de), `15/10/2026, 15:05` (en-GB), `10/15/2026, 3:05 PM` (en-US). The `*In` functions take a [TemporalLocale] (or a language string, which is
 * resolved through [resolveTemporalLocale] with the browser language).
 *
 * **Why no `Intl.DateTimeFormat` / `toLocaleDateString`**: exactly [Money.kt]'s reasoning -- ICU output
 * depends on the browser, which would make the golden tests flaky, and this app does not need month
 * names, only numeric components in a fixed per-locale order.
 */
fun formatDate(date: LocalDate): String = formatDateIn(currentTemporalLocale(), date)

/** [formatDate] for an EXPLICIT locale -- the seam tests use so they never have to set `I18n.language`. */
internal fun formatDateIn(
    locale: TemporalLocale,
    date: LocalDate,
): String = formatDateComponents(date.year, date.month.number, date.day, locale)

/** [formatDateIn] for a UI language string (resolved with the browser language). */
internal fun formatDateIn(
    language: String,
    date: LocalDate,
): String = formatDateIn(languageLocale(language), date)

/** Day+month only (e.g. an anniversary), no year. */
fun formatDayMonth(date: LocalDate): String = formatDayMonthIn(currentTemporalLocale(), date)

internal fun formatDayMonthIn(
    locale: TemporalLocale,
    date: LocalDate,
): String = formatDayMonthComponents(date.month.number, date.day, locale)

internal fun formatDayMonthIn(
    language: String,
    date: LocalDate,
): String = formatDayMonthIn(languageLocale(language), date)

/** Date + time, no seconds -- the default form for a displayed `LocalDateTime`. */
fun formatDateTime(at: LocalDateTime): String = formatDateTimeIn(currentTemporalLocale(), at)

internal fun formatDateTimeIn(
    locale: TemporalLocale,
    at: LocalDateTime,
): String =
    formatDateComponents(at.date.year, at.date.month.number, at.date.day, locale) +
        "," + NBSP + formatTimeComponents(at.hour, at.minute, locale.hourCycle)

internal fun formatDateTimeIn(
    language: String,
    at: LocalDateTime,
): String = formatDateTimeIn(languageLocale(language), at)

/** Date + time WITH seconds -- audit trails ([AuditLogScreen.kt]) and delivery logs ([WebhookDeliveryLogPanel.kt]). */
fun formatTimestamp(at: LocalDateTime): String = formatTimestampIn(currentTemporalLocale(), at)

internal fun formatTimestampIn(
    locale: TemporalLocale,
    at: LocalDateTime,
): String =
    formatDateComponents(at.date.year, at.date.month.number, at.date.day, locale) +
        "," + NBSP + formatTimeComponents(at.hour, at.minute, locale.hourCycle, at.second)

internal fun formatTimestampIn(
    language: String,
    at: LocalDateTime,
): String = formatTimestampIn(languageLocale(language), at)

/** Time only -- for a row whose date already stands in a group heading (e.g. a day's shift plan). */
fun formatTime(at: LocalDateTime): String = formatTimeIn(currentTemporalLocale(), at)

internal fun formatTimeIn(
    locale: TemporalLocale,
    at: LocalDateTime,
): String = formatTimeComponents(at.hour, at.minute, locale.hourCycle)

internal fun formatTimeIn(
    language: String,
    at: LocalDateTime,
): String = formatTimeIn(languageLocale(language), at)

/**
 * Weekday abbreviation + date + time (`Do., 15.10.2026, 18:00`, en-US `Thu, 10/15/2026, 6:00 PM`) -- only the events window uses it (V1.9.137).
 * The abbreviation is a catalog text (`gettext`, resolved at the moment of formatting), so as widget content it goes through the token
 * ([weekdayDateTimeToken]) and follows a language switch.
 */
fun formatWeekdayDateTime(at: LocalDateTime): String = formatWeekdayDateTimeIn(currentTemporalLocale(), at)

internal fun formatWeekdayDateTimeIn(
    locale: TemporalLocale,
    at: LocalDateTime,
): String = weekdayShortLabel(at.date.dayOfWeek) + "," + NBSP + formatDateTimeIn(locale, at)

/** The abbreviation of a weekday in the current language -- resolved text, never a `tr()` marker (it is embedded mid-string). */
internal fun weekdayShortLabel(day: DayOfWeek): String =
    when (day) {
        DayOfWeek.MONDAY -> gettext("Mo.")
        DayOfWeek.TUESDAY -> gettext("Di.")
        DayOfWeek.WEDNESDAY -> gettext("Mi.")
        DayOfWeek.THURSDAY -> gettext("Do.")
        DayOfWeek.FRIDAY -> gettext("Fr.")
        DayOfWeek.SATURDAY -> gettext("Sa.")
        DayOfWeek.SUNDAY -> gettext("So.")
    }

/** The locale of a UI language string, with the browser language for the `en` split. */
internal fun languageLocale(language: String): TemporalLocale = resolveTemporalLocale(language, browserLanguageProvider())

// ---- component rendering (shared by the token resolver below) ---------------------------------------------------------------

/** Zero-padded to two digits -- `internal`, not `private`, so a screen that must embed a raw day/month/
 * hour/minute/second component inside a `gettext`-translated sentence (where the full `format*` string
 * cannot be inserted as-is) shares THIS implementation instead of re-deriving its own
 * `.toString().padStart(2, '0')` (tripwire T3 in `ClientTemporalFormatTripwireTest` forbids the latter). */
internal fun pad2(value: Int): String = value.toString().padStart(2, '0')

/** Zero-padded to four digits -- see [pad2] for why this is `internal`. */
internal fun pad4(value: Int): String = value.toString().padStart(4, '0')

internal fun formatDateComponents(
    year: Int,
    month: Int,
    day: Int,
    locale: TemporalLocale,
): String {
    val parts =
        when (locale.order) {
            DateOrder.DMY -> listOf(pad2(day), pad2(month), pad4(year))
            DateOrder.MDY -> listOf(pad2(month), pad2(day), pad4(year))
            DateOrder.YMD -> listOf(pad4(year), pad2(month), pad2(day))
        }
    return parts.joinToString(locale.separator.toString())
}

internal fun formatDayMonthComponents(
    month: Int,
    day: Int,
    locale: TemporalLocale,
): String {
    val trailingDot = if (locale.dayMonthTrailingDot) "." else ""
    val parts =
        when (locale.order) {
            DateOrder.DMY, DateOrder.YMD -> listOf(pad2(day), pad2(month))
            DateOrder.MDY -> listOf(pad2(month), pad2(day))
        }
    return parts.joinToString(locale.separator.toString()) + trailingDot
}

/** The 12-hour clock's suffixes. Constants, not literals: they are not German and must not read as a raw German fallback to the catalog scan. */
private const val AM_SUFFIX = "AM"
private const val PM_SUFFIX = "PM"

/**
 * `15:05` on the 24-hour clock; `3:05 PM` on the 12-hour clock (NBSP before `AM`/`PM`, never U+202F; `00:xx` is `12:xx AM`, `12:xx` is `12:xx PM`).
 * [second] is appended before the AM/PM suffix (`3:05:09 PM`).
 */
internal fun formatTimeComponents(
    hour: Int,
    minute: Int,
    cycle: HourCycle = HourCycle.H23,
    second: Int? = null,
): String {
    val seconds = if (second == null) "" else ":" + pad2(second)
    return when (cycle) {
        HourCycle.H23 -> "${pad2(hour)}:${pad2(minute)}$seconds"
        HourCycle.H12 -> {
            val h = if (hour % 12 == 0) 12 else hour % 12
            "$h:${pad2(minute)}$seconds" + NBSP + (if (hour < 12) AM_SUFFIX else PM_SUFFIX)
        }
    }
}

// ---- token mechanics (shares the sentinel with Money.kt, see the D5 rename there) -----------------------------------------------

internal const val DATE_KIND_DATE = 'd'
internal const val DATE_KIND_DAY_MONTH = 'm'
internal const val DATE_KIND_DATE_TIME = 't'
internal const val DATE_KIND_TIMESTAMP = 's'
internal const val DATE_KIND_TIME = 'c'
internal const val DATE_KIND_WEEKDAY_DATE_TIME = 'w'

/** Live-translatable widget content for a [LocalDate]: `###KvI18nS###` + sentinel + kind + canonical ISO date. */
internal fun dateToken(date: LocalDate): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_DATE + date.toString()

internal fun dayMonthToken(date: LocalDate): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_DAY_MONTH + date.toString()

internal fun dateTimeToken(at: LocalDateTime): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_DATE_TIME + at.toString()

internal fun timestampToken(at: LocalDateTime): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_TIMESTAMP + at.toString()

internal fun timeToken(at: LocalDateTime): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_TIME + at.toString()

internal fun weekdayDateTimeToken(at: LocalDateTime): String =
    KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_WEEKDAY_DATE_TIME + at.toString()

/**
 * Resolves ONE of the five date/time token kinds' digits into display text, or `null` when [digits] is
 * not a parsable ISO value of the expected shape (a forged payload) -- the caller ([formatValueTokenIn]
 * in `Money.kt`) falls back to the raw digits in that case, so this function itself never needs to be
 * exception-safe towards its caller; it is towards a hostile payload, via [runCatching].
 */
internal fun formatTemporalTokenDigits(
    kind: Char,
    digits: String,
    locale: TemporalLocale,
): String? =
    runCatching {
        when (kind) {
            DATE_KIND_DATE -> formatDateIn(locale, LocalDate.parse(digits))
            DATE_KIND_DAY_MONTH -> formatDayMonthIn(locale, LocalDate.parse(digits))
            DATE_KIND_DATE_TIME -> formatDateTimeIn(locale, LocalDateTime.parse(digits))
            DATE_KIND_TIMESTAMP -> formatTimestampIn(locale, LocalDateTime.parse(digits))
            DATE_KIND_TIME -> formatTimeIn(locale, LocalDateTime.parse(digits))
            DATE_KIND_WEEKDAY_DATE_TIME -> formatWeekdayDateTimeIn(locale, LocalDateTime.parse(digits))
            else -> null
        }
    }.getOrNull()

// ---- widget content --------------------------------------------------------------------------------------------------------------

/** CSS class of zero-width-stable digits (`theme.css` `.lapis-tnum`) -- applied to every span/column below, and to [Money.kt]'s. */
internal const val TABULAR_NUMS_CLASS = "lapis-tnum"

fun Container.dateSpan(date: LocalDate): Span = span(dateToken(date)) { addCssClass(TABULAR_NUMS_CLASS) }

fun Container.dayMonthSpan(date: LocalDate): Span = span(dayMonthToken(date)) { addCssClass(TABULAR_NUMS_CLASS) }

fun Container.dateTimeSpan(at: LocalDateTime): Span = span(dateTimeToken(at)) { addCssClass(TABULAR_NUMS_CLASS) }

fun Container.timestampSpan(at: LocalDateTime): Span = span(timestampToken(at)) { addCssClass(TABULAR_NUMS_CLASS) }

fun Container.timeSpan(at: LocalDateTime): Span = span(timeToken(at)) { addCssClass(TABULAR_NUMS_CLASS) }

/** Weekday + date + time as live-translatable widget content, for the events window. */
fun Container.weekdayDateTimeSpan(at: LocalDateTime): Span = span(weekdayDateTimeToken(at)) { addCssClass(TABULAR_NUMS_CLASS) }

// ---- table columns (DataTableState.kt's textColumn, see the file KDoc there for the trust mechanics) ---------------------------

/**
 * The temporal sibling of a plain [textColumn]: a table cell whose value is a [LocalDate]/[LocalDateTime]
 * MUST go through one of these three factories, never through a bare `textColumn { formatDate(...) }` --
 * [textColumn]'s cell renderer sanitizes a plain `String` result unconditionally (correct for untrusted
 * server text), which silently strips a token's sentinel and leaves the raw ISO text on screen (no error,
 * no log -- see the file KDoc's sanitisation-loss warning). Wrapping the token in [trusted] here, once,
 * keeps every date/time-column call site a one-liner that cannot get this wrong.
 */
fun <R> dateColumn(
    title: String,
    numeric: Boolean = true,
    primary: Boolean = false,
    sortKey: String? = null,
    cssClasses: String? = null,
    value: (R) -> LocalDate?,
): DataColumn<R> =
    textColumn<R>(
        title = title,
        numeric = numeric,
        primary = primary,
        sortKey = sortKey,
        cssClasses = listOfNotNull(TABULAR_NUMS_CLASS, cssClasses).joinToString(" "),
    ) { row -> value(row)?.let { trusted(dateToken(it)) } ?: "–" }

fun <R> dateTimeColumn(
    title: String,
    numeric: Boolean = true,
    primary: Boolean = false,
    sortKey: String? = null,
    cssClasses: String? = null,
    value: (R) -> LocalDateTime?,
): DataColumn<R> =
    textColumn<R>(
        title = title,
        numeric = numeric,
        primary = primary,
        sortKey = sortKey,
        cssClasses = listOfNotNull(TABULAR_NUMS_CLASS, cssClasses).joinToString(" "),
    ) { row -> value(row)?.let { trusted(dateTimeToken(it)) } ?: "–" }

fun <R> timestampColumn(
    title: String,
    numeric: Boolean = true,
    primary: Boolean = false,
    sortKey: String? = null,
    cssClasses: String? = null,
    value: (R) -> LocalDateTime?,
): DataColumn<R> =
    textColumn<R>(
        title = title,
        numeric = numeric,
        primary = primary,
        sortKey = sortKey,
        cssClasses = listOfNotNull(TABULAR_NUMS_CLASS, cssClasses).joinToString(" "),
    ) { row -> value(row)?.let { trusted(timestampToken(it)) } ?: "–" }

// ---- machine-readable: identity, never localized (prefill, comparison, and the SORT KEY use, D8) --------------------------------

/** Canonical ISO date -- the field-prefill / sort-key form. Byte-identical to `date.toString()`, named so a reader never reaches for `.toString()` again. */
fun machineDate(date: LocalDate): String = date.toString()

/** Canonical ISO date-time -- the field-prefill / sort-key form. Byte-identical to `at.toString()`. */
fun machineDateTime(at: LocalDateTime): String = at.toString()
