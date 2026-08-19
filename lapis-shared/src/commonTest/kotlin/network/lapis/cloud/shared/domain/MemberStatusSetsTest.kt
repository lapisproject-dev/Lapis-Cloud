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

    @Test
    fun loginBlocked_remainsUnchanged() {
        assertEquals(setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED), MemberStatusSets.LOGIN_BLOCKED)
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
}
