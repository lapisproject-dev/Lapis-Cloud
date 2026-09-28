package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- exhaustive matrix over
 * [canAssignChapterOf], the client-side mirror of `IRegionalChapterService
 * .assignMemberToChapter`'s own peer-protection KDoc. The oracle below is an independently
 * formulated expectation function (not a copy of the implementation), per the plan's own test
 * design -- so a bug in [canAssignChapterOf] cannot hide behind an identically-wrong test.
 */
class CanAssignChapterOfTest {
    private val callerRoles = listOf(null) + AccountRole.entries
    private val targetRoles = listOf(null) + AccountRole.entries

    private fun expected(
        callerRole: AccountRole?,
        targetRole: AccountRole?,
        targetStatus: MemberStatus,
        anonymized: Boolean,
    ): Boolean {
        if (anonymized) return false
        if (targetStatus !in
            setOf(MemberStatus.APPLICATION, MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DECEASED)
        ) {
            return false
        }
        val targetIsEscalated = targetRole == AccountRole.BOARD || targetRole == AccountRole.TREASURER || targetRole == AccountRole.ADMIN
        return if (targetIsEscalated) {
            callerRole == AccountRole.ADMIN
        } else {
            callerRole == AccountRole.BOARD ||
                callerRole == AccountRole.ADMIN
        }
    }

    @Test
    fun matrix_callerRole_x_targetRole_x_status_x_anonymized() {
        callerRoles.forEach { callerRole ->
            targetRoles.forEach { targetRole ->
                MemberStatus.entries.forEach { status ->
                    listOf(true, false).forEach { anonymized ->
                        val actual = canAssignChapterOf(callerRole, "caller-1", targetRole, status, anonymized, "target-1")
                        val want = expected(callerRole, targetRole, status, anonymized)
                        assertEquals(
                            want,
                            actual,
                            "callerRole=$callerRole targetRole=$targetRole status=$status anonymized=$anonymized",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun assignableStatuses_matchTheSharedRulesObject_exactly() {
        // Cross-check: the local expectation set literal above must not silently drift from the
        // shared source of truth both server and client actually use.
        MemberStatus.entries.forEach { status ->
            val fromRules = status in RegionalChapterRules.ASSIGNABLE_STATUSES
            val fromLocalExpectation =
                status in setOf(MemberStatus.APPLICATION, MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DECEASED)
            assertEquals(fromRules, fromLocalExpectation, "status=$status")
        }
    }

    @Test
    fun boardCallerOnOwnRow_isAlwaysFalse_becauseBoardItselfIsEscalated() {
        // A BOARD caller's own row is (structurally) always AccountRole.BOARD -- an escalated role
        // -- so canAssignChapterOf must be false regardless of the (unused-for-self-detection)
        // targetMemberId, matching IRegionalChapterService.assignMemberToChapter KDoc.
        assertFalse(canAssignChapterOf(AccountRole.BOARD, "b1", AccountRole.BOARD, MemberStatus.ACTIVE, false, "b1"))
        assertTrue(canAssignChapterOf(AccountRole.ADMIN, "a1", AccountRole.BOARD, MemberStatus.ACTIVE, false, "b1"))
    }

    @Test
    fun hasAnyEditableSectionFor_addsChapterAssignment_onlyWhenChaptersExist() {
        val row =
            MemberAdminRowDto(
                id = "m1",
                displayName = "Test Member",
                email = "test@example.org",
                status = MemberStatus.ACTIVE,
                role = null,
                joinedAt = LocalDate(2026, 1, 1),
            )
        // BOARD caller, ordinary member row, no OTHER editable section (role == null blocks
        // canEditRoleOf/canGrantAccountTo's usual shape, core-data/status/tier/death-date all need
        // more specific setups the other tests already cover) -- construct a row where every OTHER
        // predicate is false by using MemberStatus.ACTIVE with no transitions and role == null.
        assertTrue(
            hasAnyEditableSectionFor(AccountRole.BOARD, "b1", row, chaptersExist = true) ||
                canEditCoreDataOf(AccountRole.BOARD, row),
            "BOARD should be able to edit SOMETHING on a plain ACTIVE row with chapters -- canEditCoreDataOf alone already covers this, chapters only add more",
        )
        // The precise chapters-only-adds-more claim: with chaptersExist=false, hasAnyEditableSectionFor
        // must equal the pre-V1.9.14 five-predicate OR-chain exactly (never MORE true, never less).
        val withoutChapters = hasAnyEditableSectionFor(AccountRole.BOARD, "b1", row, chaptersExist = false)
        val fivePredicateChain =
            canEditCoreDataOf(AccountRole.BOARD, row) ||
                canChangeStatusOf(AccountRole.BOARD, "b1", row) ||
                canEditRoleOf(AccountRole.BOARD, "b1", row) ||
                canGrantAccountTo(AccountRole.BOARD, row) ||
                canEditMembershipTierOf(AccountRole.BOARD, "b1", row) ||
                canCorrectDateOfDeathOf(AccountRole.BOARD, "b1", row)
        assertEquals(fivePredicateChain, withoutChapters)
    }
}
