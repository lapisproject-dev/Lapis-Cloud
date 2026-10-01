package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.CandidacyDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionParticipationDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionDto

/*
 * V1.9.22 -- pure, DOM-free mirror of the server's election authorization (`ElectionAuthorization.kt` and the checks inside
 * `ElectionService`), so each action of the detail view can be decided -- and unit-tested -- without a DOM. As everywhere in this
 * client the server stays the authority; a stale client state surfaces as a `ForbiddenException`/`ConflictException`.
 *
 * Three separate roles, because the server separates them:
 *  - [ElectionRoles.canManage]: leadership of the MOTION's own committee, or BOARD/ADMIN (open, appoint the election board, release
 *    the candidate list, abort, withdraw foreign candidacies) -- the same rule as `GovernanceAuthzUi.canRecordForMeeting`.
 *  - [ElectionRoles.canOperate]: an appointed election board member OR BOARD/ADMIN (open voting, close voting, tally).
 *  - [ElectionRoles.boardStrict]: an appointed election board member, nobody else -- the only role that may approve the tally; the
 *    server deliberately gives BOARD/ADMIN no bypass here (the N-of-M count must reflect distinct named board members).
 */
data class ElectionRoles(
    val canManage: Boolean,
    val boardOrAdmin: Boolean,
    val boardStrict: Boolean,
) {
    val canOperate: Boolean get() = boardOrAdmin || boardStrict
}

fun electionRoles(
    isBoardOrAdmin: Boolean,
    me: String,
    motion: MotionDto,
    roster: List<CommitteeMembershipDto>,
    p: ElectionParticipationDto,
): ElectionRoles =
    ElectionRoles(
        canManage = GovernanceAuthzUi.canRecordForMeeting(isBoardOrAdmin, me, motion.targetCommitteeId, roster),
        boardOrAdmin = isBoardOrAdmin,
        boardStrict = p.isElectionBoardMember,
    )

/** The outcome of an action gate: not offered at all, offered, or offered but disabled with a visible [reason]. */
sealed interface Gate {
    data object Hidden : Gate

    data object Enabled : Gate

    data class Disabled(
        val reason: String,
    ) : Gate
}

/** The minimum election board size the server demands before voting may open. */
const val MIN_ELECTION_BOARD_SIZE = 3

fun canAppointBoard(
    e: ElectionDto,
    roles: ElectionRoles,
): Boolean = roles.canManage && e.status == ElectionStatus.PREPARATION

fun canOpenVoting(
    e: ElectionDto,
    p: ElectionParticipationDto,
    roles: ElectionRoles,
): Gate {
    val expected = if (e.electionType == ElectionType.YES_NO) ElectionStatus.PREPARATION else ElectionStatus.CANDIDATE_LIST_RELEASED
    if (!roles.canOperate || e.status != expected) return Gate.Hidden
    val needed = maxOf(MIN_ELECTION_BOARD_SIZE, e.tallyThreshold)
    return if (p.electionBoardSize < needed) {
        Gate.Disabled(
            if (p.electionBoardSize == 0) {
                gettext("Zuerst muss ein Wahlausschuss bestellt werden.")
            } else {
                gettext("Der Wahlausschuss braucht mindestens %1 Mitglieder, es sind %2.", needed, p.electionBoardSize)
            },
        )
    } else {
        Gate.Enabled
    }
}

fun canCloseVoting(
    e: ElectionDto,
    roles: ElectionRoles,
): Boolean = roles.canOperate && e.status == ElectionStatus.OPEN

fun canApproveTally(
    e: ElectionDto,
    p: ElectionParticipationDto,
    roles: ElectionRoles,
): Boolean = roles.boardStrict && e.status == ElectionStatus.CLOSED && !p.hasApprovedTally

fun canTally(
    e: ElectionDto,
    p: ElectionParticipationDto,
    roles: ElectionRoles,
): Gate {
    if (!roles.canOperate || e.status != ElectionStatus.CLOSED) return Gate.Hidden
    val missing = p.tallyThreshold - p.tallyApprovalCount
    return if (missing > 0) {
        Gate.Disabled(
            if (missing == 1) gettext("Noch 1 Freigabe nötig.") else gettext("Noch %1 Freigaben nötig.", missing),
        )
    } else {
        Gate.Enabled
    }
}

fun canEnterBooth(
    e: ElectionDto,
    p: ElectionParticipationDto,
): Boolean = e.status == ElectionStatus.OPEN && p.eligible == true && !p.hasVoted

fun canReleaseCandidateList(
    e: ElectionDto,
    candidacies: List<CandidacyDto>,
    roles: ElectionRoles,
): Gate {
    if (!roles.canManage || e.status != ElectionStatus.PREPARATION || !isPersonnelElection(e.electionType)) return Gate.Hidden
    return if (candidacies.none { it.withdrawnAt == null }) {
        Gate.Disabled(gettext("Es gibt noch keine aktive Kandidatur."))
    } else {
        Gate.Enabled
    }
}

fun canAbort(
    e: ElectionDto,
    roles: ElectionRoles,
): Boolean = roles.canManage && e.status != ElectionStatus.TALLIED && e.status != ElectionStatus.ABORTED

fun canSubmitCandidacy(
    e: ElectionDto,
    candidacies: List<CandidacyDto>,
    me: String,
): Boolean =
    e.status == ElectionStatus.PREPARATION &&
        isPersonnelElection(e.electionType) &&
        candidacies.none { it.memberId == me && it.withdrawnAt == null }

fun canWithdrawOwn(
    e: ElectionDto,
    c: CandidacyDto,
    me: String,
): Boolean = c.memberId == me && c.withdrawnAt == null && e.status == ElectionStatus.PREPARATION

fun canWithdrawForeign(
    e: ElectionDto,
    c: CandidacyDto,
    me: String,
    roles: ElectionRoles,
): Boolean = c.memberId != me && c.withdrawnAt == null && roles.canManage && e.status == ElectionStatus.PREPARATION
