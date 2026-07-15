package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.SystemicConsensusBallotCastResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus

/**
 * Systemic Consensus (V0.2.5): lowest-cumulative-resistance consensus tool, structurally
 * distinct from [IGovernanceService]'s Meritokratische-Voteen path and [IElectionService]'s
 * one-person-one-vote path -- see `network.lapis.cloud.server.rpc.SystemicConsensusService` for the
 * full lifecycle (`openSystemicConsensus` -> `addOption`/`removeOption` -> `freezeOptions` ->
 * `castResistanceBallot` -> `closeRating` -> `evaluate`, with `reopenRating` as the
 * discuss-and-revote loop back to `castResistanceBallot`) and
 * `03 Bereiche/Lapis Cloud/Systemic Consensus.md` for the concept document this implements.
 *
 * Lighter-weight than [IElectionService]: no election board, no Vier-Augen-Prinzip on the tally -- a
 * SystemicConsensus is run directly by the hosting Motion's target Committee leadership (or BOARD/
 * ADMIN), since it is a consensus-finding tool, not a formal ballot. A SystemicConsensus opens on an
 * [network.lapis.cloud.shared.domain.MotionStatus.SCHEDULED] Motion exactly like
 * [IElectionService.openElection] does, and -- only when
 * [network.lapis.cloud.shared.domain.SystemicConsensusBindingness.BINDING] -- its tally is written into
 * the same resolution book [IGovernanceService.recordResolution]/[IGovernanceService.resolveMotion]/
 * [IGovernanceService.closeVote]/[IElectionService.tally] use, tagged
 * [network.lapis.cloud.shared.domain.ResolutionMode.SYSTEMIC_CONSENSUS]. A
 * [network.lapis.cloud.shared.domain.SystemicConsensusBindingness.ADVISORY] SystemicConsensus (the default)
 * never writes a Resolution -- purely advisory.
 *
 * Anonymity is a practical DB-level table-split, not cryptography -- the identical mechanism
 * [IElectionService] already uses (see `network.lapis.cloud.server.rpc.SystemicConsensusService` KDoc).
 */
@RpcService
interface ISystemicConsensusService {
    /**
     * Role: target Committee leadership (of the Motion's own target Committee) or BOARD/ADMIN.
     * Requires [network.lapis.cloud.shared.domain.MotionStatus.SCHEDULED] and no already-open/
     * -resolved SystemicConsensus for this Motion. Transitions the new SystemicConsensus to
     * [SystemicConsensusStatus.COLLECTION]. If [SystemicConsensusOpenInput.statusQuoOptionAuto], auto-inserts a
     * `SystemicConsensusOptionDto.isStatusQuoOption` status-quo option.
     */
    suspend fun openSystemicConsensus(input: SystemicConsensusOpenInput): SystemicConsensusDto

    /**
     * Role: any member eligible to participate in this SystemicConsensus (mirrors the concept
     * document's "Teilnehmer bringen Optionen ein" collection phase). Requires
     * [SystemicConsensusStatus.COLLECTION]. Rejected once
     * `SystemicConsensusService.MAX_OPTIONS_HARD` options already exist.
     */
    suspend fun addOption(
        systemicConsensusId: String,
        input: SystemicConsensusOptionInput,
    ): SystemicConsensusOptionDto

    /**
     * Role: the option's own proposer, or target Committee leadership/BOARD/ADMIN. Requires
     * [SystemicConsensusStatus.COLLECTION]. Never removes the auto-inserted status quo option option.
     */
    suspend fun removeOption(optionId: String): SystemicConsensusDto

    suspend fun listOptions(systemicConsensusId: String): List<SystemicConsensusOptionDto>

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires [SystemicConsensusStatus.COLLECTION].
     * Snapshots eligibility (frozen at this moment, mirrors [IElectionService.openVoting]) into
     * `systemic_consensus_eligible_voter` and transitions to [SystemicConsensusStatus.RATING].
     */
    suspend fun freezeOptions(systemicConsensusId: String): SystemicConsensusDto

    /**
     * Role: any member in the eligibility snapshot taken at [freezeOptions] for the current
     * [SystemicConsensusDto.round]. Requires [SystemicConsensusStatus.RATING]. The ballot must rate
     * every frozen option exactly once -- see [SystemicConsensusBallotInput] KDoc. Exactly one ballot per
     * member per ratingRound -- a second attempt is rejected, not an upsert, same rationale as
     * [IElectionService.castElectionBallot]. Enforced at the DB level, not just the application-level
     * pre-check.
     */
    suspend fun castResistanceBallot(input: SystemicConsensusBallotInput): SystemicConsensusBallotCastResultDto

    /** Role: target Committee leadership or BOARD/ADMIN. Requires [SystemicConsensusStatus.RATING]. */
    suspend fun closeRating(systemicConsensusId: String): SystemicConsensusDto

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires [SystemicConsensusStatus.CLOSED].
     * Runs [network.lapis.cloud.server.rpc.computeSystemicConsensusResult], transitions to
     * [SystemicConsensusStatus.EVALUATED], and -- only when
     * [network.lapis.cloud.shared.domain.SystemicConsensusBindingness.BINDING] and the result is resolved
     * (not [network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule.REPEAT]-tied) -- writes the
     * resulting Resolution and transitions the Motion.
     */
    suspend fun evaluate(systemicConsensusId: String): SystemicConsensusResultDto

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires
     * [SystemicConsensusStatus.CLOSED]/[SystemicConsensusStatus.EVALUATED] and
     * [network.lapis.cloud.shared.domain.SystemicConsensusDto.round] `<`
     * [network.lapis.cloud.shared.domain.SystemicConsensusDto.maxRounds]. Transitions back to
     * [SystemicConsensusStatus.RATING] with `round` incremented by one -- prior rounds' ballots are
     * retained (DSGVO retention), only the new `round`'s ballots count toward the next tally.
     */
    suspend fun reopenRating(systemicConsensusId: String): SystemicConsensusDto

    /** Role: target Committee leadership or BOARD/ADMIN. Requires the SystemicConsensus not already [SystemicConsensusStatus.EVALUATED]/[SystemicConsensusStatus.ABORTED]. */
    suspend fun abortSystemicConsensus(systemicConsensusId: String): SystemicConsensusDto

    suspend fun getSystemicConsensus(systemicConsensusId: String): SystemicConsensusDto

    suspend fun listSystemicConsensuses(
        motionId: String? = null,
        status: SystemicConsensusStatus? = null,
    ): List<SystemicConsensusDto>

    /**
     * Transparency read of every ballot cast so far in the *current* [SystemicConsensusDto.round].
     * For a [SystemicConsensusDto.secret] SystemicConsensus, `memberId`/`memberDisplayName` are always
     * `null`, and `resistances` is empty until [SystemicConsensusStatus.EVALUATED] -- see
     * [SystemicConsensusBallotDto] KDoc.
     */
    suspend fun listResistanceBallots(systemicConsensusId: String): List<SystemicConsensusBallotDto>
}
