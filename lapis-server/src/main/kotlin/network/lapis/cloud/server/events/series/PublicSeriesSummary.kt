package network.lapis.cloud.server.events.series

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.BadRequestException
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** One concrete occurrence of a series as the public pages need it. */
data class SeriesOccurrenceRef(
    val eventId: Uuid,
    val slug: String,
    val title: String,
    val startsAt: LocalDateTime,
)

/** What the public overview shows for ONE series: the next occurrence, a short repeat sentence and how many are still to come. */
data class SeriesTeaser(
    val next: SeriesOccurrenceRef,
    val repeatSentence: String,
    val upcomingCount: Int,
    val lastStartsAt: LocalDateTime,
)

/**
 * V1.9.243 -- the short, public repeat sentence of a series ("jeden ersten Montag im Monat") and the teaser value type. Pure: no
 * database, no HTML, no clock, and NO expansion of the rule (no loop over occurrences) -- the sentence is built from the rule's
 * fields only. `object` and public so a partner-server feed can reuse it later.
 */
object PublicSeriesSummary {
    const val FALLBACK_SENTENCE = "Wiederkehrender Termin"

    fun repeatSentence(rule: RecurrenceRuleInput): String {
        val interval = rule.interval.coerceAtLeast(1)
        return when (rule.frequency) {
            RecurrenceFrequency.DAILY -> if (interval == 1) "täglich" else "alle $interval Tage"
            RecurrenceFrequency.WEEKLY -> weekly(rule = rule, interval = interval)
            RecurrenceFrequency.MONTHLY -> monthly(rule = rule, interval = interval)
            RecurrenceFrequency.YEARLY -> if (interval == 1) "jährlich" else "alle $interval Jahre"
        }
    }

    /**
     * Parses [rrule] through the whitelist reader and builds the sentence; a stored value that does not parse (tampered column) yields
     * [FALLBACK_SENTENCE]. The failure is logged WITHOUT the rule's content.
     */
    fun repeatSentenceOrFallback(rrule: String): String = parseOrNull(rrule)?.let { repeatSentence(it) } ?: FALLBACK_SENTENCE

    /** The whitelist-parsed rule, or `null` when the stored value does not parse; the failure is logged without the rule's content. */
    fun parseOrNull(rrule: String): RecurrenceRuleInput? =
        try {
            RecurrenceRuleBuilder.parseWhitelisted(rrule)
        } catch (e: BadRequestException) {
            logger.warn { "A stored series rule could not be read; showing the generic repeat sentence" }
            null
        }

    /** Pure assembly. [rule] `null` (unreadable rule) falls back to [FALLBACK_SENTENCE]. */
    fun teaser(
        rule: RecurrenceRuleInput?,
        next: SeriesOccurrenceRef,
        upcomingCount: Int,
        lastStartsAt: LocalDateTime,
    ): SeriesTeaser =
        SeriesTeaser(
            next = next,
            repeatSentence = rule?.let { repeatSentence(it) } ?: FALLBACK_SENTENCE,
            upcomingCount = upcomingCount,
            lastStartsAt = lastStartsAt,
        )

    private fun weekly(
        rule: RecurrenceRuleInput,
        interval: Int,
    ): String {
        val days =
            RecurrenceWeekday.entries
                .filter { it in rule.byWeekdays }
                .mapNotNull { RecurrenceSentence.WEEKDAY_NAMES_NOMINATIVE[it] }
        val list = joinWithUnd(days)
        return when {
            interval == 1 && days.isEmpty() -> "wöchentlich"
            interval == 1 -> "jeden $list"
            days.isEmpty() -> "alle $interval Wochen"
            else -> "alle $interval Wochen am $list"
        }
    }

    private fun monthly(
        rule: RecurrenceRuleInput,
        interval: Int,
    ): String {
        val monthDay = rule.byMonthDay
        val weekdayRule = rule.byMonthlyWeekday
        return when {
            monthDay != null -> if (interval == 1) "am $monthDay. jedes Monats" else "alle $interval Monate am $monthDay."
            weekdayRule != null -> {
                val ordinal =
                    when (weekdayRule.ordinal) {
                        1 -> "ersten"
                        2 -> "zweiten"
                        3 -> "dritten"
                        4 -> "vierten"
                        -1 -> "letzten"
                        else -> "${weekdayRule.ordinal}."
                    }
                val name = RecurrenceSentence.WEEKDAY_NAMES_NOMINATIVE[weekdayRule.weekday] ?: weekdayRule.weekday.name
                if (interval == 1) "jeden $ordinal $name im Monat" else "alle $interval Monate am $ordinal $name"
            }
            else -> if (interval == 1) "monatlich" else "alle $interval Monate"
        }
    }

    private fun joinWithUnd(items: List<String>): String =
        when (items.size) {
            0 -> ""
            1 -> items.first()
            else -> items.dropLast(1).joinToString(", ") + " und " + items.last()
        }
}
