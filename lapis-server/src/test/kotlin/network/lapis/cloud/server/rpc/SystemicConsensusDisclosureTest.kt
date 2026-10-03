package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import network.lapis.cloud.shared.domain.DisclosureRules
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import kotlin.uuid.Uuid

/**
 * V1.9.42 -- the pure part of the minimum-participation rule for anonymous consensus: which fields survive the
 * withholding, and that the decision fields are never touched by it.
 */
class SystemicConsensusDisclosureTest :
    FunSpec({
        val json = Json { encodeDefaults = true }

        val options = List(3) { Uuid.random() }

        fun ergebnisFor(
            n: Int,
            tiebreak: SystemicConsensusTiebreakRule = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
            equalVotes: Boolean = false,
        ): SkErgebnis =
            computeSystemicConsensusResult(
                ballots =
                    List(n) { i ->
                        SystemicConsensusBallotData(
                            resistances =
                                options.withIndex().associate { (idx, id) ->
                                    id to if (equalVotes) 4 else (idx * 3 + i) % 11
                                },
                        )
                    },
                optionIds = options,
                tiebreak = tiebreak,
            )

        fun collectKeys(element: JsonElement): Set<String> =
            when (element) {
                is JsonObject -> element.keys + element.values.flatMap { collectKeys(it) }
                is JsonArray -> element.flatMap { collectKeys(it) }.toSet()
                else -> emptySet()
            }

        fun SkErgebnis.outcome(
            n: Int,
            secret: Boolean,
        ) = SystemicConsensusOutcome(ergebnis = this, ballotCount = n, secret = secret)

        test("the withholding threshold is the shared poll threshold") {
            DisclosureRules.MIN_ANONYMOUS_RESPONSES shouldBe 5
            PollRules.MIN_RESPONSES_FOR_RESULT shouldBe DisclosureRules.MIN_ANONYMOUS_RESPONSES
        }

        test("anonymous below the minimum: no figures, decision fields identical to the full result") {
            val kId = Uuid.random()
            for (tiebreak in SystemicConsensusTiebreakRule.entries) {
                for (equalVotes in listOf(false, true)) {
                    for (n in listOf(0, 1, 2, 4)) {
                        val full = ergebnisFor(n = n, tiebreak = tiebreak, equalVotes = equalVotes)
                        val dto = full.outcome(n = n, secret = true).toResultDto(kId)
                        dto.figuresWithheld shouldBe true
                        dto.minimumResponses shouldBe 5
                        dto.optionResults.shouldBeEmpty()
                        dto.winnerOptionId shouldBe full.winnerOptionId?.toString()
                        dto.tie shouldBe full.tie
                        dto.tiebreakApplied shouldBe full.tiebreakApplied
                        dto.consensusViable shouldBe full.consensusViable
                        dto.groupConflictWarning shouldBe full.groupConflictWarning
                        dto.noRatings shouldBe full.noRatings
                    }
                }
            }
        }

        test("anonymous with exactly the minimum: every figure is delivered, field by field") {
            val kId = Uuid.random()
            val full = ergebnisFor(n = 5)
            val dto = full.outcome(n = 5, secret = true).toResultDto(kId)
            dto.figuresWithheld shouldBe false
            dto.optionResults.size shouldBe full.optionResults.size
            dto.optionResults.zip(full.optionResults).forEach { (d, e) ->
                d.optionId shouldBe e.optionId.toString()
                d.cumulativeResistance shouldBe e.cumulativeResistance
                d.meanResistance shouldBe e.meanResistance
                d.maxResistance shouldBe e.maxResistance
                d.standardDeviation shouldBe e.standardDeviation
                d.consensusIndex shouldBe e.consensusIndex
                d.distribution shouldBe e.distribution
            }
            dto.winnerOptionId shouldBe full.winnerOptionId?.toString()
        }

        test("an open (non-anonymous) consensus always shows its figures, even with one ballot") {
            val full = ergebnisFor(n = 1)
            val dto = full.outcome(n = 1, secret = false).toResultDto(Uuid.random())
            dto.figuresWithheld shouldBe false
            dto.optionResults.size shouldBe options.size
            systemicConsensusFiguresWithheld(secret = false, ballotCount = 0) shouldBe false
        }

        test("field allowlist: a withheld result serialises to exactly the decision fields, recursively") {
            val dto = ergebnisFor(n = 2).outcome(n = 2, secret = true).toResultDto(Uuid.random())
            val keys = collectKeys(json.encodeToJsonElement(SystemicConsensusResultDto.serializer(), dto))
            keys shouldBe
                setOf(
                    "systemicConsensusId",
                    "optionResults",
                    "winnerOptionId",
                    "tie",
                    "tiebreakApplied",
                    "consensusViable",
                    "groupConflictWarning",
                    "noRatings",
                    "figuresWithheld",
                    "minimumResponses",
                )
            val forbidden =
                setOf("cumulativeResistance", "meanResistance", "maxResistance", "standardDeviation", "consensusIndex", "distribution")
            keys.intersect(forbidden).shouldBeEmpty()
        }

        test("a disclosed result does carry the figure keys, so the allowlist test can actually fail") {
            val dto = ergebnisFor(n = 5).outcome(n = 5, secret = true).toResultDto(Uuid.random())
            val keys = collectKeys(json.encodeToJsonElement(SystemicConsensusResultDto.serializer(), dto))
            keys.contains("cumulativeResistance") shouldBe true
            keys.contains("distribution") shouldNotBe false
        }

        test("room voting state: a ballot carries no aggregate field, a new field must be added here deliberately") {
            val names =
                RoomBallotDto
                    .serializer()
                    .descriptor
                    .let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
                    .toSet()
            names shouldBe
                setOf(
                    "kind",
                    "id",
                    "motionId",
                    "motionTitle",
                    "title",
                    "status",
                    "secret",
                    "ownEligible",
                    "ownHasVoted",
                    "options",
                    "winnerOptionId",
                    "consensusPhase",
                )
        }
    })
