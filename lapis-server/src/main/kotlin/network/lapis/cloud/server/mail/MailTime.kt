package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

/**
 * Class-A (UTC system timestamp) arithmetic for the mail budget / outbox. Plain [Duration] on the instant -- these values are
 * "N seconds/minutes/hours later" deadlines, never calendar-aware dates (see `docs/architecture/time-and-timezones.adoc`).
 */
internal fun LocalDateTime.plusDuration(duration: Duration): LocalDateTime =
    toInstant(TimeZone.UTC).plus(duration).toLocalDateTime(TimeZone.UTC)

internal fun LocalDateTime.minusDuration(duration: Duration): LocalDateTime = plusDuration(-duration)

/** Whole seconds from [this] to [later] (negative if [later] is earlier). */
internal fun LocalDateTime.secondsUntil(later: LocalDateTime): Long =
    (later.toInstant(TimeZone.UTC) - toInstant(TimeZone.UTC)).inWholeSeconds
