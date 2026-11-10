package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.VotingRightSource
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.298 -- the voting right in the UI: who may edit it, the origin line and the notice that replaces a vote button. Pure client; the
 * server decides everything again.
 *
 * There is deliberately no test that opens the member editor dialog here: it ran fine locally, but on the GitHub runner the Karma job of
 * the whole suite died (the runner received a shutdown signal) with it and passed without it (diagnosed with throw-away pull requests,
 * 2026-11-10). The section of the editor is therefore not DOM-tested.
 */
class VotingRightDomTest {
    private fun session(
        role: AccountRole = AccountRole.ADMIN,
        id: String = "admin-1",
        status: MemberStatus = MemberStatus.ACTIVE,
        votingRight: Boolean = true,
    ) = SessionInfoDto(
        memberId = id,
        displayName = "Test",
        role = role,
        expiresAt = LocalDateTime(2099, 1, 1, 12, 0),
        status = status,
        votingRight = votingRight,
    )

    private fun row(
        status: MemberStatus = MemberStatus.ACTIVE,
        votingRight: Boolean = true,
        source: VotingRightSource = VotingRightSource.DEFAULT,
        id: String = "member-7",
    ) = MemberAdminRowDto(
        id = id,
        displayName = "Amara Okafor",
        email = "amara@example.org",
        status = status,
        role = AccountRole.MEMBER,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = false,
        votingRight = votingRight,
        votingRightSource = source,
        votingRightChangedAt = if (source == VotingRightSource.DEFAULT) null else LocalDateTime(2026, 11, 1, 10, 0),
    )

    @Test
    fun originText_namesTheSource() {
        fun text(r: MemberAdminRowDto) = resolvedAttributeText(votingRightOriginText(r))
        assertEquals("Standardwert", text(row()))
        assertEquals("Aus LupaBB übernommen (Status: Neumitglied)", text(row(votingRight = false, source = VotingRightSource.LUPABB)))
        assertEquals("Aus LupaBB übernommen (Status: Mitglied)", text(row(votingRight = true, source = VotingRightSource.LUPABB)))
        assertTrue(text(row(votingRight = false, source = VotingRightSource.MANUAL)).startsWith("Von Hand gesetzt am"))
    }

    @Test
    fun section_visibility_isAdminOnly_neverTheOwnRow_memberOnly() {
        assertTrue(canEditVotingRightOf(AccountRole.ADMIN, "admin-1", row()))
        assertFalse(canEditVotingRightOf(AccountRole.BOARD, "admin-1", row()))
        assertFalse(canEditVotingRightOf(AccountRole.ADMIN, "member-7", row(id = "member-7")))
        assertFalse(canEditVotingRightOf(AccountRole.ADMIN, "admin-1", row(status = MemberStatus.DONOR)))
    }

    @Test
    fun donorRoleOptions_offerMemberOnly() {
        assertEquals(listOf(AccountRole.MEMBER.name), roleOptionsFor(MemberStatus.DONOR).map { it.first })
    }

    @Test
    fun notice_replacesTheButton_onlyForAMemberWithoutVotingRight() {
        assertTrue(session(votingRight = false).lacksVotingRight())
        assertFalse(session(votingRight = true).lacksVotingRight())
        // a donor has no voting right to lack: the server never offers a vote to a non-member
        assertFalse(session(status = MemberStatus.DONOR, votingRight = false).lacksVotingRight())
        assertFalse((null as SessionInfoDto?).lacksVotingRight())
        assertTrue(noVotingRightText().contains("kein Stimmrecht"))
    }

    @Test
    fun notice_rendersAsAStatusAlert_notADisabledButton(): Promise<Unit> =
        formTest {
            mountedForm("vr-notice") { root, element ->
                renderNoVotingRightNotice(root)
                val alert = element().allOf("div.alert").single()
                assertEquals("status", alert.getAttribute("role"))
                assertTrue(alert.textContent.orEmpty().contains("kein Stimmrecht"))
                assertEquals(0, element().allOf("button").size)
            }
        }
}
