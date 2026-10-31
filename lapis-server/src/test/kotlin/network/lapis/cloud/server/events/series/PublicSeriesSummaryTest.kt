package network.lapis.cloud.server.events.series

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MonthlyWeekdayRule
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import kotlin.uuid.Uuid

/** V1.9.243 -- the pure repeat sentence: every wording, the fallback, and the teaser assembly. No database. */
class PublicSeriesSummaryTest :
    FunSpec({
        fun sentence(rule: RecurrenceRuleInput) = PublicSeriesSummary.repeatSentence(rule)

        test("daily") {
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.DAILY)) shouldBe "täglich"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.DAILY, interval = 3)) shouldBe "alle 3 Tage"
        }

        test("weekly: one day, several days, interval, no day") {
            val tu = setOf(RecurrenceWeekday.TU)
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, byWeekdays = tu)) shouldBe "jeden Dienstag"
            sentence(
                RecurrenceRuleInput(
                    frequency = RecurrenceFrequency.WEEKLY,
                    byWeekdays = setOf(RecurrenceWeekday.FR, RecurrenceWeekday.MO, RecurrenceWeekday.WE),
                ),
            ) shouldBe "jeden Montag, Mittwoch und Freitag"
            sentence(
                RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, byWeekdays = setOf(RecurrenceWeekday.MO, RecurrenceWeekday.WE)),
            ) shouldBe "jeden Montag und Mittwoch"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, interval = 2, byWeekdays = tu)) shouldBe
                "alle 2 Wochen am Dienstag"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY)) shouldBe "wöchentlich"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, interval = 2)) shouldBe "alle 2 Wochen"
        }

        test("monthly: weekday rule, month day, plain") {
            val first = MonthlyWeekdayRule(ordinal = 1, weekday = RecurrenceWeekday.MO)
            val last = MonthlyWeekdayRule(ordinal = -1, weekday = RecurrenceWeekday.FR)
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, byMonthlyWeekday = first)) shouldBe
                "jeden ersten Montag im Monat"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, byMonthlyWeekday = last)) shouldBe
                "jeden letzten Freitag im Monat"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, interval = 2, byMonthlyWeekday = first)) shouldBe
                "alle 2 Monate am ersten Montag"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, byMonthDay = 15)) shouldBe "am 15. jedes Monats"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, interval = 3, byMonthDay = 15)) shouldBe
                "alle 3 Monate am 15."
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY)) shouldBe "monatlich"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, interval = 2)) shouldBe "alle 2 Monate"
        }

        test("yearly") {
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.YEARLY)) shouldBe "jährlich"
            sentence(RecurrenceRuleInput(frequency = RecurrenceFrequency.YEARLY, interval = 2)) shouldBe "alle 2 Jahre"
        }

        test("an unreadable stored rule falls back to the generic sentence, never throws") {
            PublicSeriesSummary.repeatSentenceOrFallback("NOT-A-RULE") shouldBe PublicSeriesSummary.FALLBACK_SENTENCE
            PublicSeriesSummary.repeatSentenceOrFallback("FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;COUNT=5") shouldBe "jeden Dienstag"
        }

        test("teaser: a null rule yields the fallback sentence, the counts pass through") {
            val next =
                SeriesOccurrenceRef(
                    eventId = Uuid.random(),
                    slug = "slug",
                    title = "Titel",
                    startsAt = LocalDateTime(2031, 3, 1, 19, 0),
                )
            val last = LocalDateTime(2031, 6, 1, 19, 0)
            val teaser = PublicSeriesSummary.teaser(rule = null, next = next, upcomingCount = 4, lastStartsAt = last)
            teaser.repeatSentence shouldBe PublicSeriesSummary.FALLBACK_SENTENCE
            teaser.upcomingCount shouldBe 4
            teaser.lastStartsAt shouldBe last
            teaser.next shouldBe next
        }
    })
