package network.lapis.cloud.server.events.series

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen" -- every DoS-relevant bound this feature enforces,
 * collected in one object so [RecurrenceRuleBuilder]/[RecurrenceExpander] (and, in a later wave,
 * `EventSeriesScopeEngine`/`IEventSeriesService`) all read the same numbers rather than each
 * hardcoding its own copy. See `docs/architecture/event-series.adoc` "Grenzen" for the rationale
 * behind each value.
 */
internal object EventSeriesLimits {
    /** RFC 5545 places no upper bound on `COUNT`; this codebase caps at two years of weekly
     * occurrences (52 * 2) so a single series can never materialize an unbounded number of `event`
     * rows in one transaction. */
    const val MAX_INSTANCES_PER_SERIES = 104

    /** How far into the future [RecurrenceExpander] is willing to search for occurrences,
     * independent of [MAX_INSTANCES_PER_SERIES] -- a `MONTHLY` rule with a huge `COUNT` but no
     * matching `BYMONTHDAY` in most months (e.g. `BYMONTHDAY=31`) must still terminate quickly. */
    const val MAX_HORIZON_MONTHS = 24

    /** RFC 5545 `INTERVAL` is unbounded; this codebase's admin UI only offers 1..12 (see
     * `EventRecurrenceEditor` in a later wave) -- anything beyond a year-long interval belongs in a
     * one-off `event`, not a series. */
    const val MAX_INTERVAL = 12

    /** Caps how many series with a still-future occurrence a single organization instance may have
     * open at once (see `EventSeriesStore.countActiveSeries` in a later wave) -- guards against an
     * admin (or a compromised admin session) mass-creating series to exhaust capacity elsewhere. */
    const val MAX_ACTIVE_SERIES_PER_ORG = 50

    /** Rate limit (per admin) for series create/extend/scope-update RPCs. */
    const val WRITE_RATE_PER_MINUTE = 10

    /** Rate limit for the debounced live preview RPCs (`previewSeries`/`previewScope`) -- higher
     * than [WRITE_RATE_PER_MINUTE] because the admin UI calls these on every keystroke/selection
     * change, debounced client-side to 400ms but not trusted to enforce that server-side. */
    const val PREVIEW_RATE_PER_MINUTE = 60

    /** The only MONTHLY "nth weekday" ordinals this codebase's [RecurrenceRuleBuilder] accepts --
     * see [MonthlyWeekdayRule][network.lapis.cloud.shared.domain.MonthlyWeekdayRule] KDoc for why
     * a fifth occurrence is excluded. */
    val ALLOWED_MONTHLY_ORDINALS: Set<Int> = setOf(1, 2, 3, 4, -1)

    /** Whitelist of `event_series.timezone` values this wave supports -- mirrored by the DB
     * `chk_event_series_timezone` CHECK constraint added in a later wave's migration. A single
     * fixed zone (rather than per-series free-form IANA IDs) is a deliberate scope cut: see F2 in
     * the implementation plan for the open question this whitelist is a placeholder for. */
    val SUPPORTED_TIMEZONES: Set<String> = setOf("Europe/Berlin")

    /** Mirrors `event_series.rrule VARCHAR(255)` (added in a later wave's migration) --
     * [RecurrenceRuleBuilder.build] refuses to produce a longer string. */
    const val RRULE_MAX_LENGTH = 255
}
