package network.lapis.cloud.client

import io.kvision.i18n.I18n
import kotlinx.browser.window
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * V1.9.132 "Reisekosten-Feinschliff" -- the date INPUT convention of the travel expense screens: a person types a date in the order and with the
 * separator of their own locale (`22.11.2033`, `22/11/2033`, `11/22/2033`), the field shows the pattern as placeholder and hint, and what is
 * stored is always an ISO [LocalDate].
 *
 * **Relation to `DateTime.kt`** (two date truths, on purpose, for exactly one wave): `DateTime.kt` renders a date for DISPLAY from a fixed
 * per-language table and says "no Intl". This file serves the travel expense screens only, and there the ORDER and the SEPARATOR come from the
 * browser (see [resolveDateFieldOrder] and `LocaleDateProbe.kt`), with a deterministic fallback table when the browser cannot tell. Everything
 * that is not typed or shown on a travel expense screen keeps `DateTime.kt`'s forms (English: ISO). Follow-up wave: move `DateTime.kt` itself onto
 * [DateFieldOrder].
 *
 * Pure and DOM-free except [currentDateFieldOrder]/[localeDateString] (which read the UI language and the browser language) -- everything else is
 * directly unit-testable with injected [DateFieldOrder]s.
 */
enum class DateOrder { DMY, MDY, YMD }

/** Order of the three components plus the one separator character (`.`, `/` or `-`). */
data class DateFieldOrder(
    val order: DateOrder,
    val separator: Char,
)

/** Result of [parseLocalDate]. Every failure is a value, never an exception. */
sealed interface DateParse {
    data class Ok(
        val date: LocalDate,
    ) : DateParse

    /** Not three digit blocks with ONE common separator (or a block of an impossible length). */
    data object BadFormat : DateParse

    /** A year of one to three digits ("5.3.26"): never guessed into a century. */
    data object TwoDigitYear : DateParse

    /** Day or month out of range, or a day the month does not have (31 April, 29 February 2025). */
    data object NoSuchDate : DateParse

    /** A four-digit year outside [LOCALE_DATE_MIN_YEAR]..[LOCALE_DATE_MAX_YEAR]. */
    data object YearOutOfRange : DateParse
}

internal const val LOCALE_DATE_MIN_YEAR = 1900
internal const val LOCALE_DATE_MAX_YEAR = 2999

private val ISO_DATE_SHAPE = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
private val SPLIT_DATE_SHAPE = Regex("""^(\d+)([./-])(\d+)\2(\d+)$""")

/** `22.11.2033` for DMY/`.`: day and month two digits, year four digits, in the order and with the separator of [spec]. */
fun formatLocalDate(
    date: LocalDate,
    spec: DateFieldOrder,
): String {
    val day = pad2(date.day)
    val month = pad2(date.month.number)
    val year = pad4(date.year)
    val parts =
        when (spec.order) {
            DateOrder.DMY -> listOf(day, month, year)
            DateOrder.MDY -> listOf(month, day, year)
            DateOrder.YMD -> listOf(year, month, day)
        }
    return parts.joinToString(spec.separator.toString())
}

/**
 * Reads [text] in the order of [spec] -- the blocks are read in that order, NEVER guessed. Exception: a four-digit-first ISO date
 * (`2033-11-22`, one or two digit month and day) is YMD in every locale. The separator must be the same character twice; mixed forms
 * (`5.3/2026`) are [DateParse.BadFormat].
 */
fun parseLocalDate(
    text: String,
    spec: DateFieldOrder,
): DateParse {
    val trimmed = text.trim()
    val iso = ISO_DATE_SHAPE.matchEntire(trimmed)
    val dayText: String
    val monthText: String
    val yearText: String
    if (iso != null) {
        yearText = iso.groupValues[1]
        monthText = iso.groupValues[2]
        dayText = iso.groupValues[3]
    } else {
        val split = SPLIT_DATE_SHAPE.matchEntire(trimmed) ?: return DateParse.BadFormat
        val first = split.groupValues[1]
        val second = split.groupValues[3]
        val third = split.groupValues[4]
        when (spec.order) {
            DateOrder.DMY -> {
                dayText = first
                monthText = second
                yearText = third
            }
            DateOrder.MDY -> {
                monthText = first
                dayText = second
                yearText = third
            }
            DateOrder.YMD -> {
                yearText = first
                monthText = second
                dayText = third
            }
        }
    }
    if (dayText.length > 2 || monthText.length > 2) return DateParse.BadFormat
    when {
        yearText.length in 1..3 -> return DateParse.TwoDigitYear
        yearText.length != 4 -> return DateParse.BadFormat
    }
    val year = yearText.toInt()
    if (year < LOCALE_DATE_MIN_YEAR || year > LOCALE_DATE_MAX_YEAR) return DateParse.YearOutOfRange
    val month = monthText.toInt()
    val day = dayText.toInt()
    if (month !in 1..12 || day !in 1..31) return DateParse.NoSuchDate
    val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return DateParse.NoSuchDate
    return DateParse.Ok(date)
}

