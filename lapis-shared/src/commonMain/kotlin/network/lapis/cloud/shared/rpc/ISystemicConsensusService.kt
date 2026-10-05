package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.SystemicConsensusBallotCastResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusParticipationDto
import network.lapis.cloud.shared.domain.SystemicConsensusReceiptVerificationDto
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

    /**
     * V1.9.39. Role: the option's own proposer, or target Committee leadership/BOARD/ADMIN. Requires
     * [SystemicConsensusStatus.COLLECTION]; never for the status quo option. [rationale] `null`/blank removes it.
     */
    suspend fun setOptionRationale(
        optionId: String,
        rationale: String?,
    ): SystemicConsensusOptionDto

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
     *
     * V1.9.42: the decision is always made from the full data, but the *disclosure* is reduced -- for an anonymous
     * consensus with fewer than [network.lapis.cloud.shared.domain.DisclosureRules.MIN_ANONYMOUS_RESPONSES] ballots
     * in the current round the result carries no figures (`figuresWithheld`, empty `optionResults`). No role
     * exception: managers and moderation get the withheld form too.
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
     * Transparency read of the named ballots of the *current* round of an **open** consensus. **Always empty for an
     * anonymous ([SystemicConsensusDto.secret]) consensus** -- in every status, at every participation, for every
     * role including BOARD/ADMIN (V1.9.44). An unknown id is a NotFound, as for [getSystemicConsensus].
     */
    suspend fun listResistanceBallots(systemicConsensusId: String): List<SystemicConsensusBallotDto>

    /**
     * V1.9.28. Role: any authenticated member. The aggregated result of the *current* round, computed by
     * the very function `evaluate` uses (so what is shown can never differ from what was recorded).
     * Requires [SystemicConsensusStatus.EVALUATED], otherwise a conflict -- before that nothing about the
     * ratings is disclosed. Results of earlier rounds are not retrievable. V1.9.42: same reduced disclosure as
     * [evaluate] below the minimum participation of an anonymous consensus.
     */
    suspend fun getSystemicConsensusResult(systemicConsensusId: String): SystemicConsensusResultDto

    /** V1.9.28. Role: any authenticated member. Only the caller's OWN participation flags -- see [SystemicConsensusParticipationDto]. */
    suspend fun getSystemicConsensusParticipation(systemicConsensusId: String): SystemicConsensusParticipationDto

    /**
     * V1.9.28. Batch variant of [getSystemicConsensusParticipation] for list views: at most 100 distinct ids
     * (otherwise a conflict), constant number of queries, own flags only.
     */
    suspend fun listSystemicConsensusParticipations(systemicConsensusIds: List<String>): List<SystemicConsensusParticipationDto>

    /**
     * V1.9.28. Role: any authenticated member. Only for a secret Systemic Consensus (otherwise a conflict).
     * Returns whether the receipt exists, in which round it was issued and whether it is part of the current
     * evaluated result. V1.9.54 (receipt-freeness): never the ratings themselves.
     */
    suspend fun verifySystemicConsensusReceipt(
        systemicConsensusId: String,
        receiptCode: String,
    ): SystemicConsensusReceiptVerificationDto
}
