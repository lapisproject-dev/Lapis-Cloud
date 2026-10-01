package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollStatus

/**
 * Welle V1.9.30 "Umfragen auf LTR-Basis" (Server): non-binding opinion polls (Stimmungsbilder),
 * structurally separate from [IElectionService] and [IGovernanceService]'s votes -- a poll is never a
 * resolution and moves no LTR (the responder's free balance is only read and snapshotted as weight).
 *
 * Roles: ADMIN/BOARD or the holder of a [network.lapis.cloud.shared.domain.COMMITTEE_RECORDING_ROLES]
 * seat in any active committee may create polls; the creator (while still entitled) or a privileged
 * member may close/abort; every ACTIVE member may respond; every ACTIVE member (or creator-capable
 * caller) may read. GUEST/FRIEND/federated guests/APPLICATION are rejected with
 * [ForbiddenException] before the poll's existence is even looked at (no existence oracle).
 *
 * Kilua transmits only the exception TYPE, not its message -- a UI that gets a [ConflictException]
 * re-reads the poll's status instead of showing the message.
 */
@RpcService
interface IPollService {
    /**
     * Creates an OPEN poll. Requires ADMIN/BOARD or a committee-leadership seat ([ForbiddenException]
     * otherwise). [BadRequestException] for invalid input (see [PollRules]); [ConflictException] if
     * [PollRules.MAX_OPEN_POLLS] effectively open polls already exist.
     */
    suspend fun createPoll(input: PollCreateInput): PollDto

    /**
     * Closes an OPEN poll ([ConflictException] if it is not effectively open, i.e. also after its
     * deadline). Creator (while still creator-capable) or ADMIN/BOARD only.
     */
    suspend fun closePoll(pollId: String): PollDto

    /**
     * Aborts an OPEN poll; its responses are kept but NEVER disclosed. Same preconditions and roles
     * as [closePoll].
     */
    suspend fun abortPoll(pollId: String): PollDto

    /** One poll. [NotFoundException] if unknown. */
    suspend fun getPoll(pollId: String): PollDto

    /**
     * Lists polls newest first. [status] filters on the EFFECTIVE status. [limit] is clamped to
     * `1..PollRules.MAX_LIST_LIMIT`; an [offset] outside `0..PollRules.MAX_LIST_OFFSET` is a [BadRequestException].
     */
    suspend fun listPolls(
        status: PollStatus? = null,
        limit: Int = PollRules.DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): List<PollDto>

    /**
     * Aggregated result of an effectively CLOSED poll ([ConflictException] while OPEN and for an
     * ABORTED poll). Never contains single responses, weights or absolute LTR sums.
     */
    suspend fun getPollResult(pollId: String): PollResultDto

    /** The caller's OWN participation state for [pollId]. */
    suspend fun getPollParticipation(pollId: String): PollParticipationDto

    /**
     * Batch variant of [getPollParticipation] for the caller's own rows only; unknown ids are left
     * out, more than [PollRules.MAX_PARTICIPATION_BATCH] ids is a [ConflictException].
     */
    suspend fun listPollParticipations(pollIds: List<String>): List<PollParticipationDto>

    /**
     * Records the caller's single response. ACTIVE members only. The result carries no weight, no
     * time and no echo of the chosen option. [ConflictException] if the poll is not open or the
     * caller already responded; [BadRequestException] if the option does not belong to the poll.
     */
    suspend fun castPollResponse(input: PollResponseInput): PollParticipationDto
}
