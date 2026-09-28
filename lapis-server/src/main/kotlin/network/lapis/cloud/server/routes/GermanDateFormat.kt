package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime

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
