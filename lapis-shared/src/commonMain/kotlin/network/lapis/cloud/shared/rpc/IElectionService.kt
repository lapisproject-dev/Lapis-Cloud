package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.CandidacyDto
import network.lapis.cloud.shared.domain.CandidacyInput
import network.lapis.cloud.shared.domain.ElectionBallotCastResultDto
import network.lapis.cloud.shared.domain.ElectionBallotDto
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionBoardMemberDto
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ReceiptVerificationDto

/**
 * Demokratische Electionen (V0.2.4): one-person-one-vote elections, structurally distinct from
 * [IGovernanceService]'s Meritokratische-Voteen path (LTR-weighted eBay/Vickrey basket
 * auction). Kept as its own `@RpcService` interface rather than folded into [IGovernanceService]
 * because a Election's lifecycle (election board appointment, Kandidatenliste, secret-ballot casting,
 * four-eyes-gated Tally, receipt verification) is a materially different shape than
 * Meeting/Motion/Vote's -- see `network.lapis.cloud.server.rpc.ElectionService` for the full
 * lifecycle and `03 Bereiche/Lapis Cloud/Demokratische Electionen.md` for the concept document this
 * implements.
 *
 * A Election opens on an [network.lapis.cloud.shared.domain.MotionStatus.SCHEDULED] Motion exactly
 * like [IGovernanceService.openVote] does, and its tally is written into the *same*
 * resolution book [IGovernanceService.recordResolution]/[IGovernanceService.resolveMotion]/
 * [IGovernanceService.closeVote] use, tagged
 * [network.lapis.cloud.shared.domain.ResolutionMode.DEMOCRATIC].
 *
 * Explicitly out of scope for this wave (see [network.lapis.cloud.shared.domain.ElectionType] KDoc):
 * [network.lapis.cloud.shared.domain.ElectionType.LIST_VOTE]/
 * [network.lapis.cloud.shared.domain.ElectionType.RANKED_CHOICE] (rejected by [openElection] with a
 * `network.lapis.cloud.server.rpc.ConflictException`), cryptographic ballot secrecy (a practical
 * DB-level table-split is used instead -- see `network.lapis.cloud.server.db.tables.ElectionTables`
 * KDoc), and threshold-signature Vier-Augen (modeled as a plain N-of-M approval count instead).
 */
@RpcService
interface IElectionService {
    /**
     * Role: target Committee leadership (of the Motion's own target Committee, not necessarily
     * [network.lapis.cloud.shared.domain.ElectionOpenInput.targetCommitteeId]) or BOARD/ADMIN. Requires
     * [network.lapis.cloud.shared.domain.MotionStatus.SCHEDULED] and no already-open/-resolved
     * Election for this Motion.
     */
    suspend fun openElection(input: ElectionOpenInput): ElectionDto

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires
     * [ElectionStatus.PREPARATION]. Replaces any prior appointment wholesale (idempotent
     * re-appointment before voting starts). At least 3, at most 25 distinct members. Rejects any
     * member currently an active member of [network.lapis.cloud.shared.domain.ElectionDto
     * .targetCommitteeId] when that Committee's [network.lapis.cloud.shared.domain.CommitteeType] is
     * [network.lapis.cloud.shared.domain.CommitteeType.EXECUTIVE_BOARD] (election board/Executive Board separation).
     */
    suspend fun appointElectionBoard(
        electionId: String,
        memberIds: List<String>,
    ): List<ElectionBoardMemberDto>

    suspend fun listElectionBoard(electionId: String): List<ElectionBoardMemberDto>

    /**
     * Role: self, [network.lapis.cloud.shared.domain.MemberStatus.AKTIV] (self-nomination only,
     * no third-party nomination in this wave). Requires [ElectionStatus.PREPARATION] and a
     * [network.lapis.cloud.shared.domain.ElectionType.SINGLE_CHOICE]/
     * [network.lapis.cloud.shared.domain.ElectionType.MULTI_CHOICE] Election.
     */
    suspend fun submitCandidacy(
        electionId: String,
        input: CandidacyInput,
    ): CandidacyDto

