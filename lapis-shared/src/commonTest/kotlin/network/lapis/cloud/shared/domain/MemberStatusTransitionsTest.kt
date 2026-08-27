package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.2.12 "Mitgliederverwaltung". Pins [MemberStatusTransitions] against silent drift --
 * same cheap-regression-wall posture as [MemberStatusSetsTest].
 */
class MemberStatusTransitionsTest {
    @Test
    fun administrativelyManaged_isExactlyActiveWithdrawnDonorDeceased() {
        assertEquals(
            setOf(MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED),
            MemberStatusTransitions.ADMINISTRATIVELY_MANAGED,
        )
    }

    @Test
    fun allowedTargets_forEveryManagedStatus_isTheOtherThreeManagedStatuses() {
        MemberStatusTransitions.ADMINISTRATIVELY_MANAGED.forEach { from ->
            val expected = MemberStatusTransitions.ADMINISTRATIVELY_MANAGED - from
            assertEquals(expected, MemberStatusTransitions.allowedTargets(from), "allowedTargets($from) mismatch")
            assertFalse(from in MemberStatusTransitions.allowedTargets(from), "$from must not be its own target")
        }
    }

    /** The four statuses OUTSIDE the administratively managed quadrant offer no transition at all. */
    @Test
    fun allowedTargets_forEveryUnmanagedStatus_isEmpty() {
        val unmanaged = MemberStatus.entries.toSet() - MemberStatusTransitions.ADMINISTRATIVELY_MANAGED
        assertEquals(4, unmanaged.size)
        unmanaged.forEach { from ->
            assertTrue(
                MemberStatusTransitions.allowedTargets(from).isEmpty(),
                "expected no allowed targets from $from, got ${MemberStatusTransitions.allowedTargets(from)}",
            )
        }
    }

    @Test
    fun requiresAdmin_isTrueOnlyForDeceased() {
        MemberStatus.entries.forEach { status ->
            assertEquals(status == MemberStatus.DECEASED, MemberStatusTransitions.requiresAdmin(status))
        }
    }

    /**
     * Deliberately "redundant"-looking test: [MemberStatusTransitions.ADMINISTRATIVELY_MANAGED] is
     * NOT derived from [MemberStatusSets.LOGIN_BLOCKED]/[MemberStatusSets.MEMBERSHIP_ENDED] -- see
     * that val's own KDoc "BEWUSST EIGENSTÄNDIG". A later widening of either of those two sets
     * (e.g. a future terminal status added to [MemberStatusSets.MEMBERSHIP_ENDED]) must NOT
     * silently also widen what the admin UI offers -- this test fails loudly if the three sets'
     * relationship ever changes without a deliberate edit here too.
     */
    @Test
    fun administrativelyManaged_isNotDerivedFrom_loginBlockedOrMembershipEnded() {
        assertFalse(
            MemberStatusTransitions.ADMINISTRATIVELY_MANAGED == MemberStatusSets.LOGIN_BLOCKED,
            "ADMINISTRATIVELY_MANAGED happens to equal LOGIN_BLOCKED -- verify this is still deliberate, not a silent merge",
        )
        assertFalse(
            MemberStatusTransitions.ADMINISTRATIVELY_MANAGED == MemberStatusSets.MEMBERSHIP_ENDED,
            "ADMINISTRATIVELY_MANAGED happens to equal MEMBERSHIP_ENDED -- verify this is still deliberate, not a silent merge",
        )
        // ACTIVE/DONOR are administratively managed but neither LOGIN_BLOCKED nor MEMBERSHIP_ENDED
        // membership-ended -- the concrete evidence the three sets are genuinely independent.
        assertTrue(MemberStatus.ACTIVE in MemberStatusTransitions.ADMINISTRATIVELY_MANAGED)
        assertFalse(MemberStatus.ACTIVE in MemberStatusSets.LOGIN_BLOCKED)
        assertFalse(MemberStatus.ACTIVE in MemberStatusSets.MEMBERSHIP_ENDED)
        assertTrue(MemberStatus.DONOR in MemberStatusTransitions.ADMINISTRATIVELY_MANAGED)
        assertFalse(MemberStatus.DONOR in MemberStatusSets.MEMBERSHIP_ENDED)
    }
}
