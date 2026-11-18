package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartgReviewerEventDto
import network.lapis.cloud.shared.domain.PartgReviewerEventKind
import network.lapis.cloud.shared.domain.PartgReviewerFileFormat
import network.lapis.cloud.shared.domain.PartgReviewerInvitationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.310 (R6b) -- the pure logic of the auditor's access: words, validation, the token of the link, the code input. */
class PartyReportReviewerLogicTest {
    private val at = LocalDateTime(2026, 11, 18, 9, 30)

    private fun event(
        kind: PartgReviewerEventKind,
        number: Int? = null,
        format: PartgReviewerFileFormat? = null,
    ) = PartgReviewerEventDto(kind = kind, occurredAt = at, versionNumber = number, format = format)

    @Test
    fun everyStatus_hasAWord_andTheWordsDiffer() {
        val words = PartgReviewerInvitationStatus.entries.map { reviewerStatusWord(it) }
        assertEquals(4, words.toSet().size)
        assertTrue(words.all { it.isNotBlank() })
    }

    @Test
    fun everyEventKind_hasAText_withoutAnUnresolvedPlaceholder() {
        PartgReviewerEventKind.entries.forEach { kind ->
            val text = reviewerEventText(event(kind, number = 2, format = PartgReviewerFileFormat.PDF))
            assertTrue(text.isNotBlank(), "text of $kind")
            assertTrue(!text.contains("%1") && !text.contains("%2"), "placeholder left in '$text'")
        }
        assertEquals("Version 2 geladen (PDF)", reviewerEventText(event(PartgReviewerEventKind.DOWNLOAD, 2, PartgReviewerFileFormat.PDF)))
        assertEquals("Version 3 angesehen", reviewerEventText(event(PartgReviewerEventKind.DOCUMENT_VIEWED, 3)))
        assertEquals("Version angesehen", reviewerEventText(event(PartgReviewerEventKind.DOCUMENT_VIEWED, null)))
    }

    @Test
    fun theVersionsText_listsTheNumbers() {
        assertEquals("Version 2, 3", reviewerVersionsText(listOf(2, 3)))
        assertEquals("Keine Version mehr", reviewerVersionsText(emptyList()))
    }

    @Test
    fun anInvitation_needsAPlainAddress_andAtLeastOneVersion() {
        assertEquals(emptyList(), reviewerInviteProblems("pruefer@example.org", 1))
        assertEquals(listOf(ReviewerInviteProblem.EMAIL), reviewerInviteProblems("kein-at", 2))
        assertEquals(listOf(ReviewerInviteProblem.NO_VERSION), reviewerInviteProblems("pruefer@example.org", 0))
        assertEquals(listOf(ReviewerInviteProblem.EMAIL, ReviewerInviteProblem.NO_VERSION), reviewerInviteProblems("", 0))
        assertEquals(listOf(ReviewerInviteProblem.EMAIL), reviewerInviteProblems("a@example.org\nBcc: x@example.org", 1))
        ReviewerInviteProblem.entries.forEach { assertTrue(reviewerProblemText(it).isNotBlank()) }
    }

    @Test
    fun theTokenIsReadFromTheFragmentOnly_andOnlyInTheKnownShape() {
        val token = "A".repeat(43)
        assertEquals(token, reviewerTokenFromHash("#t=$token"))
        assertEquals(token, reviewerTokenFromHash("t=$token"))
        assertNull(reviewerTokenFromHash(""))
        assertNull(reviewerTokenFromHash("#"))
        assertNull(reviewerTokenFromHash("#x=$token"))
        assertNull(reviewerTokenFromHash("#t=short"))
        assertNull(reviewerTokenFromHash("#t=" + "A".repeat(65)))
        assertNull(reviewerTokenFromHash("#t=" + "A".repeat(30) + "+/="))
        assertNull(reviewerTokenFromHash("#t=$token&x=1"))
        assertNull(reviewerTokenFromHash("#/route?t=$token"))
    }

    @Test
    fun onlyTheExactPath_isTheAuditorsPage() {
        assertTrue(isReviewerPath("/pruefung"))
        assertTrue(!isReviewerPath("/pruefung/"))
        assertTrue(!isReviewerPath("/app"))
        assertTrue(!isReviewerPath("/"))
    }

    @Test
    fun theCodeInput_dropsBlanks_andNeedsSixDigits() {
        assertEquals("482913", reviewerCodeInput("482 913"))
        assertEquals("482913", reviewerCodeInput(" 482913 "))
        assertNull(reviewerCodeInput("48291"))
        assertNull(reviewerCodeInput("4829133"))
        assertNull(reviewerCodeInput("48a913"))
        assertNull(reviewerCodeInput(""))
    }

    @Test
    fun theValidityLabel_namesTheDays() {
        assertEquals("14 Tage", reviewerValidityLabel(14))
    }
}
