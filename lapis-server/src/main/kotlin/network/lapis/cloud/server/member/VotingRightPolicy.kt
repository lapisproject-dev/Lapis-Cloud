package network.lapis.cloud.server.member

import network.lapis.cloud.server.db.generated.MemberTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull

/**
 * Welle V1.9.307 -- the ONE definition of "this member holds the voting right NOW". Without the instance switch it is `member.voting_right`;
 * with `LAPIS_REQUIRE_EXTERNAL_ID_FOR_VOTING` it additionally needs a stored external member id. `member.voting_right` itself is never rewritten:
 * switching the option off restores the previous behaviour at once.
 *
 * Injected, never global: every electorate function, the ballot floor and the session notice take it as a parameter WITHOUT a default
 * (a forgotten call site would silently ignore the switch). [LEGACY] exists for tests and the staging seed only. A frozen electorate snapshot is
 * never recomputed, so a switch change does not act retroactively on frozen rolls.
 */
data class VotingRightPolicy(
    val requireExternalMemberId: Boolean,
) {
    /** SQL predicate; requires `MemberTable` in the query. Adds a WHERE clause only, never a projection of the id. */
    fun condition(): Op<Boolean> =
        if (requireExternalMemberId) {
            (MemberTable.votingRight eq true) and MemberTable.externalMemberId.isNotNull()
        } else {
            MemberTable.votingRight eq true
        }

    /** Kotlin twin of [condition] for a row that carries the member columns. */
    fun holds(row: ResultRow): Boolean =
        row[MemberTable.votingRight] && (!requireExternalMemberId || row[MemberTable.externalMemberId] != null)

    /** The voting right is suspended ONLY because of the missing external id (the plain right is still set). */
    fun suspendedForMissingExternalId(row: ResultRow): Boolean =
        requireExternalMemberId && row[MemberTable.votingRight] && row[MemberTable.externalMemberId] == null

    companion object {
        /** The behaviour before V1.9.307. Tests and the staging seed only. */
        val LEGACY = VotingRightPolicy(requireExternalMemberId = false)
    }
}
