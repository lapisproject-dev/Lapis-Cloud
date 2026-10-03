package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusParticipationDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteStatus

/*
 * V1.9.28 -- pure, DOM-free mirror of the server's consensus authorization, so each action of the detail view can be decided (and unit
 * tested) without a DOM. The server stays the authority; a stale state surfaces as a Forbidden/Conflict. Unlike the elections there is
 * no client-side role arithmetic: the server computes [SystemicConsensusParticipationDto.canManage], `canProposeOptions` and `canRate`
 * (it knows the committee leadership, the live roster and the frozen snapshot), and the client only combines them with the status.
 */

fun canAddOption(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto,
): Boolean = c.status == SystemicConsensusStatus.COLLECTION && p.canProposeOptions

/** The status quo option is permanent; every other option may be removed by its proposer or by the people who manage the consensus. */
fun canRemoveOption(
    c: SystemicConsensusDto,
    option: SystemicConsensusOptionDto,
    me: String,
    p: SystemicConsensusParticipationDto,
): Boolean = c.status == SystemicConsensusStatus.COLLECTION && !option.isStatusQuoOption && (option.createdById == me || p.canManage)

/** V1.9.39: the rationale of a proposal follows the same rule as removing it (COLLECTION, never the status quo option, proposer or managers). */
fun canEditRationale(
    c: SystemicConsensusDto,
    option: SystemicConsensusOptionDto,
    me: String,
    p: SystemicConsensusParticipationDto,
): Boolean = canRemoveOption(c, option, me, p)

fun canFreeze(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto,
): Gate {
    if (!p.canManage || c.status != SystemicConsensusStatus.COLLECTION) return Gate.Hidden
    return if (c.options.isEmpty()) Gate.Disabled(gettext("Es gibt noch keine Option.")) else Gate.Enabled
}

fun canCloseRating(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto,
): Boolean = p.canManage && c.status == SystemicConsensusStatus.RATING

/** Evaluating is the only main action of a closed rating: there is no revote from CLOSED (see [ReopenOffer]). */
fun canEvaluate(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto,
): Boolean = p.canManage && c.status == SystemicConsensusStatus.CLOSED

fun canAbortConsensus(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto,
): Boolean = p.canManage && c.status != SystemicConsensusStatus.EVALUATED && c.status != SystemicConsensusStatus.ABORTED

fun canEnterConsensusBooth(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto,
): Boolean = c.status == SystemicConsensusStatus.RATING && p.canRate

/** How the revote ("discuss and rate again") is offered after an evaluation. */
enum class ReopenOffer { Hidden, Primary, Secondary }

/**
 * Offered only after an evaluation, only for a non-binding consensus (a binding one has already written its resolution, and the server
 * refuses a reopen after that) and only while rounds are left. Primary when the group conflict of the winner is above the warning
 * threshold, or when there is no winner at all (a tie without decision) -- a revote is what the result calls for then; otherwise a
 * secondary action.
 */
fun canReopen(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto,
    result: SystemicConsensusResultDto?,
): ReopenOffer {
    if (!p.canManage || c.status != SystemicConsensusStatus.EVALUATED) return ReopenOffer.Hidden
    if (c.bindingness == SystemicConsensusBindingness.BINDING || c.round >= c.maxRounds) return ReopenOffer.Hidden
    // V1.9.42: decided by the server's own booleans, never by a figure (an anonymous result below the minimum participation has none).
    return if (result != null &&
        (result.winnerOptionId == null || result.groupConflictWarning)
    ) {
        ReopenOffer.Primary
    } else {
        ReopenOffer.Secondary
    }
}

/**
 * Whether "Konsensieren eröffnen" is offered on a motion: scheduled, the viewer manages it, and nothing else decides or already owns it.
 * The server allows one non-aborted consensus per motion and refuses while an election or a vote runs.
 */
fun canOpenConsensusForMotion(
    motion: MotionDto,
    consensuses: List<SystemicConsensusDto>,
    elections: List<ElectionDto>,
    activeVote: VoteDto?,
    canManage: Boolean,
): Boolean =
    canManage &&
        motion.status == MotionStatus.SCHEDULED &&
        consensuses.none { it.status != SystemicConsensusStatus.ABORTED } &&
        elections.none { it.status != ElectionStatus.ABORTED } &&
        (activeVote == null || (activeVote.status != VoteStatus.OPEN && activeVote.status != VoteStatus.CLOSED))
