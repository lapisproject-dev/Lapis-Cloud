package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V0.11.0 -- covers [NavVisibility.showsOrganizationMemberDropdowns], the pure client-side
 * predicate `App.kt`'s `refreshNavbar` uses to hide the "Mitgliedschaft"/"Selbstverwaltung"/
 * "Wirtschaft" nav dropdowns from a self-registered [MemberStatus.FRIEND] session. Same DOM-free
 * unit-test posture as [GovernanceAuthzUiTest]/[ComplianceLabelsTest] (no rendering harness exists
 * in this module) -- this cannot assert the actual navbar DOM, only the predicate feeding it, which
 * is the only part of this gate that is unit-testable at all.
 */
class NavVisibilityTest {
    @Test
    fun friend_doesNotShowOrganizationMemberDropdowns() {
        assertFalse(NavVisibility.showsOrganizationMemberDropdowns(MemberStatus.FRIEND))
    }

    @Test
    fun everyOtherStatus_showsOrganizationMemberDropdowns() {
        MemberStatus.entries.filter { it != MemberStatus.FRIEND }.forEach { status ->
            assertTrue(
                NavVisibility.showsOrganizationMemberDropdowns(status),
                "expected $status to still show the organization-member nav dropdowns",
            )
        }
    }
}
