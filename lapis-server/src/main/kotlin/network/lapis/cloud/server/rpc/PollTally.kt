package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.PollHeadOptionResultDto
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollWeightedOptionResultDto
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.uuid.Uuid

/**
 * One option's AGGREGATE over the stored responses -- the only shape in which poll responses ever
 * leave the database layer (`GROUP BY option_id`, see `PollService.getPollResult`). A single
 * response's weight is never visible to this code, let alone to a client.
 *
 * @property responses number of responses for the option (head count; a weight of 0 counts fully)
 * @property weightedResponses number of those with weight > 0
 * @property weightSum sum of the weight snapshots (never disclosed, only used for the shares)
 */
internal data class OptionTally(
    val optionId: Uuid,
    val responses: Int,
    val weightedResponses: Int,
    val weightSum: BigDecimal,
)

/**
 * Pure, database-free poll result computation (unit-testable on its own).
 *
 * Disclosure rules (anonymity protection, see `docs/architecture/polls.adoc`):
 *  - head count: only with at least [PollRules.MIN_RESPONSES_FOR_RESULT] responses (a unanimous
 *    result of 3 would reveal all 3 answers to anybody who knows the participants);
 *  - weighted result: only as WHOLE percent shares, never absolute LTR sums, and only if ALL hold:
 *    at least [PollRules.MIN_RESPONSES_FOR_RESULT] responses, a positive total weight, at least
 *    [PollRules.MIN_WEIGHTED_RESPONSES] responses with weight > 0, and EVERY option with any weighted
 *    vote has at least [PollRules.MIN_WEIGHTED_GROUP_SIZE] of them. Otherwise the weighted result is
 *    withheld ENTIRELY (never a single suppressed share, which would be derivable as "100 minus the
 *    rest") with the reason.
 *
 * Shares use the largest-remainder method on exact integer arithmetic, ties broken by option
 * position (input order), so they always sum to exactly 100.
 */
internal fun computePollResult(
    pollId: Uuid,
    optionsInPositionOrder: List<OptionTally>,
): PollResultDto {
    val responseCount = optionsInPositionOrder.sumOf { it.responses }
    val headAvailable = responseCount >= PollRules.MIN_RESPONSES_FOR_RESULT
    val head =
        if (headAvailable) {
            optionsInPositionOrder.map { PollHeadOptionResultDto(optionId = it.optionId.toString(), count = it.responses) }
        } else {
            emptyList()
        }

    val totalWeight = optionsInPositionOrder.fold(BigDecimal.ZERO) { acc, o -> acc + o.weightSum }
    val totalWeighted = optionsInPositionOrder.sumOf { it.weightedResponses }
    val withheld: PollWeightedWithheldReason? =
        when {
            responseCount < PollRules.MIN_RESPONSES_FOR_RESULT -> PollWeightedWithheldReason.TOO_FEW_RESPONSES
            totalWeight.signum() <= 0 -> PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT
            totalWeighted < PollRules.MIN_WEIGHTED_RESPONSES -> PollWeightedWithheldReason.TOO_FEW_WEIGHTED_RESPONSES
            optionsInPositionOrder.any {
                it.weightedResponses in 1 until PollRules.MIN_WEIGHTED_GROUP_SIZE
            } -> PollWeightedWithheldReason.SMALL_WEIGHTED_GROUP
            else -> null
        }

    val weighted =
        if (withheld == null) {
            weightedShares(options = optionsInPositionOrder, totalWeight = totalWeight)
        } else {
            emptyList()
        }

    return PollResultDto(
        pollId = pollId.toString(),
        responseCount = responseCount,
        headResultAvailable = headAvailable,
        headResult = head,
        weightedResultAvailable = withheld == null,
        weightedWithheldReason = withheld,
        weightedResult = weighted,
    )
}

private val HUNDRED: BigInteger = BigInteger.valueOf(100)

private fun weightedShares(
    options: List<OptionTally>,
    totalWeight: BigDecimal,
): List<PollWeightedOptionResultDto> {
    // Weights are scale-2 decimals (DECIMAL(18,2)), so the cent counts are exact integers.
    val total = totalWeight.movePointRight(2).toBigIntegerExact()

    data class Share(
        val index: Int,
        val floor: BigInteger,
        val remainder: BigInteger,
    )

    val shares =
        options.mapIndexed { index, option ->
            val numerator = option.weightSum.movePointRight(2).toBigIntegerExact() * HUNDRED
            val floor = numerator / total
            Share(index, floor, numerator - floor * total)
        }
    var leftover = (HUNDRED - shares.fold(BigInteger.ZERO) { acc, s -> acc + s.floor }).toInt()
    val result = shares.associateTo(LinkedHashMap()) { it.index to it.floor.toInt() }
    // Largest remainder first; equal remainders go to the option with the lower position.
    for (share in shares.sortedWith(compareByDescending<Share> { it.remainder }.thenBy { it.index })) {
        if (leftover <= 0) break
        result[share.index] = result.getValue(share.index) + 1
        leftover--
    }
    return options.mapIndexed { index, option ->
        PollWeightedOptionResultDto(optionId = option.optionId.toString(), sharePercent = result.getValue(index))
    }
}
