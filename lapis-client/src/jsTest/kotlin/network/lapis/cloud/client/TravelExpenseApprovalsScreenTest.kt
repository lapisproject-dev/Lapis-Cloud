package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.11 -- covers the pure, DOM-independent predicates in
 * `TravelExpenseApprovalsScreen.kt`, same scope posture as [ContributionReliefQueueScreenTest].
 */
class TravelExpenseApprovalsScreenTest {
    private fun sampleReport(
        subjectMemberId: String = "subject-1",
        requestedBy: String = "subject-1",
        submittedAt: LocalDateTime = LocalDateTime(2026, 1, 1, 10, 0),
        id: String = "report-1",
    ) = TravelExpenseReportDto(
        id = id,
        subjectMemberId = subjectMemberId,
        subjectDisplayName = "Test Mitglied",
        status = TravelExpenseReportStatus.REQUESTED,
        purpose = "Konferenz",
        travelFrom = LocalDate(2026, 1, 5),
        travelTo = LocalDate(2026, 1, 5),
        totalAmount = 36.0.toDecimal(),
        lines = emptyList(),
        createdAt = LocalDateTime(2026, 1, 1, 9, 0),
        submittedAt = submittedAt,
        requestedBy = requestedBy,
        requestedByDisplayName = "Antragsteller",
    )

    // ---- travelExpenseDecisionBlockedBySelf -- BEIDE Wege ------------------------------------

    @Test
    fun blockedBySelf_weg1_subjectEqualsCurrentMember() {
        val report = sampleReport(subjectMemberId = "board-1", requestedBy = "board-1")
        assertTrue(travelExpenseDecisionBlockedBySelf(report, currentMemberId = "board-1"))
    }

    @Test
    fun blockedBySelf_weg2_requestedByEqualsCurrentMemberButSubjectDiffers() {
        val report = sampleReport(subjectMemberId = "victim-1", requestedBy = "board-1")
        assertTrue(travelExpenseDecisionBlockedBySelf(report, currentMemberId = "board-1"))
    }

    @Test
    fun blockedBySelf_neitherPathAppliesIsNotBlocked() {
        val report = sampleReport(subjectMemberId = "subject-1", requestedBy = "subject-1")
        assertFalse(travelExpenseDecisionBlockedBySelf(report, currentMemberId = "board-2"))
    }

    @Test
    fun blockedBySelf_nullCurrentMemberIdIsNeverBlocked() {
        val report = sampleReport(subjectMemberId = "subject-1", requestedBy = "subject-1")
        assertFalse(travelExpenseDecisionBlockedBySelf(report, currentMemberId = null))
    }

    // ---- nextTravelExpenseCursor / travelExpenseHasMorePages ---------------------------------

    @Test
    fun nextTravelExpenseCursor_emptyPageIsNull() {
        assertNull(nextTravelExpenseCursor(emptyList()))
    }

    @Test
    fun nextTravelExpenseCursor_pointsAtTheLastRowsSubmittedAtAndId() {
        val page = listOf(sampleReport(id = "r1"), sampleReport(id = "r2"))
        val cursor = nextTravelExpenseCursor(page)
        assertEquals("r2", cursor?.id)
    }

    @Test
    fun travelExpenseHasMorePages_atOrAboveCapacityIsTrue() {
        assertTrue(travelExpenseHasMorePages(pageSize = 200, capacity = 200))
        assertTrue(travelExpenseHasMorePages(pageSize = 201, capacity = 200))
        assertFalse(travelExpenseHasMorePages(pageSize = 199, capacity = 200))
    }

    // ---- travelExpenseDecisionNoteIsValid -----------------------------------------------------

    @Test
    fun decisionNoteIsValid_blankOrNullIsInvalid() {
        assertFalse(travelExpenseDecisionNoteIsValid(null))
        assertFalse(travelExpenseDecisionNoteIsValid(""))
        assertFalse(travelExpenseDecisionNoteIsValid("   "))
    }

    @Test
    fun decisionNoteIsValid_nonBlankIsValid() {
        assertTrue(travelExpenseDecisionNoteIsValid("Genehmigt"))
    }

    // ---- parseTravelExpenseRateInput -- review MINOR fix regression coverage ----------------

    @Test
    fun parseTravelExpenseRateInput_nullOrBlankIsCleared() {
        assertEquals(TravelExpenseRateInput.Cleared, parseTravelExpenseRateInput(null))
        assertEquals(TravelExpenseRateInput.Cleared, parseTravelExpenseRateInput(""))
        assertEquals(TravelExpenseRateInput.Cleared, parseTravelExpenseRateInput("   "))
    }

    @Test
    fun parseTravelExpenseRateInput_validDotDecimalIsValid() {
        assertEquals(TravelExpenseRateInput.Valid(0.35), parseTravelExpenseRateInput("0.35"))
        assertEquals(TravelExpenseRateInput.Valid(14.0), parseTravelExpenseRateInput(" 14.00 "))
    }

    @Test
    fun parseTravelExpenseRateInput_germanCommaDecimalIsInvalid_neverSilentlyCleared() {
        // The regression this fix closes: on the German UI ("Kilometersatz (EUR/km)"), an ADMIN
        // typing "0,35" with a decimal comma must be rejected, never treated the same as an
        // intentionally blank input that clears the rate.
        assertEquals(TravelExpenseRateInput.Invalid, parseTravelExpenseRateInput("0,35"))
    }

    @Test
    fun parseTravelExpenseRateInput_garbageIsInvalid() {
        assertEquals(TravelExpenseRateInput.Invalid, parseTravelExpenseRateInput("abc"))
    }
}
