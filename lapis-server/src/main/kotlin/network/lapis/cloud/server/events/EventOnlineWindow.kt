package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.EventOnlinePhase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Welle V1.9.113 -- the ONLY place the online-participation window of an event is computed.
 *
 * The window opens [LEAD] before `starts_at` and closes [TRAIL] after `ends_at`. `starts_at`/`ends_at` are class-B wall-clock values of
 * the organization zone, so they are turned into instants through `orgZone` (never `TimeZone.currentSystemDefault()`), and every comparison
 * runs on instants. The stored `event_conference.window_*` columns are class A (UTC) copies of exactly these instants.
 *
 * DST: kotlinx-datetime resolves a wall-clock in a spring-forward gap forward and an ambiguous fall-back wall-clock to the earlier offset;
 * the unit test pins both so a library update cannot change the window silently.
 */
internal object EventOnlineWindow {
    /** How long before the event starts people may already come in. */
    val LEAD: Duration = 15.minutes

    /** How long after the event ends the room stays open. */
    val TRAIL: Duration = 60.minutes

    data class Window(
        val opensAt: Instant,
        val closesAt: Instant,
    )

    fun of(
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        orgZone: TimeZone,
    ): Window =
        Window(
            opensAt = startsAt.toInstant(orgZone) - LEAD,
            closesAt = endsAt.toInstant(orgZone) + TRAIL,
        )

    /** Both ends are inclusive: `opensAt <= now <= closesAt` is [EventOnlinePhase.OPEN]. */
    fun phase(
        window: Window,
        now: Instant,
    ): EventOnlinePhase =
        when {
            now < window.opensAt -> EventOnlinePhase.NOT_YET
            now > window.closesAt -> EventOnlinePhase.ENDED
            else -> EventOnlinePhase.OPEN
        }

    /** Organization-zone wall clock at which the window opens (class B, for the DTO). */
    fun wallOpensAt(
        startsAt: LocalDateTime,
        orgZone: TimeZone,
    ): LocalDateTime = of(startsAt = startsAt, endsAt = startsAt, orgZone = orgZone).opensAt.toLocalDateTime(orgZone)

    /** Organization-zone wall clock at which the window closes (class B, for the DTO). */
    fun wallClosesAt(
        endsAt: LocalDateTime,
        orgZone: TimeZone,
    ): LocalDateTime = of(startsAt = endsAt, endsAt = endsAt, orgZone = orgZone).closesAt.toLocalDateTime(orgZone)

    /** The class-A (UTC) column value of an instant. */
    fun toUtc(instant: Instant): LocalDateTime = instant.toLocalDateTime(TimeZone.UTC)

    /** The instant of a class-A (UTC) column value. */
    fun fromUtc(utc: LocalDateTime): Instant = utc.toInstant(TimeZone.UTC)
}
