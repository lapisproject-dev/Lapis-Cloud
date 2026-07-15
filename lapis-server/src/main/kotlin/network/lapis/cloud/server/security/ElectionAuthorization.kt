package network.lapis.cloud.server.security

import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Authorization helpers for Demokratische Electionen (V0.2.4). [canManageElection] reuses
 * [canRecordForMeeting]'s Committee-leadership-or-privileged rule verbatim -- managing a Election's
 * lifecycle (opening it, appointing the election board, release-ing the Kandidatenliste, aborting
 * it) is the same kind of "who runs this Committee's business" decision [canRecordForMeeting]
 * already governs for Meetingen/Resolutions/Voteen. `committeeId` here is always the hosting
 * Motion's own target Committee (see `network.lapis.cloud.shared.domain.ElectionOpenInput` KDoc), not
 * necessarily [network.lapis.cloud.shared.domain.ElectionDto.targetCommitteeId].
 */
fun CurrentMember.canManageElection(committeeId: Uuid): Boolean = canRecordForMeeting(committeeId)

/**
 * election board-or-privileged gate used by `ElectionService.openVoting`/`closeVoting`/`tally` --
 * these are operational steps a BOARD/ADMIN override is expected to be able to perform, same
 * convention as every other privileged-bypass check in this codebase. Contrast with
 * [isElectionBoardMember], which deliberately does *not* bypass for privileged roles (used only by
 * `approveTally`, where the whole point is a genuine named Vier-Augen approval count).
 */
fun CurrentMember.isElectionBoard(electionId: Uuid): Boolean = isPrivileged || isElectionBoardMember(electionId)

/** Strict membership check, no privileged bypass -- see [isElectionBoard] KDoc for why. */
fun CurrentMember.isElectionBoardMember(electionId: Uuid): Boolean =
    transaction {
        ElectionBoardMemberTable
            .selectAll()
            .where { (ElectionBoardMemberTable.electionId eq electionId) and (ElectionBoardMemberTable.memberId eq memberId) }
            .count() > 0
    }

/**
 * Self-nomination eligibility for [network.lapis.cloud.shared.domain.ElectionType.SINGLE_CHOICE]/
 * [network.lapis.cloud.shared.domain.ElectionType.MULTI_CHOICE] -- mirrors [canSubmitMotion]'s
 * General Assembly branch (any [MemberStatus.AKTIV] member), since standing as a candidate
 * for a personnel election is, like submitting to the General Assembly, a broad participation
 * right of active membership, not scoped to a specific Committee's own membership. No third-party
 * nomination flow exists in this wave (self-nomination only).
 */
fun CurrentMember.canStandAsCandidate(): Boolean {
    if (isPrivileged) return true
    return transaction {
        MemberTable
            .selectAll()
            .where { (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.AKTIV) }
            .count() > 0
    }
}
