package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.ElectionAnswer
import kotlin.uuid.Uuid

/**
 * Pure property tests of [computeJaNeinErgebnis]/[computePersonnelElectionErgebnis] -- the
 * algorithmically outcome-affecting core of Demokratische Electionen (V0.2.4), same rationale as
 * [VoteVickreyTest] gives for [computeVickreySettlement]. No DB access anywhere in this
 * file.
 */
private fun jaNeinBallotsArb(): Arb<List<ElectionAnswer>> =
    arbitrary { rs ->
        val random = rs.random
        val count = random.nextInt(0, 30)
        (0 until count).map { ElectionAnswer.entries[random.nextInt(ElectionAnswer.entries.size)] }
    }

private data class PersonnelElectionScenario(
    val optionIds: List<Uuid>,
    val ballots: List<ElectionBallotData>,
    val seatCount: Int,
)

/**
 * Random contested-or-uncontested scenario: 2..6 options, `seatCount` in `1..options-1` (always
 * a genuinely contested election, never the undersubscribed shortcut), 0..15 ballots each
 * selecting 1..seatCount distinct options.
 */
private fun personenelectionScenarioArb(): Arb<PersonnelElectionScenario> =
    arbitrary { rs ->
        val random = rs.random
        val optionCount = random.nextInt(2, 7)
        val optionIds = (0 until optionCount).map { Uuid.random() }
        val seatCount = random.nextInt(1, optionCount)
        val ballotCount = random.nextInt(0, 16)
        val ballots =
            (0 until ballotCount).map {
                val selectionCount = random.nextInt(1, seatCount + 1)
                ElectionBallotData(optionIds.shuffled(random).take(selectionCount))
            }
        PersonnelElectionScenario(optionIds = optionIds, ballots = ballots, seatCount = seatCount)
    }