/** The visible pattern, e.g. `TT.MM.JJJJ`: the three words (already translated) in the order and with the separator of [spec]. */
fun patternText(
    spec: DateFieldOrder,
    dayWord: String,
    monthWord: String,
    yearWord: String,
): String {
    val parts =
        when (spec.order) {
            DateOrder.DMY -> listOf(dayWord, monthWord, yearWord)
            DateOrder.MDY -> listOf(monthWord, dayWord, yearWord)
            DateOrder.YMD -> listOf(yearWord, monthWord, dayWord)
        }
    return parts.joinToString(spec.separator.toString())
}

/**
 * The deterministic fallback when the browser cannot tell (probe returns `null`): `de`/`pl`/`ru` day-month-year with a dot, `fr`/`es`/`it`/`en` with a
 * slash, `nl` with a dash. `en` deliberately differs from `DateTime.kt`'s ISO table (see the file KDoc). Unknown languages: German.
 */
internal fun fallbackDateFieldOrder(uiLanguage: String): DateFieldOrder =
    when (uiLanguage.substringBefore('-').lowercase()) {
        "fr", "es", "it", "en" -> DateFieldOrder(DateOrder.DMY, '/')
        "nl" -> DateFieldOrder(DateOrder.DMY, '-')
        else -> DateFieldOrder(DateOrder.DMY, '.')
    }

/**
 * The order and separator to use for the UI language [uiLanguage]:
 *  - `de`: always day-month-year with a dot, without asking the browser (the primary audience, and ICU-independent);
 *  - `en`: asks the browser with its own tag if that is an English one (so `en-US` gives month-day-year), else with `en-GB`;
 *  - any other language: asks with the UI tag.
 * [probe] answers `null` when it does not know (or the separator is none of `.`, `/`, `-`); then [fallbackDateFieldOrder] applies.
 * [probe] is injected, so tests need no browser.
 */
fun resolveDateFieldOrder(
    uiLanguage: String,
    browserLanguage: String?,
    probe: (String) -> DateFieldOrder?,
): DateFieldOrder {
    val base = uiLanguage.substringBefore('-').lowercase()
    if (base == "de") return DateFieldOrder(DateOrder.DMY, '.')
    val tag =
        if (base == "en") {
            if (browserLanguage != null && browserLanguage.substringBefore('-').lowercase() == "en") browserLanguage else "en-GB"
        } else {
            uiLanguage
        }
    return probe(tag) ?: fallbackDateFieldOrder(uiLanguage)
}

/** Test seam: the probe [currentDateFieldOrder] asks (the browser one by default). Tests must put it back. */
internal var dateFieldOrderProbe: (String) -> DateFieldOrder? = ::jsDateFieldOrderProbe

/** Test seam: the browser's own language tag. Tests must put it back. */
internal var browserLanguageProvider: () -> String? = { runCatching { window.navigator.language }.getOrNull() }

/** The order and separator for the current UI language and browser -- see [resolveDateFieldOrder]. */
fun currentDateFieldOrder(): DateFieldOrder = resolveDateFieldOrder(I18n.language, browserLanguageProvider(), dateFieldOrderProbe)

/**
 * [date] in the locale order, as a plain string for a `gettext(...)` argument (it freezes at the language of the moment, like every plain date
 * string in a message). Widget content uses [localeDateSpan] (a token, follows a language switch).
 */
fun localeDateString(date: LocalDate): String = formatLocalDate(date, currentDateFieldOrder())
