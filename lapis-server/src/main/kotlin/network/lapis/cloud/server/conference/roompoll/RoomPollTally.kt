package network.lapis.cloud.server.conference.roompoll

import network.lapis.cloud.server.rpc.OptionTally
import network.lapis.cloud.server.rpc.computePollResult
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollGroupResultDto
import network.lapis.cloud.shared.domain.RoomPollResultDto
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollSplitWithheldReason
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Pure, database-free result computation of a room poll (the ONLY place weights are summed; a weight sum never leaves this file).
 *
 * Disclosure rules (see `docs/architecture/room-polls.adoc`):
 *  - fewer than [RoomPollRules.MIN_ANSWERS_PER_GROUP] answers in total: no figures at all, only the total;
 *  - the split by group is shown only when BOTH groups have at least that many answers. Otherwise NEITHER group is shown and no group size is
 *    delivered at all (a 7/3 split would give the 3 away by subtraction from the total, so "all together" is the only column);
 *  - the LTR-weighted shares are delivered exactly once: in the members column when the split is visible, else in "all together". The guests
 *    and friends carry no weight;
 *  - the weighted shares follow the rules of [computePollResult] (at least 5 weighted answers, every option with weighted votes needs 3);
 *  - shares are whole percent by the largest-remainder method, so they sum to exactly 100.
 */
internal fun computeRoomPollResult(
    perGroup: Map<RoomPollVoterGroup, List<OptionTally>>,
    mode: RoomPollCountingMode,
): RoomPollResultDto {
    val members = perGroup[RoomPollVoterGroup.MEMBERS].orEmpty()
    val guests = perGroup[RoomPollVoterGroup.GUESTS_AND_FRIENDS].orEmpty()
    val optionIds = (members + guests).map { it.optionId }.distinct()

    // The option order is the position order the caller passed in (both lists are in it); recombine by option id.
    fun byOption(list: List<OptionTally>) = list.associateBy { it.optionId }
    val m = byOption(members)
    val g = byOption(guests)
    val all =
        optionIds.map { id ->
            val a = m[id]
            val b = g[id]
            OptionTally(
                optionId = id,
                responses = (a?.responses ?: 0) + (b?.responses ?: 0),
                weightedResponses = (a?.weightedResponses ?: 0) + (b?.weightedResponses ?: 0),
                weightSum = (a?.weightSum ?: BigDecimal.ZERO) + (b?.weightSum ?: BigDecimal.ZERO),
            )
        }
    val membersCount = members.sumOf { it.responses }
    val guestsCount = guests.sumOf { it.responses }
    val total = membersCount + guestsCount
    if (total < RoomPollRules.MIN_ANSWERS_PER_GROUP) {
        return RoomPollResultDto(totalAnswerCount = total, resultAvailable = false, splitAvailable = false)
    }
    val headWanted = mode != RoomPollCountingMode.WEIGHTED
    val weightedWanted = mode != RoomPollCountingMode.HEAD_COUNT
    val split = membersCount >= RoomPollRules.MIN_ANSWERS_PER_GROUP && guestsCount >= RoomPollRules.MIN_ANSWERS_PER_GROUP

    fun column(
        group: RoomPollVoterGroup?,
        tallies: List<OptionTally>,
        withWeighted: Boolean,
    ): RoomPollGroupResultDto {
        val n = tallies.sumOf { it.responses }
        // A column that carries no weighted figure (guests, all together beside a split) always shows its head count,
        // also in WEIGHTED mode: otherwise it would show no bar at all.
        val head = if (headWanted || !withWeighted) percentShares(tallies.map { it.responses }) else emptyList()
        if (!withWeighted) return RoomPollGroupResultDto(group = group, answerCount = n, headSharesPercent = head)
        val weighted = computePollResult(pollId = Uuid.NIL, optionsInPositionOrder = tallies)
        return RoomPollGroupResultDto(
            group = group,
            answerCount = n,
            headSharesPercent = head,
            weightedAvailable = weighted.weightedResultAvailable,
            weightedSharesPercent = weighted.weightedResult.map { it.sharePercent },
            weightedWithheldReason = weighted.weightedWithheldReason,
        )
    }

    return if (split) {
        RoomPollResultDto(
            totalAnswerCount = total,
            resultAvailable = true,
            splitAvailable = true,
            members = column(RoomPollVoterGroup.MEMBERS, members, withWeighted = weightedWanted),
            guestsAndFriends = column(RoomPollVoterGroup.GUESTS_AND_FRIENDS, guests, withWeighted = false),
            all = column(null, all, withWeighted = false),
            weightedShownIn = if (weightedWanted) RoomPollVoterGroup.MEMBERS else null,
        )
    } else {
        RoomPollResultDto(
            totalAnswerCount = total,
            resultAvailable = true,
            splitAvailable = false,
            splitWithheldReason = RoomPollSplitWithheldReason.GROUP_BELOW_MINIMUM,
            all = column(null, all, withWeighted = weightedWanted),
            weightedShownIn = null,
        )
    }
}

/** Whole percent shares of [counts] (position order), largest remainder first, ties to the lower position; sums to 100 unless all are 0. */
internal fun percentShares(counts: List<Int>): List<Int> {
    val total = counts.sum()
    if (total == 0) return counts.map { 0 }
    val floors = counts.map { it * 100 / total }
    var leftover = 100 - floors.sum()
    val result = floors.toMutableList()
    val order = counts.indices.sortedWith(compareByDescending<Int> { counts[it] * 100 % total }.thenBy { it })
    for (index in order) {
        if (leftover <= 0) break
        result[index] = result[index] + 1
        leftover--
    }
    return result
}