class ElectionTallyTest :
    FunSpec({
        context("computeJaNeinErgebnis") {
            test("ja + nein + enthaltung always equals the ballot count") {
                checkAll(300, jaNeinBallotsArb(), Arb.int(1..100)) { ballots, majority ->
                    val ergebnis = computeJaNeinErgebnis(ballots = ballots, requiredMajorityPercent = majority)
                    (ergebnis.ja + ergebnis.nein + ergebnis.enthaltung) shouldBe ballots.size
                }
            }

            test("tie (including all-abstain/no-ballots) is never reported as majorityMet") {
                checkAll(300, jaNeinBallotsArb(), Arb.int(1..100)) { ballots, majority ->
                    val ergebnis = computeJaNeinErgebnis(ballots = ballots, requiredMajorityPercent = majority)
                    if (ergebnis.tie) ergebnis.majorityMet shouldBe false
                }
            }

            test("ja == nein always resolves to tie, regardless of requiredMajorityPercent") {
                checkAll(200, Arb.int(0..20)) { n ->
                    val ballots = List(n) { ElectionAnswer.YES } + List(n) { ElectionAnswer.NO }
                    val ergebnis = computeJaNeinErgebnis(ballots = ballots, requiredMajorityPercent = 50)
                    ergebnis.tie shouldBe true
                    ergebnis.majorityMet shouldBe false
                }
            }

            test("all-abstain / zero ballots cast is a tie, not a majority") {
                val ergebnisEmpty = computeJaNeinErgebnis(ballots = emptyList(), requiredMajorityPercent = 50)
                ergebnisEmpty.tie shouldBe true
                ergebnisEmpty.majorityMet shouldBe false

                val ergebnisAllAbstain = computeJaNeinErgebnis(ballots = List(5) { ElectionAnswer.ABSTAIN }, requiredMajorityPercent = 50)
                ergebnisAllAbstain.tie shouldBe true
                ergebnisAllAbstain.majorityMet shouldBe false
            }

            test("unanimous YES always meets any requiredMajorityPercent in 1..100") {
                checkAll(200, Arb.int(1..30), Arb.int(1..100)) { n, majority ->
                    val ergebnis = computeJaNeinErgebnis(ballots = List(n) { ElectionAnswer.YES }, requiredMajorityPercent = majority)
                    ergebnis.tie shouldBe false
                    ergebnis.majorityMet shouldBe true
                }
            }

            test("exact-boundary majority (e.g. 2/3 requiring 67%, or 3/5 requiring 60%) is met without float rounding error") {
                // 2 YES / 1 NO = 66.66..% decisive share; a naive floating-point compare against
                // 67% could go either way depending on rounding -- the exact-integer-arithmetic
                // contract must reject this one cleanly (66 < 67).
                val notQuiteTwoThirds =
                    computeJaNeinErgebnis(
                        ballots = listOf(ElectionAnswer.YES, ElectionAnswer.YES, ElectionAnswer.NO),
                        requiredMajorityPercent = 67,
                    )
                notQuiteTwoThirds.majorityMet shouldBe false

                // 3 YES / 2 NO = exactly 60% decisive share, requiring exactly 60% -> met.
                val exactSixty =
                    computeJaNeinErgebnis(
                        ballots = List(3) { ElectionAnswer.YES } + List(2) { ElectionAnswer.NO },
                        requiredMajorityPercent = 60,
                    )
                exactSixty.majorityMet shouldBe true
            }

            test("ABSTAIN ballots are excluded from the decisive-vote denominator") {
                // 1 YES / 0 NO / 98 ABSTAIN: decisive vote is 1-0, a unanimous (if tiny) majority.
                val ballots = listOf(ElectionAnswer.YES) + List(98) { ElectionAnswer.ABSTAIN }
                val ergebnis = computeJaNeinErgebnis(ballots = ballots, requiredMajorityPercent = 50)
                ergebnis.tie shouldBe false
                ergebnis.majorityMet shouldBe true
            }

            test("rejects an out-of-range requiredMajorityPercent") {
                val thrownTooLow =
                    runCatching {
                        computeJaNeinErgebnis(ballots = listOf(ElectionAnswer.YES), requiredMajorityPercent = 0)
                    }.exceptionOrNull()
                (thrownTooLow is IllegalArgumentException) shouldBe true
                val thrownTooHigh =
                    runCatching {
                        computeJaNeinErgebnis(ballots = listOf(ElectionAnswer.YES), requiredMajorityPercent = 101)
                    }.exceptionOrNull()
                (thrownTooHigh is IllegalArgumentException) shouldBe true
            }

            test("determinism: identical input produces identical output") {
                checkAll(200, jaNeinBallotsArb(), Arb.int(1..100)) { ballots, majority ->
                    computeJaNeinErgebnis(ballots = ballots, requiredMajorityPercent = majority) shouldBe
                        computeJaNeinErgebnis(ballots = ballots, requiredMajorityPercent = majority)
                }
            }
        }

        context("computePersonnelElectionErgebnis") {
            test("undersubscribed election (options.size <= seatCount) elects every candidate regardless of votes") {
                checkAll(200, Arb.int(1..8)) { seatCount ->
                    val optionIds = (0 until seatCount).map { Uuid.random() }
                    val ergebnis = computePersonnelElectionErgebnis(ballots = emptyList(), optionIds = optionIds, seatCount = seatCount)
                    ergebnis.tie shouldBe false
                    ergebnis.winnerOptionIds.toSet() shouldBe optionIds.toSet()
                }
            }

            test("winnerOptionIds is always a subset of optionIds, and empty exactly when tie is true") {
                checkAll(300, personenelectionScenarioArb()) { scenario ->
                    val ergebnis =
                        computePersonnelElectionErgebnis(
                            ballots = scenario.ballots,
                            optionIds = scenario.optionIds,
                            seatCount = scenario.seatCount,
                        )
                    ergebnis.winnerOptionIds.all { it in scenario.optionIds } shouldBe true
                    (ergebnis.tie == ergebnis.winnerOptionIds.isEmpty()) shouldBe true
                }
            }

            test("winnerOptionIds size is exactly seatCount whenever there is no seat-cutoff tie (contested election)") {
                checkAll(300, personenelectionScenarioArb()) { scenario ->
                    val ergebnis =
                        computePersonnelElectionErgebnis(
                            ballots = scenario.ballots,
                            optionIds = scenario.optionIds,
                            seatCount = scenario.seatCount,
                        )
                    if (!ergebnis.tie) ergebnis.winnerOptionIds.size shouldBe scenario.seatCount
                }
            }

            test("voteCounts sums to the total number of selections cast across all ballots") {
                checkAll(300, personenelectionScenarioArb()) { scenario ->
                    val ergebnis =
                        computePersonnelElectionErgebnis(
                            ballots = scenario.ballots,
                            optionIds = scenario.optionIds,
                            seatCount = scenario.seatCount,
                        )
                    ergebnis.voteCounts.values.sum() shouldBe scenario.ballots.sumOf { it.optionIds.size }
                }
            }

            test("a landslide winner (all ballots for one option) always wins uncontested of the tie boundary") {
                val optionIds = (0 until 4).map { Uuid.random() }
                val landslideOption = optionIds.first()
                val ballots = List(20) { ElectionBallotData(listOf(landslideOption)) }
                val ergebnis = computePersonnelElectionErgebnis(ballots = ballots, optionIds = optionIds, seatCount = 1)
                ergebnis.tie shouldBe false
                ergebnis.winnerOptionIds shouldBe listOf(landslideOption)
            }

            test("exact seat-cutoff tie (seatCount-th and (seatCount+1)-th candidate tied) resolves to no winners at all") {
                val optionA = Uuid.random()
                val optionB = Uuid.random()
                val optionC = Uuid.random()
                // A: 2 votes, B: 1 vote, C: 1 vote -- seatCount=1, B and C tie for the cutoff.
                val ballots =
                    List(2) { ElectionBallotData(listOf(optionA)) } +
                        listOf(ElectionBallotData(listOf(optionB)), ElectionBallotData(listOf(optionC)))
                val ergebnis =
                    computePersonnelElectionErgebnis(ballots = ballots, optionIds = listOf(optionA, optionB, optionC), seatCount = 2)
                ergebnis.tie shouldBe true
                ergebnis.winnerOptionIds shouldBe emptyList()
            }

            test("rejects seatCount < 1, an empty option list, duplicate optionIds, and a ballot selecting an unknown option") {
                val optionA = Uuid.random()
                val optionB = Uuid.random()

                (
                    runCatching {
                        computePersonnelElectionErgebnis(
                            ballots = emptyList(),
                            optionIds = listOf(optionA),
                            seatCount = 0,
                        )
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe
                    true
                (
                    runCatching {
                        computePersonnelElectionErgebnis(
                            ballots = emptyList(),
                            optionIds = emptyList(),
                            seatCount = 1,
                        )
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe
                    true
                (
                    runCatching {
                        computePersonnelElectionErgebnis(ballots = emptyList(), optionIds = listOf(optionA, optionA), seatCount = 1)
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe true
                (
                    runCatching {
                        computePersonnelElectionErgebnis(
                            ballots = listOf(ElectionBallotData(listOf(optionB))),
                            optionIds = listOf(optionA),
                            seatCount = 1,
                        )
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe true
            }

            test("rejects a ballot selecting the same option twice") {
                val optionA = Uuid.random()
                val optionB = Uuid.random()
                val thrown =
                    runCatching {
                        computePersonnelElectionErgebnis(
                            ballots = listOf(ElectionBallotData(listOf(optionA, optionA))),
                            optionIds = listOf(optionA, optionB),
                            seatCount = 1,
                        )
                    }.exceptionOrNull()
                (thrown is IllegalArgumentException) shouldBe true
            }

            test("determinism: identical input produces identical output") {
                checkAll(200, personenelectionScenarioArb()) { scenario ->
                    val first =
                        computePersonnelElectionErgebnis(
                            ballots = scenario.ballots,
                            optionIds = scenario.optionIds,
                            seatCount = scenario.seatCount,
                        )
                    val second =
                        computePersonnelElectionErgebnis(
                            ballots = scenario.ballots,
                            optionIds = scenario.optionIds,
                            seatCount = scenario.seatCount,
                        )
                    first shouldBe second
                }
            }
        }
    })
