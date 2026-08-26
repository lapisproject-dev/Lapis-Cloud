package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.1.4 "LTR_ELIGIBLE/FRIEND-Erweiterung". Bislang existierte kein eigener Test für
 * [MemberStatusSets] (nur indirekt über `MembershipGuardsTest`). Dieser Test ist die billigste
 * denkbare Regressions-Wall: wenn eines der Sets künftig versehentlich verändert wird -- sei es
 * durch die aktuelle Welle oder eine spätere --, geht genau eine Zeile hier rot, statt dass die
 * Drift erst über einen fehlgeschlagenen Server-Test sichtbar wird.
 */
class MemberStatusSetsTest {
    @Test
    fun ltrEligible_isExactlyActiveAndFriend() {
        assertEquals(setOf(MemberStatus.ACTIVE, MemberStatus.FRIEND), MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun organizationMember_remainsUnchanged() {
        assertEquals(setOf(MemberStatus.ACTIVE), MemberStatusSets.ORGANIZATION_MEMBER)
    }

    @Test
    fun nonMember_remainsUnchanged() {
        assertEquals(setOf(MemberStatus.GUEST, MemberStatus.FRIEND), MemberStatusSets.NON_MEMBER)
    }

    @Test
    fun conferenceEligible_remainsUnchanged() {
        assertEquals(
            setOf(MemberStatus.ACTIVE, MemberStatus.GUEST, MemberStatus.FRIEND),
            MemberStatusSets.CONFERENCE_ELIGIBLE,
        )
    }

    @Test
    fun politicianRater_remainsUnchanged_friendNotIncluded() {
        assertEquals(setOf(MemberStatus.ACTIVE, MemberStatus.GUEST), MemberStatusSets.POLITICIAN_RATER)
        assertFalse(MemberStatus.FRIEND in MemberStatusSets.POLITICIAN_RATER)
    }

    /**
     * V1.2.11 (PdV-CSV-Import): widened from two to four elements -- DECEASED (terminal) and DONOR
     * (no account row is ever created for one, see `MemberCsvImport` KDoc) join WITHDRAWN/REJECTED.
     * This is a deliberate, documented change to the previous "remains unchanged" wall, not a drift.
     */
    @Test
    fun loginBlocked_includesDeceasedAndDonor() {
        assertEquals(
            setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.DONOR),
            MemberStatusSets.LOGIN_BLOCKED,
        )
    }

    /**
     * The wave's own most important test: neither newly-added status confers ANY of the five
     * capability-set memberships elsewhere in this object, only the deny-listed [MemberStatusSets
     * .LOGIN_BLOCKED] above. Every gate in the server is an allowlist test against one of these sets
     * (see [MemberStatusSets] class KDoc), so this single assertion pins that DONOR/DECEASED are
     * structurally rightless everywhere except the explicit login block.
     */
    @Test
    fun donorAndDeceased_areInNoCapabilitySetExceptLoginBlocked() {
        listOf(MemberStatus.DONOR, MemberStatus.DECEASED).forEach { status ->
            assertFalse(status in MemberStatusSets.ORGANIZATION_MEMBER)
            assertFalse(status in MemberStatusSets.NON_MEMBER)
            assertFalse(status in MemberStatusSets.CONFERENCE_ELIGIBLE)
            assertFalse(status in MemberStatusSets.LTR_ELIGIBLE)
            assertFalse(status in MemberStatusSets.POLITICIAN_RATER)
            assertTrue(status in MemberStatusSets.LOGIN_BLOCKED)
        }
    }

    @Test
    fun guest_isNotLtrEligible() {
        assertFalse(MemberStatus.GUEST in MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun application_isNotLtrEligible() {
        assertFalse(MemberStatus.APPLICATION in MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun withdrawnAndRejected_areNotLtrEligible() {
        assertFalse(MemberStatus.WITHDRAWN in MemberStatusSets.LTR_ELIGIBLE)
        assertFalse(MemberStatus.REJECTED in MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun activeAndFriend_areLtrEligible() {
        assertTrue(MemberStatus.ACTIVE in MemberStatusSets.LTR_ELIGIBLE)
        assertTrue(MemberStatus.FRIEND in MemberStatusSets.LTR_ELIGIBLE)
    }

    /**
     * Security finding fix (feature/v1.2.11-member-csv-import, LOW/latent): DECEASED is a third
     * terminal status, alongside WITHDRAWN/REJECTED, and must join this set so
     * `SepaBatchPoller.runPhaseB`'s defense-in-depth mandate revocation covers it too -- see
     * [MemberStatusSets.MEMBERSHIP_ENDED]'s own KDoc.
     */
    @Test
    fun membershipEnded_isExactlyWithdrawnRejectedDeceased() {
        assertEquals(
            setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED),
            MemberStatusSets.MEMBERSHIP_ENDED,
        )
    }

    @Test
    fun membershipEnded_excludesDonor() {
        assertFalse(MemberStatus.DONOR in MemberStatusSets.MEMBERSHIP_ENDED)
    }
}
