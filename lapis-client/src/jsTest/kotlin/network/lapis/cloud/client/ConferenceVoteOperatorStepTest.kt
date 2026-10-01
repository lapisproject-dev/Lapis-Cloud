package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** V1.9.26 -- `operatorStep` as a table: what each role is offered in each status, decided only through the existing gates. */
class ConferenceVoteOperatorStepTest {
    private fun roles(
        boardOrAdmin: Boolean,
        strict: Boolean,
    ) = ElectionRoles(canManage = false, boardOrAdmin = boardOrAdmin, boardStrict = strict)

    private val none = roles(false, false)
    private val admin = roles(true, false)
    private val strict = roles(false, true)
    private val both = roles(true, true)

    @Test
    fun aMemberWithoutARole_isOfferedNothing_inEveryStatus() {
        ElectionStatus.entries.forEach { status ->
            assertEquals(OperatorStep.None, operatorStep(election(status = status), participation(), none), "$status")
        }
    }

    @Test
    fun preparation_offersOpening_withTheBoardSizeGate_andOnlyToOperators() {
        val e = election(status = ElectionStatus.PREPARATION)
        listOf(admin, strict, both).forEach { role ->
            val step = operatorStep(e, participation(boardSize = 3), role)
            assertIs<OperatorStep.Open>(step)
            assertEquals(Gate.Enabled, step.gate)
            assertTrue(step.secret)
        }
        val small = operatorStep(e, participation(boardSize = 2), admin)
        assertIs<OperatorStep.Open>(small)
        assertIs<Gate.Disabled>(small.gate)
        assertEquals(OperatorStep.None, operatorStep(e, participation(), none))
    }

    @Test
    fun aPersonnelElection_opensFromTheReleasedCandidateList_notFromPreparation() {
        val prepared = election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION)
        val released = election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.CANDIDATE_LIST_RELEASED)
        assertEquals(OperatorStep.None, operatorStep(prepared, participation(), admin))
        assertIs<OperatorStep.Open>(operatorStep(released, participation(), admin))
        val yesNoReleased = election(status = ElectionStatus.CANDIDATE_LIST_RELEASED)
        assertEquals(OperatorStep.None, operatorStep(yesNoReleased, participation(), admin))
    }

    @Test
    fun open_offersClosing_withTheBallotCount_toOperators() {
        val e = election(status = ElectionStatus.OPEN)
        listOf(admin, strict, both).forEach { role ->
            assertEquals(OperatorStep.Close(4), operatorStep(e, participation(ballotCount = 4), role))
        }
        assertEquals(OperatorStep.None, operatorStep(e, participation(ballotCount = 4), none))
    }

    @Test
    fun closed_offersApprovalOnlyToARealBoardMember_andCountingOnlyAtTheThreshold() {
        val e = election(status = ElectionStatus.CLOSED)
        // below the threshold: a board member may approve, counting is disabled with a reason
        val below = operatorStep(e, participation(approvals = 1, threshold = 2), strict)
        assertIs<OperatorStep.Closed>(below)
        assertTrue(below.approve)
        assertIs<Gate.Disabled>(below.tally)
        assertEquals(1, below.approvals)
        assertEquals(2, below.threshold)
        // BOARD/ADMIN without a board seat never approves (the server gives no bypass)
        val adminBelow = operatorStep(e, participation(approvals = 1, threshold = 2), admin)
        assertIs<OperatorStep.Closed>(adminBelow)
        assertEquals(false, adminBelow.approve)
        // at the threshold counting is enabled
        val atThreshold = operatorStep(e, participation(approvals = 2, threshold = 2, hasApproved = true), strict)
        assertIs<OperatorStep.Closed>(atThreshold)
        assertEquals(false, atThreshold.approve, "already approved")
        assertEquals(Gate.Enabled, atThreshold.tally)
        // already approved, still below the threshold: only the counter is left
        val waiting = operatorStep(e, participation(approvals = 1, threshold = 2, hasApproved = true), both)
        assertIs<OperatorStep.Closed>(waiting)
        assertEquals(false, waiting.approve)
        assertIs<Gate.Disabled>(waiting.tally)
        assertEquals(OperatorStep.None, operatorStep(e, participation(), none))
    }

    @Test
    fun tallied_showsTheResultToOperatorsOnly_andAbortedOffersNothing() {
        assertEquals(OperatorStep.Tallied(null), operatorStep(election(status = ElectionStatus.TALLIED), participation(), admin))
        assertEquals(OperatorStep.None, operatorStep(election(status = ElectionStatus.TALLIED), participation(), none))
        listOf(none, admin, strict, both).forEach {
            assertEquals(OperatorStep.None, operatorStep(election(status = ElectionStatus.ABORTED), participation(), it))
        }
    }

    @Test
    fun theRolesOfTheRoom_neverGrantManageUntilTheRosterSaysSo() {
        val ctx = OperatorContext("me", isBoardOrAdmin = true, canModerateRoom = false, roomMeetingId = { "s1" })
        val r = electionRolesForRoom(ctx, participation(isBoard = true))
        assertEquals(false, r.canManage)
        assertTrue(r.boardOrAdmin && r.boardStrict)
        assertTrue(electionRolesForRoom(ctx, participation(), canManage = true).canManage)
        val plain = electionRolesForRoom(ctx.copy(isBoardOrAdmin = false), participation())
        assertEquals(false, plain.canOperate)
    }
}
