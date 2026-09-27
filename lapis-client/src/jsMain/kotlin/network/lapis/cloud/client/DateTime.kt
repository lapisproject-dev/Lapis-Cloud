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
 * temporal sibling of [Money.kt]'s amount convention. Every screen SHOULD call one of the five `format*`/
 * `*Span` functions below for every displayed [LocalDate]/[LocalDateTime]; no screen interpolates one
 * into a string itself, and no screen calls `.toString()` on a time field for DISPLAY (see [machineDate]/
 * [machineDateTime] for the legitimate exception: prefill and a sort key).
 *
 * **This is a rollout in progress, not yet a closed invariant** (Review-Befund 2026-09-24, MAJOR, round 3):
 * an earlier revision of this KDoc claimed the "every screen" sentence above as present-tense fact. It
 * was not -- 14 confirmed display-path `.toString()` call sites were measured across the client at round 2's
 * review time and converted, EXCEPT the three inside `ReportRows.kt`'s GoBD financial reports
 * (`generalLedgerRows`/`kassenbuchRows`/`anonymousForwardingRows`, pinned verbatim by `ReportGoldenTest` --
 * see below). Round 2's own fix pass, though, converted only SOME of the raw `.toString()` sites inside each
 * screen it touched and left the rest of that same screen on raw ISO -- a regression round 2 did not have:
 * `DunningCasesScreen.kt`, `AuditLogScreen.kt`, `SepaBatchesScreen.kt`, `OpenItemsScreen.kt`,
 * `AccountingExportScreen.kt` and `CrmContactsScreen.kt` each showed BOTH a localized and a raw-ISO
 * date/time on the same screen at once. Round 3 closed the remaining raw `.toString()` sites in exactly
 * those six files (15 further call sites).
 *
 * **That did NOT make those screens internally consistent, and an earlier revision of this KDoc wrongly
 * said it did** (Review-Befund 2026-09-24, MAJOR, round 4). Raw ISO reaches the DOM by three different
 * routes, and every count in this wave measured only the first:
 *   1. `<field>.toString()` -- the only form the field-list grep can see. Converted (except `ReportRows.kt`).
 *   2. `gettext("... %1", <LocalDate|LocalDateTime>)` -- no `.toString()` appears in the source at all;
 *      `gettext(key: String, vararg args: Any?)` stringifies every non-String argument in
 *      `I18nCatalogManager.kt` (`args[index - 1]?.toString()`). **53 such call sites in 25 client files,
 *      all still raw.**
 *   3. String interpolation of a temporal field -- at least 6 sites (`ApiKeysScreen.kt`,
 *      `EventCheckInSelectionScreen.kt`, `EventCheckInScreen.kt`, `PoliticianScreen.kt`).
 *
 * A fourth subset is unfindable by field name at all, because a lambda renames the field before the call
 * (`request.executedAt?.let { gettext("Ausgefuehrt am %1", it) }` -- only `it` remains at the call site).
 *
 * Consequence, reproducible today in `de`: `AuditLogScreen` renders `occurredAt` as "24.09.2026, 14:30:07"
 * in the list (route 1, converted) and as "2026-09-24T14:30:07" in the detail panel (route 2, untouched).
 * The same field, the same screen, two formats. `DunningCasesScreen`, `SepaBatchesScreen`,
 * `OpenItemsScreen`, `DsgvoRightsScreen` and `MemberFinancialHistoryScreen` each do the same.
 * `AccountingExportScreen.kt:261` and `VolunteerAllowanceLabels.kt:87` show the correct pattern for
 * route 2: format first, pass the resulting String into `gettext`.
 *
 * Round 3 also re-measured the client with round 2's own method (the field list of every `LocalDate`/
 * `LocalDateTime` property name in `lapis-shared`, then grepped against the client): round 2's "three sites
 * remain" undercounted -- at round 3's start there were 20 files (outside the six above), `ReportRows.kt`
 * among them, still carrying 33 raw display-path `.toString()` occurrences across 31 call sites in total.
 * That re-measurement used the same `.toString()`-only method and therefore shares its blind spot: it
 * counts route 1 exhaustively and routes 2 and 3 not at all.
 * `ReportRows.kt`'s three stay open for the reason round 2 gave (GoBD golden-test pinning); the other 19
 * files (28 call sites) are Teilwelle B, an unstarted later wave -- see
 * `docs/architecture/ui-ux-guideline.adoc`'s "Not done in this wave" section for the current file-by-file
 * list. Only `ClientTemporalFormatTripwireTest` closing that gap with a T5 rule (mirroring
 * `ClientMoneyFormatTripwireTest`'s M5, itself Teilwelle B's first task) would make the "every screen"
 * sentence above an enforced invariant rather than a convention; until then, re-run the grep above before
 * trusting any fixed count here.
 *
 * **No `Instant`, no zone conversion, no zone suffix.** Every persisted time field in `lapis-shared` is
 * [LocalDate] or [LocalDateTime] -- there is not a single `Instant` field on the wire (verified:
 * `grep -rn ": Instant\b" lapis-shared/src/commonMain/kotlin` -> 0 hits). A `LocalDateTime` here is the
 * WALL-CLOCK TIME OF THE SERVER PROCESS (`DbClock.nowLocalDateTime`, stamped with
 * `TimeZone.currentSystemDefault()`), not a UTC instant with the zone stripped -- there is no zone
 * information anywhere to convert FROM. Formatting therefore never touches `toInstant`, `TimeZone` or
 * `Clock` (enforced by the `T4` rule of `ClientTemporalFormatTripwireTest`, the temporal sibling of
 * `ClientMoneyFormatTripwireTest`'s M1-M6 for `Money.kt` -- T4 scans this very file's source for those
 * three identifiers, so it fails the moment this docstring's claim stops being true): the components of the value
 * are rendered exactly as they arrived, in the reader's UI language, never re-interpreted against a
 * browser zone that would silently disagree with the value the server actually stored (W8, a later wave,
 * replaces the storage type with `Instant`; until then a displayed zone suffix would be a claim this app
 * cannot back up).
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
