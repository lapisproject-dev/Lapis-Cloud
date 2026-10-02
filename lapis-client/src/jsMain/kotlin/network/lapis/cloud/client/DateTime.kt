package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.span
import io.kvision.i18n.I18n
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
 *   1. `<field>.toString()` -- the only form a plain field-list grep sees. Converted app-wide at Teilwelle
 *      A, EXCEPT the three sites inside `ReportRows.kt`'s GoBD financial reports
 *      (`generalLedgerRows`/`kassenbuchRows`/`anonymousForwardingRows`, pinned verbatim by `ReportGoldenTest`)
 *      and 31 further call sites across 20 files Teilwelle A explicitly deferred as "Teilwelle B". This
 *      route is STILL a ledgered snapshot, not an enforced invariant -- `ClientTemporalFormatTripwireTest`'s
 *      `T5a` rule only ever lowers `T5A_LEDGER`'s ceiling, it does not require it to reach zero. See
 *      `docs/architecture/ui-ux-guideline.adoc`'s W7 section for the current file-by-file table.
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
 * covered by `T5b`/`T5b-λ` -- `DsgvoRightsScreen.kt`'s `erasureDecidedCaption` is one confirmed, deliberately
 * left-open instance (see the guideline's W7 section, Tesler-named follow-up).
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
 * **Why no `Intl.DateTimeFormat` / `toLocaleDateString`**: exactly [Money.kt]'s reasoning -- ICU output
 * depends on the browser, which would make the golden tests flaky, and this app does not need month
 * names, only numeric components in a fixed per-language order.
 */
fun formatDate(date: LocalDate): String = formatDateIn(I18n.language, date)

/** [formatDate] for an EXPLICIT language -- the seam tests use so they never have to set `I18n.language`. */
internal fun formatDateIn(
    language: String,
    date: LocalDate,
): String = formatDateComponents(date.year, date.month.number, date.day, dateLocale(language))

/** Day+month only (e.g. an anniversary), no year -- see [DATE_LOCALES] for the per-language form. */
fun formatDayMonth(date: LocalDate): String = formatDayMonthIn(I18n.language, date)

internal fun formatDayMonthIn(
    language: String,
    date: LocalDate,
): String = formatDayMonthComponents(date.month.number, date.day, dateLocale(language))

/** Date + time, no seconds -- the default form for a displayed `LocalDateTime`. */
fun formatDateTime(at: LocalDateTime): String = formatDateTimeIn(I18n.language, at)

internal fun formatDateTimeIn(
    language: String,
    at: LocalDateTime,
): String {
    val locale = dateLocale(language)
    return formatDateComponents(at.date.year, at.date.month.number, at.date.day, locale) +
        "," + NBSP + formatTimeComponents(at.hour, at.minute)
}

/** Date + time WITH seconds -- audit trails ([AuditLogScreen.kt]) and delivery logs ([WebhookDeliveryLogPanel.kt]). */
fun formatTimestamp(at: LocalDateTime): String = formatTimestampIn(I18n.language, at)

internal fun formatTimestampIn(
    language: String,
    at: LocalDateTime,
): String {
    val locale = dateLocale(language)
    return formatDateComponents(at.date.year, at.date.month.number, at.date.day, locale) +
        "," + NBSP + formatTimeComponents(at.hour, at.minute) + ":" + pad2(at.second)
}

/** Time only -- for a row whose date already stands in a group heading (e.g. a day's shift plan). */
fun formatTime(at: LocalDateTime): String = formatTimeIn(I18n.language, at)

internal fun formatTimeIn(
    language: String,
    at: LocalDateTime,
): String = formatTimeComponents(at.hour, at.minute)

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
    locale: DateLocale,
): String =
    when (locale.datePattern) {
        DatePattern.DMY_DOT -> "${pad2(day)}.${pad2(month)}.${pad4(year)}"
        DatePattern.ISO -> "${pad4(year)}-${pad2(month)}-${pad2(day)}"
        DatePattern.DMY_SLASH -> "${pad2(day)}/${pad2(month)}/${pad4(year)}"
        DatePattern.DMY_DASH -> "${pad2(day)}-${pad2(month)}-${pad4(year)}"
    }

internal fun formatDayMonthComponents(
    month: Int,
    day: Int,
    locale: DateLocale,
): String {
    val trailingDot = if (locale.dayMonthTrailingDot) "." else ""
    return when (locale.datePattern) {
        DatePattern.DMY_DOT -> "${pad2(day)}.${pad2(month)}$trailingDot"
        DatePattern.ISO -> "${pad2(month)}-${pad2(day)}"
        DatePattern.DMY_SLASH -> "${pad2(day)}/${pad2(month)}"
        DatePattern.DMY_DASH -> "${pad2(day)}-${pad2(month)}"
    }
}

/** No `locale` parameter: unlike the date, every one of the eight UI languages renders `HH:MM` identically
 * (see `DateTimeTest.eightLanguageMatrix_time`) -- there is no per-language variant to select, so there is
 * nothing here for a locale parameter to do. An earlier revision carried one anyway, dead and
 * `@Suppress`-ed; removed rather than kept as decoration a reader might assume does something. */
internal fun formatTimeComponents(
    hour: Int,
    minute: Int,
): String = "${pad2(hour)}:${pad2(minute)}"

