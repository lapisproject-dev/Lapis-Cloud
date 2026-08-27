package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.2.12 "Mitgliederverwaltung" -- covers [MemberAdministrationScreen.kt]'s pure, DOM-free
 * predicates/formatters: [canEditCoreDataOf], [canEditRoleOf], [canChangeStatusOf],
 * [statusChangeConsequence], [pagerLabel]. Same DOM-free unit-test posture as [NavVisibilityTest] -- there is no rendering
 * harness in this module, only the logic feeding the actual screen is directly testable.
 */
class MemberAdministrationScreenTest {
    private val otherMemberId = "00000000-0000-0000-0000-000000000099"
    private val callerMemberId = "00000000-0000-0000-0000-000000000001"

    private fun row(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole? = AccountRole.MEMBER,
        anonymized: Boolean = false,
        id: String = otherMemberId,
    ) = MemberAdminRowDto(
        id = id,
        displayName = "Test Mitglied",
        email = "test@example.org",
        status = status,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = anonymized,
    )

    // ── canEditCoreDataOf ──

    @Test
    fun canEditCoreDataOf_boardAndAdmin_canEditAnOrdinaryMember() {
        assertTrue(canEditCoreDataOf(AccountRole.BOARD, row(role = AccountRole.MEMBER)))
        assertTrue(canEditCoreDataOf(AccountRole.ADMIN, row(role = AccountRole.MEMBER)))
        assertFalse(canEditCoreDataOf(AccountRole.MEMBER, row(role = AccountRole.MEMBER)))
        assertFalse(canEditCoreDataOf(null, row(role = AccountRole.MEMBER)))
    }

    @Test
    fun canEditCoreDataOf_escalatedTarget_isAdminOnly() {
        listOf(AccountRole.ADMIN, AccountRole.BOARD, AccountRole.TREASURER).forEach { escalatedRole ->
            assertFalse(
                canEditCoreDataOf(AccountRole.BOARD, row(role = escalatedRole)),
                "expected BOARD to be rejected for an escalated ($escalatedRole) target -- matches the server's " +
                    "Peer-Schutz in MemberService.updateMemberCoreData",
            )
            assertTrue(canEditCoreDataOf(AccountRole.ADMIN, row(role = escalatedRole)))
        }
    }

    @Test
    fun canEditCoreDataOf_anonymizedMember_isFalseEvenForAdmin() {
        assertFalse(canEditCoreDataOf(AccountRole.ADMIN, row(role = AccountRole.MEMBER, anonymized = true)))
    }

    // ── canEditRoleOf ──

    @Test
    fun canEditRoleOf_onlyAdmin_andOnlyWithAnAccount() {
        assertTrue(canEditRoleOf(AccountRole.ADMIN, callerMemberId, row(role = AccountRole.MEMBER)))
        assertFalse(canEditRoleOf(AccountRole.BOARD, callerMemberId, row(role = AccountRole.MEMBER)))
        assertFalse(canEditRoleOf(AccountRole.TREASURER, callerMemberId, row(role = AccountRole.MEMBER)))
        assertFalse(canEditRoleOf(null, callerMemberId, row(role = AccountRole.MEMBER)))
    }

    @Test
    fun canEditRoleOf_accountlessMember_isFalseEvenForAdmin() {
        assertFalse(canEditRoleOf(AccountRole.ADMIN, callerMemberId, row(role = null)))
    }

    @Test
    fun canEditRoleOf_anonymizedMember_isFalseEvenForAdmin() {
        assertFalse(canEditRoleOf(AccountRole.ADMIN, callerMemberId, row(role = AccountRole.MEMBER, anonymized = true)))
    }

    @Test
    fun canEditRoleOf_ownRow_isFalseEvenForAdmin() {
        // MemberService.updateMemberRole rejects a self-target unconditionally with
        // ForbiddenException, regardless of role/direction -- the editor must not offer the
        // "Rolle" section for an ADMIN's own roster row.
        assertFalse(canEditRoleOf(AccountRole.ADMIN, callerMemberId, row(role = AccountRole.ADMIN, id = callerMemberId)))
    }

    // ── canChangeStatusOf ──

