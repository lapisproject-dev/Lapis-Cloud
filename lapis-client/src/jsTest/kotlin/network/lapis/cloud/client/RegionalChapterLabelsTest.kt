package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- pure, DOM-free coverage
 * for `RegionalChaptersScreen.kt`'s labels/predicates: [chapterDeleteBlockReason],
 * [chapterCountsLine], [chapterNameCheck], [officerCandidateState], [officerCandidateQuery] and
 * [officerGrantConsequence].
 */
class RegionalChapterLabelsTest {
    private fun chapter(
        assignedMemberCount: Int,
        activeOfficerCount: Int,
        activeMemberCount: Int = assignedMemberCount,
    ) = RegionalChapterDto(
        id = "c1",
        name = "Bayern",
        activeMemberCount = activeMemberCount,
        assignedMemberCount = assignedMemberCount,
        activeOfficerCount = activeOfficerCount,
    )

    @Test
    fun chapterDeleteBlockReason_null_whenBothCountsAreZero() {
        assertNull(chapterDeleteBlockReason(chapter(assignedMemberCount = 0, activeOfficerCount = 0)))
    }

    @Test
    fun chapterDeleteBlockReason_namesBothCounts_whenEitherIsNonZero() {
        val membersOnly = chapterDeleteBlockReason(chapter(assignedMemberCount = 3, activeOfficerCount = 0))
        assertTrue(membersOnly != null && membersOnly.contains("3") && membersOnly.contains("0"))

        val officersOnly = chapterDeleteBlockReason(chapter(assignedMemberCount = 0, activeOfficerCount = 1))
        assertTrue(officersOnly != null && officersOnly.contains("0") && officersOnly.contains("1"))

        val both = chapterDeleteBlockReason(chapter(assignedMemberCount = 3, activeOfficerCount = 1))
        assertTrue(both != null && both.contains("3") && both.contains("1"))
    }

    @Test
    fun officerGrantConsequence_namesTheMemberAndTheChapter() {
        val text = officerGrantConsequence("Erika Musterfrau", "Bayern")
        assertTrue(text.contains("Erika Musterfrau"))
        assertTrue(text.contains("Bayern"))
        // "protokolliert" -- the process is logged, per plan §2.5 "officerGrantConsequence".
        assertTrue(text.contains("protokolliert"))
    }

    @Test
    fun chapterCountsLine_containsAllThreeNumbers() {
        val text = chapterCountsLine(chapter(assignedMemberCount = 5, activeOfficerCount = 2, activeMemberCount = 7))
        assertTrue(text.contains("7"))
        assertTrue(text.contains("5"))
        assertTrue(text.contains("2"))
    }

    @Test
    fun chapterNameCheck_bordersOfTheAllowedLength() {
        assertTrue(chapterNameCheck("A").let { it is FieldCheck.Invalid }, "1 char is below NAME_MIN=2")
        assertTrue(chapterNameCheck("AB") is FieldCheck.Ok, "2 chars is the minimum")
        assertTrue(chapterNameCheck("A".repeat(80)) is FieldCheck.Ok, "80 chars is the maximum")
        assertTrue(chapterNameCheck("A".repeat(81)) is FieldCheck.Invalid, "81 chars is above NAME_MAX=80")
    }

    @Test
    fun chapterNameCheck_rejectsAControlCharacter() {
        assertTrue(chapterNameCheck("Bayern\u0000Nord") is FieldCheck.Invalid)
    }

    @Test
    fun chapterNameCheck_normalizesWhitespaceBeforeValidating() {
        // Collapsed to "AB" (2 chars) after normalizeName -- still valid, not "  A   B  " (9 chars).
        assertTrue(chapterNameCheck("  A   B  ") is FieldCheck.Ok)
    }

    @Test
    fun officerCandidateState_threeStates() {
        assertEquals(
            OfficerCandidateState.ALREADY_OFFICER,
            officerCandidateState(
                candidateRow(role = network.lapis.cloud.shared.domain.AccountRole.MEMBER, id = "m1"),
                officerMemberIds = setOf("m1"),
            ),
        )
        assertEquals(
            OfficerCandidateState.NO_ACCOUNT,
            officerCandidateState(candidateRow(role = null, id = "m2"), officerMemberIds = emptySet()),
        )
        assertEquals(
            OfficerCandidateState.ELIGIBLE,
            officerCandidateState(
                candidateRow(role = network.lapis.cloud.shared.domain.AccountRole.MEMBER, id = "m3"),
                officerMemberIds = emptySet(),
            ),
        )
    }

    private fun candidateRow(
        role: network.lapis.cloud.shared.domain.AccountRole?,
        id: String,
    ) = network.lapis.cloud.shared.domain.MemberAdminRowDto(
        id = id,
        displayName = "Candidate $id",
        email = "$id@example.org",
        status = MemberStatus.ACTIVE,
        role = role,
        joinedAt = kotlinx.datetime.LocalDate(2026, 1, 1),
    )

    @Test
    fun officerCandidateQuery_scopesToActiveStatusAndTheChapter() {
        val query: MemberAdminQuery = officerCandidateQuery("Meier", "c1")
        assertEquals(setOf(MemberStatus.ACTIVE), query.statuses)
        assertEquals("c1", query.regionalChapterId)
        assertEquals("Meier", query.search)
        assertEquals(10, query.limit)
    }

    @Test
    fun officerCandidateQuery_blankSearch_becomesNull() {
        assertNull(officerCandidateQuery("", "c1").search)
        assertNull(officerCandidateQuery("   ", "c1").search)
    }
}
