package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.ElectionAnswer
import java.math.BigInteger

/**
 * V1.9.23 -- the exact majority fraction. Pure tests, no DB. The boundary cases around 2/3 are the reason the
 * fraction exists: a whole percent cannot express it (66 % is too lenient, 67 % too strict).
 */
class ElectionMajorityTest :
    FunSpec({
        fun ballots(
            yes: Int,
            no: Int,
            abstain: Int = 0,
        ): List<ElectionAnswer> =
            List(yes) { ElectionAnswer.YES } + List(no) { ElectionAnswer.NO } + List(abstain) { ElectionAnswer.ABSTAIN }

        val twoThirds = MajorityFraction(numerator = 2, denominator = 3)
        val threeQuarters = MajorityFraction(numerator = 3, denominator = 4)
        val half = MajorityFraction(numerator = 1, denominator = 2)

        fun met(
            yes: Int,
            no: Int,
            fraction: MajorityFraction,
            abstain: Int = 0,
        ) = computeJaNeinErgebnis(ballots = ballots(yes, no, abstain), requiredMajorityPercent = 50, fraction = fraction).majorityMet

        test("2/3: 2 to 1 is enough, 66 to 33 is not, 67 to 33 is, a tie never is") {
            met(2, 1, twoThirds) shouldBe true
            met(66, 33, twoThirds) shouldBe true // 66/99 is exactly two thirds
            met(65, 33, twoThirds) shouldBe false
            met(67, 33, twoThirds) shouldBe true
            met(1, 1, twoThirds) shouldBe false
            computeJaNeinErgebnis(ballots = ballots(1, 1), requiredMajorityPercent = 50, fraction = twoThirds).tie shouldBe true
        }

        test("2/3 versus the percent path: 66 to 34 passes 66 percent but fails exactly two thirds") {
            // 66 of 100 decisive votes: 6600 >= 6600 with 66 percent, but 66 * 3 = 198 < 2 * 100 = 200 with 2/3.
            computeJaNeinErgebnis(ballots = ballots(66, 34), requiredMajorityPercent = 66).majorityMet shouldBe true
            met(66, 34, twoThirds) shouldBe false
        }

        test("3/4: 3 to 1 is enough, 2 to 1 is not") {
            met(3, 1, threeQuarters) shouldBe true
            met(2, 1, threeQuarters) shouldBe false
        }

        test("1/2: 2 to 1 is enough; 1 to 1 is a tie") {
            met(2, 1, half) shouldBe true
            met(1, 1, half) shouldBe false
        }

        test("abstentions do not change the result, and no ballots at all is not a majority") {
            met(2, 1, twoThirds, abstain = 50) shouldBe true
            met(1, 2, twoThirds, abstain = 50) shouldBe false
            met(0, 0, twoThirds, abstain = 5) shouldBe false
            met(0, 0, twoThirds) shouldBe false
        }

        test("normalize reduces by the gcd and treats null/null as the percent path") {
            ElectionMajority.normalize(numerator = 4, denominator = 6) shouldBe MajorityFraction(numerator = 2, denominator = 3)
            ElectionMajority.normalize(numerator = 50, denominator = 100) shouldBe MajorityFraction(numerator = 1, denominator = 2)
            ElectionMajority.normalize(numerator = 3, denominator = 4) shouldBe MajorityFraction(numerator = 3, denominator = 4)
            ElectionMajority.normalize(numerator = null, denominator = null) shouldBe null
        }

        test("normalize rejects below half, above one, zero, a denominator over 100 and a half-set pair") {
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = 1, denominator = 3) }
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = 101, denominator = 100) }
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = 0, denominator = 1) }
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = 1, denominator = 0) }
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = 3, denominator = null) }
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = null, denominator = 4) }
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = 51, denominator = 101) }
            shouldThrow<IllegalArgumentException> { ElectionMajority.normalize(numerator = -2, denominator = -3) }
        }

        test("the legacy display percent is ceil(numerator * 100 / denominator)") {
            ElectionMajority.percentForLegacyDisplay(half) shouldBe 50
            ElectionMajority.percentForLegacyDisplay(twoThirds) shouldBe 67
            ElectionMajority.percentForLegacyDisplay(threeQuarters) shouldBe 75
            ElectionMajority.percentForLegacyDisplay(MajorityFraction(numerator = 1, denominator = 1)) shouldBe 100
        }

        test("property: the fraction check equals an exact BigInteger rational comparison") {
            checkAll(500, Arb.int(0..60), Arb.int(0..60), Arb.int(1..100), Arb.int(1..100)) { yes, no, a, b ->
                val numerator = minOf(a, b)
                val denominator = maxOf(a, b)
                if (2 * numerator >= denominator) {
                    val decisive = yes + no
                    val expected =
                        decisive != 0 &&
                            yes != no &&
                            BigInteger.valueOf(yes.toLong()).multiply(BigInteger.valueOf(denominator.toLong())) >=
                            BigInteger.valueOf(numerator.toLong()).multiply(BigInteger.valueOf(decisive.toLong()))
                    met(yes, no, MajorityFraction(numerator = numerator, denominator = denominator)) shouldBe expected
                }
            }
        }

        test("MULTI_CHOICE-style plurality is not affected: a fraction of 1/2 behaves like 50 percent") {
            checkAll(300, Arb.int(0..40), Arb.int(0..40)) { yes, no ->
                val fromFraction = computeJaNeinErgebnis(ballots = ballots(yes, no), requiredMajorityPercent = 50, fraction = half)
                val fromPercent = computeJaNeinErgebnis(ballots = ballots(yes, no), requiredMajorityPercent = 50)
                fromFraction shouldBe fromPercent
            }
        }
    })