    @Test
    fun canChangeStatusOf_boardAndAdmin_canChangeAnOrdinaryTransition() {
        assertTrue(canChangeStatusOf(AccountRole.BOARD, callerMemberId, row(status = MemberStatus.ACTIVE)))
        assertTrue(canChangeStatusOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.ACTIVE)))
        assertFalse(canChangeStatusOf(AccountRole.MEMBER, callerMemberId, row(status = MemberStatus.ACTIVE)))
        assertFalse(canChangeStatusOf(null, callerMemberId, row(status = MemberStatus.ACTIVE)))
    }

    @Test
    fun canChangeStatusOf_deceasedOrigin_isAdminOnly() {
        assertFalse(canChangeStatusOf(AccountRole.BOARD, callerMemberId, row(status = MemberStatus.DECEASED)))
        assertTrue(canChangeStatusOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.DECEASED)))
    }

    @Test
    fun canChangeStatusOf_notAdministrativelyManaged_isAlwaysFalse() {
        listOf(MemberStatus.APPLICATION, MemberStatus.GUEST, MemberStatus.REJECTED, MemberStatus.FRIEND).forEach { status ->
            assertFalse(
                canChangeStatusOf(AccountRole.ADMIN, callerMemberId, row(status = status)),
                "expected $status to offer no transition",
            )
        }
    }

    @Test
    fun canChangeStatusOf_anonymizedMember_isFalseEvenForAdmin() {
        assertFalse(canChangeStatusOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.ACTIVE, anonymized = true)))
    }

    @Test
    fun canChangeStatusOf_ownRow_isFalseEvenForAdmin() {
        // MemberService.updateMemberStatus rejects a self-target unconditionally, checked before
        // even the reason/transition validation (see its own KDoc "must never be a self-service
        // action") -- the editor must not offer the "Status" section for the caller's own row.
        assertFalse(
            canChangeStatusOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.ACTIVE, id = callerMemberId)),
        )
    }

    @Test
    fun canChangeStatusOf_escalatedTarget_isAdminOnly() {
        listOf(AccountRole.ADMIN, AccountRole.BOARD, AccountRole.TREASURER).forEach { escalatedRole ->
            assertFalse(
                canChangeStatusOf(AccountRole.BOARD, callerMemberId, row(status = MemberStatus.ACTIVE, role = escalatedRole)),
                "expected BOARD to be rejected for an escalated ($escalatedRole) target -- matches the server's " +
                    "Peer-Schutz in MemberService.updateMemberStatus",
            )
            assertTrue(canChangeStatusOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.ACTIVE, role = escalatedRole)))
        }
    }

    // ── hasAnyEditableSectionFor (Review Runde 4) ──
    // Pins the `canEditCoreDataOf(...) || canChangeStatusOf(...) || canEditRoleOf(...)` OR-chain
    // itself, not just its three components individually -- see this function's own KDoc for the
    // regression it guards: a BOARD caller on a FRIEND/APPLICANT/GUEST row with role==MEMBER has
    // canChangeStatusOf==false (status not administratively managed) AND canEditRoleOf==false (not
    // ADMIN), so ONLY canEditCoreDataOf keeps the "Bearbeiten" button enabled for that row. Dropping
    // that term from the OR-chain would silently disable core-data editing for exactly this case
    // without any of the three individual predicate tests above going red.

    @Test
    fun hasAnyEditableSectionFor_boardCallerOnEscalatedTargetRow_isFalse() {
        val row = row(status = MemberStatus.ACTIVE, role = AccountRole.BOARD, id = otherMemberId)
        assertFalse(hasAnyEditableSectionFor(AccountRole.BOARD, callerMemberId, row))
    }

    @Test
    fun hasAnyEditableSectionFor_boardCallerOnOwnRow_isFalse() {
        val row = row(status = MemberStatus.ACTIVE, role = AccountRole.BOARD, id = callerMemberId)
        assertFalse(hasAnyEditableSectionFor(AccountRole.BOARD, callerMemberId, row))
    }

    @Test
    fun hasAnyEditableSectionFor_boardCallerOnOrdinaryMemberRowWithNoStatusOrRoleTransition_isTrueViaCoreDataOnly() {
        // FRIEND is not administratively managed (canChangeStatusOf false, see
        // canChangeStatusOf_notAdministrativelyManaged_isAlwaysFalse above), and BOARD may never
        // edit roles (canEditRoleOf false) -- only canEditCoreDataOf keeps this true.
        val row = row(status = MemberStatus.FRIEND, role = AccountRole.MEMBER, id = otherMemberId)
        assertFalse(canChangeStatusOf(AccountRole.BOARD, callerMemberId, row))
        assertFalse(canEditRoleOf(AccountRole.BOARD, callerMemberId, row))
        assertTrue(hasAnyEditableSectionFor(AccountRole.BOARD, callerMemberId, row))
    }

    @Test
    fun hasAnyEditableSectionFor_adminCaller_isTrueEvenOnAnEscalatedRow() {
        val row = row(status = MemberStatus.ACTIVE, role = AccountRole.BOARD, id = otherMemberId)
        assertTrue(hasAnyEditableSectionFor(AccountRole.ADMIN, callerMemberId, row))
    }

    // ── statusChangeConsequence ──

    @Test
    fun statusChangeConsequence_toWithdrawnOrDeceased_mentionsSessionsCommitteesAndMandate() {
        val toWithdrawn = statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, hasAccount = true)
        val toDeceased = statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.DECEASED, hasAccount = true)
        listOf(toWithdrawn, toDeceased).forEach { text ->
            assertTrue(text.contains("Sitzungen"), text)
            assertTrue(text.contains("Gremien"), text)
            assertTrue(text.contains("SEPA-Mandat"), text)
        }
    }

    @Test
    fun statusChangeConsequence_toDonor_mentionsLoginBlockOnly() {
        val text = statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.DONOR, hasAccount = true)
        assertTrue(text.contains("Login"))
        assertFalse(text.contains("SEPA-Mandat"))
    }

    @Test
    fun statusChangeConsequence_toActiveWithoutAccount_warnsNoLoginAndNoContribution() {
        val text = statusChangeConsequence(MemberStatus.DONOR, MemberStatus.ACTIVE, hasAccount = false)
        assertTrue(text.contains("kein Login-Konto"))
        assertTrue(text.contains("Beitragstarif"))
    }

    @Test
    fun statusChangeConsequence_toActiveWithAccount_isTheGenericFallback() {
        val text = statusChangeConsequence(MemberStatus.DONOR, MemberStatus.ACTIVE, hasAccount = true)
        assertFalse(text.contains("kein Login-Konto"))
        assertFalse(text.contains("SEPA-Mandat"))
    }

    // ── pagerLabel ──

    @Test
    fun pagerLabel_middlePage() {
        assertEquals("26–50 von 407", pagerLabel(offset = 25, pageSize = 25, totalCount = 407))
    }

    @Test
    fun pagerLabel_firstPage() {
        assertEquals("1–25 von 407", pagerLabel(offset = 0, pageSize = 25, totalCount = 407))
    }

    @Test
    fun pagerLabel_lastPartialPage() {
        assertEquals("401–407 von 407", pagerLabel(offset = 400, pageSize = 25, totalCount = 407))
    }

    @Test
    fun pagerLabel_empty() {
        assertEquals("Keine Treffer", pagerLabel(offset = 0, pageSize = 25, totalCount = 0))
    }

    // ── canGrantAccountTo (Welle V1.2.13) ──

    @Test
    fun canGrantAccountTo_adminOnAccountlessRow_isTrue() {
        assertTrue(canGrantAccountTo(AccountRole.ADMIN, row(role = null)))
    }

    @Test
    fun canGrantAccountTo_nonAdminCallers_areFalse() {
        assertFalse(canGrantAccountTo(AccountRole.BOARD, row(role = null)))
        assertFalse(canGrantAccountTo(AccountRole.TREASURER, row(role = null)))
        assertFalse(canGrantAccountTo(AccountRole.MEMBER, row(role = null)))
        assertFalse(canGrantAccountTo(null, row(role = null)))
    }

    @Test
    fun canGrantAccountTo_rowAlreadyHasAnAccount_isFalse() {
        assertFalse(canGrantAccountTo(AccountRole.ADMIN, row(role = AccountRole.MEMBER)))
    }

    @Test
    fun canGrantAccountTo_anonymizedRow_isFalseEvenForAdmin() {
        // FoundationPersonalData.erase hard-deletes the account row on an Art. 17 erasure, so an
        // anonymized member is indistinguishable from a CSV import by role == null alone -- this
        // guard is what stops grantMemberAccount from becoming the one way to revive a login for a
        // DSGVO-erased person.
        assertFalse(canGrantAccountTo(AccountRole.ADMIN, row(role = null, anonymized = true)))
    }

    @Test
    fun canGrantAccountTo_deceasedRow_isFalseEvenForAdmin() {
        assertFalse(canGrantAccountTo(AccountRole.ADMIN, row(role = null, status = MemberStatus.DECEASED)))
    }

    @Test
    fun canGrantAccountTo_loginBlockedButNotDeceasedRows_areTrue() {
        assertTrue(canGrantAccountTo(AccountRole.ADMIN, row(role = null, status = MemberStatus.DONOR)))
        assertTrue(canGrantAccountTo(AccountRole.ADMIN, row(role = null, status = MemberStatus.WITHDRAWN)))
    }

    @Test
    fun canGrantAccountTo_impliesHasAnyEditableSectionFor_acrossEveryRoleAndAccountState() {
        // The actually-provable property: canGrantAccountTo => hasAnyEditableSectionFor (an
        // implication, not an equivalence -- canEditCoreDataOf alone already makes several of
        // these cases true regardless of canGrantAccountTo, see the isolating test below for why
        // no case exists where ONLY canGrantAccountTo differs the outcome).
        val roles = listOf(AccountRole.ADMIN, AccountRole.BOARD, AccountRole.TREASURER, AccountRole.MEMBER, null)
        val accountStates = listOf(null, AccountRole.MEMBER)
        roles.forEach { callerRole ->
            accountStates.forEach { accountState ->
                val candidateRow = row(role = accountState)
                if (canGrantAccountTo(callerRole, candidateRow)) {
                    assertTrue(
                        hasAnyEditableSectionFor(callerRole, callerMemberId, candidateRow),
                        "canGrantAccountTo($callerRole, role=$accountState) was true but hasAnyEditableSectionFor was false",
                    )
                }
            }
        }
    }

    // ── grantAccountConsequence (Welle V1.2.13) ──

    @Test
    fun grantAccountConsequence_donor_mentionsLogin() {
        val text = grantAccountConsequence(MemberStatus.DONOR)
        assertTrue(text != null && text.contains("Login"))
    }

    @Test
    fun grantAccountConsequence_withdrawn_mentionsLogin() {
        val text = grantAccountConsequence(MemberStatus.WITHDRAWN)
        assertTrue(text != null && text.contains("Login"))
    }

    @Test
    fun grantAccountConsequence_active_isNull() {
        assertEquals(null, grantAccountConsequence(MemberStatus.ACTIVE))
    }
}
