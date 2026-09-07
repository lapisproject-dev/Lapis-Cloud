package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.2.12 "Mitgliederverwaltung" -- covers [MemberAdministrationScreen.kt]'s pure, DOM-free
 * predicates/formatters: [canEditCoreDataOf], [canEditRoleOf], [canChangeStatusOf],
 * [canGrantAccountTo], [canEditMembershipTierOf], [hasAnyEditableSectionFor],
 * [statusChangeConsequence], [grantAccountConsequence], [pagerLabel]. Same DOM-free unit-test
 * posture as [NavVisibilityTest] -- there is no rendering harness in this module, only the logic
 * feeding the actual screen is directly testable.
 */
class MemberAdministrationScreenTest {
    private val otherMemberId = "00000000-0000-0000-0000-000000000099"
    private val callerMemberId = "00000000-0000-0000-0000-000000000001"

    private fun row(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole? = AccountRole.MEMBER,
        anonymized: Boolean = false,
        id: String = otherMemberId,
        membershipTierId: String? = null,
        // Welle V1.4.4.5
        dateOfDeath: LocalDate? = null,
        familyRole: FamilyMemberRole? = null,
    ) = MemberAdminRowDto(
        id = id,
        displayName = "Test Mitglied",
        email = "test@example.org",
        status = status,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = anonymized,
        membershipTierId = membershipTierId,
        dateOfDeath = dateOfDeath,
        familyRole = familyRole,
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

    @Test
    fun hasAnyEditableSectionFor_boardCallerOnEscalatedTargetRowWithARemovableTier_isFalse() {
        // Security fix (Welle V1.4.4.4 review, MAJOR finding): `canEditMembershipTierOf` now applies
        // the SAME Peer-Schutz as every other section, so a BOARD caller must not be able to strip a
        // fellow BOARD/TREASURER/ADMIN peer's membership tier (removing their payment obligation) any
        // more than they may edit that peer's core data, status, or role -- see
        // MemberService.updateMemberMembershipTier's own ESCALATED_ROLES peer gate. This SUPERSEDES
        // the pre-fix test of the same shape (which pinned the vulnerable behaviour: BOARD could
        // remove an escalated peer's tier even though all four other sections were correctly
        // blocked) -- all FIVE predicates are false now, so the "Bearbeiten" button stays disabled.
        val row = row(status = MemberStatus.ACTIVE, role = AccountRole.BOARD, id = otherMemberId, membershipTierId = "tier-1")
        assertFalse(canEditCoreDataOf(AccountRole.BOARD, row))
        assertFalse(canChangeStatusOf(AccountRole.BOARD, callerMemberId, row))
        assertFalse(canEditRoleOf(AccountRole.BOARD, callerMemberId, row))
        assertFalse(canGrantAccountTo(AccountRole.BOARD, row))
        assertFalse(canEditMembershipTierOf(AccountRole.BOARD, callerMemberId, row))
        assertFalse(hasAnyEditableSectionFor(AccountRole.BOARD, callerMemberId, row))
    }

    @Test
    fun canEditMembershipTierOf_adminCaller_stillTrueOnEscalatedPeerRow() {
        // ADMIN is exempt from the Peer-Schutz gate (only a SELF-target stays forbidden, see
        // canEditMembershipTierOf_selfTarget_isAlwaysFalseRegardlessOfRole below) -- same asymmetry
        // canEditCoreDataOf/canChangeStatusOf already establish.
        val row = row(status = MemberStatus.ACTIVE, role = AccountRole.BOARD, id = otherMemberId, membershipTierId = "tier-1")
        assertTrue(canEditMembershipTierOf(AccountRole.ADMIN, callerMemberId, row))
    }

    // ── canEditMembershipTierOf (Welle V1.4.4.4) ──
    // Rollen-Asymmetrie gemirrort an `IMemberService.updateMemberMembershipTier`s eigener KDoc:
    // Zuweisen eines echten Tarifs braucht TREASURER/ADMIN, Entfernen (Tarif = null) nur
    // `isPrivileged` (BOARD/ADMIN) -- dieser Abschnitt hier gated nur die SICHTBARKEIT des
    // "Beitragstarif"-Abschnitts, nicht welche der beiden Aktionen darin freigeschaltet ist.

    @Test
    fun canEditMembershipTierOf_treasurer_isTrueRegardlessOfCurrentTier() {
        // TREASURER darf jederzeit einen (anderen) Tarif zuweisen, unabhaengig vom aktuellen --
        // bewusst KEIN `membershipTierId != null`-Gate, siehe Funktions-KDoc.
        assertTrue(canEditMembershipTierOf(AccountRole.TREASURER, callerMemberId, row(membershipTierId = null)))
        assertTrue(canEditMembershipTierOf(AccountRole.TREASURER, callerMemberId, row(membershipTierId = "tier-1")))
    }

    @Test
    fun canEditMembershipTierOf_board_onlyWhenARemovableTierExists() {
        // BOARD darf nur entfernen (Tarif = null), niemals zuweisen -- also nur true, wenn
        // aktuell ein Tarif existiert, den es entfernen koennte.
        assertTrue(canEditMembershipTierOf(AccountRole.BOARD, callerMemberId, row(membershipTierId = "tier-1")))
        assertFalse(canEditMembershipTierOf(AccountRole.BOARD, callerMemberId, row(membershipTierId = null)))
    }

    @Test
    fun canEditMembershipTierOf_admin_isAlwaysTrueRegardlessOfCurrentTier() {
        assertTrue(canEditMembershipTierOf(AccountRole.ADMIN, callerMemberId, row(membershipTierId = null)))
        assertTrue(canEditMembershipTierOf(AccountRole.ADMIN, callerMemberId, row(membershipTierId = "tier-1")))
    }

    @Test
    fun canEditMembershipTierOf_plainMemberOrNullCaller_isAlwaysFalse() {
        assertFalse(canEditMembershipTierOf(AccountRole.MEMBER, callerMemberId, row(membershipTierId = "tier-1")))
        assertFalse(canEditMembershipTierOf(null, callerMemberId, row(membershipTierId = "tier-1")))
    }

    @Test
    fun canEditMembershipTierOf_anonymizedMember_isFalseEvenForAdminOrTreasurer() {
        assertFalse(canEditMembershipTierOf(AccountRole.ADMIN, callerMemberId, row(membershipTierId = "tier-1", anonymized = true)))
        assertFalse(canEditMembershipTierOf(AccountRole.TREASURER, callerMemberId, row(membershipTierId = "tier-1", anonymized = true)))
    }

    @Test
    fun canEditMembershipTierOf_selfTarget_isAlwaysFalseRegardlessOfRole() {
        // Security fix (Welle V1.4.4.4 review, MAJOR finding) -- unconditional, mirrors
        // MemberService.updateMemberMembershipTier's own self-target ForbiddenException: even ADMIN
        // may not edit their OWN membership tier through this dialog.
        val ownRow = row(id = callerMemberId, membershipTierId = "tier-1")
        assertFalse(canEditMembershipTierOf(AccountRole.ADMIN, callerMemberId, ownRow))
        assertFalse(canEditMembershipTierOf(AccountRole.TREASURER, callerMemberId, ownRow))
        assertFalse(canEditMembershipTierOf(AccountRole.BOARD, callerMemberId, ownRow))
    }

    @Test
    fun canEditMembershipTierOf_escalatedPeerRow_isFalseUnlessCallerIsAdmin() {
        // Security fix (Welle V1.4.4.4 review, MAJOR finding) -- same ESCALATED_ROLES peer gate
        // canEditCoreDataOf/canChangeStatusOf already establish, now also for the tier section.
        listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN).forEach { escalatedRole ->
            val row = row(id = otherMemberId, role = escalatedRole, membershipTierId = "tier-1")
            assertFalse(canEditMembershipTierOf(AccountRole.BOARD, callerMemberId, row))
            assertFalse(canEditMembershipTierOf(AccountRole.TREASURER, callerMemberId, row))
            assertTrue(canEditMembershipTierOf(AccountRole.ADMIN, callerMemberId, row))
        }
    }

