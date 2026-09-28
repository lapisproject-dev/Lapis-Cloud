package network.lapis.cloud.server.events.series

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.rpc.BadRequestException
import java.time.ZoneId

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen" -- [RecurrenceExpander] against real ical4j
 * `Recur` expansion (no DB). DST-crossing behaviour is the load-bearing case: a weekly Tuesday
 * 19:00 rule must stay at wall-clock 19:00 across both the 2026-10-25 (CEST->CET) and the
 * 2027-03-28 (CET->CEST) transitions.
 */
class RecurrenceExpanderTest :
    FunSpec({
        val berlin = ZoneId.of("Europe/Berlin")

        test("weekly Tuesday 19:00 stays at wall-clock 19:00 across the autumn and spring DST transitions") {
            val rule =
                network.lapis.cloud.shared.domain.RecurrenceRuleInput(
                    frequency = network.lapis.cloud.shared.domain.RecurrenceFrequency.WEEKLY,
                    byWeekdays = setOf(network.lapis.cloud.shared.domain.RecurrenceWeekday.TU),
                    count = 30,
                )
            val dtstart = LocalDateTime(2026, 10, 6, 19, 0)
            val rrule =
                (
                    RecurrenceRuleBuilder.build(
                        rule = rule,
                        dtstart = dtstart,
                        zone = berlin,
                    ) as RecurrenceRuleBuilder.Result.Ok
                ).rrule

            val occurrences = RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = berlin)

            occurrences shouldHaveSize 30
            occurrences.forEach {
                it.hour shouldBe 19
                it.minute shouldBe 0
            }
            occurrences shouldContain LocalDateTime(2026, 10, 20, 19, 0) // last Tuesday before the Oct 25 DST switch
            occurrences shouldContain LocalDateTime(2026, 10, 27, 19, 0) // first Tuesday after the Oct 25 DST switch
            occurrences shouldContain LocalDateTime(2027, 3, 23, 19, 0) // last Tuesday before the Mar 28 DST switch
            occurrences shouldContain LocalDateTime(2027, 3, 30, 19, 0) // first Tuesday after the Mar 28 DST switch
        }

        test("BYMONTHDAY=31 skips months that have no 31st") {
            val rule =
                network.lapis.cloud.shared.domain.RecurrenceRuleInput(
                    frequency = network.lapis.cloud.shared.domain.RecurrenceFrequency.MONTHLY,
                    byMonthDay = 31,
                    count = 6,
                )
            val dtstart = LocalDateTime(2026, 10, 31, 18, 0)
            val rrule =
                (
                    RecurrenceRuleBuilder.build(
                        rule = rule,
                        dtstart = dtstart,
                        zone = berlin,
                    ) as RecurrenceRuleBuilder.Result.Ok
                ).rrule

            val occurrences = RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = berlin)

            // Oct, Dec, Jan, Mar, May, Jul all have a 31st -- Nov, Feb, Apr, Jun do not and are skipped.
            occurrences.map { it.monthNumber } shouldBe listOf(10, 12, 1, 3, 5, 7)
        }

        test("-1TU (last Tuesday of the month) matches the correct date") {
            val rule =
                network.lapis.cloud.shared.domain.RecurrenceRuleInput(
                    frequency = network.lapis.cloud.shared.domain.RecurrenceFrequency.MONTHLY,
                    byMonthlyWeekday =
                        network.lapis.cloud.shared.domain.MonthlyWeekdayRule(
                            ordinal = -1,
                            weekday = network.lapis.cloud.shared.domain.RecurrenceWeekday.TU,
                        ),
                    count = 3,
                )
            val dtstart = LocalDateTime(2026, 10, 27, 18, 0) // the last Tuesday of Oct 2026
            val rrule =
                (
                    RecurrenceRuleBuilder.build(
                        rule = rule,
                        dtstart = dtstart,
                        zone = berlin,
                    ) as RecurrenceRuleBuilder.Result.Ok
                ).rrule

            val occurrences = RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = berlin)

            occurrences shouldBe
                listOf(
                    LocalDateTime(2026, 10, 27, 18, 0),
                    LocalDateTime(2026, 11, 24, 18, 0),
                    LocalDateTime(2026, 12, 29, 18, 0),
                )
        }

        test("expand throws TooManyOccurrencesException for a COUNT-less DAILY rule whose UNTIL still yields > 104 raw occurrences") {
            // parseWhitelisted has no `dtstart`, so it cannot itself reject a distant-but-otherwise-
            // well-formed UNTIL -- that check only happens in RecurrenceRuleBuilder.build (which is
            // bypassed here to simulate a hand-crafted/tampered rule reaching the expander
            // directly). ~244 daily occurrences before Sep 2026, far past MAX_INSTANCES_PER_SERIES.
            val dtstart = LocalDateTime(2026, 1, 1, 9, 0)
            shouldThrow<TooManyOccurrencesException> {
                RecurrenceExpander.expand(rrule = "FREQ=DAILY;INTERVAL=1;UNTIL=20260901T235959Z", dtstart = dtstart, zone = berlin)
            }
        }

        test("expand rejects a COUNT above 104 (parseWhitelisted's own defense-in-depth bound)") {
            val dtstart = LocalDateTime(2026, 1, 1, 9, 0)
            shouldThrow<BadRequestException> {
                RecurrenceExpander.expand(rrule = "FREQ=DAILY;INTERVAL=1;COUNT=1000", dtstart = dtstart, zone = berlin)
            }
        }

        test("a manipulated RRULE with an unsupported field is rejected before expansion is ever attempted") {
            val dtstart = LocalDateTime(2026, 1, 1, 9, 0)
            shouldThrow<BadRequestException> {
                RecurrenceExpander.expand(rrule = "FREQ=DAILY;INTERVAL=1;BYSETPOS=1;COUNT=5", dtstart = dtstart, zone = berlin)
            }
        }

        test("exdates are subtracted from the expanded occurrences") {
            val rule =
                network.lapis.cloud.shared.domain.RecurrenceRuleInput(
                    frequency = network.lapis.cloud.shared.domain.RecurrenceFrequency.WEEKLY,
                    byWeekdays = setOf(network.lapis.cloud.shared.domain.RecurrenceWeekday.TU),
                    count = 4,
                )
            val dtstart = LocalDateTime(2026, 10, 6, 19, 0)
            val rrule =
                (
                    RecurrenceRuleBuilder.build(
                        rule = rule,
                        dtstart = dtstart,
                        zone = berlin,
                    ) as RecurrenceRuleBuilder.Result.Ok
                ).rrule
            val deleted = LocalDateTime(2026, 10, 13, 19, 0)

            val occurrences = RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = berlin, exdates = setOf(deleted))

            occurrences shouldHaveSize 3
            occurrences shouldNotContain deleted
        }
    })
