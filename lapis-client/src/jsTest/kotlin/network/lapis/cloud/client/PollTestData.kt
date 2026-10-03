package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PollDecisionOutcome
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollHeadOptionResultDto
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollOptionDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollRatingOptionResultDto
import network.lapis.cloud.shared.domain.PollRatingResultDto
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.PollWeightedOptionResultDto
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason

// V1.9.31 -- shared fixtures of the poll tests.

internal val POLL_AT = LocalDateTime(2026, 10, 1, 12, 0)

internal fun pollOptionDto(
    id: String,
    text: String,
    position: Int,
    explanation: String? = null,
    isPassive: Boolean = false,
) = PollOptionDto(id = id, position = position, text = text, explanation = explanation, isPassive = isPassive)

internal fun pollOptions() =
    listOf(
        pollOptionDto("o-a", "Ja, im Juli", 0),
        pollOptionDto("o-b", "Nein, im August", 1),
        pollOptionDto("o-c", "Egal", 2),
    )

internal fun pollDto(
    id: String = "p1",
    status: PollStatus = PollStatus.OPEN,
    question: String = "Soll das Sommerfest im Juli stattfinden?",
    description: String? = null,
    options: List<PollOptionDto> = pollOptions(),
    closesAt: LocalDateTime? = LocalDateTime(2026, 10, 8, 18, 0),
    canManage: Boolean = false,
    responseCount: Int? = null,
    createdBy: String = "Clara Chair",
    kind: PollKind = PollKind.SINGLE_CHOICE,
) = PollDto(
    id = id,
    question = question,
    description = description,
    options = options,
    status = status,
    createdAt = POLL_AT,
    createdByDisplayName = createdBy,
    closesAt = closesAt,
    closedAt = if (status == PollStatus.OPEN) null else POLL_AT,
    resultAvailable = status == PollStatus.CLOSED && (responseCount ?: 0) >= 5,
    responseCount = if (status == PollStatus.OPEN || status == PollStatus.ABORTED) null else responseCount,
    canManage = canManage,
    kind = kind,
)

internal fun pollParticipation(
    id: String = "p1",
    eligible: Boolean = true,
    hasResponded: Boolean = false,
    canRespond: Boolean = eligible && !hasResponded,
) = PollParticipationDto(pollId = id, eligible = eligible, hasResponded = hasResponded, canRespond = canRespond)

internal fun pollResult(
    pollId: String = "p1",
    responseCount: Int = 7,
    headAvailable: Boolean = true,
    head: List<Pair<String, Int>> = listOf("o-c" to 1, "o-b" to 2, "o-a" to 4),
    weightedAvailable: Boolean = true,
    reason: PollWeightedWithheldReason? = null,
    weighted: List<Pair<String, Int>> = listOf("o-b" to 30, "o-c" to 20, "o-a" to 50),
) = PollResultDto(
    pollId = pollId,
    responseCount = responseCount,
    headResultAvailable = headAvailable,
    headResult = if (headAvailable) head.map { PollHeadOptionResultDto(it.first, it.second) } else emptyList(),
    weightedResultAvailable = weightedAvailable,
    weightedWithheldReason = if (weightedAvailable) null else reason,
    weightedResult = if (weightedAvailable) weighted.map { PollWeightedOptionResultDto(it.first, it.second) } else emptyList(),
)

/** V1.9.41: the options of a consensus poll: two real ones (the first with an explanation) and, for a decision, the passive one at position 10. */
internal fun pollSkOptions(withPassive: Boolean = true) =
    listOf(
        pollOptionDto("o-a", "Im Juli", 0, explanation = "Wetter ist meist besser."),
        pollOptionDto("o-b", "Im August", 1),
    ) + if (withPassive) listOf(pollOptionDto("o-p", "No change", 10, isPassive = true)) else emptyList()

internal fun pollSkDto(
    kind: PollKind = PollKind.SK_DECISION,
    status: PollStatus = PollStatus.OPEN,
    responseCount: Int? = null,
    options: List<PollOptionDto> = pollSkOptions(withPassive = kind == PollKind.SK_DECISION),
    question: String = "Wann soll das Sommerfest stattfinden?",
) = pollDto(status = status, kind = kind, options = options, responseCount = responseCount, question = question)

internal fun pollRatingOption(
    optionId: String,
    rank: Int,
    cumulative: Int,
    mean: Double,
    max: Int = 6,
    top: Int = 0,
    index: Double = 0.3,
    tied: Boolean = false,
    distribution: Map<Int, Int> = mapOf(max to 1),
    strong: Boolean = max >= 9,
) = PollRatingOptionResultDto(
    optionId = optionId,
    rank = rank,
    tied = tied,
    cumulativeResistance = cumulative,
    meanResistance = mean,
    maxResistance = max,
    topValueCount = top,
    consensusIndex = index,
    strongObjection = strong,
    distribution = (0..10).associateWith { distribution[it] ?: 0 },
)

internal fun pollRatingResult(
    kind: PollKind = PollKind.SK_DECISION,
    responseCount: Int = 7,
    options: List<PollRatingOptionResultDto>,
    outcome: PollDecisionOutcome? = if (kind == PollKind.SK_DECISION) PollDecisionOutcome.OPTION_WINS else null,
    winner: String? = options.firstOrNull()?.optionId,
    tieAtLowest: Boolean = false,
    decidedByLowestMax: Boolean = false,
    available: Boolean = true,
) = PollResultDto(
    pollId = "p1",
    responseCount = responseCount,
    headResultAvailable = false,
    headResult = emptyList(),
    weightedResultAvailable = false,
    weightedWithheldReason = null,
    weightedResult = emptyList(),
    kind = kind,
    ratingResultAvailable = available,
    ratingResult =
        if (available) {
            PollRatingResultDto(
                options = options,
                outcome = outcome,
                winnerOptionId = winner,
                tieAtLowest = tieAtLowest,
                decidedByLowestMax = decidedByLowestMax,
            )
        } else {
            null
        },
)
