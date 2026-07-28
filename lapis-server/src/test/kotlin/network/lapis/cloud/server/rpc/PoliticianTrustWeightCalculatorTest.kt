package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * Pure unit tests for [PoliticianTrustWeightCalculator] -- fed synthetic korb/balance maps
 * directly, no database, same style [LargestRemainderApportionmentTest] already establishes for
 * its own extraction target. See that calculator's KDoc for the "single shared pool, split by
 * ratio" algorithm this exercises.
 */
class PoliticianTrustWeightCalculatorTest :
    FunSpec({
        test("two politicians, unequal baskets -- pool splits proportionally, sum equals pool exactly") {
            val politicianA = Uuid.random()
            val politicianB = Uuid.random()
            val rater1 = Uuid.random()
            val rater2 = Uuid.random()
            val rater3 = Uuid.random()

            // A: 2 likes (rater1, rater2) -> korb 2. B: 1 like (rater3) -> korb 1. Total korb 3.
            val reactionsByProfile =
                mapOf(
                    politicianA to
                        listOf(
                            rater1 to PoliticianReactionValue.LIKE,
                            rater2 to PoliticianReactionValue.LIKE,
                        ),
                    politicianB to listOf(rater3 to PoliticianReactionValue.LIKE),
                )
            val raterBalances =
                mapOf(
                    rater1 to BigDecimal("30.00"),
                    rater2 to BigDecimal("30.00"),
                    rater3 to BigDecimal("30.00"),
                )
            // pool = 90.00, korb ratio 2:1 -> A gets 60.00, B gets 30.00
            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            result.getValue(politicianA).memberTrustWeight.compareTo(BigDecimal("60.00")) shouldBe 0
            result.getValue(politicianB).memberTrustWeight.compareTo(BigDecimal("30.00")) shouldBe 0
            val sum = result.values.fold(ZERO_2DP) { acc, r -> acc + r.memberTrustWeight }
            sum.compareTo(BigDecimal("90.00")) shouldBe 0
        }

        test("a rater who rated multiple politicians contributes their balance to the pool only once") {
            val politicianA = Uuid.random()
            val politicianB = Uuid.random()
            val sharedRater = Uuid.random()

            val reactionsByProfile =
                mapOf(
                    politicianA to listOf(sharedRater to PoliticianReactionValue.LIKE),
                    politicianB to listOf(sharedRater to PoliticianReactionValue.LIKE),
                )
            val raterBalances = mapOf(sharedRater to BigDecimal("50.00"))

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            // pool == 50.00 (counted once, not 100.00), split evenly 25.00/25.00 across equal korb 1/1.
            val sum = result.values.fold(ZERO_2DP) { acc, r -> acc + r.memberTrustWeight }
            sum.compareTo(BigDecimal("50.00")) shouldBe 0
        }

        test("korb is floored at zero -- more dislikes than likes never produces a negative basket or negative weight") {
            val politician = Uuid.random()
            val rater1 = Uuid.random()
            val rater2 = Uuid.random()
            val rater3 = Uuid.random()

            val reactionsByProfile =
                mapOf(
                    politician to
                        listOf(
                            rater1 to PoliticianReactionValue.LIKE,
                            rater2 to PoliticianReactionValue.DISLIKE,
                            rater3 to PoliticianReactionValue.DISLIKE,
                        ),
                )
            val raterBalances =
                mapOf(
                    rater1 to BigDecimal("10.00"),
                    rater2 to BigDecimal("10.00"),
                    rater3 to BigDecimal("10.00"),
                )

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            result.getValue(politician).memberLikeCount shouldBe 1
            result.getValue(politician).memberDislikeCount shouldBe 2
            result.getValue(politician).memberTrustWeight.compareTo(ZERO_2DP) shouldBe 0
        }

        test("a politician with an empty reaction list is still represented, with zero weight (not omitted)") {
            val politicianWithVotes = Uuid.random()
            val politicianWithNone = Uuid.random()
            val rater = Uuid.random()

            val reactionsByProfile =
                mapOf(
                    politicianWithVotes to listOf(rater to PoliticianReactionValue.LIKE),
                    politicianWithNone to emptyList(),
                )
            val raterBalances = mapOf(rater to BigDecimal("100.00"))

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            result.keys shouldBe setOf(politicianWithVotes, politicianWithNone)
            result.getValue(politicianWithNone).memberTrustWeight.compareTo(ZERO_2DP) shouldBe 0
            result.getValue(politicianWithVotes).memberTrustWeight.compareTo(BigDecimal("100.00")) shouldBe 0
        }

        test("empty input map returns an empty result") {
            PoliticianTrustWeightCalculator.computeMemberTrustWeights(emptyMap(), emptyMap()) shouldBe emptyMap()
        }

        test("a rater missing from raterBalances is treated as zero balance, defensively") {
            val politician = Uuid.random()
            val rater = Uuid.random()
            val reactionsByProfile = mapOf(politician to listOf(rater to PoliticianReactionValue.LIKE))

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, emptyMap())

            result.getValue(politician).memberTrustWeight.compareTo(ZERO_2DP) shouldBe 0
        }

        // ── computeGuestTrustWeights (guest-rating wave) ─────────────────────────
        // Interim, plain-unweighted-count weighting -- see that function's own KDoc for why it is
        // NOT run through LargestRemainderApportionment like computeMemberTrustWeights is.

        test("computeGuestTrustWeights: empty input map returns an empty result") {
            PoliticianTrustWeightCalculator.computeGuestTrustWeights(emptyMap()) shouldBe emptyMap()
        }

        test("computeGuestTrustWeights: single profile, 2 likes 1 dislike -> guestTrustWeight = 1.00") {
            val politician = Uuid.random()
            val guest1 = Uuid.random()
            val guest2 = Uuid.random()
            val guest3 = Uuid.random()
            val reactionsByProfile =
                mapOf(
                    politician to
                        listOf(
                            guest1 to PoliticianReactionValue.LIKE,
                            guest2 to PoliticianReactionValue.LIKE,
                            guest3 to PoliticianReactionValue.DISLIKE,
                        ),
                )

            val result = PoliticianTrustWeightCalculator.computeGuestTrustWeights(reactionsByProfile)

            result.getValue(politician).guestLikeCount shouldBe 2
            result.getValue(politician).guestDislikeCount shouldBe 1
            result.getValue(politician).guestTrustWeight.compareTo(BigDecimal("1.00")) shouldBe 0
        }

        test("computeGuestTrustWeights: dislikes exceeding likes floors guestTrustWeight at zero, never negative") {
            val politician = Uuid.random()
            val guest1 = Uuid.random()
            val guest2 = Uuid.random()
            val guest3 = Uuid.random()
            val reactionsByProfile =
                mapOf(
                    politician to
                        listOf(
                            guest1 to PoliticianReactionValue.LIKE,
                            guest2 to PoliticianReactionValue.DISLIKE,
                            guest3 to PoliticianReactionValue.DISLIKE,
                        ),
                )

            val result = PoliticianTrustWeightCalculator.computeGuestTrustWeights(reactionsByProfile)

            result.getValue(politician).guestLikeCount shouldBe 1
            result.getValue(politician).guestDislikeCount shouldBe 2
            result.getValue(politician).guestTrustWeight.compareTo(ZERO_2DP) shouldBe 0
        }

        test("computeGuestTrustWeights: multiple profiles are computed independently, no shared-pool interaction") {
            val politicianA = Uuid.random()
            val politicianB = Uuid.random()
            val guest1 = Uuid.random()
            val guest2 = Uuid.random()
            val guest3 = Uuid.random()
            val reactionsByProfile =
                mapOf(
                    politicianA to
                        listOf(
                            guest1 to PoliticianReactionValue.LIKE,
                            guest2 to PoliticianReactionValue.LIKE,
                        ),
                    politicianB to listOf(guest3 to PoliticianReactionValue.LIKE),
                )

            val result = PoliticianTrustWeightCalculator.computeGuestTrustWeights(reactionsByProfile)

            // Unlike computeMemberTrustWeights, there is no shared pool to split -- A's weight is
            // its own raw count (2.00), completely unaffected by B's raters or count.
            result.getValue(politicianA).guestTrustWeight.compareTo(BigDecimal("2.00")) shouldBe 0
            result.getValue(politicianB).guestTrustWeight.compareTo(BigDecimal("1.00")) shouldBe 0
        }

        test("computeGuestTrustWeights: a profile with an empty reaction list is still represented, with zero weight") {
            val politicianWithVotes = Uuid.random()
            val politicianWithNone = Uuid.random()
            val guest = Uuid.random()
            val reactionsByProfile =
                mapOf(
                    politicianWithVotes to listOf(guest to PoliticianReactionValue.LIKE),
                    politicianWithNone to emptyList(),
                )

            val result = PoliticianTrustWeightCalculator.computeGuestTrustWeights(reactionsByProfile)

            result.keys shouldBe setOf(politicianWithVotes, politicianWithNone)
            result.getValue(politicianWithNone).guestTrustWeight.compareTo(ZERO_2DP) shouldBe 0
        }

        // Regression: computeMemberTrustWeights' own formula is untouched by this wave -- only its
        // caller-side inputs are now pre-filtered by raterType before reaching it.
        test("computeMemberTrustWeights regression: unaffected by the computeGuestTrustWeights addition") {
            val politician = Uuid.random()
            val rater = Uuid.random()
            val reactionsByProfile = mapOf(politician to listOf(rater to PoliticianReactionValue.LIKE))
            val raterBalances = mapOf(rater to BigDecimal("5.00"))

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            result.getValue(politician).memberTrustWeight.compareTo(BigDecimal("5.00")) shouldBe 0
        }
    })
