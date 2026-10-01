package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The calling member's OWN participation flags for one election. [eligible] is `null` iff the
 * eligibility snapshot has not been taken yet (`voting_opened_at IS NULL`).
 */
internal data class OwnElectionFlags(
    val eligible: Boolean?,
    val hasVoted: Boolean,
)

/**
 * V1.9.24 -- the single place the "did I vote / am I eligible" lookup lives, shared by
 * [ElectionService.getElectionParticipation] and [ConferenceService.getRoomVotingState] so the two
 * cannot drift apart. Ballot-secrecy table separation is preserved exactly: for SECRET elections
 * "has voted" comes from `election_participation` (who voted, no link to the ballot), for open
 * elections from `election_ballot`. Only the caller's OWN rows are ever read.
 */
internal object ElectionOwnParticipation {
    /**
     * MUST run inside a transaction. At most 3 queries regardless of `electionRows.size` (`inList`),
     * each hitting a unique `(election_id, member_id)` index.
     */
    fun load(
        electionRows: List<ResultRow>,
        memberId: Uuid,
    ): Map<Uuid, OwnElectionFlags> {
        if (electionRows.isEmpty()) return emptyMap()
        val secretIds = electionRows.filter { it[ElectionTable.secret] }.map { it[ElectionTable.id] }
        val openIds = electionRows.filterNot { it[ElectionTable.secret] }.map { it[ElectionTable.id] }
        val snapshotIds =
            electionRows.filter { it[ElectionTable.votingOpenedAt] != null }.map { it[ElectionTable.id] }

        val votedSecret: Set<Uuid> =
            if (secretIds.isEmpty()) {
                emptySet()
            } else {
                ElectionParticipationTable
                    .selectAll()
                    .where {
                        (ElectionParticipationTable.electionId inList secretIds) and
                            (ElectionParticipationTable.memberId eq memberId)
                    }.map { it[ElectionParticipationTable.electionId] }
                    .toSet()
            }
        val votedOpen: Set<Uuid> =
            if (openIds.isEmpty()) {
                emptySet()
            } else {
                ElectionBallotTable
                    .selectAll()
                    .where { (ElectionBallotTable.electionId inList openIds) and (ElectionBallotTable.memberId eq memberId) }
                    .map { it[ElectionBallotTable.electionId] }
                    .toSet()
            }
        val eligibleIds: Set<Uuid> =
            if (snapshotIds.isEmpty()) {
                emptySet()
            } else {
                ElectionEligibleVoterTable
                    .selectAll()
                    .where {
                        (ElectionEligibleVoterTable.electionId inList snapshotIds) and
                            (ElectionEligibleVoterTable.memberId eq memberId)
                    }.map { it[ElectionEligibleVoterTable.electionId] }
                    .toSet()
            }
        return electionRows.associate { row ->
            val id = row[ElectionTable.id]
            id to
                OwnElectionFlags(
                    eligible = if (row[ElectionTable.votingOpenedAt] != null) id in eligibleIds else null,
                    hasVoted = if (row[ElectionTable.secret]) id in votedSecret else id in votedOpen,
                )
        }
    }
}
