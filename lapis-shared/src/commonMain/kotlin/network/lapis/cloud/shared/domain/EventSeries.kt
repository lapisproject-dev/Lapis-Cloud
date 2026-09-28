package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen / Veranstaltungsreihen" -- see
 * `docs/architecture/event-series.adoc` for the full RFC 5545 subset this codebase supports.
 *
 * This file holds ONLY the recurrence-rule input shape shared between server and client
 * (`RecurrenceRuleBuilder`/`RecurrenceExpander` in `:lapis-server` consume it, the client's
 * `EventRecurrenceEditor` produces it) plus, since the V1.4.37 "Folgewelle (Rest)" scope-engine
 * step, [EventSeriesEditScope] -- the "this / this-and-following / all" choice both
 * `EventSeriesScopeEngine` (`:lapis-server`) and the eventual client scope-picker dialog need to
 * share. It deliberately does NOT (yet) declare `EventSeriesInput`, `EventSeriesDto`,
 * `EventScopeImpactDto` etc. from the full design -- those depend on `EventInput`/`EventStatus`
 * RPC wiring that lands in a later wave of this feature; keeping this file narrow keeps it
 * independently testable and mergeable before that follow-on work exists.
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

/**
 * Dritte und letzte Folgewelle "Wiederkehrende Veranstaltungen" -- RPC-Verdrahtung. Diese vier DTOs
 * sind die einzige Wahrheitsquelle, die der Client für Serien-Vorschau/-Anlage/-Bearbeitung
 * anzeigt; siehe `docs/architecture/event-series.adoc` Abschnitt "RPC-Verdrahtung".
 *
 * Ergebnis von `IEventService.previewSeries` -- die eine Wahrheitsquelle, die der Client anzeigt,
 * sobald sie da ist.
 */
@Serializable
data class SeriesPreviewDto(
    val valid: Boolean,
    val count: Int = 0,
    val first: LocalDateTime? = null,
    val last: LocalDateTime? = null,
    /** Fertig formatierter Live-Satz ("Wöchentlich am Dienstag, 19:00–21:00 · 26 Termine · ..."), serverseitig gebaut -- siehe `RecurrenceSentence`. */
    val sentence: String = "",
    /** `RecurrenceRuleBuilder.build`'s `Result.Invalid.messages`, leer wenn `valid`. */
    val errors: List<String> = emptyList(),
)

/** Ergebnis von `IEventService.createEventSeries`. */
@Serializable
data class EventSeriesCreateResultDto(
    val seriesId: String,
    val createdEventIds: List<String>,
    val firstEvent: EventDto,
)

/** Ergebnis von `IEventService.impactOfSeriesEdit` -- Grundlage der Zahlen unter den Radiobuttons + im Bestätigungsdialog. */
@Serializable
data class EventSeriesImpactDto(
    val affectedEventCount: Int,
    val affectedRegistrationCount: Int,
    /** `false` nur für die allererste Instanz der Serie -- steuert, ob THIS überhaupt als Option gezeigt wird. */
    val isFirstOccurrence: Boolean,
    /** `true` wenn die Regel (Wochentag/Frequenz) sich geändert hat -- steuert "THIS ausgeblendet" + Split-Hinweis. */
    val ruleChanged: Boolean,
)

/**
 * Ergebnis von `IEventService.updateSeriesEvent`/`cancelSeriesEvent`.
 *
 * Review MINOR fix: [affectedRegistrationCount] used to be named `notifiedRegistrationCount` for
 * BOTH RPCs, obwohl `updateSeriesEvent` (eine reine Terminänderung) niemandem eine Mail schickt --
 * nur `cancelSeriesEvent` versendet tatsächlich Absage-Benachrichtigungen (an genau diese aktiven
 * Registrierungen). Der alte Name hätte ein künftiges Admin-UI, das ihn direkt als "X Personen
 * benachrichtigt" anzeigt, für `updateSeriesEvent` fälschlich glauben lassen, Registrierte hätten
 * eine Mail über die geänderte Zeit/den geänderten Ort erhalten. Der neue, neutrale Name (bewusst
 * identisch zu [EventSeriesImpactDto.affectedRegistrationCount]) beschreibt in beiden Fällen korrekt
 * nur die Anzahl der betroffenen aktiven Registrierungen -- ob tatsächlich gemailt wurde, ergibt
 * sich allein daraus, welche RPC man aufgerufen hat, nicht aus diesem Feldnamen.
 */
@Serializable
data class EventSeriesEditResultDto(
    val affectedEventCount: Int,
    val affectedRegistrationCount: Int,
)

/**
 * Welle V1.4.37 "Wiederkehrende Veranstaltungen, Folgewelle (Rest)" -- the classic
 * calendar-app "edit recurring event" choice (Google/Outlook both offer exactly these three),
 * consumed by `EventSeriesScopeEngine` (`:lapis-server`) to decide which materialized `event` rows
 * an edit touches:
 * - [THIS]: only the single occurrence being edited. It is detached from the series
 *   (`event.series_detached = true`, `series_id`/`series_original_start` stay put so the ICS
 *   feed/UI can still show "part of a series, edited") -- future series-wide edits never touch it
 *   again.
 * - [FOLLOWING]: the edited occurrence and every later occurrence of the SAME series. Unless the
 *   edited occurrence is itself the very first one (in which case this degenerates to [ALL] -- there
 *   is nothing "before" it to keep on the old rule), this SPLITS the series in two:
 *   `event_series.split_from_series_id` on the new series points back at the original, whose own
 *   `rrule` is truncated to stop just before the edited occurrence.
 * - [ALL]: every occurrence of the series, past and future alike (except any individually
 *   [THIS]-detached instance, which by definition opted out of series-wide edits already).
 */
@Serializable
enum class EventSeriesEditScope { THIS, FOLLOWING, ALL }
