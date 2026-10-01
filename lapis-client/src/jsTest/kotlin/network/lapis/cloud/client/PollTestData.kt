package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollHeadOptionResultDto
import network.lapis.cloud.shared.domain.PollOptionDto
import network.lapis.cloud.shared.domain.PollParticipationDto
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
) = PollOptionDto(id = id, position = position, text = text)

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
