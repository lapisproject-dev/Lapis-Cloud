package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One of the caller's OWN cast ballots -- no other member's stake, no aggregate/count, no vote status/outcome. */
internal data class SelfBallotSummary(
    val voteTitle: String,
    val stakeLtr: BigDecimal,
    val castAt: LocalDateTime,
)

/**
 * Welle V1.8.1 "MCP-Server" -- **deliberately NOT built on
 * [network.lapis.cloud.server.rpc.GovernanceService.listVoteBallots]**, which today returns every
 * ballot of a vote to every caller (with `memberDisplayName`/`stakeLtr` of every voter, see
 * `VoteBallotDto`) -- see implementation-plan §8/§12 "known finding, not fixed this wave". This
 * object instead queries [VoteBallotTable] directly, filtered hard on the caller's own member id --
 * never joined against the vote's status/outcome, never returning another member's row.
 * **Must run inside an already-open `transaction {}`**, same contract as [MemberReads].
 */
internal object GovernanceSelfReads {
    fun listMyBallots(
        memberId: Uuid,
        limit: Int,
    ): List<SelfBallotSummary> =
        VoteBallotTable
            .join(VoteTable, JoinType.INNER, VoteBallotTable.voteId, VoteTable.id)
            .selectAll()
            .where { VoteBallotTable.memberId eq memberId }
            .orderBy(VoteBallotTable.castAt, SortOrder.DESC)
            .limit(limit)
            .map {
                SelfBallotSummary(
                    voteTitle = it[VoteTable.title],
                    stakeLtr = it[VoteBallotTable.stakeLtr],
                    castAt = it[VoteBallotTable.castAt],
                )
            }
}
