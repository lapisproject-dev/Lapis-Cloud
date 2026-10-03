package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import kotlin.math.sqrt
import kotlin.uuid.Uuid

/** Frozen copy of the pre-V1.9.41 per-option lambda body of `computeSystemicConsensusResult` -- the reference. */
private fun frozenOptionResult(
    optionId: Uuid,
    ballots: List<SystemicConsensusBallotData>,
    scaleMax: Int,
): SkOptionErgebnis {
    val n = ballots.size
    val valuee = ballots.map { it.resistances.getValue(optionId) }
    val kw = valuee.sum()
    val mittel = if (n == 0) 0.0 else kw.toDouble() / n
    val maxWert = valuee.maxOrNull() ?: 0
    val variance = if (n == 0) 0.0 else valuee.sumOf { (it - mittel) * (it - mittel) } / n
    val consensusIndex = if (n == 0) 0.0 else kw.toDouble() / (n.toDouble() * scaleMax)
    return SkOptionErgebnis(
        optionId = optionId,
        cumulativeResistance = kw,
        meanResistance = mittel,
        maxResistance = maxWert,
        standardDeviation = sqrt(variance),
        consensusIndex = consensusIndex,
        distribution = valuee.groupingBy { it }.eachCount(),
    )
}

private class RegressionScenario(
    val optionIds: List<Uuid>,
    val ballots: List<SystemicConsensusBallotData>,
    val scaleMax: Int,
)

private fun regressionScenarioArb(): Arb<RegressionScenario> =
    arbitrary { rs ->
        val random = rs.random
        val optionIds = (0 until random.nextInt(2, 7)).map { Uuid.random() }
        val scaleMax = random.nextInt(1, 11)
        val ballots =
            (0 until random.nextInt(0, 16)).map {
                SystemicConsensusBallotData(optionIds.associateWith { random.nextInt(0, scaleMax + 1) })
            }
        RegressionScenario(optionIds = optionIds, ballots = ballots, scaleMax = scaleMax)
    }

/**
 * V1.9.41: extracting `computeSkOptionResult` must not change a single bit of the proposal-consensus result (the standard
 * deviation tiebreak compares Doubles with `==`).
 */
class SystemicConsensusTallyRegressionTest :
    FunSpec({
        test("computeSystemicConsensusResult is bit-identical to the frozen reference for every tiebreak rule") {
            checkAll(300, regressionScenarioArb()) { sc ->
                SystemicConsensusTiebreakRule.entries.forEach { rule ->
                    val result =
                        computeSystemicConsensusResult(
                            ballots = sc.ballots,
                            optionIds = sc.optionIds,
                            scaleMax = sc.scaleMax,
                            tiebreak = rule,
                        )
                    result.optionResults.forEach { actual ->
                        val expected = frozenOptionResult(optionId = actual.optionId, ballots = sc.ballots, scaleMax = sc.scaleMax)
                        actual shouldBe expected
                        actual.standardDeviation.toRawBits() shouldBe expected.standardDeviation.toRawBits()
                        actual.meanResistance.toRawBits() shouldBe expected.meanResistance.toRawBits()
                    }
                }
            }
        }

        test("the distribution variant agrees with the list variant on KW, mean, max and index") {
            checkAll(300, regressionScenarioArb()) { sc ->
                sc.optionIds.forEach { optionId ->
                    val values = sc.ballots.map { it.resistances.getValue(optionId) }
                    val direct = computeSkOptionResult(optionId = optionId, values = values, scaleMax = sc.scaleMax)
                    val viaHistogram =
                        computeSkOptionResultFromDistribution(
                            optionId = optionId,
                            distribution = direct.distribution,
                            scaleMax = sc.scaleMax,
                        )
                    viaHistogram.cumulativeResistance shouldBe direct.cumulativeResistance
                    viaHistogram.meanResistance shouldBe direct.meanResistance
                    viaHistogram.maxResistance shouldBe direct.maxResistance
                    viaHistogram.consensusIndex shouldBe direct.consensusIndex
                    viaHistogram.distribution shouldBe direct.distribution
                }
            }
        }
    })
