package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.225 -- the sidebar entry of the party report: visible for BOARD and ADMIN of a party only. */
class PartyReportNavTest {
    private fun session(
        role: AccountRole,
        party: Boolean,
    ) = SessionInfoDto(
        memberId = "m",
        displayName = "Test",
        role = role,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        status = MemberStatus.ACTIVE,
        politicalParty = party,
    )

    @Test
    fun invisibleWithoutPartyFlag_orWithoutSession() {
        assertFalse(NavVisibility.showsPartyReport(session(AccountRole.BOARD, party = false)))
        assertFalse(NavVisibility.showsPartyReport(session(AccountRole.ADMIN, party = false)))
        assertFalse(NavVisibility.showsPartyReport(null))
    }

    @Test
    fun invisibleForTreasurerAndMember_evenOnAParty() {
        assertFalse(NavVisibility.showsPartyReport(session(AccountRole.TREASURER, party = true)))
        assertFalse(NavVisibility.showsPartyReport(session(AccountRole.MEMBER, party = true)))
    }

    @Test
    fun visibleForBoardAndAdminOfAParty() {
        assertTrue(NavVisibility.showsPartyReport(session(AccountRole.BOARD, party = true)))
        assertTrue(NavVisibility.showsPartyReport(session(AccountRole.ADMIN, party = true)))
    }

    @Test
    fun theRouteHasASidebarEntryInTheFinanceGroup_andAnIntro() {
        val entry = assertNotNull(NavRegistry.entries.singleOrNull { it.route == Routes.PARTY_REPORT })
        assertEquals(SidebarGroupId.FINANCE, entry.group)
        assertEquals("/rechenschaftsbericht", Routes.PARTY_REPORT)
        assertNotNull(PageIntros.forRoute(Routes.PARTY_REPORT))
        assertNull(null)
    }
}
