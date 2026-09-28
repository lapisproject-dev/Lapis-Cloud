package network.lapis.cloud.server.events.series

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MonthlyWeekdayRule
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.BadRequestException
import java.time.ZoneId

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen" -- [RecurrenceRuleBuilder] has no DB access, so
 * every branch is unit-testable in isolation, same posture `EventPolicyTest`/`CrmContactPolicyTest`
 * already establish for their own policy objects. `build` DOES exercise real `ical4j` `Recur`
 * expansion indirectly (through [RecurrenceExpander.expand], called once per [RecurrenceRuleBuilder.build]
 * to cross-check `DTSTART` membership and the `COUNT`-vs-horizon fit -- see that function's KDoc),
 * which is why several `dtstart` values below are chosen to actually be an occurrence of their rule
 * rather than merely "a Tuesday".
 */
class RecurrenceRuleBuilderTest :
    FunSpec({
        val berlin = ZoneId.of("Europe/Berlin")
        val dtstart = LocalDateTime(2026, 10, 6, 19, 0) // a Tuesday

        fun weekly(
            interval: Int = 1,
            days: Set<RecurrenceWeekday> = setOf(RecurrenceWeekday.TU),
            count: Int? = 52,
            until: LocalDate? = null,
        ) = RecurrenceRuleInput(
            frequency = RecurrenceFrequency.WEEKLY,
            interval = interval,
            byWeekdays = days,
            count = count,
            until = until,
        )

        // ── build: happy paths / normalized output ──────────────────────────────────

        test("build produces the normalized field order for a WEEKLY rule with COUNT") {
            val result = RecurrenceRuleBuilder.build(rule = weekly(), dtstart = dtstart, zone = berlin)
            result shouldBe RecurrenceRuleBuilder.Result.Ok("FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;COUNT=52")
        }

        test("build sorts multiple BYDAY weekdays into RFC week order regardless of input order") {
            // dtstart must itself be an occurrence of the rule (see the "DTSTART must be a rule
            // date" tests below), so this uses a Monday -- one of the three rule weekdays -- rather
            // than the shared `dtstart` (a Tuesday, which is NOT in {SA, MO, WE}).
            val mondayDtstart = LocalDateTime(2026, 10, 5, 19, 0)
            val rule = weekly(days = setOf(RecurrenceWeekday.SA, RecurrenceWeekday.MO, RecurrenceWeekday.WE))
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = mondayDtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Ok
            result.rrule shouldBe "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE,SA;COUNT=52"
        }

        test("build with UNTIL emits a UTC timestamp (summer, CEST)") {
            val rule = weekly(count = null, until = LocalDate(2026, 10, 20))
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Ok
            // 2026-10-20 23:59:59 CEST (UTC+2) -> 2026-10-20 21:59:59 UTC
            result.rrule shouldBe "FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;UNTIL=20261020T215959Z"
        }

        test("build with UNTIL emits a UTC timestamp (winter, CET)") {
            val rule = weekly(count = null, until = LocalDate(2027, 1, 5))
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Ok
            // 2027-01-05 23:59:59 CET (UTC+1) -> 2027-01-05 22:59:59 UTC
            result.rrule shouldBe "FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;UNTIL=20270105T225959Z"
        }

        test("build MONTHLY with BYMONTHDAY") {
            // dtstart must itself be an occurrence of the rule -- the 15th of the month, not the
            // shared `dtstart` (the 6th).
            val dtstartOnThe15th = LocalDateTime(2026, 10, 15, 19, 0)
            val rule =
                RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, byMonthDay = 15, count = 12)
            val result =
                RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstartOnThe15th, zone = berlin) as RecurrenceRuleBuilder.Result.Ok
            result.rrule shouldBe "FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=15;COUNT=12"
        }

        test("build MONTHLY with the nth-weekday variant, including a negative (last) ordinal") {
            // dtstart must itself be an occurrence of the rule -- the last Tuesday of October 2026
            // (Oct 27), not the shared `dtstart` (Oct 6, merely a Tuesday but not the LAST one).
            val lastTuesdayOfOctober = LocalDateTime(2026, 10, 27, 19, 0)
            val rule =
                RecurrenceRuleInput(
                    frequency = RecurrenceFrequency.MONTHLY,
                    byMonthlyWeekday = MonthlyWeekdayRule(ordinal = -1, weekday = RecurrenceWeekday.TU),
                    count = 12,
                )
            val result =
                RecurrenceRuleBuilder.build(rule = rule, dtstart = lastTuesdayOfOctober, zone = berlin) as RecurrenceRuleBuilder.Result.Ok
            result.rrule shouldBe "FREQ=MONTHLY;INTERVAL=1;BYDAY=-1TU;COUNT=12"
        }

        test("build YEARLY has no BY-clause") {
            // COUNT=3 is the most a YEARLY rule can reach inside the 24-month horizon from this
            // dtstart (see "build rejects YEARLY COUNT that cannot fit inside the 24-month horizon"
            // below) -- COUNT=5 would now correctly be rejected by the horizon cross-check.
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.YEARLY, count = 3)
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Ok
            result.rrule shouldBe "FREQ=YEARLY;INTERVAL=1;COUNT=3"
        }

        // ── build: rejections ────────────────────────────────────────────────────────

        test("build rejects INTERVAL 0 and 13") {
            (
                RecurrenceRuleBuilder.build(
                    rule = weekly(interval = 0),
                    dtstart = dtstart,
                    zone = berlin,
                ) as RecurrenceRuleBuilder.Result.Invalid
            ).messages shouldContain "Intervall muss zwischen 1 und 12 liegen."
            (
                RecurrenceRuleBuilder.build(
                    rule = weekly(interval = 13),
                    dtstart = dtstart,
                    zone = berlin,
                ) as RecurrenceRuleBuilder.Result.Invalid
            ).messages shouldContain "Intervall muss zwischen 1 und 12 liegen."
        }

        test("build rejects COUNT 0 and 105") {
            (
                RecurrenceRuleBuilder.build(
                    rule = weekly(count = 0),
                    dtstart = dtstart,
                    zone = berlin,
                ) as RecurrenceRuleBuilder.Result.Invalid
            ).messages shouldContain "Anzahl der Termine muss zwischen 1 und 104 liegen."
            (
                RecurrenceRuleBuilder.build(
                    rule = weekly(count = 105),
                    dtstart = dtstart,
                    zone = berlin,
                ) as RecurrenceRuleBuilder.Result.Invalid
            ).messages shouldContain "Anzahl der Termine muss zwischen 1 und 104 liegen."
        }

        test("build rejects UNTIL more than 24 months after dtstart") {
            val rule = weekly(count = null, until = LocalDate(2028, 10, 7))
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid
            result.messages shouldContain "Enddatum darf höchstens 24 Monate nach dem Start liegen."
        }

        test("build rejects UNTIL before dtstart") {
            val rule = weekly(count = null, until = LocalDate(2026, 1, 1))
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid
            result.messages shouldContain "Enddatum darf nicht vor dem Startdatum liegen."
        }

        test("build rejects COUNT and UNTIL both set, and neither set") {
            val both = weekly(count = 5, until = LocalDate(2026, 12, 1))
            (RecurrenceRuleBuilder.build(rule = both, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid)
                .messages shouldContain "Es muss genau eine Begrenzung angegeben werden: entweder Anzahl der Termine oder Enddatum."
            val neither = weekly(count = null, until = null)
            (RecurrenceRuleBuilder.build(rule = neither, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid)
                .messages shouldContain "Es muss genau eine Begrenzung angegeben werden: entweder Anzahl der Termine oder Enddatum."
        }

        test("build rejects WEEKLY without any weekday") {
            val rule = weekly(days = emptySet())
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid
            result.messages shouldContain "Wöchentliche Wiederholung braucht mindestens einen Wochentag."
        }

        test("build rejects MONTHLY with both variants or neither") {
            val both =
                RecurrenceRuleInput(
                    frequency = RecurrenceFrequency.MONTHLY,
                    byMonthDay = 15,
                    byMonthlyWeekday = MonthlyWeekdayRule(ordinal = 1, weekday = RecurrenceWeekday.MO),
                    count = 5,
                )
            (RecurrenceRuleBuilder.build(rule = both, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid)
                .messages shouldContain "Monatliche Wiederholung braucht genau eine Variante (Tag im Monat ODER n-ter Wochentag)."
            val neither = RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, count = 5)
            (RecurrenceRuleBuilder.build(rule = neither, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid)
                .messages shouldContain "Monatliche Wiederholung braucht genau eine Variante (Tag im Monat ODER n-ter Wochentag)."
        }

        test("build rejects an out-of-range monthly weekday ordinal (5 and -2)") {
            val ordinal5 =
                RecurrenceRuleInput(
                    frequency = RecurrenceFrequency.MONTHLY,
                    byMonthlyWeekday = MonthlyWeekdayRule(ordinal = 5, weekday = RecurrenceWeekday.MO),
                    count = 5,
                )
            (RecurrenceRuleBuilder.build(rule = ordinal5, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid)
                .messages shouldContain "Ordinalzahl für den n-ten Wochentag muss 1, 2, 3, 4 oder -1 (letzter) sein."
            val ordinalMinus2 =
                RecurrenceRuleInput(
                    frequency = RecurrenceFrequency.MONTHLY,
                    byMonthlyWeekday = MonthlyWeekdayRule(ordinal = -2, weekday = RecurrenceWeekday.MO),
                    count = 5,
                )
            (RecurrenceRuleBuilder.build(rule = ordinalMinus2, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid)
                .messages shouldContain "Ordinalzahl für den n-ten Wochentag muss 1, 2, 3, 4 oder -1 (letzter) sein."
        }

        // ── build: COUNT-vs-horizon cross-check (silent divergence between the stored RRULE and
        // what RecurrenceExpander.expand actually materializes) ────────────────────────

        test("build rejects a YEARLY COUNT that cannot fit inside the 24-month horizon") {
            // Only 3 anniversaries (2026, 2027, 2028) land on/before dtstart + 24 months -- COUNT=5
            // would silently diverge from what expand() ever produces.
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.YEARLY, count = 5)
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid
            result.messages shouldContain
                "Anzahl der Termine (5) passt nicht in den 24-Monats-Horizont (nur 3 Termine bis dahin möglich). " +
                "Anzahl verringern oder Intervall bzw. Wochentage anpassen."
        }

        test("build rejects a large INTERVAL whose COUNT cannot fit inside the 24-month horizon") {
            val rule = weekly(interval = 3, count = 104)
            val result = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid
            result.messages.single() shouldStartWith "Anzahl der Termine (104) passt nicht in den 24-Monats-Horizont"
        }

        // ── build: DTSTART must itself be an occurrence of the rule (RFC 5545 §3.8.5.3) ─

        test("build rejects a WEEKLY dtstart that does not fall on one of its own BYDAY weekdays") {
            val mondayDtstart = LocalDateTime(2026, 10, 5, 19, 0) // dtstart is a Monday, rule is BYDAY=TU
            val rule = weekly(count = 3)
            val result =
                RecurrenceRuleBuilder.build(rule = rule, dtstart = mondayDtstart, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid
            result.messages shouldContain
                "Der Startzeitpunkt (DTSTART) muss selbst ein Termin der Regel sein -- er passt nicht zu " +
                "dem gewählten Wochentag/Monatstag."
        }

        test("build rejects a MONTHLY BYMONTHDAY dtstart that is on a different day of the month") {
            val dtstartOnThe10th = LocalDateTime(2026, 10, 10, 19, 0)
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.MONTHLY, byMonthDay = 15, count = 5)
            val result =
                RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstartOnThe10th, zone = berlin) as RecurrenceRuleBuilder.Result.Invalid
            result.messages shouldContain
                "Der Startzeitpunkt (DTSTART) muss selbst ein Termin der Regel sein -- er passt nicht zu " +
                "dem gewählten Wochentag/Monatstag."
        }

        // ── build: DTSTART inside a DST gap ─────────────────────────────────────────────

        test("build rejects a dtstart that falls into a DST gap") {
            // 2027-03-28 is the Europe/Berlin spring-forward date (same transition RecurrenceExpanderTest
            // exercises); 02:00-03:00 does not exist that day.
            val gapDtstart = LocalDateTime(2027, 3, 28, 2, 30)
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.YEARLY, count = 2)
            val result =
                RecurrenceRuleBuilder.build(
                    rule = rule,
                    dtstart = gapDtstart,
                    zone = berlin,
                ) as RecurrenceRuleBuilder.Result.Invalid
            result.messages shouldContain
                "Der Startzeitpunkt fällt in eine Zeitumstellungs-Lücke (DST) und existiert in der Zeitzone nicht."
        }

        // ── parseWhitelisted ─────────────────────────────────────────────────────────

        test("parseWhitelisted round-trips everything build can produce") {
            val rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR;COUNT=30"
            val parsed = RecurrenceRuleBuilder.parseWhitelisted(rrule)
            parsed shouldBe
                RecurrenceRuleInput(
                    frequency = RecurrenceFrequency.WEEKLY,
                    interval = 2,
                    byWeekdays = setOf(RecurrenceWeekday.MO, RecurrenceWeekday.WE, RecurrenceWeekday.FR),
                    count = 30,
                )
        }

        test("parseWhitelisted rejects SECONDLY/MINUTELY/HOURLY even though they are valid RFC 5545 FREQ values") {
            shouldThrow<BadRequestException> { RecurrenceRuleBuilder.parseWhitelisted("FREQ=SECONDLY;INTERVAL=1;COUNT=5") }
            shouldThrow<BadRequestException> { RecurrenceRuleBuilder.parseWhitelisted("FREQ=MINUTELY;INTERVAL=1;COUNT=5") }
            shouldThrow<BadRequestException> { RecurrenceRuleBuilder.parseWhitelisted("FREQ=HOURLY;INTERVAL=1;COUNT=5") }
        }

        test("parseWhitelisted rejects BYSETPOS, BYHOUR and BYWEEKNO") {
            shouldThrow<BadRequestException> {
                RecurrenceRuleBuilder.parseWhitelisted("FREQ=MONTHLY;INTERVAL=1;BYDAY=1MO;BYSETPOS=1;COUNT=5")
            }
            shouldThrow<BadRequestException> {
                RecurrenceRuleBuilder.parseWhitelisted("FREQ=DAILY;INTERVAL=1;BYHOUR=9;COUNT=5")
            }
            shouldThrow<BadRequestException> {
                RecurrenceRuleBuilder.parseWhitelisted("FREQ=YEARLY;INTERVAL=1;BYWEEKNO=1;COUNT=5")
            }
        }

        test("parseWhitelisted rejects COUNT and UNTIL both present, and both absent") {
            shouldThrow<BadRequestException> {
                RecurrenceRuleBuilder.parseWhitelisted("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO;COUNT=5;UNTIL=20261231T235959Z")
            }
            shouldThrow<BadRequestException> {
                RecurrenceRuleBuilder.parseWhitelisted("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO")
            }
        }

        test("parseWhitelisted rejects WEEKLY without BYDAY") {
            shouldThrow<BadRequestException> { RecurrenceRuleBuilder.parseWhitelisted("FREQ=WEEKLY;INTERVAL=1;COUNT=5") }
        }

        test("parseWhitelisted rejects MONTHLY with both BYMONTHDAY and BYDAY, or neither") {
            shouldThrow<BadRequestException> {
                RecurrenceRuleBuilder.parseWhitelisted("FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=15;BYDAY=1MO;COUNT=5")
            }
            shouldThrow<BadRequestException> { RecurrenceRuleBuilder.parseWhitelisted("FREQ=MONTHLY;INTERVAL=1;COUNT=5") }
        }

        test("parseWhitelisted rejects a RRULE longer than 255 characters") {
            val bloated = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO;COUNT=5;X-PADDING=" + "A".repeat(230)
            (bloated.length > 255) shouldBe true
            shouldThrow<BadRequestException> { RecurrenceRuleBuilder.parseWhitelisted(bloated) }
        }

        test("parseWhitelisted rejects a duplicated key") {
            shouldThrow<BadRequestException> {
                RecurrenceRuleBuilder.parseWhitelisted("FREQ=WEEKLY;FREQ=DAILY;INTERVAL=1;BYDAY=MO;COUNT=5")
            }
        }

        // ── untilUtc ─────────────────────────────────────────────────────────────────

        test("untilUtc anchors to end-of-day in the given zone, summer and winter") {
            RecurrenceRuleBuilder.untilUtc(lastLocalDate = LocalDate(2026, 10, 20), zone = berlin) shouldBe "20261020T215959Z"
            RecurrenceRuleBuilder.untilUtc(lastLocalDate = LocalDate(2027, 1, 5), zone = berlin) shouldBe "20270105T225959Z"
        }
    })
