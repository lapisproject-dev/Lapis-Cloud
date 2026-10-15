package network.lapis.cloud.client

import io.kvision.i18n.I18n
import kotlinx.browser.window

/**
 * V1.9.137 "Datum und Uhrzeit überall im Locale" -- the ONE table that says how a date and a time are written in each UI language.
 * It replaces the two older truths (`DateTime.kt`'s per-language table and `LocaleDate.kt`'s browser-probe) with a fixed table of nine
 * locales; there is no `Intl` call anywhere in the client (tripwire T1 has no exception any more).
 *
 * The nine entries: `de`, `pl`, `ru` (day.month.year, 24 h), `en-GB`, `fr`, `es`, `it` (day/month/year, 24 h), `nl` (day-month-year, 24 h) and
 * `en-US` (month/day/year, 12 h with AM/PM). Only the English UI picks between `en-GB` and `en-US`, and only by the browser language: exactly
 * `en-US` gives `en-US`, every other value (`en-CA`, `en-AU`, `de-DE`, none) gives `en-GB`.
 */
enum class DateOrder { DMY, MDY, YMD }

/** 24-hour clock (`15:05`) or 12-hour clock with `AM`/`PM` (`3:05 PM`). */
enum class HourCycle { H23, H12 }

/**
 * How one locale writes a date and a time. [tag] is for humans and tests only.
 *
 * [dayMonthTrailingDot] is only meaningful with the separator `.` (`15.10.`) -- the `init` block fails closed otherwise, so a table entry cannot
 * claim a trailing dot the formatter would never print.
 */
data class TemporalLocale(
    val tag: String,
    val order: DateOrder,
    val separator: Char,
    val dayMonthTrailingDot: Boolean,
    val hourCycle: HourCycle,
) {
    init {
        require(!dayMonthTrailingDot || separator == '.') {
            "dayMonthTrailingDot=true has no effect with separator '$separator' -- pass false"
        }
    }
}

/** The nine locales. The key is the [TemporalLocale.tag]. */
internal val TEMPORAL_LOCALES: Map<String, TemporalLocale> =
    listOf(
        TemporalLocale("de", DateOrder.DMY, '.', true, HourCycle.H23),
        TemporalLocale("pl", DateOrder.DMY, '.', true, HourCycle.H23),
        TemporalLocale("ru", DateOrder.DMY, '.', true, HourCycle.H23),
        TemporalLocale("en-GB", DateOrder.DMY, '/', false, HourCycle.H23),
        TemporalLocale("en-US", DateOrder.MDY, '/', false, HourCycle.H12),
        TemporalLocale("fr", DateOrder.DMY, '/', false, HourCycle.H23),
        TemporalLocale("es", DateOrder.DMY, '/', false, HourCycle.H23),
        TemporalLocale("it", DateOrder.DMY, '/', false, HourCycle.H23),
        TemporalLocale("nl", DateOrder.DMY, '-', false, HourCycle.H23),
    ).associateBy { it.tag }

/**
 * The locale of the UI language [uiLanguage] (which may carry a region, `de-DE`): `de` is always `de`; `en` is `en-US` when [browserLanguage] is
 * exactly `en-US` (case-insensitive) and `en-GB` otherwise; the other languages use their own entry; an unknown language reads like German.
 * Pure -- the browser language is a parameter.
 */
fun resolveTemporalLocale(
    uiLanguage: String,
    browserLanguage: String?,
): TemporalLocale {
    val base = uiLanguage.substringBefore('-').lowercase()
    val key =
        when (base) {
            "en" -> if (browserLanguage != null && browserLanguage.trim().equals("en-US", ignoreCase = true)) "en-US" else "en-GB"
            else -> base
        }
    return TEMPORAL_LOCALES[key] ?: TEMPORAL_LOCALES.getValue("de")
}

/** Test seam: the browser's own language tag. Tests must put it back. */
internal var browserLanguageProvider: () -> String? = { runCatching { window.navigator.language }.getOrNull() }

/** The locale for the current UI language and browser -- see [resolveTemporalLocale]. */
fun currentTemporalLocale(): TemporalLocale = resolveTemporalLocale(I18n.language, browserLanguageProvider())
