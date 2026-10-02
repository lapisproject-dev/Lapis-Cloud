package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.ServerClock

/**
 * Welle V1.4.36 -- extracted from `EmbedEventsFeedRoutes.kt` so [EmbedArticlesFeedRoutes] can reuse the
 * exact same UTC rendering. Rendered as a plain ISO-8601 UTC string (`yyyy-MM-ddTHH:mm:ssZ`) -- a JSON
 * wire field for a partner website's own `Date` parsing, not an RFC-5545 content line.
 *
 * V1.9.38: the zone is now explicit. [zone] is the zone the wall-clock [dt] was typed in
 * (the organization zone for event times, class B). NEVER a hardcoded `Z` appended to the raw wall-clock
 * value, and no longer the process default zone (which is fixed to UTC and would silently skip the
 * conversion). For a class-A system timestamp (e.g. an article's `published_at`, stored as UTC) use
 * [embedFeedUtcFromSystem].
 */
internal fun embedFeedUtc(
    dt: LocalDateTime,
    zone: TimeZone,
): String = embedFeedUtcFromSystem(ServerClock.wallToInstant(wall = dt, orgZone = zone).toLocalDateTime(TimeZone.UTC))

/** [utc] is already a UTC wall-clock (class A): formatted only, never converted. */
internal fun embedFeedUtcFromSystem(utc: LocalDateTime): String =
    "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
        utc.year,
        utc.monthNumber,
        utc.dayOfMonth,
        utc.hour,
        utc.minute,
        utc.second,
    )
