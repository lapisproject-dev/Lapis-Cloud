package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.12 -- covers the pure, DOM-independent predicate in `VolunteerAllowanceScreen.kt`,
 * same scope posture as [TravelExpenseScreenTest]. This test locks in the fix for the review MAJOR
 * finding: a BOARD/ADMIN member who filed a payment on someone ELSE's behalf
 * (`createDraft(subjectMemberId = someone else)`) must never see the "Selbstauskunft bestätigen"
 * button on that payment's own-payments-list card -- `declareSelf` always records the declaration
 * for the CALLER, never for the payment's subject, and there is no way to undo a wrong-person
 * declaration afterwards.
 */
class VolunteerAllowanceScreenTest {
    private val subject = "member-subject"
    private val requester = "member-requester"

    // ---- volunteerAllowanceShowsSelfDeclaration ----------------------------------------------

    @Test
    fun volunteerAllowanceShowsSelfDeclaration_trueForSubjectViewingTheirOwnApprovedPayment() {
        assertTrue(
            volunteerAllowanceShowsSelfDeclaration(
                VolunteerAllowancePaymentStatus.APPROVED,
                subjectMemberId = subject,
                currentMemberId = subject,
            ),
        )
    }

    @Test
    fun volunteerAllowanceShowsSelfDeclaration_trueForSubjectViewingTheirOwnExecutedPayment() {
        assertTrue(
            volunteerAllowanceShowsSelfDeclaration(
                VolunteerAllowancePaymentStatus.EXECUTED,
                subjectMemberId = subject,
                currentMemberId = subject,
            ),
        )
    }

    // The review MAJOR finding itself: a BOARD/ADMIN requester who is NOT the subject must never
    // get the button, on any status.
    @Test
    fun volunteerAllowanceShowsSelfDeclaration_falseForARequesterWhoIsNotTheSubject() {
        VolunteerAllowancePaymentStatus.entries.forEach { status ->
            assertFalse(
                volunteerAllowanceShowsSelfDeclaration(status, subjectMemberId = subject, currentMemberId = requester),
                "requester must never see the self-declaration button for status=$status",
            )
        }
    }

    @Test
    fun volunteerAllowanceShowsSelfDeclaration_falseWhenNoSessionMemberIdIsKnown() {
        assertFalse(
            volunteerAllowanceShowsSelfDeclaration(
                VolunteerAllowancePaymentStatus.APPROVED,
                subjectMemberId = subject,
                currentMemberId = null,
            ),
        )
    }

    // Round-1-regression guard: the button must never come back on a status that never carries a
    // self-declaration need (DRAFT/REQUESTED/REJECTED/WITHDRAWN), even for the subject themselves.
    @Test
    fun volunteerAllowanceShowsSelfDeclaration_falseForTheSubjectOnEveryOtherStatus() {
        val otherStatuses =
            VolunteerAllowancePaymentStatus.entries.filter {
                it != VolunteerAllowancePaymentStatus.APPROVED && it != VolunteerAllowancePaymentStatus.EXECUTED
            }
        otherStatuses.forEach { status ->
            assertFalse(
                volunteerAllowanceShowsSelfDeclaration(status, subjectMemberId = subject, currentMemberId = subject),
                "expected no self-declaration need for status=$status",
            )
        }
    }
}
