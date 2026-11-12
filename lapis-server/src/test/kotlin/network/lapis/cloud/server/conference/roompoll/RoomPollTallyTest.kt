package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import network.lapis.cloud.server.rpc.OptionTally
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollResultDto
import network.lapis.cloud.shared.domain.RoomPollSplitWithheldReason
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val OPTIONS = listOf(Uuid.random(), Uuid.random(), Uuid.random())

/** [counts] per option; every answer carries [weightEach] (a member weight). */
private fun group(
    counts: List<Int>,
    weightEach: String = "0.00",
): List<OptionTally> =
    OPTIONS.mapIndexed { i, id ->
        val n = counts.getOrElse(i) { 0 }
        val w = BigDecimal(weightEach)
        OptionTally(
            optionId = id,
            responses = n,
            weightedResponses = if (w.signum() > 0) n else 0,
            weightSum = w.multiply(BigDecimal(n)),
        )
    }

private fun result(
    members: List<Int>,
    guests: List<Int>,
    mode: RoomPollCountingMode = RoomPollCountingMode.BOTH,
    memberWeight: String = "10.00",
): RoomPollResultDto =
    computeRoomPollResult(
        perGroup =
            mapOf(
                RoomPollVoterGroup.MEMBERS to group(counts = members, weightEach = memberWeight),
                RoomPollVoterGroup.GUESTS_AND_FRIENDS to group(counts = guests),
            ),
        mode = mode,
    )

