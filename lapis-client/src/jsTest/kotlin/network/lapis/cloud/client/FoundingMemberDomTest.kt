package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FoundingMemberRules
import network.lapis.cloud.shared.domain.FoundingMemberVisibility
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OwnFoundingMemberDto
import network.lapis.cloud.shared.rpc.FoundingMemberConsentOutdatedException
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V154 -- the founding-member marker in the client: the member's consent card ("Meine Daten"), who may edit the marker in the roster
 * editor, the roster filter and the confirm rule. Pure client; the server decides everything again.
 */
class FoundingMemberDomTest {
    private fun row(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole? = AccountRole.MEMBER,
        id: String = "member-7",
        anonymized: Boolean = false,
        foundingMember: Boolean = false,
        consent: Boolean = false,
    ) = MemberAdminRowDto(
        id = id,
        displayName = "Amara Okafor",
        email = "amara@example.org",
        status = status,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = anonymized,
        foundingMember = foundingMember,
        foundingMemberPublicConsent = consent,
    )

    private fun memberDto(
        marked: Boolean = true,
        public: Boolean = false,
    ) = MemberDto(
        id = "m1",
        displayName = "Amara Okafor",
        email = "amara@example.org",
        status = MemberStatus.ACTIVE,
        joinedAt = LocalDate(2026, 1, 1),
        role = AccountRole.MEMBER,
        foundingMember = marked,
        foundingMemberPublic = public,
    )

    private class FakeRpc(
        private val failWith: Throwable? = null,
    ) : FoundingMemberRpc {
        val calls = mutableListOf<Pair<FoundingMemberVisibility, String?>>()

        override suspend fun getCurrentMember(): MemberDto = error("not used by the card itself")

        override suspend fun setVisibility(
            visibility: FoundingMemberVisibility,
            consentTextVersion: String?,
        ): OwnFoundingMemberDto {
            calls += visibility to consentTextVersion
            failWith?.let { throw it }
            return OwnFoundingMemberDto(foundingMember = true, visibility = visibility, consentOutdated = false)
        }
    }

    @Test
    fun editRight_isBoardOrAdminOnly_neverOwnRow_neverAnonymised() {
        assertTrue(canEditFoundingMemberOf(AccountRole.BOARD, "board-1", row()))
        assertTrue(canEditFoundingMemberOf(AccountRole.ADMIN, "admin-1", row()))
        assertFalse(canEditFoundingMemberOf(AccountRole.TREASURER, "t-1", row()))
        assertFalse(canEditFoundingMemberOf(AccountRole.MEMBER, "m-1", row()))
        assertFalse(canEditFoundingMemberOf(null, null, row()))
        assertFalse(canEditFoundingMemberOf(AccountRole.BOARD, "member-7", row(id = "member-7")))
        assertFalse(canEditFoundingMemberOf(AccountRole.ADMIN, "admin-1", row(anonymized = true)))
    }

    @Test
    fun editRight_escalatedRowsOnlyForAdmin() {
        for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)) {
            assertFalse(canEditFoundingMemberOf(AccountRole.BOARD, "board-1", row(role = role)), "BOARD must not edit a $role row")
            assertTrue(canEditFoundingMemberOf(AccountRole.ADMIN, "admin-1", row(role = role)), "ADMIN may edit a $role row")
        }
    }

    @Test
    fun editRight_formerMemberOnlyWithAStaleMarker_andThenOnlyToClear() {
        assertFalse(canEditFoundingMemberOf(AccountRole.BOARD, "board-1", row(status = MemberStatus.WITHDRAWN)))
        val stale = row(status = MemberStatus.WITHDRAWN, foundingMember = true)
        assertTrue(canEditFoundingMemberOf(AccountRole.BOARD, "board-1", stale))
        assertFalse(foundingMemberCanBeSet(stale), "a former member can only be cleared")
        assertTrue(foundingMemberCanBeSet(row()))
    }

    @Test
    fun confirmDialog_onlyWhenRemovingAMarkerThatHasConsent() {
        assertTrue(foundingMemberRemovalNeedsConfirmation(row(foundingMember = true, consent = true), wanted = false))
        assertFalse(foundingMemberRemovalNeedsConfirmation(row(foundingMember = true, consent = false), wanted = false))
        assertFalse(foundingMemberRemovalNeedsConfirmation(row(foundingMember = true, consent = true), wanted = true))
        assertFalse(foundingMemberRemovalNeedsConfirmation(row(foundingMember = false, consent = false), wanted = false))
    }

    @Test
    fun rosterQuery_carriesTheFoundingMemberFilter() {
        assertFalse(rosterQuery(RosterState()).foundingMemberOnly)
        assertTrue(rosterQuery(RosterState(foundingMemberOnly = true)).foundingMemberOnly)
    }

    @Test
    fun card_rendersNothingForAMemberWhoIsNotMarked(): Promise<Unit> =
        formTest {
            mountedForm("fm-card-unmarked") { root, element ->
                MemberFoundingMemberCard(root, FakeRpc(), onChanged = {}).render(memberDto(marked = false))
                assertEquals(0, element().allOf("input").size)
            }
        }

    @Test
    fun card_echoOfTheServerState_firesNoRpc_andAClickSendsTheVersionedConsent(): Promise<Unit> =
        formTest {
            mountedForm("fm-card-consent") { root, element ->
                val rpc = FakeRpc()
                var changed = 0
                MemberFoundingMemberCard(root, rpc, onChanged = { changed++ }).render(memberDto(public = false))
                delay(200)
                assertTrue(rpc.calls.isEmpty(), "rendering must not call the server")
                (element().querySelector("input[type=checkbox]") as HTMLInputElement).click()
                awaitUntil("the consent was sent") { rpc.calls.isNotEmpty() }
                assertEquals(FoundingMemberVisibility.PUBLIC to FoundingMemberRules.CONSENT_TEXT_VERSION, rpc.calls.single())
                awaitUntil("the host was told to reload") { changed == 1 }
            }
        }

    @Test
    fun card_withdrawal_sendsPrivateWithoutAVersion(): Promise<Unit> =
        formTest {
            mountedForm("fm-card-withdraw") { root, element ->
                val rpc = FakeRpc()
                MemberFoundingMemberCard(root, rpc, onChanged = {}).render(memberDto(public = true))
                delay(200)
                assertTrue(rpc.calls.isEmpty(), "an already public member renders without a call")
                (element().querySelector("input[type=checkbox]") as HTMLInputElement).click()
                awaitUntil("the withdrawal was sent") { rpc.calls.isNotEmpty() }
                assertEquals(FoundingMemberVisibility.PRIVATE, rpc.calls.single().first)
                assertNull(rpc.calls.single().second)
            }
        }

    @Test
    fun card_outdatedConsentText_showsAnError_andResetsThroughOnChanged(): Promise<Unit> =
        formTest {
            mountedForm("fm-card-outdated") { root, element ->
                val rpc = FakeRpc(failWith = FoundingMemberConsentOutdatedException())
                var changed = 0
                val errors = mutableListOf<String>()
                val card = MemberFoundingMemberCard(root, rpc, onChanged = { changed++ }, toastError = { errors += it })
                card.render(memberDto(public = false))
                (element().querySelector("input[type=checkbox]") as HTMLInputElement).click()
                awaitUntil("the host was told to reload") { changed == 1 }
                assertEquals(1, errors.size, "the user is told the wording changed")
                assertEquals(1, rpc.calls.size)
            }
        }
}