    /**
     * Role: the candidate themself while [ElectionStatus.PREPARATION], or that Election's target Committee
     * leadership/BOARD/ADMIN at any status -- mirrors
     * [IGovernanceService.withdrawMotion]'s asymmetric rule.
     */
    suspend fun withdrawCandidacy(id: String): CandidacyDto

    suspend fun listCandidacies(electionId: String): List<CandidacyDto>

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires [ElectionStatus.PREPARATION] and at
     * least one non-withdrawn Candidacy. Freezes the candidate list into
     * [network.lapis.cloud.shared.domain.ElectionOptionDto] rows.
     */
    suspend fun releaseCandidateList(electionId: String): ElectionDto

    /**
     * Role: election board member or BOARD/ADMIN. Snapshots eligibility (frozen at this moment, not
     * re-evaluated per ballot) into the Election's electorate and opens voting.
     */
    suspend fun openVoting(electionId: String): ElectionDto

    /**
     * Role: any member in the eligibility snapshot taken at [openVoting]. Requires
     * [ElectionStatus.OPEN]. Exactly one ballot per member -- a second attempt is rejected, not an
     * upsert (unlike [IGovernanceService.castVoteBallot]), because a secret ballot cannot distinguish
     * "correcting my own vote" from "someone else voting again" once identity is decoupled from
     * ballot content. Enforced at the DB level, not just the application-level pre-check -- see
     * `network.lapis.cloud.server.db.tables.ElectionTables` KDoc.
     */
    suspend fun castElectionBallot(input: ElectionBallotInput): ElectionBallotCastResultDto

    /** Role: election board member or BOARD/ADMIN. Requires [ElectionStatus.OPEN]. */
    suspend fun closeVoting(electionId: String): ElectionDto

    /**
     * Role: an actual [network.lapis.cloud.shared.domain.ElectionBoardMemberDto] member of *this* Election
     * specifically -- deliberately does not accept a BOARD/ADMIN privileged bypass here, unlike
     * every other role check in this interface, because the point of the N-of-M
     * Vier-Augen-Prinzip count is that it reflects genuinely distinct named election board
     * approvals. Requires [ElectionStatus.CLOSED] and no prior approval by the same member.
     */
    suspend fun approveTally(electionId: String): ElectionDto

    /**
     * Role: election board member or BOARD/ADMIN. Requires [ElectionStatus.CLOSED] and at least
     * [network.lapis.cloud.shared.domain.ElectionDto.tallyThreshold] [approveTally] approvals.
     * Runs the pure tally, writes the resulting Resolution, transitions the Motion, and -- for a
     * decisive personnel result -- seats the winners into
     * [network.lapis.cloud.shared.domain.ElectionDto.targetCommitteeId].
     */
    suspend fun tally(electionId: String): ElectionResultDto

    /** Role: target Committee leadership or BOARD/ADMIN. Requires the Election not already [ElectionStatus.TALLIED]/[ElectionStatus.ABORTED]. */
    suspend fun abortElection(electionId: String): ElectionDto

    suspend fun getElection(electionId: String): ElectionDto

    suspend fun listElections(
        motionId: String? = null,
        status: ElectionStatus? = null,
    ): List<ElectionDto>

    /**
     * Transparency read of every ballot cast so far. For a [network.lapis.cloud.shared.domain
     * .ElectionDto.secret] Election, `memberId`/`memberDisplayName` are always `null` in the returned
     * [ElectionBallotDto], and `selectedOptionLabels` is empty until the Election reaches
     * [ElectionStatus.TALLIED] -- see that DTO's KDoc.
     */
    suspend fun listElectionBallots(electionId: String): List<ElectionBallotDto>

    /**
     * Role: any authenticated member -- the receipt code itself is the real access gate (matches
     * the Helios-style "anyone holding the code may check" model), not the caller's identity.
     */
    suspend fun verifyReceipt(
        electionId: String,
        receiptCode: String,
    ): ReceiptVerificationDto
}
