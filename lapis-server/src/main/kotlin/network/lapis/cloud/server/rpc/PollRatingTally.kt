package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.PollDecisionOutcome
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollRatingOptionResultDto
import network.lapis.cloud.shared.domain.PollRatingResultDto
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.SystemicConsensusRules
import kotlin.uuid.Uuid

/** One option's AGGREGATE (value -> count) over the stored ratings -- the only shape in which ratings leave the database layer. */
internal data class PollRatingOptionTally(
    val optionId: Uuid,
    val position: Int,
    val isPassive: Boolean,
    val distribution: Map<Int, Int>,
)

/**
 * Pure, database-free result of a consensus poll ([PollKind.SK_DECISION] / [PollKind.SK_PRIORITY]).
 *
 * Disclosure: the single threshold [PollRules.MIN_RESPONSES_FOR_RESULT] governs everything; below it only the response
 * count is returned. Per option only (never a cross-option link).
 *
 * SK_PRIORITY: ranking by cumulative resistance (KW), ties keep the creator's order, shared competition rank (1,2,2,4).
 * SK_DECISION: lowest KW wins; the passive option ("No change") wins every tie it is part of; among tied
 * non-passive options the lowest maximum value decides; a remaining tie is NO_CLEAR_RESULT. NEVER a UUID-based
 * tiebreak (an id has no meaning for the group).
 */
internal fun computePollRatingResult(
    pollId: Uuid,
    kind: PollKind,
    responseCount: Int,
    optionsInPositionOrder: List<PollRatingOptionTally>,
): PollResultDto {
    require(kind != PollKind.SINGLE_CHOICE) { "computePollRatingResult needs a consensus poll" }
    optionsInPositionOrder.forEach {
        check(it.distribution.values.sum() == responseCount) { "Inconsistent rating aggregate" }
    }
    val base =
        PollResultDto(
            pollId = pollId.toString(),
            responseCount = responseCount,
            headResultAvailable = false,
            headResult = emptyList(),
            weightedResultAvailable = false,
            weightedWithheldReason = null,
            weightedResult = emptyList(),
            kind = kind,
        )
    if (responseCount < PollRules.MIN_RESPONSES_FOR_RESULT) return base

    val scaleMax = PollRules.SK_SCALE_MAX
    val strong = SystemicConsensusRules.strongObjectionThreshold(scaleMax)
    val computed =
        optionsInPositionOrder.map {
            it to computeSkOptionResultFromDistribution(optionId = it.optionId, distribution = it.distribution, scaleMax = scaleMax)
        }
    val kwOf = computed.associate { (tally, result) -> tally.optionId to result.cumulativeResistance }

    var outcome: PollDecisionOutcome? = null
    var winner: Uuid? = null
    var tieAtLowest = false
    var decidedByLowestMax = false
    if (kind == PollKind.SK_DECISION) {
        val minKw = computed.minOf { it.second.cumulativeResistance }
        val atMin = computed.filter { it.second.cumulativeResistance == minKw }
        val passive = atMin.firstOrNull { it.first.isPassive }
        if (passive != null) {
            outcome = PollDecisionOutcome.NO_CHANGE_WINS
            winner = passive.first.optionId
            tieAtLowest = atMin.size > 1
        } else if (atMin.size == 1) {
            outcome = PollDecisionOutcome.OPTION_WINS
            winner = atMin.single().first.optionId
        } else {
            tieAtLowest = true
            val minMax = atMin.minOf { it.second.maxResistance }
            val atMinMax = atMin.filter { it.second.maxResistance == minMax }
            if (atMinMax.size == 1) {
                outcome = PollDecisionOutcome.OPTION_WINS
                winner = atMinMax.single().first.optionId
                decidedByLowestMax = true
            } else {
                outcome = PollDecisionOutcome.NO_CLEAR_RESULT
            }
        }
    }

    val sorted =
        computed.sortedWith(
            compareBy<Pair<PollRatingOptionTally, SkOptionErgebnis>> { it.second.cumulativeResistance }
                .thenBy { if (it.first.optionId == winner) 0 else 1 }
                .thenBy { if (it.first.isPassive) 0 else 1 }
                .thenBy { it.first.position },
        )
    val dtos =
        sorted.map { (tally, result) ->
            val kw = result.cumulativeResistance
            val rank = sorted.indexOfFirst { it.second.cumulativeResistance == kw } + 1
            PollRatingOptionResultDto(
                optionId = tally.optionId.toString(),
                rank = rank,
                tied = kwOf.count { it.value == kw } > 1,
                cumulativeResistance = kw,
                meanResistance = result.meanResistance,
                maxResistance = result.maxResistance,
                topValueCount = tally.distribution[scaleMax] ?: 0,
                consensusIndex = result.consensusIndex,
                strongObjection = result.maxResistance >= strong,
                distribution = (0..scaleMax).associateWith { tally.distribution[it] ?: 0 },
            )
        }
    return base.copy(
        ratingResultAvailable = true,
        ratingResult =
            PollRatingResultDto(
                options = dtos,
                outcome = outcome,
                winnerOptionId = winner?.toString(),
                tieAtLowest = tieAtLowest,
                decidedByLowestMax = decidedByLowestMax,
            ),
    )
}
