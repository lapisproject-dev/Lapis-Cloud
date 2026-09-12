package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche" -- covers the pure, DOM-independent
 * helper functions [activeBlockingDeferralRequest]/[canRequestDeferral] extracted from
 * `ContributionsScreen.kt`. No rendering harness exists in this module, so `renderContributionsScreen`
 * itself stays out of scope (same posture as every other `*ScreenTest` in this package).
 */
class ContributionsScreenTest {
    private fun reliefRequest(
        kind: ContributionReliefKind,
        status: ContributionReliefStatus,
    ): ContributionReliefRequestDto =
        ContributionReliefRequestDto(
            id = "req-1",
            subjectMemberId = "member-1",
            subjectDisplayName = "Test Mitglied",
            kind = kind,
            status = status,
            reasonCategory = ContributionReliefReason.FINANCIAL_HARDSHIP,
            reasonText = null,
            deferralContributionId = if (kind == ContributionReliefKind.DEFERRAL) "contribution-1" else null,
            deferralNewDueDate = if (kind == ContributionReliefKind.DEFERRAL) LocalDate(2026, 6, 15) else null,
            requestedAt = LocalDateTime(2026, 1, 1, 10, 0),
            requestedBy = "member-1",
            requestedByDisplayName = "Test Mitglied",
        )

    // ---- activeBlockingDeferralRequest -------------------------------------------------------------

    @Test
    fun activeBlockingDeferralRequest_emptyList_returnsNull() {
        assertNull(activeBlockingDeferralRequest(emptyList()))
    }

    @Test
    fun activeBlockingDeferralRequest_requestedDeferral_isFound() {
        val request = reliefRequest(ContributionReliefKind.DEFERRAL, ContributionReliefStatus.REQUESTED)
        assertTrue(activeBlockingDeferralRequest(listOf(request)) === request)
    }

    @Test
    fun activeBlockingDeferralRequest_approvedDeferral_isFound() {
        // APPROVED is still in ContributionReliefStatusSets.BLOCKS_NEW_REQUEST (F1) -- a retryable
        // failed execution still blocks a second DEFERRAL request.
        val request = reliefRequest(ContributionReliefKind.DEFERRAL, ContributionReliefStatus.APPROVED)
        assertTrue(activeBlockingDeferralRequest(listOf(request)) === request)
    }

    @Test
    fun activeBlockingDeferralRequest_rejectedDeferral_isNotBlocking() {
        val request = reliefRequest(ContributionReliefKind.DEFERRAL, ContributionReliefStatus.REJECTED)
        assertNull(activeBlockingDeferralRequest(listOf(request)))
    }

    @Test
    fun activeBlockingDeferralRequest_withdrawnDeferral_isNotBlocking() {
        val request = reliefRequest(ContributionReliefKind.DEFERRAL, ContributionReliefStatus.WITHDRAWN)
        assertNull(activeBlockingDeferralRequest(listOf(request)))
    }

    @Test
    fun activeBlockingDeferralRequest_executedDeferral_isNotBlocking() {
        val request = reliefRequest(ContributionReliefKind.DEFERRAL, ContributionReliefStatus.EXECUTED)
        assertNull(activeBlockingDeferralRequest(listOf(request)))
    }

    @Test
    fun activeBlockingDeferralRequest_exemptionOrReduction_neverBlocksRegardlessOfStatus() {
        ContributionReliefStatus.entries.forEach { status ->
            assertNull(activeBlockingDeferralRequest(listOf(reliefRequest(ContributionReliefKind.EXEMPTION, status))))
            assertNull(activeBlockingDeferralRequest(listOf(reliefRequest(ContributionReliefKind.REDUCTION, status))))
        }
    }

    // ---- canRequestDeferral -------------------------------------------------------------------------

    @Test
    fun canRequestDeferral_deferrableStatusAndNoBlockingRequest_isTrue() {
        assertTrue(canRequestDeferral(ContributionStatus.OPEN, blockingRequest = null))
        assertTrue(canRequestDeferral(ContributionStatus.OVERDUE, blockingRequest = null))
    }

    @Test
    fun canRequestDeferral_deferrableStatusButBlockingRequestPresent_isFalse() {
        val blocking = reliefRequest(ContributionReliefKind.DEFERRAL, ContributionReliefStatus.REQUESTED)
        assertFalse(canRequestDeferral(ContributionStatus.OPEN, blockingRequest = blocking))
    }

    @Test
    fun canRequestDeferral_nonDeferrableStatus_isFalseEvenWithoutABlockingRequest() {
        val nonDeferrable =
            setOf(
                ContributionStatus.PAID,
                ContributionStatus.WAIVED,
                ContributionStatus.DEBIT_SCHEDULED,
                ContributionStatus.DEBIT_SUBMITTED,
                ContributionStatus.RETURNED,
                ContributionStatus.IN_DUNNING,
            )
        nonDeferrable.forEach { status ->
            assertFalse(canRequestDeferral(status, blockingRequest = null), "expected $status to be non-deferrable")
        }
    }
}
