package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** The calling member's OWN flags for one meritocratic vote: may they bid, and did they already. */
internal data class OwnVoteFlags(
    val eligible: Boolean,
    val hasVoted: Boolean,
)

/**
 * V1.9.27 -- counterpart of [ElectionOwnParticipation] for meritocratic votes, used by
 * [ConferenceService.getRoomVotingState]. Reads ONLY the caller's own `vote_ballot` rows and selects
 * ONLY `vote_ballot.vote_id` -- never `stake_ltr`, `settled_ltr` or `option_id`, so no bid amount can
 * ever reach the room DTO.
 *
 * Eligibility mirrors `GovernanceService.castVoteBallot` exactly: the member status must be in
 * [MemberStatusSets.ORGANIZATION_MEMBER] (the rule behind `requireActiveMembership`) AND the member must be
 * in the Committee/General-Assembly eligible set ([isCommitteeEligible]) of the vote's Sitzung.
 */
internal object VoteOwnParticipation {
    /**
     * MUST run inside a transaction. All [voteRows] belong to ONE Sitzung (a room is bound to one), so
     * the Sitzung/Committee are loaded once: at most 3 queries regardless of the row count.
     */
    fun load(
        voteRows: List<ResultRow>,
        memberId: Uuid,
        memberStatus: MemberStatus,
    ): Map<Uuid, OwnVoteFlags> {
        if (voteRows.isEmpty()) return emptyMap()
        val voteIds = voteRows.map { it[VoteTable.id] }
        val voted: Set<Uuid> =
            VoteBallotTable
                .select(VoteBallotTable.voteId)
                .where { (VoteBallotTable.voteId inList voteIds) and (VoteBallotTable.memberId eq memberId) }
                .map { it[VoteBallotTable.voteId] }
                .toSet()
        val eligible =
            memberStatus in MemberStatusSets.ORGANIZATION_MEMBER &&
                run {
                    val meetingId = voteRows.first()[VoteTable.meetingId]
                    val meetingRow =
                        MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.singleOrNull()
                    val committeeRow =
                        meetingRow?.let { m ->
                            CommitteeTable.selectAll().where { CommitteeTable.id eq m[MeetingTable.committeeId] }.singleOrNull()
                        }
                    meetingRow != null &&
                        committeeRow != null &&
                        isCommitteeEligible(
                            committeeRow = committeeRow,
                            scheduledDate = meetingRow[MeetingTable.scheduledAt].date,
                            memberId = memberId,
                        )
                }
        return voteRows.associate { row ->
            val id = row[VoteTable.id]
            id to OwnVoteFlags(eligible = eligible, hasVoted = id in voted)
        }
    }
}
