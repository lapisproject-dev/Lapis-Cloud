package network.lapis.cloud.server.events.series

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventSeriesEditScope
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.BadRequestException
import java.time.ZoneId

/**
 * Welle V1.4.37 "Wiederkehrende Veranstaltungen, Folgewelle (Rest)" -- [EventSeriesScopeEngine] has
 * no DB access, so every branch is unit-testable in isolation, same posture
 * `RecurrenceRuleBuilderTest`/`EventPolicyTest` already establish for their own policy objects.
 */
class EventSeriesScopeEngineTest :
    FunSpec({
        val berlin = ZoneId.of("Europe/Berlin")
        val dtstart = LocalDateTime(2026, 10, 6, 19, 0) // a Tuesday

        val weeklyCount10 =
            (
                RecurrenceRuleBuilder.build(
                    rule =
                        RecurrenceRuleInput(
                            frequency = RecurrenceFrequency.WEEKLY,
                            byWeekdays = setOf(RecurrenceWeekday.TU),
                            count = 10,
                        ),
                    dtstart = dtstart,
                    zone = berlin,
                ) as RecurrenceRuleBuilder.Result.Ok
            ).rrule

        val weeklyUntil =
            (
                RecurrenceRuleBuilder.build(
                    rule =
                        RecurrenceRuleInput(
                            frequency = RecurrenceFrequency.WEEKLY,
                            byWeekdays = setOf(RecurrenceWeekday.TU),
                            until = kotlinx.datetime.LocalDate(2027, 1, 5),
                        ),
                    dtstart = dtstart,
                    zone = berlin,
                ) as RecurrenceRuleBuilder.Result.Ok
            ).rrule

        val allOccurrences = RecurrenceExpander.expand(rrule = weeklyCount10, dtstart = dtstart, zone = berlin)

        test("THIS affects only the target occurrence, never splits") {
            val target = allOccurrences[3]

            val plan =
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.THIS,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = target,
                )

            plan.affectedOriginalStarts shouldContainExactly setOf(target)
            plan.split.shouldBeNull()
        }

        test("ALL affects every occurrence, never splits") {
            val target = allOccurrences[3]

            val plan =
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.ALL,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = target,
                )

            plan.affectedOriginalStarts shouldContainExactly allOccurrences.toSet()
            plan.split.shouldBeNull()
        }

        test("FOLLOWING on the first occurrence degenerates to ALL, no split") {
            val plan =
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.FOLLOWING,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = allOccurrences[0],
                )

            plan.affectedOriginalStarts shouldContainExactly allOccurrences.toSet()
            plan.split.shouldBeNull()
        }

        test("FOLLOWING on a middle occurrence of a COUNT-bounded series splits the series in two") {
            val target = allOccurrences[4] // 5th occurrence (index 4)

            val plan =
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.FOLLOWING,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = target,
                )

            plan.affectedOriginalStarts shouldContainExactly allOccurrences.drop(4).toSet()
            val split = plan.split.shouldNotBeNull()
            split.newSeriesDtstart shouldBe target

            val originalKept = RecurrenceExpander.expand(rrule = split.originalSeriesRrule, dtstart = dtstart, zone = berlin)
            originalKept shouldContainExactly allOccurrences.take(4)

            val newMaterialized = RecurrenceExpander.expand(rrule = split.newSeriesRrule, dtstart = target, zone = berlin)
            newMaterialized shouldContainExactly allOccurrences.drop(4)
        }

        test("FOLLOWING on a middle occurrence of an UNTIL-bounded series splits, keeping the same absolute UNTIL") {
            val untilOccurrences = RecurrenceExpander.expand(rrule = weeklyUntil, dtstart = dtstart, zone = berlin)
            val target = untilOccurrences[3]

            val plan =
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.FOLLOWING,
                    rrule = weeklyUntil,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = target,
                )

            val split = plan.split.shouldNotBeNull()
            // The original series switches from UNTIL to a COUNT bound (see EventSeriesScopeEngine
            // KDoc: an UNTIL clause bounds an absolute instant, not "how many occurrences", so it
            // cannot be left unchanged to stop the original series before the split point).
            split.originalSeriesRrule shouldBe "$weeklyUntil".replace(Regex(";UNTIL=[^;]+"), ";COUNT=3")
            // The new series keeps the ORIGINAL absolute UNTIL unchanged -- only re-anchored at a
            // later DTSTART, which still bounds it correctly.
            split.newSeriesRrule shouldBe weeklyUntil

            val originalKept = RecurrenceExpander.expand(rrule = split.originalSeriesRrule, dtstart = dtstart, zone = berlin)
            originalKept shouldContainExactly untilOccurrences.take(3)

            val newMaterialized = RecurrenceExpander.expand(rrule = split.newSeriesRrule, dtstart = target, zone = berlin)
            newMaterialized shouldContainExactly untilOccurrences.drop(3)
        }

        test("exdates are excluded before the target occurrence is located, matching what the materializer sees") {
            val excluded = allOccurrences[2]
            val target = allOccurrences[4]

            val plan =
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.ALL,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = target,
                    exdates = setOf(excluded),
                )

            plan.affectedOriginalStarts shouldContainExactly (allOccurrences.toSet() - excluded)
        }

        test(
            "FOLLOWING split with an exdate strictly before the target reconstructs the full " +
                "exdate-filtered occurrence set exactly, without losing or fabricating occurrences",
        ) {
            // Regression for the raw-vs-filtered-index bug: COUNT/UNTIL bound RAW occurrences (see
            // RecurrenceExpander.expand KDoc), but the target's index in the exdate-FILTERED list
            // (as plan() computes it for affectedOriginalStarts) is one less once an earlier
            // occurrence has been excluded. Using that filtered index to build the split RRULEs
            // used to truncate/extend the wrong number of raw occurrences.
            val excluded = allOccurrences[2] // 2026-10-20, strictly before the split target
            val target = allOccurrences[4] // 2026-11-03

            val plan =
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.FOLLOWING,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = target,
                    exdates = setOf(excluded),
                )

            val filteredOccurrences = allOccurrences - excluded
            val filteredTargetIndex = filteredOccurrences.indexOf(target)
            plan.affectedOriginalStarts shouldContainExactly filteredOccurrences.drop(filteredTargetIndex).toSet()

            val split = plan.split.shouldNotBeNull()
            split.newSeriesDtstart shouldBe target

            // The RAW split index must be 4 (the target's position among ALL 10 raw occurrences),
            // not 3 (its position after the earlier exdate has been filtered out) -- COUNT=3 would
            // wrongly drop 2026-10-27 (it belongs to neither series) and COUNT=7 on the new series
            // would wrongly fabricate a 2026-12-15 occurrence the original rule never had.
            split.originalSeriesRrule shouldBe "$weeklyCount10".replace(Regex(";COUNT=\\d+"), ";COUNT=4")
            split.newSeriesRrule shouldBe "$weeklyCount10".replace(Regex(";COUNT=\\d+"), ";COUNT=6")

            val originalKept =
                RecurrenceExpander
                    .expand(rrule = split.originalSeriesRrule, dtstart = dtstart, zone = berlin)
                    .filterNot { it in setOf(excluded) }
            val newMaterialized =
                RecurrenceExpander
                    .expand(rrule = split.newSeriesRrule, dtstart = target, zone = berlin)
                    .filterNot { it in setOf(excluded) }

            // No occurrence lost (2026-10-27 must still be present, on the original side) and none
            // fabricated (exactly 9 occurrences total, matching the original series minus the exdate).
            (originalKept + newMaterialized) shouldContainExactly filteredOccurrences
            newMaterialized.size shouldBe 6
            originalKept.size shouldBe 3
        }

        test("a target that is not an occurrence of the rule (stale/tampered input) is rejected") {
            shouldThrow<BadRequestException> {
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.THIS,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = LocalDateTime(2026, 10, 7, 19, 0), // a Wednesday, rule is Tuesdays
                )
            }
        }

        test("a target already excluded via exdates is rejected the same way") {
            val excluded = allOccurrences[2]

            shouldThrow<BadRequestException> {
                EventSeriesScopeEngine.plan(
                    scope = EventSeriesEditScope.THIS,
                    rrule = weeklyCount10,
                    dtstart = dtstart,
                    zone = berlin,
                    targetOriginalStart = excluded,
                    exdates = setOf(excluded),
                )
            }
        }
    })
