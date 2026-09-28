package network.lapis.cloud.server.events.series

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDate
import network.lapis.cloud.shared.domain.MonthlyWeekdayRule
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.BadRequestException
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen" -- builds and validates the normalized RRULE
 * string this codebase persists in `event_series.rrule` (added in a later wave's migration), and
 * provides the defense-in-depth [parseWhitelisted] read path back from that column.
 *
 * This object is pure fachlogik in the sense that it owns no DB access and no independent notion of
 * "what does this rule actually materialize to" -- [build] cross-checks the RRULE string it is
 * about to return against a real [RecurrenceExpander.expand] of that same string (see [build]'s own
 * KDoc) rather than re-deriving an estimate, so a rule [build] accepts is guaranteed to actually
 * start on `dtstart` and to fit its requested `COUNT` inside the horizon. `RecurrenceRuleBuilderTest`
 * therefore does exercise real `ical4j` `Recur` expansion (indirectly, through [RecurrenceExpander]),
 * same posture `EventPolicy`/`CrmContactPolicy` already establish for their own policy objects.
 *
 * **Supported RFC 5545 subset** (see `docs/architecture/event-series.adoc` for the full
 * rationale): only `DAILY`/`WEEKLY`/`MONTHLY`/`YEARLY` (never `SECONDLY`/`MINUTELY`/`HOURLY`),
 * `INTERVAL` 1..[EventSeriesLimits.MAX_INTERVAL], `BYDAY` (WEEKLY: 1..7 days; MONTHLY: exactly one
 * "nth weekday" token) xor `BYMONTHDAY` (MONTHLY only, a single day-of-month), and exactly one of
 * `COUNT` (1..[EventSeriesLimits.MAX_INSTANCES_PER_SERIES]) or `UNTIL` (at most
 * [EventSeriesLimits.MAX_HORIZON_MONTHS] months after `DTSTART`). Never `BYSETPOS`/`BYHOUR`/
 * `BYWEEKNO`/`BYYEARDAY`/etc. -- anything outside this subset is rejected by both [build] (typed
 * input, so most of this is unreachable from a well-typed caller) and [parseWhitelisted] (untyped
 * string, the actual line of defense against a tampered `event_series.rrule` value).
 */
internal object RecurrenceRuleBuilder {
    sealed interface Result {
        data class Ok(
            val rrule: String,
        ) : Result

        data class Invalid(
            val messages: List<String>,
        ) : Result
    }

    private val WEEKDAYS_IN_RFC_ORDER = RecurrenceWeekday.entries // MO..SU, matches declaration order

    /**
     * Validates [rule] against [dtstart] and builds the normalized RRULE string in fixed field
     * order `FREQ;INTERVAL;(BYDAY|BYMONTHDAY);(COUNT|UNTIL)` -- omitting the BY-clause entirely
     * for `DAILY`/`YEARLY`. Returns every violation found (not just the first) so a caller wiring
     * this into a live preview (`previewSeries`) can show them all at once. [zone] is consulted
     * both when [RecurrenceRuleInput.until] is set (see [untilUtc]) and, unconditionally, to detect
     * a [dtstart] that falls into a DST gap (e.g. `02:30` on the day Central Europe springs forward)
     * -- such a wall-clock time does not exist in [zone], and silently rolling it forward (as
     * `java.time.ZonedDateTime.of` does) would move every later occurrence's wall-clock time along
     * with it.
     *
     * Once every structural check above passes, [build] does not stop at producing the RRULE
     * string: it actually expands that string once via [RecurrenceExpander.expand] against
     * [dtstart]/[zone] and cross-checks the real result against two RFC 5545 §3.8.5.3 requirements
     * a purely structural check cannot see:
     * - **`DTSTART` must itself be an occurrence of the rule** (`expand(...).first() == dtstart`).
     *   Otherwise the recurrence set is undefined, and consumers like Google/Outlook silently count
     *   `DTSTART` as the first instance within `COUNT` -- e.g. a `WEEKLY;BYDAY=TU` rule whose
     *   `dtstart` falls on a Monday.
     * - **A `COUNT`-bounded rule must actually reach `COUNT` occurrences within
     *   [EventSeriesLimits.MAX_HORIZON_MONTHS]** (`occurrences.size == count`). Otherwise the stored
     *   RRULE and what [RecurrenceExpander.expand] (and, later, the ICS export) actually materialize
     *   silently diverge -- e.g. `YEARLY;COUNT=5` only ever produces 3 dates inside a 24-month
     *   horizon. A large `INTERVAL` combined with a large `COUNT` has the same failure mode.
     */
    fun build(
        rule: RecurrenceRuleInput,
        dtstart: LocalDateTime,
        zone: ZoneId,
    ): Result {
        val messages = mutableListOf<String>()

        if (zone.rules.getValidOffsets(dtstart.toJavaLocalDateTime()).isEmpty()) {
            messages += "Der Startzeitpunkt fällt in eine Zeitumstellungs-Lücke (DST) und existiert in der Zeitzone nicht."
        }

        if (rule.interval < 1 || rule.interval > EventSeriesLimits.MAX_INTERVAL) {
            messages += "Intervall muss zwischen 1 und ${EventSeriesLimits.MAX_INTERVAL} liegen."
        }

        val byDayToken: String?
        when (rule.frequency) {
            RecurrenceFrequency.WEEKLY -> {
                if (rule.byWeekdays.isEmpty()) {
                    messages += "Wöchentliche Wiederholung braucht mindestens einen Wochentag."
                }
                if (rule.byMonthDay != null || rule.byMonthlyWeekday != null) {
                    messages += "Wöchentliche Wiederholung darf keinen Monatstag angeben."
                }
                byDayToken =
                    rule.byWeekdays
                        .takeIf { it.isNotEmpty() }
                        ?.let { days -> WEEKDAYS_IN_RFC_ORDER.filter { it in days }.joinToString(",") }
            }
            RecurrenceFrequency.MONTHLY -> {
                if (rule.byWeekdays.isNotEmpty()) {
                    messages += "Monatliche Wiederholung nutzt keine Wochentags-Liste."
                }
                val monthDay: Int? = rule.byMonthDay
                val monthlyWeekday: MonthlyWeekdayRule? = rule.byMonthlyWeekday
                val hasMonthDay = monthDay != null
                val hasMonthlyWeekday = monthlyWeekday != null
                if (hasMonthDay == hasMonthlyWeekday) {
                    messages += "Monatliche Wiederholung braucht genau eine Variante (Tag im Monat ODER n-ter Wochentag)."
                }
                if (monthDay != null && (monthDay < 1 || monthDay > 31)) {
                    messages += "Tag im Monat muss zwischen 1 und 31 liegen."
                }
                if (monthlyWeekday != null && monthlyWeekday.ordinal !in EventSeriesLimits.ALLOWED_MONTHLY_ORDINALS) {
                    messages += "Ordinalzahl für den n-ten Wochentag muss 1, 2, 3, 4 oder -1 (letzter) sein."
                }
                byDayToken =
                    when {
                        monthDay != null -> null
                        monthlyWeekday != null -> "${monthlyWeekday.ordinal}${monthlyWeekday.weekday.name}"
                        else -> null
                    }
            }
            RecurrenceFrequency.DAILY, RecurrenceFrequency.YEARLY -> {
                if (rule.byWeekdays.isNotEmpty() || rule.byMonthDay != null || rule.byMonthlyWeekday != null) {
                    messages += "${rule.frequency.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                        "Wiederholung darf keine BY-Einschränkung angeben."
                }
                byDayToken = null
            }
        }

        val count: Int? = rule.count
        val until: LocalDate? = rule.until
        val hasCount = count != null
        val hasUntil = until != null
        if (hasCount == hasUntil) {
            messages += "Es muss genau eine Begrenzung angegeben werden: entweder Anzahl der Termine oder Enddatum."
        }
        if (count != null && (count < 1 || count > EventSeriesLimits.MAX_INSTANCES_PER_SERIES)) {
            messages += "Anzahl der Termine muss zwischen 1 und ${EventSeriesLimits.MAX_INSTANCES_PER_SERIES} liegen."
        }
        val horizonLimit =
            dtstart.date
                .toJavaLocalDate()
                .plusMonths(EventSeriesLimits.MAX_HORIZON_MONTHS.toLong())
                .toKotlinLocalDate()
        if (until != null) {
            if (until < dtstart.date) {
                messages += "Enddatum darf nicht vor dem Startdatum liegen."
            } else if (until > horizonLimit) {
                messages += "Enddatum darf höchstens ${EventSeriesLimits.MAX_HORIZON_MONTHS} Monate nach dem Start liegen."
            }
        }

        if (messages.isNotEmpty()) return Result.Invalid(messages)

        val monthDayForOutput: Int? = rule.byMonthDay
        val sb = StringBuilder("FREQ=${rule.frequency.name};INTERVAL=${rule.interval}")
        when {
            byDayToken != null -> sb.append(";BYDAY=").append(byDayToken)
            rule.frequency == RecurrenceFrequency.MONTHLY && monthDayForOutput != null ->
                sb
                    .append(
                        ";BYMONTHDAY=",
                    ).append(monthDayForOutput)
        }
        if (count != null) {
            sb.append(";COUNT=").append(count)
        } else {
            sb.append(";UNTIL=").append(untilUtc(lastLocalDate = until!!, zone = zone))
        }

        val rrule = sb.toString()
        if (rrule.length > EventSeriesLimits.RRULE_MAX_LENGTH) {
            return Result.Invalid(listOf("Die erzeugte Wiederholungsregel ist zu lang (${rrule.length} Zeichen)."))
        }

        // Cross-check the rule we are about to hand back against what it would actually expand to
        // -- see this function's own KDoc for why a purely structural check cannot catch either of
        // the two things checked below.
        val occurrences =
            try {
                RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = zone)
            } catch (e: TooManyOccurrencesException) {
                return Result.Invalid(
                    listOf(
                        "Die Wiederholungsregel würde mehr als ${EventSeriesLimits.MAX_INSTANCES_PER_SERIES} Termine " +
                            "innerhalb von ${EventSeriesLimits.MAX_HORIZON_MONTHS} Monaten erzeugen.",
                    ),
                )
            }

        if (occurrences.isEmpty() || occurrences.first() != dtstart) {
            return Result.Invalid(
                listOf(
                    "Der Startzeitpunkt (DTSTART) muss selbst ein Termin der Regel sein -- er passt nicht zu " +
                        "dem gewählten Wochentag/Monatstag.",
                ),
            )
        }

        if (count != null && occurrences.size < count) {
            return Result.Invalid(
                listOf(
                    "Anzahl der Termine ($count) passt nicht in den ${EventSeriesLimits.MAX_HORIZON_MONTHS}-Monats-Horizont " +
                        "(nur ${occurrences.size} Termine bis dahin möglich). Anzahl verringern oder Intervall bzw. " +
                        "Wochentage anpassen.",
                ),
            )
        }

        return Result.Ok(rrule)
    }

    /**
     * RFC 5545 §3.3.10: when `DTSTART` carries a `TZID`, `UNTIL` MUST be expressed in UTC ("form
     * #2" of the DATE-TIME value type) -- a local-time `UNTIL` is technically legal per the ABNF
     * but is widely mis-interpreted by consumers (Google Calendar, Outlook) as UTC regardless,
     * silently shifting the effective end date by the zone offset. [lastLocalDate] is a calendar
     * date (this codebase's [RecurrenceRuleInput.until] carries no time-of-day), so this always
     * anchors it to the END of that day (23:59:59) in [zone] before converting to UTC -- this
     * guarantees any occurrence whose `DTSTART` time-of-day falls on [lastLocalDate] is still
     * `<= UNTIL`, regardless of what that time-of-day is.
     */
    fun untilUtc(
        lastLocalDate: LocalDate,
        zone: ZoneId,
    ): String {
        val endOfDay = ZonedDateTime.of(lastLocalDate.toJavaLocalDate(), LocalTime.of(23, 59, 59), zone)
        return endOfDay.withZoneSameInstant(ZoneId.of("UTC")).format(UNTIL_FORMAT)
    }

    private val UNTIL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val UNTIL_PATTERN = Regex("^\\d{8}T\\d{6}Z$")
    private val KNOWN_KEYS = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "COUNT", "UNTIL")
    private val MONTHLY_BYDAY_PATTERN = Regex("^(-?\\d+)([A-Z]{2})$")

    /**
     * The read-back path for an `event_series.rrule` DB value: re-validates the string against
     * exactly the subset [build] can produce (same key set, same per-`FREQ` combination rules,
     * same numeric bounds) and throws [BadRequestException] on anything else -- including a
     * syntactically-valid RFC 5545 RRULE this codebase simply never emits (`BYSETPOS`, `BYHOUR`,
     * `BYWEEKNO`, `SECONDLY`/`MINUTELY`/`HOURLY`, a duplicated key, an out-of-range `COUNT`, ...).
     * This is defense-in-depth against a tampered/corrupted column value reaching
     * [RecurrenceExpander] -- a value [build] itself produced always round-trips successfully.
     *
     * The reconstructed [RecurrenceRuleInput.until], when the rule has an `UNTIL` clause, is
     * derived from the UTC instant's own calendar date (this function has no `DTSTART`/zone
     * context to convert it back precisely) -- callers that need the exact original local end date
     * should prefer reading it from `event_series.dtstart`/`event_series.timezone` directly rather
     * than round-tripping through this value; it is intended for coarse display use
     * (`RecurrenceSentence`), not for re-driving [build].
     */
    fun parseWhitelisted(rrule: String): RecurrenceRuleInput {
        fun fail(reason: String): Nothing = throw BadRequestException("Ungültige Wiederholungsregel: $reason")

        if (rrule.isEmpty() || rrule.length > EventSeriesLimits.RRULE_MAX_LENGTH) {
            fail("Länge außerhalb des zulässigen Bereichs")
        }

        val parts = rrule.split(";")
        val fields = LinkedHashMap<String, String>()
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq <= 0) fail("nicht lesbares Feld \"$part\"")
            val key = part.substring(0, eq)
            val value = part.substring(eq + 1)
            if (key !in KNOWN_KEYS) fail("nicht unterstütztes Feld \"$key\"")
            if (fields.containsKey(key)) fail("Feld \"$key\" mehrfach angegeben")
            fields[key] = value
        }

        val freqRaw = fields["FREQ"] ?: fail("FREQ fehlt")
        val frequency = RecurrenceFrequency.entries.firstOrNull { it.name == freqRaw } ?: fail("nicht unterstützte Häufigkeit \"$freqRaw\"")

        val intervalRaw = fields["INTERVAL"] ?: fail("INTERVAL fehlt")
        val interval = intervalRaw.toIntOrNull() ?: fail("INTERVAL ist keine Zahl")
        if (interval < 1 || interval > EventSeriesLimits.MAX_INTERVAL) fail("INTERVAL außerhalb des zulässigen Bereichs")

        val byDayRaw = fields["BYDAY"]
        val byMonthDayRaw = fields["BYMONTHDAY"]

        var byWeekdays = emptySet<RecurrenceWeekday>()
        var byMonthDay: Int? = null
        var byMonthlyWeekday: MonthlyWeekdayRule? = null

        when (frequency) {
            RecurrenceFrequency.WEEKLY -> {
                if (byMonthDayRaw != null) fail("BYMONTHDAY ist bei WEEKLY nicht erlaubt")
                val dayTokens = byDayRaw?.split(",") ?: fail("BYDAY fehlt bei WEEKLY")
                if (dayTokens.isEmpty() || dayTokens.size > 7) fail("BYDAY außerhalb des zulässigen Bereichs")
                val parsed =
                    dayTokens.map { token ->
                        RecurrenceWeekday.entries.firstOrNull { it.name == token }
                            ?: fail("unbekannter Wochentag \"$token\"")
                    }
                if (parsed.toSet().size != parsed.size) fail("BYDAY enthält Duplikate")
                byWeekdays = parsed.toSet()
            }
            RecurrenceFrequency.MONTHLY -> {
                val hasMonthDay = byMonthDayRaw != null
                val hasByDay = byDayRaw != null
                if (hasMonthDay == hasByDay) fail("MONTHLY braucht genau eine Variante (BYMONTHDAY oder BYDAY)")
                if (hasMonthDay) {
                    val day = byMonthDayRaw.toIntOrNull() ?: fail("BYMONTHDAY ist keine Zahl")
                    if (day < 1 || day > 31) fail("BYMONTHDAY außerhalb des zulässigen Bereichs")
                    byMonthDay = day
                } else {
                    val match = MONTHLY_BYDAY_PATTERN.matchEntire(byDayRaw!!) ?: fail("unlesbares BYDAY \"$byDayRaw\"")
                    val ordinal = match.groupValues[1].toIntOrNull() ?: fail("unlesbare Ordinalzahl in BYDAY")
                    if (ordinal !in EventSeriesLimits.ALLOWED_MONTHLY_ORDINALS) fail("Ordinalzahl außerhalb des zulässigen Bereichs")
                    val weekday =
                        RecurrenceWeekday.entries.firstOrNull { it.name == match.groupValues[2] } ?: fail("unbekannter Wochentag in BYDAY")
                    byMonthlyWeekday = MonthlyWeekdayRule(ordinal = ordinal, weekday = weekday)
                }
            }
            RecurrenceFrequency.DAILY, RecurrenceFrequency.YEARLY -> {
                if (byDayRaw != null || byMonthDayRaw != null) fail("${frequency.name} darf keine BY-Einschränkung haben")
            }
        }

        val countRaw = fields["COUNT"]
        val untilRaw = fields["UNTIL"]
        if ((countRaw != null) == (untilRaw != null)) fail("es muss genau COUNT oder UNTIL angegeben sein")

        var count: Int? = null
        var until: LocalDate? = null
        if (countRaw != null) {
            val c = countRaw.toIntOrNull() ?: fail("COUNT ist keine Zahl")
            if (c < 1 || c > EventSeriesLimits.MAX_INSTANCES_PER_SERIES) fail("COUNT außerhalb des zulässigen Bereichs")
            count = c
        } else {
            if (!UNTIL_PATTERN.matches(untilRaw!!)) fail("UNTIL hat kein gültiges UTC-Format")
            val instant =
                try {
                    java.time.Instant.from(UNTIL_FORMAT.withZone(ZoneId.of("UTC")).parse(untilRaw))
                } catch (e: java.time.format.DateTimeParseException) {
                    fail("UNTIL ist kein gültiger Zeitpunkt")
                }
            until = instant.atZone(ZoneId.of("UTC")).toLocalDate().toKotlinLocalDate()
        }

        return RecurrenceRuleInput(
            frequency = frequency,
            interval = interval,
            byWeekdays = byWeekdays,
            byMonthDay = byMonthDay,
            byMonthlyWeekday = byMonthlyWeekday,
            count = count,
            until = until,
        )
    }
}
