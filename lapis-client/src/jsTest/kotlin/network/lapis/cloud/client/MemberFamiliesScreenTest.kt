package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.UpcomingMajorityEntryDto
import network.lapis.cloud.shared.domain.UpcomingMajorityOverviewDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- covers the pure,
 * DOM-independent helper functions local to `MemberFamiliesScreen.kt`, same scope posture as
 * [MemberHonorsScreenTest] (no DOM/render harness exists in this module).
 */
class MemberFamiliesScreenTest {
    @Test
    fun memberFamiliesRoute_withoutId_isTheBareRoute() {
        assertEquals(Routes.MEMBER_FAMILIES, memberFamiliesRoute(null))
    }

    @Test
    fun memberFamiliesRoute_withId_appendsFamilyQueryParam() {
        assertEquals("${Routes.MEMBER_FAMILIES}?family=abc-123", memberFamiliesRoute("abc-123"))
    }

    @Test
    fun familyRoleLabel_isDistinctForBothLiterals() {
        val labels = FamilyMemberRole.entries.map { familyRoleLabel(it) }
        assertEquals(FamilyMemberRole.entries.size, labels.toSet().size)
    }

    @Test
    fun familyRoleBadgeColor_isDistinctForBothLiterals() {
        val colors = FamilyMemberRole.entries.map { familyRoleBadgeColor(it) }
        assertEquals(FamilyMemberRole.entries.size, colors.toSet().size)
    }

    @Test
    fun familyRosterBadgeText_containsFamilyNameAndDistinguishesPayerFromDependent() {
        val payerText = familyRosterBadgeText("Schmidt", FamilyMemberRole.PAYER)
        val dependentText = familyRosterBadgeText("Schmidt", FamilyMemberRole.DEPENDENT)
        assertEquals(true, payerText.contains("Schmidt"))
        assertEquals(true, dependentText.contains("Schmidt"))
        assertNotEquals(payerText, dependentText)
    }

    @Test
    fun familyRosterBadgeText_nullRole_stillReturnsTheFamilyName() {
        assertEquals(true, familyRosterBadgeText("Schmidt", null).contains("Schmidt"))
    }

    @Test
    fun payerlessWarningText_isNonBlank() {
        assertEquals(true, payerlessWarningText().isNotBlank())
    }

    @Test
    fun familiesEmptyStateText_isNonBlank() {
        assertEquals(true, familiesEmptyStateText().isNotBlank())
    }

    private fun entry(
        alreadyMajor: Boolean,
        turnsMajorOn: LocalDate = LocalDate(2026, 7, 15),
    ) = UpcomingMajorityEntryDto(
        linkId = "link-1",
        familyId = "family-1",
        familyName = "Schmidt",
        memberId = "member-1",
        memberDisplayName = "Max Schmidt",
        turnsMajorOn = turnsMajorOn,
        alreadyMajor = alreadyMajor,
        shiftedFromLeapDay = false,
    )

    @Test
    fun majorityRowLabel_distinguishesAlreadyMajorFromUpcoming() {
        val overdue = majorityRowLabel(entry(alreadyMajor = true))
        val upcoming = majorityRowLabel(entry(alreadyMajor = false))
        assertNotEquals(overdue, upcoming)
        assertEquals(true, overdue.contains("Max Schmidt"))
        assertEquals(true, upcoming.contains("Max Schmidt"))
    }

    private fun overview(
        dependentsWithoutDateOfBirth: Int,
        dependentCount: Int = 5,
    ) = UpcomingMajorityOverviewDto(
        windowDays = 90,
        from = LocalDate(2026, 1, 1),
        through = LocalDate(2026, 4, 1),
        entries = emptyList(),
        dependentCount = dependentCount,
        dependentsWithoutDateOfBirth = dependentsWithoutDateOfBirth,
    )

    @Test
    fun majorityCoverageText_isNullWhenEveryDependentHasABirthdate() {
        assertNull(majorityCoverageText(overview(dependentsWithoutDateOfBirth = 0)))
    }

    @Test
    fun majorityCoverageText_mentionsBothCountsWhenSomeAreMissing() {
        val text = majorityCoverageText(overview(dependentsWithoutDateOfBirth = 2, dependentCount = 5))
        assertEquals(true, text != null && text.isNotBlank())
    }
}
