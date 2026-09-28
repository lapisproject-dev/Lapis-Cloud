package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Welle V1.4.36 -- extracted, WITHOUT any behavior change, from `EmbedEventsFeedRoutes.kt` (which
 * used to be the only caller) so [EmbedArticlesFeedRoutes] can reuse the exact same UTC-rendering
 * logic. Identical conversion to `EventIcsFeed.icsUtc` (same Stolperfalle this whole codebase's
 * KDoc repeatedly flags: NEVER a hardcoded `Z`-suffix appended to the raw wall-clock value, always
 * an `Instant` round-trip through the server's own default zone) -- rendered as a plain ISO-8601
 * UTC string (`yyyy-MM-ddTHH:mm:ssZ`) rather than iCal's compact form, since this is a JSON wire
 * field for a partner website's own `Date` parsing, not an RFC-5545 content line.
 */
internal fun embedFeedUtc(dt: LocalDateTime): String {
    val instant = dt.toInstant(TimeZone.currentSystemDefault())
    val utc = instant.toLocalDateTime(TimeZone.UTC)
    return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
        utc.year,
        utc.monthNumber,
        utc.dayOfMonth,
        utc.hour,
        utc.minute,
        utc.second,
    )
}
