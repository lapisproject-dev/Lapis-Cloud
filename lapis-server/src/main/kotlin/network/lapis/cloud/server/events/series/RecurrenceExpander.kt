package network.lapis.cloud.server.events.series

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import net.fortuna.ical4j.model.Recur
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Thrown by [RecurrenceExpander.expand] when a (structurally valid, whitelist-passing) rule would
 * still materialize more than [EventSeriesLimits.MAX_INSTANCES_PER_SERIES] occurrences within
 * [EventSeriesLimits.MAX_HORIZON_MONTHS] -- this is the DoS backstop for [RecurrenceRuleBuilder]'s
 * own `COUNT`/`UNTIL` bounds, which are enforced against a typed [network.lapis.cloud.shared.domain.RecurrenceRuleInput]
 * at rule-authoring time but not against a raw RRULE string re-read from the DB (a `COUNT=104`
 * rule can still, in principle, be paired with a manipulated `dtstart`/`interval` combination that
 * this exception guards against as the very last line of defense before `EventSeriesMaterializer`
 * would otherwise attempt to insert an unbounded number of `event` rows in one transaction).
 */
internal class TooManyOccurrencesException(
    occurrenceCount: Int,
) : RuntimeException(
        "Wiederholungsregel ergibt $occurrenceCount Termine, mehr als das Limit von " +
            "${EventSeriesLimits.MAX_INSTANCES_PER_SERIES}.",
    )

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen" -- turns a normalized RRULE string (as produced
 * by [RecurrenceRuleBuilder.build]) plus a `DTSTART` wall-clock time into the concrete list of
 * occurrence wall-clock times [EventSeriesMaterializer] later turns into `event` rows.
 *
 * [expand] has two callers with two different purposes: [RecurrenceRuleBuilder.build] itself calls
 * it once, synchronously, right after constructing a candidate RRULE string, purely to validate that
 * string against its own `dtstart`/`COUNT` (see that function's KDoc) -- the actual persisted
 * materialization ([EventSeriesMaterializer], a later wave) calls it again afterwards to turn the
 * now-persisted RRULE into `event` rows. Both calls are expected to agree, because both expand the
 * exact same RRULE string against the exact same `dtstart`/`zone`.
 *
 * **Only** `net.fortuna.ical4j.model.Recur` is used from ical4j -- no `TimeZoneRegistry` (which
 * would otherwise attempt an outbound network call to refresh ical4j's bundled tzdata; disabled
 * defense-in-depth via the `net.fortuna.ical4j.timezone.update.enabled=false` system property set
 * in `Application.kt` regardless), no `CalendarBuilder`/`CalendarOutputter` in production code
 * (those appear only in `EventIcsFeedSeriesTest`'s round-trip assertion, in a later wave). The
 * expansion itself is carried out entirely in `java.time.ZonedDateTime` against [zone] -- this is
 * what keeps a `19:00` weekly rule at wall-clock `19:00` across a DST transition (the alternative,
 * expanding in UTC `Instant` arithmetic, would silently shift the wall-clock time by an hour the
 * moment the rule crosses a DST boundary).
 */
internal object RecurrenceExpander {
    /**
     * Expands [rrule] starting at [dtstart] (wall-clock time in [zone]) into the list of wall-
     * clock occurrence times, in [zone], minus anything in [exdates] (compared as wall-clock
     * values, matching how `event.recurrence_id`/`event_series_exdate.recurrence_id` are stored --
     * both are `TIMESTAMP` columns without a zone, i.e. always wall-clock in `event_series.timezone`).
     *
     * Always bounds the search to [EventSeriesLimits.MAX_HORIZON_MONTHS] after [dtstart] and to
     * [EventSeriesLimits.MAX_INSTANCES_PER_SERIES] + 1 candidates (`ical4j`'s own
     * `getDates(seed, periodStart, periodEnd, maxCount)` overload) so a pathological rule (e.g. one
     * whose `BYMONTHDAY` rarely matches) cannot make this loop for an unbounded number of internal
     * increments -- the `net.fortuna.ical4j.recur.maxincrementcount` system property (also set in
     * `Application.kt`) is a second, independent backstop for the same failure mode. Throws
     * [TooManyOccurrencesException] if more than [EventSeriesLimits.MAX_INSTANCES_PER_SERIES] raw
     * occurrences come back (checked BEFORE [exdates] are subtracted -- an EXDATE represents an
     * already-materialized-then-deleted occurrence of the SAME rule, not a smaller rule, so it must
     * not be able to "buy back" headroom against the limit).
     */
    fun expand(
        rrule: String,
        dtstart: LocalDateTime,
        zone: ZoneId,
        exdates: Set<LocalDateTime> = emptySet(),
    ): List<LocalDateTime> {
        // Defense in depth: re-validate against our own restricted grammar before ical4j ever sees
        // the string -- see RecurrenceRuleBuilder.parseWhitelisted KDoc.
        RecurrenceRuleBuilder.parseWhitelisted(rrule)

        val seed = ZonedDateTime.of(dtstart.toJavaLocalDateTime(), zone)
        val periodEnd = seed.plusMonths(EventSeriesLimits.MAX_HORIZON_MONTHS.toLong())
        val recur = Recur<ZonedDateTime>(rrule)
        val occurrences: List<ZonedDateTime> =
            recur.getDates(seed, seed, periodEnd, EventSeriesLimits.MAX_INSTANCES_PER_SERIES + 1)

        if (occurrences.size > EventSeriesLimits.MAX_INSTANCES_PER_SERIES) {
            throw TooManyOccurrencesException(occurrences.size)
        }

        val local = occurrences.map { it.toLocalDateTime().toKotlinLocalDateTime() }
        return if (exdates.isEmpty()) local else local.filterNot { it in exdates }
    }
}