/** V1.9.304 -- [computeRoomPollResult]: thresholds, complementary suppression, where the weight is shown, rounding. */
class RoomPollTallyTest :
    FunSpec({
        test("below five answers in total there are no figures at all, only the total") {
            val r = result(members = listOf(2, 1, 0), guests = listOf(1, 0, 0))
            r.totalAnswerCount shouldBe 4
            r.resultAvailable shouldBe false
            r.all shouldBe null
            r.members shouldBe null
            r.guestsAndFriends shouldBe null
        }

        test("7 members and 3 guests: only the all-together column, no group size, no split") {
            val r = result(members = listOf(4, 2, 1), guests = listOf(1, 1, 1))
            r.resultAvailable shouldBe true
            r.splitAvailable shouldBe false
            r.splitWithheldReason shouldBe RoomPollSplitWithheldReason.GROUP_BELOW_MINIMUM
            r.members shouldBe null
            r.guestsAndFriends shouldBe null
            r.all!!.answerCount shouldBe 10
            r.all!!.headSharesPercent shouldBe listOf(50, 30, 20)
        }

        test("3 members and 7 guests is symmetric") {
            val r = result(members = listOf(1, 1, 1), guests = listOf(4, 2, 1))
            r.splitAvailable shouldBe false
            r.members shouldBe null
            r.guestsAndFriends shouldBe null
            r.all!!.answerCount shouldBe 10
        }

        test("5 and 5 shows the split with each group's own size") {
            val r = result(members = listOf(5, 0, 0), guests = listOf(0, 5, 0))
            r.splitAvailable shouldBe true
            r.splitWithheldReason shouldBe null
            r.members!!.answerCount shouldBe 5
            r.members!!.headSharesPercent shouldBe listOf(100, 0, 0)
            r.guestsAndFriends!!.answerCount shouldBe 5
            r.guestsAndFriends!!.headSharesPercent shouldBe listOf(0, 100, 0)
            r.all!!.answerCount shouldBe 10
            r.all!!.headSharesPercent shouldBe listOf(50, 50, 0)
        }

        test("the weighted shares are delivered exactly once: in the members column when the split is visible, in all together otherwise") {
            val split = result(members = listOf(4, 3, 3), guests = listOf(2, 2, 1))
            split.weightedShownIn shouldBe RoomPollVoterGroup.MEMBERS
            split.members!!.weightedAvailable shouldBe true
            split.members!!.weightedSharesPercent shouldBe listOf(40, 30, 30)
            split.guestsAndFriends!!.weightedAvailable shouldBe false
            split.guestsAndFriends!!.weightedSharesPercent shouldBe emptyList()
            split.all!!.weightedAvailable shouldBe false
            split.all!!.weightedSharesPercent shouldBe emptyList()

            val merged = result(members = listOf(4, 3, 0), guests = listOf(1, 1, 1))
            merged.weightedShownIn shouldBe null
            merged.all!!.weightedAvailable shouldBe true
            // the all-together weighted shares equal the weighted shares of the members alone: the guests weigh nothing
            merged.all!!.weightedSharesPercent shouldBe listOf(57, 43, 0)
        }

        test("a guest's weight is never in a result, even when a weight were stored") {
            val r = result(members = listOf(4, 3, 3), guests = listOf(2, 2, 1))
            r.guestsAndFriends!!.weightedSharesPercent shouldBe emptyList()
            r.guestsAndFriends!!.weightedAvailable shouldBe false
        }

        test("HEAD_COUNT delivers no weight field at all, WEIGHTED delivers head shares only where no weight is shown") {
            val head = result(members = listOf(4, 3, 3), guests = listOf(2, 2, 1), mode = RoomPollCountingMode.HEAD_COUNT)
            head.weightedShownIn shouldBe null
            head.members!!.weightedAvailable shouldBe false
            head.members!!.weightedSharesPercent shouldBe emptyList()
            head.members!!.weightedWithheldReason shouldBe null
            head.members!!.headSharesPercent shouldBe listOf(40, 30, 30)

            val weighted = result(members = listOf(4, 3, 3), guests = listOf(2, 2, 1), mode = RoomPollCountingMode.WEIGHTED)
            weighted.members!!.headSharesPercent shouldBe emptyList()
            // the columns without a weighted figure keep their head count, else they would be empty
            weighted.guestsAndFriends!!.headSharesPercent shouldBe listOf(40, 40, 20)
            weighted.all!!.headSharesPercent shouldBe listOf(40, 33, 27)
            weighted.members!!.weightedSharesPercent shouldBe listOf(40, 30, 30)
        }

        test("a weighted result without any weight is withheld with the reason, the head count stays") {
            val r = result(members = listOf(4, 3, 3), guests = listOf(2, 2, 1), memberWeight = "0.00")
            r.members!!.weightedAvailable shouldBe false
            r.members!!.weightedWithheldReason shouldBe PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT
            r.members!!.headSharesPercent shouldBe listOf(40, 30, 30)
        }

        test("the shares are whole percent and sum to exactly 100 (largest remainder), abstentions count in the denominator") {
            val r = result(members = listOf(1, 1, 1), guests = listOf(1, 1, 1))
            r.splitAvailable shouldBe false
            r.all!!.headSharesPercent shouldBe listOf(34, 33, 33)
            percentShares(listOf(0, 0, 0)) shouldBe listOf(0, 0, 0)
        }

        test("property: no visible figure lets one rebuild a group of fewer than five answers") {
            checkAll(Arb.int(0..9), Arb.int(0..9), Arb.int(0..9), Arb.int(0..9)) { m1, m2, g1, g2 ->
                val r = result(members = listOf(m1, m2, 0), guests = listOf(g1, g2, 0))
                val m = m1 + m2
                val g = g1 + g2
                if (m + g < 5) {
                    r.all shouldBe null
                } else if (m < 5 || g < 5) {
                    // neither group is shown and the only count delivered is the total
                    r.members shouldBe null
                    r.guestsAndFriends shouldBe null
                    r.all!!.answerCount shouldBe m + g
                    r.splitAvailable shouldBe false
                } else {
                    r.members!!.answerCount shouldBe m
                    r.guestsAndFriends!!.answerCount shouldBe g
                }
                (r.all?.headSharesPercent?.sum() ?: 100).let { (it == 100 || it == 0) shouldBe true }
            }
        }
    })
