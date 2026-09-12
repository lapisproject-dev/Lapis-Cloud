package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche" -- covers the pure, DOM-independent
 * helper functions extracted from `ContributionReliefQueueScreen.kt`. No rendering harness exists
 * in this module (verified: `SocialModerationScreenTest.kt` only covers labels/colors,
 * `renderXScreen` is out of scope by every existing `*ScreenTest`'s own KDoc) -- these five pure
 * functions are the only way this screen's cursor/pagination/Vier-Augen/note-validation logic can
 * be tested at all.
 */
class ContributionReliefQueueScreenTest {
    private fun reliefRequest(
        id: String = "req-1",
        subjectMemberId: String = "member-1",
        requestedAt: LocalDateTime = LocalDateTime(2026, 1, 1, 10, 0),
    ): ContributionReliefRequestDto =
        ContributionReliefRequestDto(
            id = id,
            subjectMemberId = subjectMemberId,
            subjectDisplayName = "Test Mitglied",
            kind = ContributionReliefKind.EXEMPTION,
            status = ContributionReliefStatus.REQUESTED,
            reasonCategory = ContributionReliefReason.FINANCIAL_HARDSHIP,
            reasonText = null,
            requestedAt = requestedAt,
            requestedBy = subjectMemberId,
            requestedByDisplayName = "Test Mitglied",
        )

    // ---- reliefHasMorePages -------------------------------------------------------------------------

    @Test
    fun reliefHasMorePages_belowCapacity_isFalse() {
        assertFalse(reliefHasMorePages(pageSize = 199, capacity = 200))
    }

    @Test
    fun reliefHasMorePages_atCapacity_isTrue() {
        assertTrue(reliefHasMorePages(pageSize = 200, capacity = 200))
    }

    @Test
    fun reliefHasMorePages_aboveCapacity_isTrue() {
        // Cannot actually happen against the real RPC (server-side limit(200)), but the predicate
        // itself must not silently invert for an out-of-range input.
        assertTrue(reliefHasMorePages(pageSize = 201, capacity = 200))
    }

    // ---- nextReliefCursor ----------------------------------------------------------------------------

    @Test
    fun nextReliefCursor_emptyPage_returnsNull() {
        assertNull(nextReliefCursor(emptyList()))
    }

    @Test
    fun nextReliefCursor_nonEmptyPage_usesTheLastElement() {
        val first = reliefRequest(id = "req-1", requestedAt = LocalDateTime(2026, 1, 1, 10, 0))
        val last = reliefRequest(id = "req-2", requestedAt = LocalDateTime(2026, 1, 2, 10, 0))
        val cursor = nextReliefCursor(listOf(first, last))
        assertEquals(ReliefPageCursor(requestedAt = last.requestedAt, id = last.id), cursor)
    }

    // ---- reliefDecisionBlockedBySelf (Vier-Augen-Prinzip) -------------------------------------------

    @Test
    fun reliefDecisionBlockedBySelf_subjectIsCurrentMember_isTrue() {
        val request = reliefRequest(subjectMemberId = "member-1")
        assertTrue(reliefDecisionBlockedBySelf(request, currentMemberId = "member-1"))
    }

    @Test
    fun reliefDecisionBlockedBySelf_subjectIsAnotherMember_isFalse() {
        val request = reliefRequest(subjectMemberId = "member-1")
        assertFalse(reliefDecisionBlockedBySelf(request, currentMemberId = "member-2"))
    }

    @Test
    fun reliefDecisionBlockedBySelf_currentMemberIdIsNull_isFalse() {
        val request = reliefRequest(subjectMemberId = "member-1")
        assertFalse(reliefDecisionBlockedBySelf(request, currentMemberId = null))
    }

    // ---- reliefDecisionNoteIsValid ---------------------------------------------------------------

    @Test
    fun reliefDecisionNoteIsValid_null_isFalse() {
        assertFalse(reliefDecisionNoteIsValid(null))
    }

    @Test
    fun reliefDecisionNoteIsValid_blank_isFalse() {
        assertFalse(reliefDecisionNoteIsValid(""))
    }

    @Test
    fun reliefDecisionNoteIsValid_whitespaceOnly_isFalse() {
        assertFalse(reliefDecisionNoteIsValid("   "))
    }

    @Test
    fun reliefDecisionNoteIsValid_realText_isTrue() {
        assertTrue(reliefDecisionNoteIsValid("Rücksprache mit dem Mitglied gehalten."))
    }
}
