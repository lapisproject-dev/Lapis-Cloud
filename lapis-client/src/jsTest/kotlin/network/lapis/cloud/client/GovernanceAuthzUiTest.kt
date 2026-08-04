package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Governance UI wave -- covers [GovernanceAuthzUi.canRecordForMeeting], the pure client-side
 * mirror of `GovernanceAuthorization.canRecordForMeeting`. Same DOM-free unit-test posture as
 * [ValidationTest]/[CommitteesScreenTest] (no rendering harness exists in this module).
 */
class GovernanceAuthzUiTest {
    private val committeeId = "committee-1"
    private val otherCommitteeId = "committee-2"
    private val currentMemberId = "member-1"
    private val since = LocalDate(2026, 1, 1)

    private fun membership(
        committeeId: String,
        memberId: String,
        role: CommitteeRole,
    ) = CommitteeMembershipDto(
        id = "membership-$committeeId-$memberId",
        committeeId = committeeId,
        memberId = memberId,
        memberDisplayName = "Test Member",
        role = role,
        since = since,
        until = null,
    )

    @Test
    fun boardOrAdmin_isAlwaysAllowed_evenWithNoMemberships() {
        assertTrue(
            GovernanceAuthzUi.canRecordForMeeting(
                isBoardOrAdmin = true,
                currentMemberId = currentMemberId,
                committeeId = committeeId,
                activeCommitteeMemberships = emptyList(),
            ),
        )
    }

    @Test
    fun chairOfTargetCommittee_isAllowed() {
        val memberships = listOf(membership(committeeId, currentMemberId, CommitteeRole.CHAIR))
        assertTrue(
            GovernanceAuthzUi.canRecordForMeeting(
                isBoardOrAdmin = false,
                currentMemberId = currentMemberId,
                committeeId = committeeId,
                activeCommitteeMemberships = memberships,
            ),
        )
    }

    @Test
    fun deputyChairAndSecretary_areAlsoAllowed() {
        listOf(CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY).forEach { role ->
            val memberships = listOf(membership(committeeId, currentMemberId, role))
            assertTrue(
                GovernanceAuthzUi.canRecordForMeeting(
                    isBoardOrAdmin = false,
                    currentMemberId = currentMemberId,
                    committeeId = committeeId,
                    activeCommitteeMemberships = memberships,
                ),
                "expected $role to qualify",
            )
        }
    }

    @Test
    fun plainMember_isNotAllowed() {
        val memberships = listOf(membership(committeeId, currentMemberId, CommitteeRole.MEMBER))
        assertFalse(
            GovernanceAuthzUi.canRecordForMeeting(
                isBoardOrAdmin = false,
                currentMemberId = currentMemberId,
                committeeId = committeeId,
                activeCommitteeMemberships = memberships,
            ),
        )
    }

    @Test
    fun chairOfADifferentCommittee_isNotAllowed() {
        val memberships = listOf(membership(otherCommitteeId, currentMemberId, CommitteeRole.CHAIR))
        assertFalse(
            GovernanceAuthzUi.canRecordForMeeting(
                isBoardOrAdmin = false,
                currentMemberId = currentMemberId,
                committeeId = committeeId,
                activeCommitteeMemberships = memberships,
            ),
        )
    }

    @Test
    fun someoneElsesLeadershipMembership_doesNotGrantAccess() {
        val memberships = listOf(membership(committeeId, "someone-else", CommitteeRole.CHAIR))
        assertFalse(
            GovernanceAuthzUi.canRecordForMeeting(
                isBoardOrAdmin = false,
                currentMemberId = currentMemberId,
                committeeId = committeeId,
                activeCommitteeMemberships = memberships,
            ),
        )
    }
}
