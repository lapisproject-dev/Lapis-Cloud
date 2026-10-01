package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** V1.9.22: the pure station logic of the phase bar -- which stations an election type has and where an election stands. */
class ElectionPhaseTest {
    private val at = LocalDateTime(2026, 5, 1, 12, 0)

    private fun election(
        type: ElectionType = ElectionType.SINGLE_CHOICE,
        status: ElectionStatus = ElectionStatus.PREPARATION,
        candidateListApprovedAt: LocalDateTime? = null,
        votingOpenedAt: LocalDateTime? = null,
        votingClosedAt: LocalDateTime? = null,
    ) = ElectionDto(
        id = "e1",
        motionId = "m1",
        meetingId = "s1",
        title = "T",
        electionType = type,
        secret = true,
        seatCount = 1,
        targetCommitteeId = null,
        targetCommitteeName = null,
        targetRole = null,
        requiredMajorityPercent = 50,
        status = status,
        openedById = "x",
        openedByDisplayName = "X",
        openedAt = at,
        candidateListApprovedAt = candidateListApprovedAt,
        votingOpenedAt = votingOpenedAt,
        votingClosedAt = votingClosedAt,
        tallyThreshold = 2,
        tallyRunAt = null,
        resolutionId = null,
        options = emptyList(),
    )

    @Test
    fun yesNo_hasNoCandidateListStation_peopleElectionsHaveFive() {
        assertEquals(
            listOf(
                ElectionPhaseStep.PREPARATION,
                ElectionPhaseStep.VOTING_OPEN,
                ElectionPhaseStep.VOTING_CLOSED,
                ElectionPhaseStep.TALLIED,
            ),
            phaseSteps(ElectionType.YES_NO),
        )
        assertEquals(5, phaseSteps(ElectionType.SINGLE_CHOICE).size)
        assertEquals(5, phaseSteps(ElectionType.MULTI_CHOICE).size)
        assertFalse(ElectionPhaseStep.CANDIDATES_RELEASED in phaseSteps(ElectionType.YES_NO))
    }

    @Test
    fun everyStatus_mapsToItsOwnStation() {
        assertEquals(ElectionPhaseStep.PREPARATION, reachedStep(election(status = ElectionStatus.PREPARATION)))
        assertEquals(ElectionPhaseStep.CANDIDATES_RELEASED, reachedStep(election(status = ElectionStatus.CANDIDATE_LIST_RELEASED)))
        assertEquals(ElectionPhaseStep.VOTING_OPEN, reachedStep(election(status = ElectionStatus.OPEN)))
        assertEquals(ElectionPhaseStep.VOTING_CLOSED, reachedStep(election(status = ElectionStatus.CLOSED)))
        assertEquals(ElectionPhaseStep.TALLIED, reachedStep(election(status = ElectionStatus.TALLIED)))
    }

    @Test
    fun anAbortedElection_keepsTheStationItWasAbortedAt_readFromItsTimestamps() {
        assertEquals(ElectionPhaseStep.PREPARATION, reachedStep(election(status = ElectionStatus.ABORTED)))
        assertEquals(
            ElectionPhaseStep.CANDIDATES_RELEASED,
            reachedStep(election(status = ElectionStatus.ABORTED, candidateListApprovedAt = at)),
        )
        assertEquals(
            ElectionPhaseStep.VOTING_OPEN,
            reachedStep(election(status = ElectionStatus.ABORTED, candidateListApprovedAt = at, votingOpenedAt = at)),
        )
        assertEquals(
            ElectionPhaseStep.VOTING_CLOSED,
            reachedStep(election(status = ElectionStatus.ABORTED, votingOpenedAt = at, votingClosedAt = at)),
        )
        // a yes/no election has no candidate list: a stray timestamp must not put it on a station it does not have
        assertEquals(
            ElectionPhaseStep.PREPARATION,
            reachedStep(election(type = ElectionType.YES_NO, status = ElectionStatus.ABORTED, candidateListApprovedAt = at)),
        )
    }
}
