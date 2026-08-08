package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.BoardMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.TransparenzregisterReminderDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compliance UI wave -- covers only the pure, DOM-independent functions factored out of
 * `BoardMembershipScreen.kt`: the displaced-incumbent predicate (plan "Displaced-incumbent
 * behavior is not hidden"), the beneficial-owner-data-gap copy builders, the reminder-resolution
 * label/color helpers, and D9/D8(b)'s exact required copy -- same scope posture as
 * [DsgvoComplianceScreenTest]/[DsgvoRightsScreenTest] (no DOM/rendering test harness exists in this
 * module).
 */
class BoardMembershipScreenTest {
    private fun membership(
        id: String,
        memberId: String,
        memberDisplayName: String,
        role: CommitteeRole,
        endedAt: LocalDate? = null,
    ): BoardMembershipDto =
        BoardMembershipDto(
            id = id,
            memberId = memberId,
            memberDisplayName = memberDisplayName,
            committeeRole = role,
            startedAt = LocalDate(2026, 1, 1),
            endedAt = endedAt,
        )

    // ---- findDisplacedIncumbent -----------------------------------------------------------

    @Test
    fun findDisplacedIncumbent_singleHolderRoleWithDifferentIncumbent_returnsIncumbent() {
        val board =
            listOf(
                membership("m1", "member-alice", "Alice", CommitteeRole.CHAIR),
                membership("m2", "member-bob", "Bob", CommitteeRole.MEMBER),
            )
        val displaced = findDisplacedIncumbent(board, CommitteeRole.CHAIR, "member-carol")
        assertEquals("member-alice", displaced?.memberId)
    }

    @Test
    fun findDisplacedIncumbent_sameMemberReappointedIntoSameRole_returnsNull() {
        val board = listOf(membership("m1", "member-alice", "Alice", CommitteeRole.CHAIR))
        assertNull(findDisplacedIncumbent(board, CommitteeRole.CHAIR, "member-alice"))
    }

    @Test
    fun findDisplacedIncumbent_vacantSingleHolderSeat_returnsNull() {
        val board = listOf(membership("m1", "member-alice", "Alice", CommitteeRole.MEMBER))
        assertNull(findDisplacedIncumbent(board, CommitteeRole.CHAIR, "member-carol"))
    }

    @Test
    fun findDisplacedIncumbent_multiHolderRole_neverReturnsAnIncumbentEvenWithOtherHolders() {
        val board =
            listOf(
                membership("m1", "member-alice", "Alice", CommitteeRole.MEMBER),
                membership("m2", "member-bob", "Bob", CommitteeRole.MEMBER),
            )
        assertNull(findDisplacedIncumbent(board, CommitteeRole.MEMBER, "member-carol"))
        assertNull(findDisplacedIncumbent(board, CommitteeRole.ASSESSOR, "member-carol"))
    }

    @Test
    fun findDisplacedIncumbent_ignoresAlreadyEndedRows() {
        val board = listOf(membership("m1", "member-alice", "Alice", CommitteeRole.SECRETARY, endedAt = LocalDate(2026, 2, 1)))
        assertNull(findDisplacedIncumbent(board, CommitteeRole.SECRETARY, "member-carol"))
    }

    @Test
    fun findDisplacedIncumbent_emptyBoard_returnsNull() {
        assertNull(findDisplacedIncumbent(emptyList(), CommitteeRole.CHAIR, "member-carol"))
    }

    // ---- displacedIncumbentWarningText -----------------------------------------------------

    @Test
    fun displacedIncumbentWarningText_namesTheRoleAndTheIncumbentAndTheAutomaticConsequence() {
        val text = displacedIncumbentWarningText(CommitteeRole.CHAIR, "Alice")
        assertTrue(text.contains(committeeRoleLabel(CommitteeRole.CHAIR)))
        assertTrue(text.contains("Alice"))
        assertTrue(text.contains("automatisch"))
    }

    // ---- beneficial-owner data-gap copy ----------------------------------------------------

    @Test
    fun beneficialOwnerGapsSummary_namesTheCount() {
        assertTrue(beneficialOwnerGapsSummary(3).contains("3"))
        assertTrue(beneficialOwnerGapsSummary(3).contains("Transparenzregister"))
    }

    @Test
    fun beneficialOwnerGapDetail_namesOnlyTheActuallyMissingFields() {
        val bothMissing =
            BeneficialOwnerDataGapDto(
                memberId = "member-alice",
                memberDisplayName = "Alice",
                committeeRole = CommitteeRole.CHAIR,
                missingDateOfBirth = true,
                missingNationality = true,
            )
        val text = beneficialOwnerGapDetail(bothMissing)
        assertTrue(text.contains("Alice"))
        assertTrue(text.contains("Geburtsdatum"))
        assertTrue(text.contains("Staatsangehörigkeit"))
    }

    @Test
    fun beneficialOwnerGapDetail_dateOfBirthOnly_omitsNationality() {
        val gap =
            BeneficialOwnerDataGapDto(
                memberId = "member-bob",
                memberDisplayName = "Bob",
                committeeRole = CommitteeRole.MEMBER,
                missingDateOfBirth = true,
                missingNationality = false,
            )
        val text = beneficialOwnerGapDetail(gap)
        assertTrue(text.contains("Geburtsdatum"))
        assertTrue(!text.contains("Staatsangehörigkeit"))
    }

    @Test
    fun beneficialOwnerGapDetail_nationalityOnly_omitsDateOfBirth() {
        val gap =
            BeneficialOwnerDataGapDto(
                memberId = "member-carol",
                memberDisplayName = "Carol",
                committeeRole = CommitteeRole.ASSESSOR,
                missingDateOfBirth = false,
                missingNationality = true,
            )
        val text = beneficialOwnerGapDetail(gap)
        assertTrue(!text.contains("Geburtsdatum"))
        assertTrue(text.contains("Staatsangehörigkeit"))
    }

    // ---- reminder resolution label/color ---------------------------------------------------

    @Test
    fun reminderResolutionLabel_coversBothStates() {
        assertEquals("Erledigt", reminderResolutionLabel(true))
        assertEquals("Offen", reminderResolutionLabel(false))
    }

    @Test
    fun reminderResolutionColor_coversBothStates() {
        assertEquals("success", reminderResolutionColor(true))
        assertEquals("warning", reminderResolutionColor(false))
    }

    // ---- resolvedCaption ---------------------------------------------------------------------

    private fun reminder(
        resolved: Boolean,
        resolvedById: String? = null,
        resolvedByDisplayName: String? = null,
    ): TransparenzregisterReminderDto =
        TransparenzregisterReminderDto(
            id = "reminder-1",
            triggeredAt = LocalDateTime(2026, 1, 1, 0, 0),
            memberId = "member-alice",
            memberDisplayName = "Alice",
            committeeRole = CommitteeRole.CHAIR,
            changeType = BoardChangeType.JOINED,
            resolved = resolved,
            resolvedAt = if (resolved) LocalDateTime(2026, 1, 2, 0, 0) else null,
            resolvedById = resolvedById,
            resolvedByDisplayName = resolvedByDisplayName,
        )

    @Test
    fun resolvedCaption_prefersDisplayNameOverRawId() {
        val text = resolvedCaption(reminder(resolved = true, resolvedById = "member-bob", resolvedByDisplayName = "Bob"))
        assertTrue(text.contains("Bob"))
        assertTrue(!text.contains("member-bob"))
    }

    @Test
    fun resolvedCaption_fallsBackToRawIdWhenNoDisplayNameResolved() {
        val text = resolvedCaption(reminder(resolved = true, resolvedById = "member-bob", resolvedByDisplayName = null))
        assertTrue(text.contains("member-bob"))
    }

    @Test
    fun resolvedCaption_fallsBackToUnbekanntWhenNeitherIsSet() {
        val text = resolvedCaption(reminder(resolved = true, resolvedById = null, resolvedByDisplayName = null))
        assertTrue(text.contains("unbekannt"))
    }

    // ---- D9/D8(b): exact required copy -----------------------------------------------------

    @Test
    fun boardMembershipHeaderNote_namesTheExecutiveBoardCommitteeSource() {
        assertTrue(BOARD_MEMBERSHIP_HEADER_NOTE.contains("EXECUTIVE_BOARD"))
        assertTrue(BOARD_MEMBERSHIP_HEADER_NOTE.contains("Wahlen"))
    }

    @Test
    fun boardCommitteeCrossLinkCaption_namesTheExecutiveBoardCommittee() {
        assertTrue(BOARD_COMMITTEE_CROSS_LINK_CAPTION.contains("EXECUTIVE_BOARD"))
    }

    @Test
    fun manualAppointmentCaption_pointsToElectionsAsThePrimaryPath() {
        assertTrue(MANUAL_APPOINTMENT_CAPTION.contains("Wahlfunktion"))
        assertTrue(MANUAL_APPOINTMENT_CAPTION.contains("Anträge"))
    }

    @Test
    fun transparenzregisterReminderHonestyBanner_namesTheLimitationExplicitly() {
        assertTrue(TRANSPARENZREGISTER_REMINDER_HONESTY_BANNER.contains("nicht prüfen"))
        assertTrue(TRANSPARENZREGISTER_REMINDER_HONESTY_BANNER.contains("transparenzregister.de"))
    }

    @Test
    fun resolveReminderButtonLabel_statesTheClaimNotAGenericAcknowledgement() {
        assertEquals("Ich habe das Register aktualisiert", RESOLVE_REMINDER_BUTTON_LABEL)
    }
}
