package network.lapis.cloud.server.events.series

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday

/**
 * Dritte Folgewelle "Wiederkehrende Veranstaltungen" -- baut den EINEN Wahrheits-Satz, den
 * `IEventService.previewSeries` zurückgibt und der Client 1:1 anzeigt, z.B.:
 * `"Wöchentlich am Dienstag, 19:00–21:00 · 26 Termine · 06.10.2026 bis 30.03.2027"`.
 *
 * Deutsch, serverseitig gebaut, deliberately NOT run through `tr()` -- see the implementation
 * plan's own "Frage F5": this codebase's `AllClientMessagesCatalogTest` only scans CLIENT Kotlin
 * files for `tr()`/`gettext()` calls, exactly like the existing precedent of un-translated
 * server-built text (`EventService.mailEventCancelled`'s `"Abgesagt: $eventTitle"`). The CLIENT's
 * own immediate, pre-`previewSeries`-answer sentence is a SEPARATE, deliberately simpler function
 * (`EventRecurrenceSentencePreview` in `:lapis-client`) -- not this object, and not shared code:
 * this is the single source of TRUTH, that one is only formatting until the truth arrives.
 */
internal object RecurrenceSentence {
    private val WEEKDAY_NAMES_NOMINATIVE =
        mapOf(
            RecurrenceWeekday.MO to "Montag",
            RecurrenceWeekday.TU to "Dienstag",
            RecurrenceWeekday.WE to "Mittwoch",
            RecurrenceWeekday.TH to "Donnerstag",
            RecurrenceWeekday.FR to "Freitag",
            RecurrenceWeekday.SA to "Samstag",
            RecurrenceWeekday.SU to "Sonntag",
        )

    private val WEEKDAYS_IN_RFC_ORDER = RecurrenceWeekday.entries

    /**
     * [templateEndTime] is the template instance's OWN end-of-day wall-clock time (i.e.
     * `startsAt + durationMinutes`'s hour/minute, on the FIRST occurrence's day) -- passed
     * separately from [occurrences] because [occurrences] only carries each instance's START
     * (see [RecurrenceExpander.expand]).
     */
    fun build(
        rule: RecurrenceRuleInput,
        occurrences: List<LocalDateTime>,
        templateEndTime: LocalDateTime,
    ): String {
        if (occurrences.isEmpty()) return ""
        val frequencyPhrase =
            when (rule.frequency) {
                RecurrenceFrequency.DAILY -> if (rule.interval == 1) "Täglich" else "Alle ${rule.interval} Tage"
                RecurrenceFrequency.WEEKLY -> weeklyPhrase(rule)
                RecurrenceFrequency.MONTHLY -> monthlyPhrase(rule)
                RecurrenceFrequency.YEARLY -> if (rule.interval == 1) "Jährlich" else "Alle ${rule.interval} Jahre"
            }
        val first = occurrences.first()
        val timeRange = "%02d:%02d–%02d:%02d".format(first.hour, first.minute, templateEndTime.hour, templateEndTime.minute)
        val countPhrase = "${occurrences.size} ${if (occurrences.size == 1) "Termin" else "Termine"}"
        val rangePhrase = "${formatDate(occurrences.first())} bis ${formatDate(occurrences.last())}"
        return "$frequencyPhrase, $timeRange · $countPhrase · $rangePhrase"
    }

    /**
     * The frequency+weekday phrase alone (no count/date-range) -- used for the terminliste ↻ symbol's
     * tooltip, where re-expanding the whole series just to get a full [build] sentence per row would
     * be a needless per-row cost (see `EventService.toEventDto`'s call site).
     */
    fun frequencyOnly(rule: RecurrenceRuleInput): String =
        when (rule.frequency) {
            RecurrenceFrequency.DAILY -> if (rule.interval == 1) "Täglich" else "Alle ${rule.interval} Tage"
            RecurrenceFrequency.WEEKLY -> weeklyPhrase(rule)
            RecurrenceFrequency.MONTHLY -> monthlyPhrase(rule)
            RecurrenceFrequency.YEARLY -> if (rule.interval == 1) "Jährlich" else "Alle ${rule.interval} Jahre"
        }

    private fun weeklyPhrase(rule: RecurrenceRuleInput): String {
        val prefix = if (rule.interval == 1) "Wöchentlich" else "Alle ${rule.interval} Wochen"
        val days =
            WEEKDAYS_IN_RFC_ORDER
                .filter { it in rule.byWeekdays }
                .mapNotNull { WEEKDAY_NAMES_NOMINATIVE[it] }
        return if (days.isEmpty()) prefix else "$prefix am ${days.joinToString(", ")}"
    }

    private fun monthlyPhrase(rule: RecurrenceRuleInput): String {
        val prefix = if (rule.interval == 1) "Monatlich" else "Alle ${rule.interval} Monate"
        val monthDay = rule.byMonthDay
        val monthlyWeekday = rule.byMonthlyWeekday
        return when {
            monthDay != null -> "$prefix am $monthDay."
            monthlyWeekday != null -> {
                val ordinalWord =
                    when (monthlyWeekday.ordinal) {
                        1 -> "ersten"
                        2 -> "zweiten"
                        3 -> "dritten"
                        4 -> "vierten"
                        -1 -> "letzten"
                        else -> "${monthlyWeekday.ordinal}."
                    }
                val weekdayName = WEEKDAY_NAMES_NOMINATIVE[monthlyWeekday.weekday] ?: monthlyWeekday.weekday.name
                "$prefix am $ordinalWord $weekdayName"
            }
            else -> prefix
        }
    }

    private fun formatDate(dt: LocalDateTime): String = "%02d.%02d.%04d".format(dt.dayOfMonth, dt.monthNumber, dt.year)
}