    // ── statusChangeConsequence ──

    @Test
    fun statusChangeConsequence_toWithdrawn_mentionsSessionsCommitteesAndMandate() {
        val text = statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, hasAccount = true)
        assertTrue(text.contains("Sitzungen"), text)
        assertTrue(text.contains("Gremien"), text)
        assertTrue(text.contains("SEPA-Mandat"), text)
        assertFalse(text.contains("§ 38 BGB"), text)
    }

    // Welle V1.4.4.5 -- DECEASED was split off from the WITHDRAWN case above; it now carries its
    // own, legally-grounded text (see statusChangeConsequence KDoc).
    @Test
    fun statusChangeConsequence_toDeceased_mentionsLegalBasisAndNoNotificationAndStillSessionsCommitteesAndMandate() {
        val text = statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.DECEASED, hasAccount = true)
        assertTrue(text.contains("§ 38 BGB"), text)
        assertTrue(text.contains("Sitzungen"), text)
        assertTrue(text.contains("Gremien"), text)
        assertTrue(text.contains("SEPA-Mandat"), text)
        assertTrue(text.contains("Nachlassangelegenheit"), text)
        assertTrue(text.contains("niemand benachrichtigt"), text)
    }

    @Test
    fun statusChangeConsequence_toDeceased_payerFamilyRole_mentionsMissingNewPayer() {
        val withPayer =
            statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.DECEASED, hasAccount = true, familyRole = FamilyMemberRole.PAYER)
        assertTrue(withPayer.contains("neuen Zahler"), withPayer)

        val withDependent =
            statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.DECEASED, hasAccount = true, familyRole = FamilyMemberRole.DEPENDENT)
        assertFalse(withDependent.contains("neuen Zahler"), withDependent)

        val withoutFamily = statusChangeConsequence(MemberStatus.ACTIVE, MemberStatus.DECEASED, hasAccount = true, familyRole = null)
        assertFalse(withoutFamily.contains("neuen Zahler"), withoutFamily)
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

    // ── deceasedDateNote (Welle V1.4.4.5) ──

    @Test
    fun deceasedDateNote_nonDeceasedStatus_isNull() {
        assertNull(deceasedDateNote(MemberStatus.ACTIVE, null))
        assertNull(deceasedDateNote(MemberStatus.ACTIVE, LocalDate(2026, 1, 1)))
        assertNull(deceasedDateNote(MemberStatus.WITHDRAWN, null))
    }

    @Test
    fun deceasedDateNote_deceasedWithoutDate_saysDateMissing() {
        val note = deceasedDateNote(MemberStatus.DECEASED, null)
        // .contains, not assertEquals -- tr() (no substitution args) prefixes the KVision
        // extraction-tooling marker "###KvI18nS###" onto its return value in this test's bare
        // I18nCatalogManager setup (see TestI18nSetup.kt); every other bare-tr() assertion in this
        // file already follows the same .contains posture for exactly this reason.
        assertTrue(note!!.contains("Sterbedatum fehlt"))
    }

    @Test
    fun deceasedDateNote_deceasedWithDate_containsTheDateAndNeverACross() {
        val note = deceasedDateNote(MemberStatus.DECEASED, LocalDate(2026, 3, 3))
        assertTrue(note!!.contains("2026-03-03"))
        assertFalse(note.contains("†"), "must never use a religiously-coded cross symbol")
    }

    // ── canCorrectDateOfDeathOf (Welle V1.4.4.5) ──

    @Test
    fun canCorrectDateOfDeathOf_adminOnDeceasedForeignRow_isTrue() {
        assertTrue(canCorrectDateOfDeathOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.DECEASED)))
    }

    @Test
    fun canCorrectDateOfDeathOf_boardOrTreasurer_isFalse() {
        assertFalse(canCorrectDateOfDeathOf(AccountRole.BOARD, callerMemberId, row(status = MemberStatus.DECEASED)))
        assertFalse(canCorrectDateOfDeathOf(AccountRole.TREASURER, callerMemberId, row(status = MemberStatus.DECEASED)))
    }

    @Test
    fun canCorrectDateOfDeathOf_adminOnNonDeceasedRow_isFalse() {
        assertFalse(canCorrectDateOfDeathOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.ACTIVE)))
    }

    @Test
    fun canCorrectDateOfDeathOf_adminOnOwnRow_isFalse() {
        assertFalse(
            canCorrectDateOfDeathOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.DECEASED, id = callerMemberId)),
        )
    }

    @Test
    fun canCorrectDateOfDeathOf_adminOnAnonymizedRow_isFalse() {
        assertFalse(
            canCorrectDateOfDeathOf(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.DECEASED, anonymized = true)),
        )
    }

    // Regression against the V1.4.4.4 finding (see hasAnyEditableSectionFor KDoc): an ADMIN on a
    // DECEASED row must have an editable section (here: the new "Sterbedatum" one), so the
    // "Bearbeiten" button stays enabled.
    @Test
    fun hasAnyEditableSectionFor_adminOnDeceasedRow_isTrue() {
        assertTrue(hasAnyEditableSectionFor(AccountRole.ADMIN, callerMemberId, row(status = MemberStatus.DECEASED)))
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
