package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber

/**
 * Welle V1.9.11 -- moved here from [ArticlePublicHtml] (was `private`, file-scoped there) so
 * [PublicOverviewHtml] can reuse the SAME German date formatting for `/aktuelles` and
 * `/veranstaltungen`, rather than a second, independently-drifting copy (Tesler: no duplication).
 * [germanDate]/[germanTime] use [LocalDateTime] as-is, no timezone conversion -- same convention every
 * other rendered German date in this codebase already uses (see [ArticlePublicHtml]'s original KDoc,
 * preserved here verbatim).
 */
internal val GERMAN_MONTHS =
    listOf(
        "Januar",
        "Februar",
        "März",
        "April",
        "Mai",
        "Juni",
        "Juli",
        "August",
        "September",
        "Oktober",
        "November",
        "Dezember",
    )

/** German day/month/year, no timezone conversion -- see this file's class KDoc. */
internal fun germanDate(dt: LocalDateTime): String = "${dt.dayOfMonth}. ${GERMAN_MONTHS[dt.monthNumber - 1]} ${dt.year}"

/** `HH:mm`, zero-padded -- used by [PublicOverviewHtml.eventsPage] alongside [germanDate]. */
internal fun germanTime(dt: LocalDateTime): String = "%02d:%02d".format(dt.hour, dt.minute)

/** German weekday names, Monday first (index = `isoDayNumber - 1`). */
internal val GERMAN_WEEKDAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

/** German weekday name of [dt]'s calendar date, no timezone conversion -- see this file's class KDoc. */
internal fun germanWeekday(dt: LocalDateTime): String = GERMAN_WEEKDAYS[dt.dayOfWeek.isoDayNumber - 1]

/**
 * V1.9.128 -- human-readable event time span for the public event page and the ticket page.
 * - same day: "Dienstag, 20. Oktober 2026, 20:15 bis 22:15 Uhr"
 * - different days: "Freitag, 23. Oktober 2026, 18:00 Uhr bis Sonntag, 25. Oktober 2026, 14:00 Uhr"
 * - defensive `end <= start`: only the start, "Dienstag, 20. Oktober 2026, 20:15 Uhr"
 */
internal fun formatEventRange(
    start: LocalDateTime,
    end: LocalDateTime,
): String {
    val startDay = "${germanWeekday(start)}, ${germanDate(start)}"
    return when {
        end <= start -> "$startDay, ${germanTime(start)} Uhr"
        start.date == end.date -> "$startDay, ${germanTime(start)} bis ${germanTime(end)} Uhr"
        else -> "$startDay, ${germanTime(start)} Uhr bis ${germanWeekday(end)}, ${germanDate(end)}, ${germanTime(end)} Uhr"
    }
}
