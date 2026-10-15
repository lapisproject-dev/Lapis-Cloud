package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number

/**
 * V1.9.132 "Reisekosten-Feinschliff" / V1.9.137 -- the date and time INPUT convention of the whole client: a person types a date in the order and
 * with the separator of their own locale (`22.11.2033`, `22/11/2033`, `11/22/2033`) and a time as `15:05` (en-US also `3:05 PM`), the field shows the
 * pattern as placeholder and hint, and what is stored is always an ISO [LocalDate]/[LocalTime].
 *
 * The locale comes from the fixed table in `TemporalLocale.kt` (the same one `DateTime.kt` renders with, so what is shown is what can be typed
 * back); there is no `Intl` and no browser probe. Pure and DOM-free except [currentTemporalLocale]'s callers -- everything here takes the
 * [TemporalLocale] as a parameter and is directly unit-testable.
 *
 * [DateParse] is the result of [parseLocalDate]; every failure is a value, never an exception.
 */
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
    spec: TemporalLocale,
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
    spec: TemporalLocale,
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

/** [parseLocalDate] as a nullable: the date, or `null` for blank text or any failure. ISO is read in every locale. */
fun parseLocalDateOrNull(
    text: String,
    spec: TemporalLocale = currentTemporalLocale(),
): LocalDate? = (parseLocalDate(text, spec) as? DateParse.Ok)?.date

/** The visible pattern, e.g. `TT.MM.JJJJ`: the three words (already translated) in the order and with the separator of [spec]. */
fun patternText(
    spec: TemporalLocale,
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
 * [date] in the locale order, as a plain string for a `gettext(...)` argument (it freezes at the language of the moment, like every plain date
 * string in a message). Widget content uses `dateSpan` (a token, follows a language switch).
 */
fun localeDateString(date: LocalDate): String = formatLocalDate(date, currentTemporalLocale())

// ---- time ------------------------------------------------------------------------------------------------------------------------

/** Result of [parseLocalTime]. Every failure is a value, never an exception. */
sealed interface TimeParse {
    data class Ok(
        val time: LocalTime,
    ) : TimeParse

    /** Not `H:MM`/`HH:MM` (`.` also accepted as separator), or a 12-hour form in a locale that has none. */
    data object BadFormat : TimeParse

    /** Hour or minute out of range (`24:00`, `12:60`), or an hour a 12-hour clock does not have (`13:00 PM`, `0:30 AM`). */
    data object NoSuchTime : TimeParse

    /** A 12-hour locale and a bare `h:mm` (`3:05`): neither `AM` nor `PM` given and not a two-digit 24-hour form. */
    data object AmPmMissing : TimeParse
}

private val TIME_SHAPE = Regex("""^(\d{1,2})[:.](\d{2})(?:\s*([AaPp][Mm]))?$""")

/**
 * Reads [text] as a time of day. The 24-hour form (`15:05`, `9:05`, `09.05`) is read in every locale; a bare one-digit hour in a 12-hour locale
 * (`3:05`) is [TimeParse.AmPmMissing] because it could be either. Only a 12-hour locale ([HourCycle.H12]) accepts `h:mm AM|PM` (case-insensitive,
 * with or without a space); `12:xx AM` is `00:xx`, `12:xx PM` is `12:xx`. Digits without a separator (`9`, `0905`) are [TimeParse.BadFormat].
 */
fun parseLocalTime(
    text: String,
    locale: TemporalLocale,
): TimeParse {
    val match = TIME_SHAPE.matchEntire(text.trim()) ?: return TimeParse.BadFormat
    val hourText = match.groupValues[1]
    val hour = hourText.toInt()
    val minute = match.groupValues[2].toInt()
    val suffix = match.groupValues[3].uppercase()
    if (suffix.isNotEmpty()) {
        if (locale.hourCycle != HourCycle.H12) return TimeParse.BadFormat
        if (hour !in 1..12 || minute !in 0..59) return TimeParse.NoSuchTime
        val hour24 = if (suffix == "AM") hour % 12 else hour % 12 + 12
        return TimeParse.Ok(LocalTime(hour24, minute))
    }
    if (locale.hourCycle == HourCycle.H12 && hourText.length == 1) return TimeParse.AmPmMissing
    if (hour !in 0..23 || minute !in 0..59) return TimeParse.NoSuchTime
    return TimeParse.Ok(LocalTime(hour, minute))
}

/** The time of [time] as the locale writes it (`15:05`, en-US `3:05 PM`). */
fun formatLocalTime(
    time: LocalTime,
    locale: TemporalLocale,
): String = formatTimeComponents(time.hour, time.minute, locale.hourCycle)

/** The visible time pattern: `hh:mm` (translated) on a 24-hour clock, the fixed `h:mm AM/PM` on a 12-hour clock. */
fun timePatternText(locale: TemporalLocale): String = if (locale.hourCycle == HourCycle.H12) "h:mm AM/PM" else gettext("hh:mm")

/** Today (organization zone) as the locale writes it -- the prefill of a plain text date field. */
fun todayLocaleText(): String = formatLocalDate(organizationToday(), currentTemporalLocale())
