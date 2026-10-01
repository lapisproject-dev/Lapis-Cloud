package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusParticipationTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The calling member's OWN participation flags for one Systemic Consensus in its CURRENT round.
 * [eligible] is `null` iff no eligibility snapshot exists for the current round yet.
 */
internal data class OwnConsensusFlags(
    val eligible: Boolean?,
    val hasRated: Boolean,
)

/**
 * V1.9.28 -- the single place the "did I rate / am I eligible" lookup lives, mirroring
 * [ElectionOwnParticipation]. Anonymity table separation is preserved exactly: for secret consensuses
 * "has rated" comes from `systemic_consensus_participation` (who rated, no link to the ballot), for open
 * ones from `systemic_consensus_ballot`. Only the caller's OWN rows are read, and every lookup is
 * filtered to the round of the respective row -- a rating from round 1 must not survive into round 2.
 */
internal object SystemicConsensusOwnParticipation {
    /** MUST run inside a transaction. At most 3 queries regardless of `rows.size`. */
    fun load(
        rows: List<ResultRow>,
        memberId: Uuid,
    ): Map<Uuid, OwnConsensusFlags> {
        if (rows.isEmpty()) return emptyMap()
        val secretIds = rows.filter { it[SystemicConsensusTable.secret] }.map { it[SystemicConsensusTable.id] }
        val openIds = rows.filterNot { it[SystemicConsensusTable.secret] }.map { it[SystemicConsensusTable.id] }
        val snapshotIds = rows.filter { it[SystemicConsensusTable.ratingOpenedAt] != null }.map { it[SystemicConsensusTable.id] }

        val ratedSecret: Set<Pair<Uuid, Int>> =
            if (secretIds.isEmpty()) {
                emptySet()
            } else {
                SystemicConsensusParticipationTable
                    .selectAll()
                    .where {
                        (SystemicConsensusParticipationTable.systemicConsensusId inList secretIds) and
                            (SystemicConsensusParticipationTable.memberId eq memberId)
                    }.map { it[SystemicConsensusParticipationTable.systemicConsensusId] to it[SystemicConsensusParticipationTable.round] }
                    .toSet()
            }
        val ratedOpen: Set<Pair<Uuid, Int>> =
            if (openIds.isEmpty()) {
                emptySet()
            } else {
                SystemicConsensusBallotTable
                    .selectAll()
                    .where {
                        (SystemicConsensusBallotTable.systemicConsensusId inList openIds) and
                            (SystemicConsensusBallotTable.memberId eq memberId)
                    }.map { it[SystemicConsensusBallotTable.systemicConsensusId] to it[SystemicConsensusBallotTable.round] }
                    .toSet()
            }
        val eligibleRounds: Set<Pair<Uuid, Int>> =
            if (snapshotIds.isEmpty()) {
                emptySet()
            } else {
                SystemicConsensusEligibleVoterTable
                    .selectAll()
                    .where {
                        (SystemicConsensusEligibleVoterTable.systemicConsensusId inList snapshotIds) and
                            (SystemicConsensusEligibleVoterTable.memberId eq memberId)
                    }.map { it[SystemicConsensusEligibleVoterTable.systemicConsensusId] to it[SystemicConsensusEligibleVoterTable.round] }
                    .toSet()
            }
        return rows.associate { row ->
            val id = row[SystemicConsensusTable.id]
            val key = id to row[SystemicConsensusTable.round]
            id to
                OwnConsensusFlags(
                    eligible = if (row[SystemicConsensusTable.ratingOpenedAt] != null) key in eligibleRounds else null,
                    hasRated = if (row[SystemicConsensusTable.secret]) key in ratedSecret else key in ratedOpen,
                )
        }
    }
}
