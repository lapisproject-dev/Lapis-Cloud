package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen / Veranstaltungsreihen" -- see
 * `docs/architecture/event-series.adoc` for the full RFC 5545 subset this codebase supports.
 *
 * This file holds ONLY the recurrence-rule input shape shared between server and client
 * (`RecurrenceRuleBuilder`/`RecurrenceExpander` in `:lapis-server` consume it, the client's
 * `EventRecurrenceEditor` produces it). It deliberately does NOT (yet) declare `EventSeriesInput`,
 * `EventSeriesDto`, `EventScopeImpactDto` etc. from the full design -- those depend on
 * `EventInput`/`EventStatus` wiring and the scope-engine data model that land in later waves of
 * this feature; keeping this file narrow keeps it independently testable and mergeable before that
 * follow-on work exists.
 *
 * Literal order of [RecurrenceFrequency] and [RecurrenceWeekday] is load-bearing wherever this
 * codebase's other `@Serializable enum class`es document the same rule (see `Events.kt` file
 * header) -- both are persisted indirectly via the normalized RRULE string
 * (`RecurrenceRuleBuilder.build`), never via ordinal, so reordering is safe for persistence but
 * would still needlessly break wire compatibility between already-deployed client/server pairs.
 */
@Serializable
enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** RFC 5545 two-letter weekday codes, in RFC week order (Monday first). */
@Serializable
enum class RecurrenceWeekday { MO, TU, WE, TH, FR, SA, SU }

/**
 * The "nth weekday of the month" MONTHLY variant, e.g. `ordinal=1, weekday=TU` for "the first
 * Tuesday", or `ordinal=-1, weekday=FR` for "the last Friday". [ordinal] is restricted to
 * `{1, 2, 3, 4, -1}` by `RecurrenceRuleBuilder` -- RFC 5545 permits `-4..-1, 1..4` for BYDAY with a
 * BYSETPOS-free MONTHLY rule, but this codebase's supported subset stops at the fourth occurrence
 * (a fifth Tuesday does not exist in every month, which would silently skip months in a confusing
 * way) plus the always-valid "last" (`-1`).
 */
@Serializable
data class MonthlyWeekdayRule(
    val ordinal: Int,
    val weekday: RecurrenceWeekday,
)

/**
 * The admin-facing recurrence rule shape -- never round-tripped verbatim to/from the DB's
 * `event_series.rrule VARCHAR(255)` column (that column holds `RecurrenceRuleBuilder.build`'s
 * normalized RRULE string). Exactly one of [count]/[until] must be set; which BY-fields are
 * meaningful depends on [frequency] -- see `RecurrenceRuleBuilder` KDoc for the full validation
 * table. [interval] defaults to `1` (RFC 5545 default, "every N periods").
 */
@Serializable
data class RecurrenceRuleInput(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val byWeekdays: Set<RecurrenceWeekday> = emptySet(),
    val byMonthDay: Int? = null,
    val byMonthlyWeekday: MonthlyWeekdayRule? = null,
    val count: Int? = null,
    val until: LocalDate? = null,
)