// ---- locale table --------------------------------------------------------------------------------------------------------------

/** The four date-component orderings this app renders. No month names, no AM/PM, no zone -- see the file KDoc. */
internal enum class DatePattern { DMY_DOT, ISO, DMY_SLASH, DMY_DASH }

/**
 * Date pattern and [formatDayMonth]'s trailing dot of one UI language.
 *
 * [dayMonthTrailingDot] is only ever WIRED UP for [DatePattern.DMY_DOT] -- [formatDayMonthComponents]'s
 * `ISO`/`DMY_SLASH`/`DMY_DASH` branches never consult it (a trailing dot is a `de`/`pl`/`ru`-specific
 * ordinal-day convention, not something a slash- or dash-ordered date has ever needed). The `init` block
 * turns "a ninth language pairs `dayMonthTrailingDot = true` with a non-dot pattern" from a silent
 * mismatch between this KDoc and [formatDayMonthComponents] into a construction-time failure, so a future
 * [DATE_LOCALES] entry cannot make the same claim [formatDayMonthComponents] would then quietly ignore.
 */
internal data class DateLocale(
    val datePattern: DatePattern,
    /** `true` = [formatDayMonth] ends with a dot (`de`/`pl`/`ru`'s ordinal-day convention) -- meaningful
     * ONLY when [datePattern] is [DatePattern.DMY_DOT], see the class KDoc. */
    val dayMonthTrailingDot: Boolean,
) {
    init {
        require(!dayMonthTrailingDot || datePattern == DatePattern.DMY_DOT) {
            "dayMonthTrailingDot=true has no effect outside DatePattern.DMY_DOT " +
                "(formatDayMonthComponents never reads it for $datePattern) -- pass false, or add support " +
                "for a trailing dot to that branch first"
        }
    }
}

/** The eight UI languages (`SUPPORTED_LANGUAGES` in App.kt) -- same set as [MONEY_LOCALES]. */
internal val DATE_LOCALES: Map<String, DateLocale> =
    mapOf(
        "de" to DateLocale(datePattern = DatePattern.DMY_DOT, dayMonthTrailingDot = true),
        "pl" to DateLocale(datePattern = DatePattern.DMY_DOT, dayMonthTrailingDot = true),
        "ru" to DateLocale(datePattern = DatePattern.DMY_DOT, dayMonthTrailingDot = true),
        "en" to DateLocale(datePattern = DatePattern.ISO, dayMonthTrailingDot = false),
        "fr" to DateLocale(datePattern = DatePattern.DMY_SLASH, dayMonthTrailingDot = false),
        "es" to DateLocale(datePattern = DatePattern.DMY_SLASH, dayMonthTrailingDot = false),
        "it" to DateLocale(datePattern = DatePattern.DMY_SLASH, dayMonthTrailingDot = false),
        "nl" to DateLocale(datePattern = DatePattern.DMY_DASH, dayMonthTrailingDot = false),
    )

/** `I18n.language` may carry a region ("de-DE"); the table is keyed by the base tag. Unknown -> German. */
internal fun dateLocale(language: String): DateLocale =
    DATE_LOCALES[language.substringBefore('-').lowercase()] ?: DATE_LOCALES.getValue("de")

// ---- token mechanics (shares the sentinel with Money.kt, see the D5 rename there) -----------------------------------------------

internal const val DATE_KIND_DATE = 'd'
internal const val DATE_KIND_DAY_MONTH = 'm'
internal const val DATE_KIND_DATE_TIME = 't'
internal const val DATE_KIND_TIMESTAMP = 's'
internal const val DATE_KIND_TIME = 'c'

/** Live-translatable widget content for a [LocalDate]: `###KvI18nS###` + sentinel + kind + canonical ISO date. */
internal fun dateToken(date: LocalDate): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_DATE + date.toString()

internal fun dayMonthToken(date: LocalDate): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_DAY_MONTH + date.toString()

internal fun dateTimeToken(at: LocalDateTime): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_DATE_TIME + at.toString()

internal fun timestampToken(at: LocalDateTime): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_TIMESTAMP + at.toString()

internal fun timeToken(at: LocalDateTime): String = KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_TIME + at.toString()

/**
 * Resolves ONE of the five date/time token kinds' digits into display text, or `null` when [digits] is
 * not a parsable ISO value of the expected shape (a forged payload) -- the caller ([formatValueTokenIn]
 * in `Money.kt`) falls back to the raw digits in that case, so this function itself never needs to be
 * exception-safe towards its caller; it is towards a hostile payload, via [runCatching].
 */
internal fun formatTemporalTokenDigits(
    kind: Char,
    digits: String,
    language: String,
): String? =
    runCatching {
        when (kind) {
            DATE_KIND_DATE -> formatDateIn(language, LocalDate.parse(digits))
            DATE_KIND_DAY_MONTH -> formatDayMonthIn(language, LocalDate.parse(digits))
            DATE_KIND_DATE_TIME -> formatDateTimeIn(language, LocalDateTime.parse(digits))
            DATE_KIND_TIMESTAMP -> formatTimestampIn(language, LocalDateTime.parse(digits))
            DATE_KIND_TIME -> formatTimeIn(language, LocalDateTime.parse(digits))
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
