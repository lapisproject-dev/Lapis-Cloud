package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * V1.9.22: the role and gate rules of the elections detail view. The server stays the authority; these tests pin that the client
 * offers exactly the actions the server would accept -- above all that BOARD/ADMIN have NO bypass on the four-eyes approval.
 */
class ElectionAuthzUiTest {
    private fun roles(
        isBoardOrAdmin: Boolean = false,
        me: String = "m-1",
        roster: List<network.lapis.cloud.shared.domain.CommitteeMembershipDto> = emptyList(),
        isBoard: Boolean = false,
    ) = electionRoles(isBoardOrAdmin, me, motionDto(), roster, participation(isBoard = isBoard))

    @Test
    fun canManage_isCommitteeLeadershipOfTheMotionsOwnCommittee_orBoardOrAdmin() {
        assertFalse(roles().canManage)
        assertFalse(roles(roster = listOf(rosterEntry("m-1", CommitteeRole.MEMBER))).canManage)
        assertTrue(roles(roster = listOf(rosterEntry("m-1", CommitteeRole.CHAIR))).canManage)
        assertTrue(roles(roster = listOf(rosterEntry("m-1", CommitteeRole.SECRETARY))).canManage)
        assertFalse(roles(roster = listOf(rosterEntry("someone-else", CommitteeRole.CHAIR))).canManage)
        assertTrue(roles(isBoardOrAdmin = true).canManage)
    }

    @Test
    fun canOperate_isBoardMemberOrBoardOrAdmin_butBoardStrictIsTheAppointedMemberOnly() {
        assertFalse(roles().canOperate)
        assertTrue(roles(isBoard = true).canOperate)
        assertTrue(roles(isBoardOrAdmin = true).canOperate)
        assertFalse(roles(isBoardOrAdmin = true).boardStrict, "an admin is NOT an appointed election committee member")
        assertTrue(roles(isBoard = true).boardStrict)
    }

    @Test
    fun approveTally_hasNoAdminBypass_andIsOfferedOnlyOnce() {
        val closed = election(status = ElectionStatus.CLOSED)
        assertFalse(canApproveTally(closed, participation(), roles(isBoardOrAdmin = true)))
        assertTrue(canApproveTally(closed, participation(isBoard = true), roles(isBoard = true)))
        assertFalse(canApproveTally(closed, participation(isBoard = true, hasApproved = true), roles(isBoard = true)))
        assertFalse(canApproveTally(election(status = ElectionStatus.OPEN), participation(isBoard = true), roles(isBoard = true)))
    }

    @Test
    fun tally_isDisabledWithTheMissingCountUntilTheThresholdIsReached() {
        val closed = election(status = ElectionStatus.CLOSED)
        val admin = roles(isBoardOrAdmin = true)
        assertIs<Gate.Hidden>(canTally(closed, participation(), roles()))
        assertIs<Gate.Hidden>(canTally(election(status = ElectionStatus.OPEN), participation(), admin))
        assertEquals(Gate.Disabled("Noch 2 Freigaben nötig."), canTally(closed, participation(approvals = 0, threshold = 2), admin))
        assertEquals(Gate.Disabled("Noch 1 Freigabe nötig."), canTally(closed, participation(approvals = 1, threshold = 2), admin))
        assertIs<Gate.Enabled>(canTally(closed, participation(approvals = 2, threshold = 2), admin))
    }

    @Test
    fun openVoting_isDisabledWithAReasonForATooSmallOrMissingBoard() {
        val prep = election(type = ElectionType.YES_NO, status = ElectionStatus.PREPARATION)
        val admin = roles(isBoardOrAdmin = true)
        assertEquals(
            Gate.Disabled("Zuerst muss ein Wahlausschuss bestellt werden."),
            canOpenVoting(prep, participation(boardSize = 0), admin),
        )
        assertEquals(
            Gate.Disabled("Der Wahlausschuss braucht mindestens 3 Mitglieder, es sind 2."),
            canOpenVoting(prep, participation(boardSize = 2), admin),
        )
        // a threshold above the minimum raises the needed size
        assertEquals(
            Gate.Disabled("Der Wahlausschuss braucht mindestens 4 Mitglieder, es sind 3."),
            canOpenVoting(election(status = ElectionStatus.PREPARATION, tallyThreshold = 4), participation(boardSize = 3), admin),
        )
        assertIs<Gate.Enabled>(canOpenVoting(prep, participation(boardSize = 3), admin))
        assertIs<Gate.Hidden>(canOpenVoting(prep, participation(boardSize = 3), roles()))
    }

