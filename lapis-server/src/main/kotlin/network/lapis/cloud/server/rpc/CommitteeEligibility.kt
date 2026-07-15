package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The "who counts" set shared by [GovernanceService.computeQuorum] (headcount quorum),
 * [GovernanceService.castVoteBallot] (Meritokratische Voteen, V0.2.3 LTR-staking eligibility)
 * and `ElectionService.openVoting` (Demokratische Electionen, V0.2.4 eligibility snapshot) -- extracted
 * out of [GovernanceService] (where this originated in V0.2.3, factored out of
 * [GovernanceService.computeQuorum] at the time) into this standalone file so all three call
 * sites share exactly one definition of Committee/General Assembly eligibility rather than
 * risking the democratic and meritocratic paths silently drifting apart.
 *
 * For a [CommitteeType.GENERAL_ASSEMBLY]-typed Committee, eligibility is "all members with
 * [MemberStatus.AKTIV]" queried directly from [MemberTable]: syncing every member into
 * [CommitteeMembershipTable] on join/leave would be a brittle parallel bookkeeping system.
 * Known limitation of this path: unlike the Committee path (date-scoped via `since`/`until`), it
 * checks *current* [MemberStatus], not "status as of [scheduledDate]" -- an accepted
 * simplification carried over unchanged from the original V0.2.1 KDoc.
 *
 * Must run inside an already-open `transaction {}` (all call sites do).
 */
internal fun eligibleMemberIds(
    committeeRow: ResultRow,
    scheduledDate: LocalDate,
): Set<Uuid> {
    val committeeId = committeeRow[CommitteeTable.id]
    return if (committeeRow[CommitteeTable.type] == CommitteeType.GENERAL_ASSEMBLY) {
        MemberTable
            .selectAll()
            .where { MemberTable.status eq MemberStatus.AKTIV }
            .map { it[MemberTable.id] }
            .toSet()
    } else {
        CommitteeMembershipTable
            .selectAll()
            .where {
                (CommitteeMembershipTable.committeeId eq committeeId) and
                    (CommitteeMembershipTable.since lessEq scheduledDate) and
                    (
                        CommitteeMembershipTable.until.isNull() or
                            (CommitteeMembershipTable.until greaterEq scheduledDate)
                    )
            }.map { it[CommitteeMembershipTable.memberId] }
            .toSet()
    }
}
