package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Pure unit tests of [computePollResult] -- the disclosure rules and the largest-remainder shares. */
class PollTallyTest :
    FunSpec({
        val poll = Uuid.random()

        fun option(
            responses: Int,
            weighted: Int,
            weight: String,
        ) = OptionTally(optionId = Uuid.random(), responses = responses, weightedResponses = weighted, weightSum = BigDecimal(weight))

        test("head count: every option is listed (also with 0), in position order") {
            val a = option(3, 3, "30.00")
            val b = option(2, 2, "20.00")
            val c = option(0, 0, "0.00")
            val result = computePollResult(pollId = poll, optionsInPositionOrder = listOf(a, b, c))
            result.responseCount shouldBe 5
            result.headResultAvailable shouldBe true
            result.headResult.map { it.optionId to it.count } shouldBe
                listOf(a.optionId.toString() to 3, b.optionId.toString() to 2, c.optionId.toString() to 0)
        }

        test("fewer than 5 responses: no head result, but the response count; weighted withheld as TOO_FEW_RESPONSES") {
            val result = computePollResult(pollId = poll, optionsInPositionOrder = listOf(option(4, 4, "40.00"), option(0, 0, "0.00")))
            result.responseCount shouldBe 4
            result.headResultAvailable shouldBe false
            result.headResult.shouldBeEmpty()
            result.weightedResultAvailable shouldBe false
            result.weightedWithheldReason shouldBe PollWeightedWithheldReason.TOO_FEW_RESPONSES
            result.weightedResult.shouldBeEmpty()
        }

        test("weighted shares: 60/30/10 are exact and sum to 100") {
            val result =
                computePollResult(
                    pollId = poll,
                    optionsInPositionOrder = listOf(option(3, 3, "60.00"), option(3, 3, "30.00"), option(3, 3, "10.00")),
                )
            result.weightedResultAvailable shouldBe true
            result.weightedWithheldReason shouldBe null
            result.weightedResult.map { it.sharePercent } shouldBe listOf(60, 30, 10)
        }

        test("largest remainder: 1/3 each gives 34/33/33, the tie goes to the lower position") {
            val result =
                computePollResult(
                    pollId = poll,
                    optionsInPositionOrder = listOf(option(3, 3, "1.00"), option(3, 3, "1.00"), option(3, 3, "1.00")),
                )
            result.weightedResult.map { it.sharePercent } shouldBe listOf(34, 33, 33)
            result.weightedResult.sumOf { it.sharePercent } shouldBe 100
        }

        test("largest remainder picks the biggest remainder, not the first option") {
            // weights 1:1:4 -> 16.67 / 16.67 / 66.67 -> floors 16/16/66, two leftover go to the two 0.67 remainders (positions 0,1 tie, 2 is .67 too)
            val result =
                computePollResult(
                    pollId = poll,
                    optionsInPositionOrder = listOf(option(3, 3, "1.00"), option(3, 3, "1.00"), option(3, 3, "4.00")),
                )
            result.weightedResult.sumOf { it.sharePercent } shouldBe 100
            result.weightedResult.map { it.sharePercent } shouldBe listOf(17, 17, 66)
        }

        test("a weight of 0 counts fully in the head count and not at all in the weighted result") {
            val result = computePollResult(pollId = poll, optionsInPositionOrder = listOf(option(5, 0, "0.00"), option(3, 3, "9.00")))
            result.headResult.map { it.count } shouldBe listOf(5, 3)
            // option 0 has only zero-weight votes: it is not a "small weighted group" (nobody counted), it just has share 0.
            result.weightedResultAvailable shouldBe false
            result.weightedWithheldReason shouldBe PollWeightedWithheldReason.TOO_FEW_WEIGHTED_RESPONSES
        }

        test("a zero weight response next to enough weighted ones yields share 0 for its option") {
            val result =
                computePollResult(
                    pollId = poll,
                    optionsInPositionOrder = listOf(option(2, 0, "0.00"), option(3, 3, "3.00"), option(3, 3, "7.00")),
                )
            result.weightedResultAvailable shouldBe true
            result.weightedResult.map { it.sharePercent } shouldBe listOf(0, 30, 70)
        }

        test("total weight 0 withholds the weighted result as ZERO_TOTAL_WEIGHT") {
            val result = computePollResult(pollId = poll, optionsInPositionOrder = listOf(option(3, 0, "0.00"), option(3, 0, "0.00")))
            result.headResultAvailable shouldBe true
            result.weightedResultAvailable shouldBe false
            result.weightedWithheldReason shouldBe PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT
        }

        test("fewer than 5 weighted responses withholds as TOO_FEW_WEIGHTED_RESPONSES") {
            val result = computePollResult(pollId = poll, optionsInPositionOrder = listOf(option(3, 3, "3.00"), option(3, 1, "1.00")))
            result.weightedWithheldReason shouldBe PollWeightedWithheldReason.TOO_FEW_WEIGHTED_RESPONSES
        }

        test("an option with only one or two weighted votes withholds the WHOLE weighted result as SMALL_WEIGHTED_GROUP") {
            for (small in listOf(1, 2)) {
                val result =
                    computePollResult(pollId = poll, optionsInPositionOrder = listOf(option(6, 6, "60.00"), option(small, small, "5.00")))
                result.weightedResultAvailable shouldBe false
                result.weightedWithheldReason shouldBe PollWeightedWithheldReason.SMALL_WEIGHTED_GROUP
                result.weightedResult.shouldBeEmpty()
                // the head count is unaffected
                result.headResultAvailable shouldBe true
            }
        }

        test("exactly 3 weighted votes in the smallest group is enough") {
            val result = computePollResult(pollId = poll, optionsInPositionOrder = listOf(option(3, 3, "5.00"), option(3, 3, "5.00")))
            result.weightedResultAvailable shouldBe true
            result.weightedResult.map { it.sharePercent } shouldBe listOf(50, 50)
        }

        test("property: the weighted shares are always empty or sum to exactly 100, each between 0 and 100") {
            checkAll(Arb.list(Arb.int(0..5000), 2..10)) { weightsInCents ->
                val options =
                    weightsInCents.map { cents ->
                        // every option gets >= 3 weighted votes so that only the share maths is under test
                        OptionTally(
                            optionId = Uuid.random(),
                            responses = 3,
                            weightedResponses =
                                if (cents >
                                    0
                                ) {
                                    3
                                } else {
                                    0
                                },
                            weightSum = BigDecimal(cents).movePointLeft(2),
                        )
                    }
                val result = computePollResult(pollId = poll, optionsInPositionOrder = options)
                if (result.weightedResultAvailable) {
                    result.weightedResult.sumOf { it.sharePercent } shouldBe 100
                    result.weightedResult.all { it.sharePercent in 0..100 } shouldBe true
                } else {
                    result.weightedResult.shouldBeEmpty()
                }
            }
        }
    })
