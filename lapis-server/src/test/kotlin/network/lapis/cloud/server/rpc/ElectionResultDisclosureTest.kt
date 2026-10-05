package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.DisclosureRules
import kotlin.uuid.Uuid

/** V1.9.53 -- the pure part of the minimum-participation rule for secret elections. */
class ElectionResultDisclosureTest :
    FunSpec({
        val minimum = DisclosureRules.MIN_ANONYMOUS_RESPONSES

        test("the rule uses the shared minimum, the same as polls and consensus") {
            minimum shouldBe 5
        }

        test("figures are withheld iff the election is secret and has fewer than the minimum ballots") {
            for (secret in listOf(false, true)) {
                for (ballots in listOf(0, 1, 4, 5, 6)) {
                    electionFiguresWithheld(secret = secret, ballotCount = ballots) shouldBe (secret && ballots < minimum)
                }
            }
        }

        val figures =
            ElectionOutcomeFigures(
                electionId = Uuid.random(),
                winnerOptionIds = listOf("o-1"),
                tie = false,
                majorityMet = true,
                perOptionVotes = mapOf("o-1" to 3, "o-2" to 1),
            )

        test("a withheld result keeps winners, tie and majority and drops every figure") {
            val result = disclosedElectionResult(figures = figures, secret = true, ballotCount = 4)
            result.figuresWithheld shouldBe true
            result.perOptionVotes shouldBe emptyMap()
            result.winnerOptionIds shouldBe figures.winnerOptionIds
            result.tie shouldBe figures.tie
            result.majorityMet shouldBe figures.majorityMet
            result.minimumResponses shouldBe minimum
        }

        test("a disclosed result carries the figures: at the minimum, or for an open election at any count") {
            disclosedElectionResult(figures = figures, secret = true, ballotCount = 5).perOptionVotes shouldBe figures.perOptionVotes
            disclosedElectionResult(figures = figures, secret = false, ballotCount = 1).perOptionVotes shouldBe figures.perOptionVotes
            disclosedElectionResult(figures = figures, secret = false, ballotCount = 0).figuresWithheld shouldBe false
        }
    })
