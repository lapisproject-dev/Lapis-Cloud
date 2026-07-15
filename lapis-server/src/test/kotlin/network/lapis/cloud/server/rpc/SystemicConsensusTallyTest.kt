package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import kotlin.uuid.Uuid

/**
 * Pure property tests of [computeSystemicConsensusResult] -- the algorithmically outcome-affecting
 * core of Systemic Consensus (V0.2.5), same rationale as [ElectionTallyTest] gives for
 * [computePersonnelElectionErgebnis]. No DB access anywhere in this file.
 */
private data class SystemicConsensusScenario(
    val optionIds: List<Uuid>,
    val ballots: List<SystemicConsensusBallotData>,
    val scaleMax: Int,
)

/** Random scenario: 2..6 options, 0..15 ballots, each rating every option in `0..scaleMax`. */
private fun systemicConsensusScenarioArb(): Arb<SystemicConsensusScenario> =
    arbitrary { rs ->
        val random = rs.random
        val optionCount = random.nextInt(2, 7)
        val optionIds = (0 until optionCount).map { Uuid.random() }
        val scaleMax = random.nextInt(1, 11)
        val ballotCount = random.nextInt(0, 16)
        val ballots =
            (0 until ballotCount).map {
                SystemicConsensusBallotData(optionIds.associateWith { random.nextInt(0, scaleMax + 1) })
            }
        SystemicConsensusScenario(optionIds, ballots, scaleMax)
    }

