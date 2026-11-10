package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.VotingRightSource
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.298 -- the voting right in the UI: the section of the member editor (who sees it, the origin line, the way back to the sync, the
 * consequence line), the roster text badge and the notice that replaces a vote button. Pure client; the server decides everything again.
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

    private suspend fun editorTextFor(
        caller: SessionInfoDto,
        target: MemberAdminRowDto,
        id: String,
    ): Pair<String, HTMLElement> {
        AppState.setSession(caller)
        var text = ""
        var modalElement: HTMLElement? = null
        withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() }) {
            mountedForm(id) { _, _ ->
                openMemberEditorDialog(target, onChanged = {})
                val modal = lastOpenModal()
                modalElement = modal
                awaitUntil("the dialog is rendered") { modal.allOf("button").isNotEmpty() }
                text = modal.textContent.orEmpty()
                // let KVision's own scheduled re-render of the dialog finish before the modal is torn down (otherwise it patches a detached node
                // inside the NEXT test)
                kotlinx.coroutines.delay(300)
            }
        }
        return text to modalElement!!
    }

    // ONE dialog per test class on purpose: a second Modal opened in the same Karma page raised a stale KVision re-render ("insertBefore ...
    // not a child") in the tests that run after this class (WritePathsSingleShotDomTest) when the whole suite ran; the variants are pure functions.
    @Test
    fun editor_adminSeesTheSection_manualValue_withDateAndTheWayBackToTheSync(): Promise<Unit> =
        formTest {
            val (text, modal) =
                editorTextFor(session(), row(votingRight = false, source = VotingRightSource.MANUAL), "vr-editor-manual")
            assertTrue(text.contains("Stimmrecht"), "the section title")
            assertTrue(text.contains("Von Hand gesetzt am"), "origin line: manual with the date")
            assertTrue(text.contains("Wieder dem LupaBB-Abgleich überlassen"), "the way back to the sync")
            assertTrue(text.contains("zählt das Mitglied dort weiter zur Beschlussfähigkeit"), "the consequence line for 'no'")
            assertTrue(modal.allOf("select").isNotEmpty())
        }

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
