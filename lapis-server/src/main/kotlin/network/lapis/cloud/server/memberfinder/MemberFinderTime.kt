package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Arithmetic on class-A (UTC wall-clock) timestamps; the zone is UTC, so there is no daylight-saving ambiguity. */
internal fun LocalDateTime.plusDays(days: Int): LocalDateTime = (toInstant(TimeZone.UTC) + days.days).toLocalDateTime(TimeZone.UTC)

internal fun LocalDateTime.minusDays(days: Int): LocalDateTime = plusDays(-days)

internal fun LocalDateTime.plusHours(hours: Int): LocalDateTime = (toInstant(TimeZone.UTC) + hours.hours).toLocalDateTime(TimeZone.UTC)