class SystemicConsensusTallyTest :
    FunSpec({
        test("consensusIndex is always in [0,1]") {
            checkAll(300, systemicConsensusScenarioArb()) { scenario ->
                val ergebnis = computeSystemicConsensusResult(scenario.ballots, scenario.optionIds, scenario.scaleMax)
                ergebnis.optionResults.forEach { (it.consensusIndex in 0.0..1.0) shouldBe true }
            }
        }

        test("distribution counts always sum to the ballot count") {
            checkAll(300, systemicConsensusScenarioArb()) { scenario ->
                val ergebnis = computeSystemicConsensusResult(scenario.ballots, scenario.optionIds, scenario.scaleMax)
                ergebnis.optionResults.forEach { it.distribution.values.sum() shouldBe scenario.ballots.size }
            }
        }

        test("cumulativeResistance == n * meanResistance (integer/double consistency)") {
            checkAll(300, systemicConsensusScenarioArb()) { scenario ->
                val ergebnis = computeSystemicConsensusResult(scenario.ballots, scenario.optionIds, scenario.scaleMax)
                val n = scenario.ballots.size
                ergebnis.optionResults.forEach {
                    if (n == 0) {
                        it.meanResistance shouldBe 0.0
                    } else {
                        it.meanResistance shouldBe (it.cumulativeResistance.toDouble() / n plusOrMinus 1e-9)
                    }
                }
            }
        }

        test("single voter: winner is that voter's lowest-resistance option, G-K = value/scaleMax") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val optionC = Uuid.random()
            val ballot = SystemicConsensusBallotData(mapOf(optionA to 2, optionB to 7, optionC to 9))
            val ergebnis = computeSystemicConsensusResult(listOf(ballot), listOf(optionA, optionB, optionC), scaleMax = 10)
            ergebnis.winnerOptionId shouldBe optionA
            ergebnis.tie shouldBe false
            val optionAErgebnis = ergebnis.optionResults.single { it.optionId == optionA }
            optionAErgebnis.consensusIndex shouldBe 0.2
        }

        test(
            "unanimous 0 on all options: KW=0/G-K=0/consensusViable=true for every option, KW tie flagged, a deterministic winner is still picked unless REPEAT",
        ) {
            val optionIds = (0 until 4).map { Uuid.random() }
            val ballots = List(5) { SystemicConsensusBallotData(optionIds.associateWith { 0 }) }

            val niedrigsterMax =
                computeSystemicConsensusResult(
                    ballots,
                    optionIds,
                    scaleMax = 10,
                    tiebreak = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
                )
            niedrigsterMax.optionResults.forEach {
                it.cumulativeResistance shouldBe 0
                it.consensusIndex shouldBe 0.0
            }
            niedrigsterMax.tie shouldBe true
            niedrigsterMax.winnerOptionId shouldNotBe null
            niedrigsterMax.consensusViable shouldBe true

            val wiederholung =
                computeSystemicConsensusResult(ballots, optionIds, scaleMax = 10, tiebreak = SystemicConsensusTiebreakRule.REPEAT)
            wiederholung.tie shouldBe true
            wiederholung.winnerOptionId shouldBe null
        }

        test("unanimous max resistance on all options: G-K=1.0 and groupConflictWarning=true for the winner") {
            val optionIds = (0 until 3).map { Uuid.random() }
            val ballots = List(4) { SystemicConsensusBallotData(optionIds.associateWith { 10 }) }
            val ergebnis = computeSystemicConsensusResult(ballots, optionIds, scaleMax = 10)
            ergebnis.optionResults.forEach { it.consensusIndex shouldBe 1.0 }
            ergebnis.groupConflictWarning shouldBe true
            ergebnis.consensusViable shouldBe false
        }

        test("KW tie, different maxResistance: LOWEST_MAX_RESISTANCE picks the option with the lower per-option maximum") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            // Both sum to 6 across 2 ballots -- A: 3+3 (max 3), B: 0+6 (max 6).
            val ballots =
                listOf(
                    SystemicConsensusBallotData(mapOf(optionA to 3, optionB to 0)),
                    SystemicConsensusBallotData(mapOf(optionA to 3, optionB to 6)),
                )
            val ergebnis =
                computeSystemicConsensusResult(
                    ballots,
                    listOf(optionA, optionB),
                    scaleMax = 10,
                    tiebreak = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
                )
            ergebnis.tie shouldBe true
            ergebnis.tiebreakApplied shouldBe SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE
            ergebnis.winnerOptionId shouldBe optionA
        }

        test("KW tie, equal maxResistance but different stddev: LOWEST_STD_DEV picks the more uniform option") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            // Both sum to 8 across 2 ballots, both max 5 -- A: 3+5 (stddev low-ish), B: 5+3 is
            // the same distribution as A, so use a genuinely different shape: A: 4+4 (stddev 0),
            // B: 0+8 clipped -- use scaleMax 10, A: 4+4 (max 4, sum 8), B: 8+0 (max 8, sum 8).
            // To isolate stddev specifically at EQUAL max, use three ballots each: A: 3,3,2 (sum 8,
            // max 3), B: 4,3,1 (sum 8, max 4) -- still differs in max. Construct equal-max
            // instead: A: 4,4,0 (sum 8, max 4, stddev higher), B: 3,3,2 (sum 8, max... 3). Not
            // equal max either. Use four equal values vs spread with the same max deliberately:
            // A: 4,4,0,0 (sum 8, max 4), B: 4,2,2,0 (sum 8, max 4) -- equal max=4, different stddev.
            val ballots =
                listOf(
                    SystemicConsensusBallotData(mapOf(optionA to 4, optionB to 4)),
                    SystemicConsensusBallotData(mapOf(optionA to 4, optionB to 2)),
                    SystemicConsensusBallotData(mapOf(optionA to 0, optionB to 2)),
                    SystemicConsensusBallotData(mapOf(optionA to 0, optionB to 0)),
                )
            val ergebnis =
                computeSystemicConsensusResult(
                    ballots,
                    listOf(optionA, optionB),
                    scaleMax = 10,
                    tiebreak = SystemicConsensusTiebreakRule.LOWEST_STD_DEV,
                )
            val optionAErgebnis = ergebnis.optionResults.single { it.optionId == optionA }
            val optionBErgebnis = ergebnis.optionResults.single { it.optionId == optionB }
            optionAErgebnis.cumulativeResistance shouldBe optionBErgebnis.cumulativeResistance
            optionAErgebnis.maxResistance shouldBe optionBErgebnis.maxResistance
            (optionAErgebnis.standardDeviation > optionBErgebnis.standardDeviation) shouldBe true
            ergebnis.tie shouldBe true
            ergebnis.tiebreakApplied shouldBe SystemicConsensusTiebreakRule.LOWEST_STD_DEV
            ergebnis.winnerOptionId shouldBe optionB
        }

        test("KW tie under REPEAT: tie=true, winnerOptionId=null") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val ballots = listOf(SystemicConsensusBallotData(mapOf(optionA to 5, optionB to 5)))
            val ergebnis =
                computeSystemicConsensusResult(
                    ballots,
                    listOf(optionA, optionB),
                    scaleMax = 10,
                    tiebreak = SystemicConsensusTiebreakRule.REPEAT,
                )
            ergebnis.tie shouldBe true
            ergebnis.winnerOptionId shouldBe null
            ergebnis.tiebreakApplied shouldBe null
        }

        test("aggregation invariance: SUM and MEAN always produce the identical winner for the same input") {
            checkAll(300, systemicConsensusScenarioArb()) { scenario ->
                val summe =
                    computeSystemicConsensusResult(
                        scenario.ballots,
                        scenario.optionIds,
                        scenario.scaleMax,
                        aggregation = SystemicConsensusAggregation.SUM,
                    )
                val mittelvalue =
                    computeSystemicConsensusResult(
                        scenario.ballots,
                        scenario.optionIds,
                        scenario.scaleMax,
                        aggregation = SystemicConsensusAggregation.MEAN,
                    )
                summe.winnerOptionId shouldBe mittelvalue.winnerOptionId
                summe.tie shouldBe mittelvalue.tie
            }
        }

        test("zero ballots: noRatings=true, tie=true, winnerOptionId=null, no divide-by-zero") {
            val optionIds = (0 until 3).map { Uuid.random() }
            val ergebnis = computeSystemicConsensusResult(emptyList(), optionIds, scaleMax = 10)
            ergebnis.noRatings shouldBe true
            ergebnis.tie shouldBe true
            ergebnis.winnerOptionId shouldBe null
            ergebnis.consensusViable shouldBe false
            ergebnis.groupConflictWarning shouldBe false
            ergebnis.optionResults.forEach {
                it.meanResistance shouldBe 0.0
                it.consensusIndex shouldBe 0.0
                it.standardDeviation shouldBe 0.0
            }
        }

        test("rejects an empty option list, duplicate optionIds, an invalid scaleMax, and out-of-range thresholds") {
            val optionA = Uuid.random()
            (
                runCatching {
                    computeSystemicConsensusResult(
                        emptyList(),
                        emptyList(),
                        10,
                    )
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe
                true
            (
                runCatching {
                    computeSystemicConsensusResult(emptyList(), listOf(optionA, optionA), 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
            (
                runCatching {
                    computeSystemicConsensusResult(emptyList(), listOf(optionA), 0)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
            (
                runCatching {
                    computeSystemicConsensusResult(emptyList(), listOf(optionA), 10, groupConflictViableThreshold = 1.5)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
            (
                runCatching {
                    computeSystemicConsensusResult(emptyList(), listOf(optionA), 10, groupConflictWarnThreshold = -0.1)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
        }

        test("rejects a ballot that is missing an option or rates an option outside the frozen set") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val missingOption = SystemicConsensusBallotData(mapOf(optionA to 3))
            (
                runCatching {
                    computeSystemicConsensusResult(listOf(missingOption), listOf(optionA, optionB), 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true

            val extraOption = SystemicConsensusBallotData(mapOf(optionA to 3, optionB to 2, Uuid.random() to 1))
            (
                runCatching {
                    computeSystemicConsensusResult(listOf(extraOption), listOf(optionA, optionB), 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
        }

        test("rejects a resistance value outside 0..scaleMax") {
            val optionA = Uuid.random()
            val tooHigh = SystemicConsensusBallotData(mapOf(optionA to 11))
            (
                runCatching {
                    computeSystemicConsensusResult(listOf(tooHigh), listOf(optionA), scaleMax = 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true

            val negative = SystemicConsensusBallotData(mapOf(optionA to -1))
            (
                runCatching {
                    computeSystemicConsensusResult(listOf(negative), listOf(optionA), scaleMax = 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
        }

        test("determinism: identical input produces identical output") {
            checkAll(200, systemicConsensusScenarioArb()) { scenario ->
                val first = computeSystemicConsensusResult(scenario.ballots, scenario.optionIds, scenario.scaleMax)
                val second = computeSystemicConsensusResult(scenario.ballots, scenario.optionIds, scenario.scaleMax)
                first shouldBe second
            }
        }
    })
