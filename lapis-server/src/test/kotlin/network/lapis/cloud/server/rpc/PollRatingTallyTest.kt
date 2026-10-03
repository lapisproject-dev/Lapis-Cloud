package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PollDecisionOutcome
import network.lapis.cloud.shared.domain.PollKind
import kotlin.uuid.Uuid

/** Builds an option from the explicit list of resistance values (one per response). */
private fun option(
    position: Int,
    values: List<Int>,
    passive: Boolean = false,
    id: Uuid = Uuid.random(),
) = PollRatingOptionTally(
    optionId = id,
    position = position,
    isPassive = passive,
    distribution = values.groupingBy { it }.eachCount(),
)

private fun decide(
    vararg options: PollRatingOptionTally,
    kind: PollKind = PollKind.SK_DECISION,
) = computePollRatingResult(
    pollId = Uuid.random(),
    kind = kind,
    responseCount =
        options
            .first()
            .distribution.values
            .sum(),
    optionsInPositionOrder = options.toList(),
)

class PollRatingTallyTest :
    FunSpec({
        test("below five responses only the response count is disclosed") {
            val r = decide(option(position = 0, values = listOf(1, 2, 3, 4)), option(position = 1, values = listOf(5, 5, 5, 5)))
            r.responseCount shouldBe 4
            r.ratingResultAvailable shouldBe false
            r.ratingResult.shouldBeNull()
        }

        test("exactly five responses disclose the result with a complete 0..10 distribution") {
            val r = decide(option(position = 0, values = listOf(0, 1, 2, 3, 10)), option(position = 1, values = listOf(5, 5, 5, 5, 5)))
            r.ratingResultAvailable shouldBe true
            val first =
                r.ratingResult
                    .shouldNotBeNull()
                    .options
                    .first { it.cumulativeResistance == 16 }
            first.distribution.keys shouldBe (0..10).toSet()
            first.topValueCount shouldBe 1
            first.maxResistance shouldBe 10
            first.strongObjection shouldBe true
        }

        test("strong objection starts at 9") {
            val r =
                decide(
                    option(position = 0, values = listOf(0, 0, 0, 0, 8)),
                    option(position = 1, values = listOf(0, 0, 0, 0, 9)),
                    kind = PollKind.SK_PRIORITY,
                )
            val byKw = r.ratingResult.shouldNotBeNull().options
            byKw.first { it.maxResistance == 8 }.strongObjection shouldBe false
            byKw.first { it.maxResistance == 9 }.strongObjection shouldBe true
        }

        test("priority ranks use shared competition ranks 1,2,2,4 and keep the creator's order inside a tie") {
            val a = option(position = 0, values = List(5) { 4 })
            val b = option(position = 1, values = List(5) { 1 })
            val c = option(position = 2, values = List(5) { 4 })
            val d = option(position = 3, values = List(5) { 9 })
            val r = decide(a, b, c, d, kind = PollKind.SK_PRIORITY).ratingResult.shouldNotBeNull()
            r.outcome.shouldBeNull()
            r.options.map { it.optionId } shouldBe listOf(b, a, c, d).map { it.optionId.toString() }
            r.options.map { it.rank } shouldBe listOf(1, 2, 2, 4)
            r.options.map { it.tied } shouldBe listOf(false, true, true, false)
        }

        test("decision: a clear lowest wins") {
            val a = option(position = 0, values = List(5) { 3 })
            val p = option(position = 10, values = List(5) { 5 }, passive = true)
            val r = decide(a, option(position = 1, values = List(5) { 6 }), p).ratingResult.shouldNotBeNull()
            r.outcome shouldBe PollDecisionOutcome.OPTION_WINS
            r.winnerOptionId shouldBe a.optionId.toString()
            r.tieAtLowest shouldBe false
        }

        test("decision: the passive option wins alone and wins every tie it is part of") {
            val p = option(position = 10, values = List(5) { 1 }, passive = true)
            val alone =
                decide(
                    option(
                        position = 0,
                        values =
                            List(5) {
                                3
                            },
                    ),
                    option(position = 1, values = List(5) { 2 }),
                    p,
                ).ratingResult.shouldNotBeNull()
            alone.outcome shouldBe PollDecisionOutcome.NO_CHANGE_WINS
            alone.winnerOptionId shouldBe p.optionId.toString()
            alone.tieAtLowest shouldBe false

            val p2 = option(position = 10, values = List(5) { 2 }, passive = true)
            val tied =
                decide(
                    option(
                        position = 0,
                        values =
                            List(5) {
                                2
                            },
                    ),
                    option(position = 1, values = List(5) { 3 }),
                    p2,
                ).ratingResult.shouldNotBeNull()
            tied.outcome shouldBe PollDecisionOutcome.NO_CHANGE_WINS
            tied.winnerOptionId shouldBe p2.optionId.toString()
            tied.tieAtLowest shouldBe true
            tied.options.first().optionId shouldBe p2.optionId.toString()
        }

        test("decision: a tie of options is decided by the lowest maximum, a remaining tie is no clear result") {
            val flat = option(position = 0, values = List(5) { 2 })
            val spiky = option(position = 1, values = listOf(0, 0, 0, 0, 10))
            val p = option(position = 10, values = List(5) { 9 }, passive = true)
            val byMax = decide(flat, spiky, p).ratingResult.shouldNotBeNull()
            byMax.outcome shouldBe PollDecisionOutcome.OPTION_WINS
            byMax.winnerOptionId shouldBe flat.optionId.toString()
            byMax.decidedByLowestMax shouldBe true
            byMax.tieAtLowest shouldBe true

            val none =
                decide(
                    option(
                        position = 0,
                        values =
                            List(5) {
                                2
                            },
                    ),
                    option(position = 1, values = List(5) { 2 }),
                    option(position = 10, values = List(5) { 9 }, passive = true),
                )
            val noneRating = none.ratingResult.shouldNotBeNull()
            noneRating.outcome shouldBe PollDecisionOutcome.NO_CLEAR_RESULT
            noneRating.winnerOptionId.shouldBeNull()
        }

        test("the outcome never depends on option ids") {
            repeat(30) {
                val r =
                    decide(
                        option(position = 0, values = List(5) { 2 }),
                        option(position = 1, values = List(5) { 2 }),
                        option(position = 10, values = List(5) { 9 }, passive = true),
                    )
                r.ratingResult.shouldNotBeNull().outcome shouldBe PollDecisionOutcome.NO_CLEAR_RESULT
            }
        }

        test("an inconsistent aggregate is an integrity failure") {
            shouldThrow<IllegalStateException> {
                computePollRatingResult(
                    pollId = Uuid.random(),
                    kind = PollKind.SK_PRIORITY,
                    responseCount = 6,
                    optionsInPositionOrder =
                        listOf(
                            option(position = 0, values = List(5) { 1 }),
                            option(position = 1, values = List(6) { 1 }),
                        ),
                )
            }
        }

        test("a single choice poll is rejected") {
            shouldThrow<IllegalArgumentException> { decide(option(position = 0, values = List(5) { 1 }), kind = PollKind.SINGLE_CHOICE) }
        }
    })
