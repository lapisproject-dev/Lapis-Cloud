package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V0.11.0, umgebaut in Welle V1.1.4 -- covers [NavVisibility]'s seven pure, DOM-free predicates
 * that `App.kt`'s `refreshNavbar` uses to decide which nav dropdowns/links a given
 * [MemberStatus] session sees. Same DOM-free unit-test posture as
 * [GovernanceAuthzUiTest]/[ComplianceLabelsTest] (no rendering harness exists in this module) --
 * this cannot assert the actual navbar DOM, only the predicates feeding it.
 *
 * Welle V1.1.4 widened [NavVisibility.showsEconomySection]/[NavVisibility.showsLtrLedger]/
 * [NavVisibility.showsSocialNetwork] from `ORGANIZATION_MEMBER` to `LTR_ELIGIBLE` (admits FRIEND)
 * and introduced [NavVisibility.showsDsgvoRights] (true for EVERY authenticated status). The old
 * single `showsOrganizationMemberDropdowns` predicate was removed -- `App.kt` was its only caller.
 */
class NavVisibilityTest {
    @Test
    fun friend_seesTheV114Openings_butNotTheOrganizationMemberOnlySections() {
        assertFalse(NavVisibility.showsMembershipSection(MemberStatus.FRIEND))
        assertFalse(NavVisibility.showsSelfGovernance(MemberStatus.FRIEND))
        assertFalse(NavVisibility.showsMemberOnlyEconomy(MemberStatus.FRIEND))

        assertTrue(NavVisibility.showsDsgvoRights(MemberStatus.FRIEND))
        assertTrue(NavVisibility.showsEconomySection(MemberStatus.FRIEND))
        assertTrue(NavVisibility.showsLtrLedger(MemberStatus.FRIEND))
        assertTrue(NavVisibility.showsSocialNetwork(MemberStatus.FRIEND))
    }

    @Test
    fun active_seesEverything() {
        assertTrue(NavVisibility.showsMembershipSection(MemberStatus.ACTIVE))
        assertTrue(NavVisibility.showsSelfGovernance(MemberStatus.ACTIVE))
        assertTrue(NavVisibility.showsDsgvoRights(MemberStatus.ACTIVE))
        assertTrue(NavVisibility.showsEconomySection(MemberStatus.ACTIVE))
        assertTrue(NavVisibility.showsLtrLedger(MemberStatus.ACTIVE))
        assertTrue(NavVisibility.showsSocialNetwork(MemberStatus.ACTIVE))
        assertTrue(NavVisibility.showsMemberOnlyEconomy(MemberStatus.ACTIVE))
    }

    @Test
    fun guestApplicationWithdrawnRejected_seeNoEconomySection_butDoSeeDsgvoRights() {
        listOf(MemberStatus.GUEST, MemberStatus.APPLICATION, MemberStatus.WITHDRAWN, MemberStatus.REJECTED).forEach { status ->
            assertFalse(NavVisibility.showsEconomySection(status), "expected $status to NOT see the Wirtschaft dropdown")
            assertFalse(NavVisibility.showsLtrLedger(status), "expected $status to NOT see LTR-Konto")
            assertFalse(NavVisibility.showsSocialNetwork(status), "expected $status to NOT see Soziales Netzwerk")
            assertFalse(NavVisibility.showsMembershipSection(status), "expected $status to NOT see Mitgliedschaft")
            assertFalse(NavVisibility.showsSelfGovernance(status), "expected $status to NOT see Selbstverwaltung")
            assertFalse(NavVisibility.showsMemberOnlyEconomy(status), "expected $status to NOT see Crowdfunding/Auktion/Politiker")
            assertTrue(NavVisibility.showsDsgvoRights(status), "expected $status to still see Meine Daten (every authenticated status)")
        }
    }

    @Test
    fun showsDsgvoRights_isUnconditionallyTrue_forEveryStatus() {
        MemberStatus.entries.forEach { status ->
            assertTrue(NavVisibility.showsDsgvoRights(status), "showsDsgvoRights must be true for every status, got false for $status")
        }
    }

    @Test
    fun ltrEligibleSections_matchMemberStatusSetsLtrEligible_exactly() {
        MemberStatus.entries.forEach { status ->
            val expected = status == MemberStatus.ACTIVE || status == MemberStatus.FRIEND
            assertEquals(expected, NavVisibility.showsEconomySection(status), "showsEconomySection mismatch for $status")
            assertEquals(expected, NavVisibility.showsLtrLedger(status), "showsLtrLedger mismatch for $status")
            assertEquals(expected, NavVisibility.showsSocialNetwork(status), "showsSocialNetwork mismatch for $status")
        }
    }
}
