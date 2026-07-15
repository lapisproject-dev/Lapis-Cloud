package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Pure property tests of [computeVickreySettlement] — the algorithmically novel, outcome-affecting
 * core of Meritokratische Voteen (V0.2.3). No DB access anywhere in this file (matches
 * [Ballot]/[Settlement] being plain data classes) — see that file's KDoc for the algorithm this
 * exercises.
 */
private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

private data class Scenario(
    val optionIds: List<Uuid>,
    val ballots: List<Ballot>,
)

/**
 * Random contested-or-uncontested-or-empty scenario: 2..4 options, 0..12 ballots, each ballot a
 * random member staking a random amount (1 cent .. 10 000.00 LTR, i.e. always > 0, matching
 * [computeVickreySettlement]'s `require` on positive stakes) into a random option. Distinct
 * `memberId` per ballot (a real caller only ever has one ballot per member via the DB unique
 * constraint + upsert; this generator mirrors that invariant).
 */
private fun scenarioArb(): Arb<Scenario> =
    arbitrary { rs ->
        val random = rs.random
        val optionIds = (0 until random.nextInt(2, 5)).map { Uuid.random() }
        val ballotCount = random.nextInt(0, 13)
        val ballots =
            (0 until ballotCount).map {
                val optionId = optionIds[random.nextInt(optionIds.size)]
                val cents = random.nextInt(1, 1_000_000)
                Ballot(memberId = Uuid.random(), optionId = optionId, stake = BigDecimal(cents).movePointLeft(2).setScale(2))
            }
        Scenario(optionIds, ballots)
    }

class VoteVickreyTest :
    FunSpec({
        test("winner charges sum exactly to secondPrice, to the cent, over random scenarios") {
            checkAll(300, scenarioArb()) { scenario ->
                val settlement = computeVickreySettlement(scenario.ballots, scenario.optionIds)
                val sum = settlement.charges.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
                sum.setScale(2) shouldBe settlement.secondPrice.setScale(2)
            }
        }

        test("no charge is ever negative") {
            checkAll(300, scenarioArb()) { scenario ->
                val settlement = computeVickreySettlement(scenario.ballots, scenario.optionIds)
                settlement.charges.values.all { it.signum() >= 0 } shouldBe true
            }
        }

        test("every losing ballot's implied charge is 0 (absent from the charges map)") {
            checkAll(300, scenarioArb()) { scenario ->
                val settlement = computeVickreySettlement(scenario.ballots, scenario.optionIds)
                val losingMemberIds =
                    scenario.ballots
                        .filter { it.optionId != settlement.winnerOptionId }
                        .map { it.memberId }
                losingMemberIds.none { it in settlement.charges } shouldBe true
            }
        }

        test("no winning ballot is ever charged more than its own stake") {
            checkAll(300, scenarioArb()) { scenario ->
                val settlement = computeVickreySettlement(scenario.ballots, scenario.optionIds)
                scenario.ballots
                    .filter { it.optionId == settlement.winnerOptionId }
                    .all { ballot -> (settlement.charges[ballot.memberId] ?: ZERO_2DP) <= ballot.stake } shouldBe true
            }
        }

        test("uncontested vote (only one option ever staked) charges every winner 0") {
            checkAll(200, Arb.int(2..4), Arb.int(1..8)) { optionCount, ballotCount ->
                val optionIds = (0 until optionCount).map { Uuid.random() }
                val onlyOption = optionIds.first()
                val ballots =
                    (0 until ballotCount).map {
                        Ballot(Uuid.random(), onlyOption, BigDecimal(100 + it).movePointLeft(2).setScale(2))
                    }
                val settlement = computeVickreySettlement(ballots, optionIds)
                settlement.winnerOptionId shouldBe onlyOption
                settlement.secondPrice shouldBe ZERO_2DP
                settlement.charges.values.all { it.signum() == 0 } shouldBe true
            }
        }

        test("exact tie between the top two options -> no winner, no charges") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val optionC = Uuid.random()
            val ballots =
                listOf(
                    Ballot(Uuid.random(), optionA, BigDecimal("50.00")),
                    Ballot(Uuid.random(), optionB, BigDecimal("50.00")),
                    Ballot(Uuid.random(), optionC, BigDecimal("10.00")),
                )
            val settlement = computeVickreySettlement(ballots, listOf(optionA, optionB, optionC))
            settlement.winnerOptionId shouldBe null
            settlement.secondPrice shouldBe ZERO_2DP
            settlement.charges shouldBe emptyMap()
        }

        test("all-abstain (zero ballots cast anywhere) -> no winner, no charges") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val settlement = computeVickreySettlement(emptyList(), listOf(optionA, optionB))
            settlement.winnerOptionId shouldBe null
            settlement.secondPrice shouldBe ZERO_2DP
            settlement.charges shouldBe emptyMap()
        }

        test("single voter, single contested option against an untouched option -> winner pays nothing") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val voter = Uuid.random()
            val ballots = listOf(Ballot(voter, optionA, BigDecimal("42.00")))
            val settlement = computeVickreySettlement(ballots, listOf(optionA, optionB))
            settlement.winnerOptionId shouldBe optionA
            settlement.secondPrice shouldBe ZERO_2DP
            settlement.charges[voter] shouldBe ZERO_2DP
        }

        test("zero balance / weight-manipulation attempt: a member cannot spoof a second ballot for more voting weight") {
            // computeVickreySettlement itself is agnostic to "who is a member" -- ballot-stuffing
            // prevention lives in GovernanceService.castVoteBallot's upsert + the DB unique
            // constraint (see GovernanceServiceTest). What this pure function DOES guarantee is
            // that a member appearing twice for the SAME option is treated as two independent
            // stakes summed into that option's total, not as some multiplied "weight" -- i.e.
            // there is no hidden amplification factor in the algorithm itself.
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val member = Uuid.random()
            val single = computeVickreySettlement(listOf(Ballot(member, optionA, BigDecimal("30.00"))), listOf(optionA, optionB))
            val doubled =
                computeVickreySettlement(
                    listOf(Ballot(member, optionA, BigDecimal("15.00")), Ballot(member, optionA, BigDecimal("15.00"))),
                    listOf(optionA, optionB),
                )
            // Same total stake on optionA either way -> same winner/secondPrice; the settlement
            // has no notion of "member identity multiplies weight", only summed stake.
            single.winnerOptionId shouldBe doubled.winnerOptionId
            single.secondPrice shouldBe doubled.secondPrice
        }

        test("a rejected (non-positive) stake is refused by the pure function's own invariant check") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val thrown =
                runCatching {
                    computeVickreySettlement(listOf(Ballot(Uuid.random(), optionA, BigDecimal.ZERO)), listOf(optionA, optionB))
                }.exceptionOrNull()
            (thrown is IllegalArgumentException) shouldBe true
        }

        test("determinism: identical input produces identical output") {
            checkAll(200, scenarioArb()) { scenario ->
                val first = computeVickreySettlement(scenario.ballots, scenario.optionIds)
                val second = computeVickreySettlement(scenario.ballots, scenario.optionIds)
                first shouldBe second
            }
        }
    })
