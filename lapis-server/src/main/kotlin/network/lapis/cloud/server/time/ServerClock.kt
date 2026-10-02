package network.lapis.cloud.server.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.truncatedToDbPrecision
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The single place the server reads "now" and converts between wall-clock values and instants
 * (V1.9.38, see `docs/architecture/time-and-timezones.adoc`).
 *
 * Two kinds of time live in the database, and they must never be compared with the same clock:
 *  - class A, system timestamps: stamped by the server clock and stored as zone-less UTC
 *    [LocalDateTime]s. Compare with [now]; convert for display with the organization zone.
 *  - class B, wall-clock times: typed in by a person in the organization's local time and stored
 *    as-is. Compare with [nowIn] using the organization zone; never convert for display.
 *  - class D, calendar dates: compare with [todayIn].
 *
 * The storage zone [zone] is fixed to UTC and is never derived from the environment, so a changed
 * container `TZ` or a developer machine in Berlin cannot silently re-interpret stored values.
 */
object ServerClock {
    /** Storage zone of every class-A system timestamp. Fixed, never derived from the environment. */
    val zone: TimeZone = TimeZone.UTC

    /** Test seam; production always reads the system clock. */
    @Volatile
    internal var source: Clock = Clock.System

    fun nowInstant(): Instant = source.now()

    /** Class A "now": UTC wall-clock, truncated to the precision the database can store. */
    fun now(): LocalDateTime = nowInstant().toLocalDateTime(zone).truncatedToDbPrecision()

    /** Class B "now": the wall-clock in [zone] (the organization zone), truncated like [now]. */
    fun nowIn(zone: TimeZone): LocalDateTime = nowInstant().toLocalDateTime(zone).truncatedToDbPrecision()

    /** Class D "today" in [zone]. */
    fun todayIn(zone: TimeZone): LocalDate = nowInstant().toLocalDateTime(zone).date

    /**
     * A class-A "now" (UTC system timestamp) re-expressed as the class-B wall-clock of [orgZone]. Used by code
     * that is handed an injected A-clock and must compare it with a B field, so that one instant stays one instant.
     */
    fun systemToWall(
        utc: LocalDateTime,
        orgZone: TimeZone,
    ): LocalDateTime = utc.toInstant(zone).toLocalDateTime(orgZone)

    /** Class A value (UTC wall-clock) to the instant it denotes. */
    fun systemToInstant(utc: LocalDateTime): Instant = utc.toInstant(zone)

    /**
     * Class B value (wall-clock in [orgZone]) to the instant it denotes.
     *
     * DST: a wall-clock inside a spring-forward gap resolves forward (02:30 becomes 03:30 local), and an
     * ambiguous wall-clock in a fall-back overlap resolves to the EARLIER offset. That is the
     * kotlinx-datetime behaviour; `ServerClockTest` pins it so a library update cannot change it silently.
     */
    fun wallToInstant(
        wall: LocalDateTime,
        orgZone: TimeZone,
    ): Instant = wall.toInstant(orgZone)
}