    @Test
    fun openVoting_expectsThePreparationStatusForYesNo_andTheReleasedListForPeople() {
        val admin = roles(isBoardOrAdmin = true)
        val p = participation(boardSize = 3)
        assertIs<Gate.Hidden>(canOpenVoting(election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION), p, admin))
        assertIs<Gate.Enabled>(
            canOpenVoting(election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.CANDIDATE_LIST_RELEASED), p, admin),
        )
        assertIs<Gate.Hidden>(
            canOpenVoting(election(type = ElectionType.YES_NO, status = ElectionStatus.CANDIDATE_LIST_RELEASED), p, admin),
        )
    }

    @Test
    fun releaseCandidateList_needsAnActiveCandidacy_andAPeopleElection() {
        val manager = roles(isBoardOrAdmin = true)
        val prep = election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION)
        assertIs<Gate.Disabled>(canReleaseCandidateList(prep, emptyList(), manager))
        assertIs<Gate.Disabled>(canReleaseCandidateList(prep, listOf(candidacy("k1", "m-1", "A", withdrawn = true)), manager))
        assertIs<Gate.Enabled>(canReleaseCandidateList(prep, listOf(candidacy("k1", "m-1", "A")), manager))
        assertIs<Gate.Hidden>(canReleaseCandidateList(election(type = ElectionType.YES_NO), emptyList(), manager))
        assertIs<Gate.Hidden>(canReleaseCandidateList(prep, listOf(candidacy("k1", "m-1", "A")), roles()))
    }

    @Test
    fun abort_isManagerOnly_andNeverForAFinishedElection() {
        val manager = roles(isBoardOrAdmin = true)
        ElectionStatus.entries.forEach { status ->
            val expected = status != ElectionStatus.TALLIED && status != ElectionStatus.ABORTED
            assertEquals(expected, canAbort(election(status = status), manager), "abort at $status")
            assertFalse(canAbort(election(status = status), roles()), "a plain member never aborts ($status)")
        }
    }

    @Test
    fun booth_isOpenOnlyToAnEligibleMemberWhoHasNotVoted_whileTheElectionIsOpen() {
        val open = election(status = ElectionStatus.OPEN)
        assertTrue(canEnterBooth(open, participation(eligible = true, hasVoted = false)))
        assertFalse(canEnterBooth(open, participation(eligible = true, hasVoted = true)))
        assertFalse(canEnterBooth(open, participation(eligible = false)))
        assertFalse(canEnterBooth(open, participation(eligible = null)))
        assertFalse(canEnterBooth(election(status = ElectionStatus.CLOSED), participation(eligible = true)))
    }

    @Test
    fun candidacy_ownWithdrawalOnlyInPreparation_foreignWithdrawalByManagersOnlyInPreparation() {
        val prep = election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION)
        val released = election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.CANDIDATE_LIST_RELEASED)
        val mine = candidacy("k1", "m-1", "Ich")
        val foreign = candidacy("k2", "m-2", "Andere")
        assertTrue(canWithdrawOwn(prep, mine, "m-1"))
        assertFalse(canWithdrawOwn(released, mine, "m-1"))
        assertFalse(canWithdrawOwn(prep, foreign, "m-1"))
        assertFalse(canWithdrawOwn(prep, candidacy("k1", "m-1", "Ich", withdrawn = true), "m-1"))
        assertTrue(canWithdrawForeign(prep, foreign, "m-1", roles(isBoardOrAdmin = true)))
        assertFalse(canWithdrawForeign(released, foreign, "m-1", roles(isBoardOrAdmin = true)))
        for (st in ElectionStatus.entries.filter { it != ElectionStatus.PREPARATION }) {
            assertFalse(canWithdrawForeign(election(status = st), foreign, "m-1", roles(isBoardOrAdmin = true)))
        }
        assertFalse(canWithdrawForeign(prep, foreign, "m-1", roles()))
        assertFalse(
            canWithdrawForeign(prep, mine, "m-1", roles(isBoardOrAdmin = true)),
            "a manager withdraws OTHERS here; own goes through withdrawOwn",
        )
        assertTrue(canSubmitCandidacy(prep, listOf(foreign), "m-1"))
        assertFalse(canSubmitCandidacy(prep, listOf(mine), "m-1"), "one active candidacy per member")
        assertTrue(canSubmitCandidacy(prep, listOf(candidacy("k1", "m-1", "Ich", withdrawn = true)), "m-1"))
        assertFalse(canSubmitCandidacy(election(type = ElectionType.YES_NO), emptyList(), "m-1"))
        assertFalse(canSubmitCandidacy(released, emptyList(), "m-1"))
    }

    @Test
    fun appointBoard_isManagerOnly_andOnlyInPreparation() {
        assertTrue(canAppointBoard(election(status = ElectionStatus.PREPARATION), roles(isBoardOrAdmin = true)))
        assertFalse(canAppointBoard(election(status = ElectionStatus.CANDIDATE_LIST_RELEASED), roles(isBoardOrAdmin = true)))
        assertFalse(canAppointBoard(election(status = ElectionStatus.PREPARATION), roles()))
    }
}
