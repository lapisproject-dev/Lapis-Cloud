package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.ServerClock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition

/**
 * RFC 5545 time formatting for the iCal feed (V1.9.38). The two kinds of stored time need different
 * handling and must never be mixed (see `docs/architecture/time-and-timezones.adoc`):
 *  - class B wall-clock values (`event.starts_at`/`ends_at`, typed in by a person in the organization's
 *    zone): converted to UTC with [icsUtcFromWall] using the ZONE THE WALL-CLOCK WAS ENTERED IN.
 *  - class A system timestamps (`DTSTAMP`, stored as UTC): rendered as-is with [icsUtcFromSystem].
 *
 * NEVER `dt.toString() + "Z"` on a wall-clock value.
 */
internal fun icsUtcFromWall(
    wall: LocalDateTime,
    zone: TimeZone,
): String = icsUtcFromSystem(ServerClock.wallToInstant(wall = wall, orgZone = zone).toLocalDateTime(TimeZone.UTC))

/** [utc] is already a UTC wall-clock (a class-A system timestamp): format only, no conversion. */
internal fun icsUtcFromSystem(utc: LocalDateTime): String = icsLocal(utc) + "Z"

/** Compact floating form `yyyyMMddTHHmmss` (no `Z`), used with a `;TZID=` parameter. */
internal fun icsLocal(dt: LocalDateTime): String =
    "%04d%02d%02dT%02d%02d%02d".format(dt.year, dt.monthNumber, dt.dayOfMonth, dt.hour, dt.minute, dt.second)

/**
 * A minimal `VTIMEZONE` for [zone], derived from the JVM's own rules, so a `TZID=` reference in the
 * feed is self-contained (RFC 5545 §3.6.5; some clients ignore an unknown TZID without it). A zone with
 * yearly transition rules yields a STANDARD and a DAYLIGHT sub-component with an `RRULE`; a fixed-offset
 * zone yields one STANDARD component.
 */
internal object IcsVTimezone {
    fun render(zoneId: String): String {
        val rules = ZoneId.of(zoneId).rules
        val nowInstant = java.time.Instant.ofEpochMilli(ServerClock.nowInstant().toEpochMilliseconds())
        val sb = StringBuilder()
        sb.append("BEGIN:VTIMEZONE\r\n")
        sb.append("TZID:").append(zoneId).append("\r\n")
        val transitionRules = rules.transitionRules
        if (transitionRules.size == 2 && transitionRules.all { isRepresentable(it) }) {
            transitionRules.forEach { rule ->
                appendRule(sb = sb, rule = rule, standardOffset = rules.getStandardOffset(nowInstant))
            }
        } else {
            // Fixed-offset zones, and zones whose rules RRULE cannot express (see isRepresentable): a single
            // STANDARD block at the offset currently in force. Known limitation for the latter.
            val offset = rules.getOffset(nowInstant)
            sb.append("BEGIN:STANDARD\r\n")
            sb.append("DTSTART:19700101T000000\r\n")
            sb.append("TZOFFSETFROM:").append(offsetText(offset)).append("\r\n")
            sb.append("TZOFFSETTO:").append(offsetText(offset)).append("\r\n")
            sb.append("END:STANDARD\r\n")
        }
        sb.append("END:VTIMEZONE\r\n")
        return sb.toString()
    }

    /**
     * Only "last DOW of the month" (indicator -1) and "DOW on or after day N" with N in 1..25 map onto a valid RRULE
     * (BYMONTHDAY N..N+6 stays within 31 days for N <= 25); a transition at 24:00 shifts the day and is not expressed.
     */
    internal fun isRepresentable(rule: ZoneOffsetTransitionRule): Boolean =
        !rule.isMidnightEndOfDay && (rule.dayOfMonthIndicator == -1 || rule.dayOfMonthIndicator in 1..25)

    private fun appendRule(
        sb: StringBuilder,
        rule: ZoneOffsetTransitionRule,
        standardOffset: ZoneOffset,
    ) {
        val toDaylight = rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds
        val name = if (toDaylight) "DAYLIGHT" else "STANDARD"
        // Local wall-clock time of the transition, as seen on the clocks BEFORE it (RFC 5545 DTSTART of the component).
        val wall: LocalTime =
            when (rule.timeDefinition ?: TimeDefinition.WALL) {
                TimeDefinition.WALL -> rule.localTime
                TimeDefinition.UTC -> rule.localTime.plusSeconds(rule.offsetBefore.totalSeconds.toLong())
                TimeDefinition.STANDARD ->
                    rule.localTime.plusSeconds((rule.offsetBefore.totalSeconds - standardOffset.totalSeconds).toLong())
            }
        val dow = dayCode(rule.dayOfWeek)
        val byDay =
            if (rule.dayOfMonthIndicator < 0) {
                "BYDAY=-1$dow"
            } else {
                "BYDAY=$dow;BYMONTHDAY=${(rule.dayOfMonthIndicator..rule.dayOfMonthIndicator + 6).joinToString(",")}"
            }
        sb.append("BEGIN:").append(name).append("\r\n")
        // DTSTART SHOULD coincide with the first occurrence of the RRULE (RFC 5545 §3.8.2.4): first matching day in 1970.
        val firstDay =
            if (rule.dayOfMonthIndicator < 0) {
                LocalDate.of(1970, rule.month, 1).with(TemporalAdjusters.lastInMonth(rule.dayOfWeek))
            } else {
                LocalDate.of(1970, rule.month, rule.dayOfMonthIndicator).with(TemporalAdjusters.nextOrSame(rule.dayOfWeek))
            }
        sb.append(
            "DTSTART:1970%02d%02dT%02d%02d%02d\r\n".format(rule.month.value, firstDay.dayOfMonth, wall.hour, wall.minute, wall.second),
        )
        sb
            .append("RRULE:FREQ=YEARLY;BYMONTH=")
            .append(rule.month.value)
            .append(';')
            .append(byDay)
            .append("\r\n")
        sb.append("TZOFFSETFROM:").append(offsetText(rule.offsetBefore)).append("\r\n")
        sb.append("TZOFFSETTO:").append(offsetText(rule.offsetAfter)).append("\r\n")
        sb.append("END:").append(name).append("\r\n")
    }

    private fun dayCode(dow: DayOfWeek): String =
        when (dow) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }

    private fun offsetText(offset: ZoneOffset): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) '-' else '+'
        val abs = kotlin.math.abs(total)
        return "%c%02d%02d".format(sign, abs / 3600, (abs % 3600) / 60)
    }
}
