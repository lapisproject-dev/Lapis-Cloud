package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AnniversaryEmphasis
import network.lapis.cloud.shared.domain.AnniversaryEntryDto
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.MemberAnniversaryOverviewDto
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- covers the pure, DOM-independent helper functions local
 * to `MemberAnniversariesScreen.kt`, same scope posture as [MemberFinancialHistoryScreenTest] (no
 * DOM/render harness exists in this module).
 */
class MemberAnniversariesScreenTest {
    private fun entry(
        kind: AnniversaryEntryKind = AnniversaryEntryKind.BIRTHDAY,
        memberStatus: MemberStatus = MemberStatus.ACTIVE,
        occursOn: LocalDate = LocalDate(2026, 3, 1),
        originalDate: LocalDate = LocalDate(1990, 3, 1),
        shiftedFromLeapDay: Boolean = false,
        years: Int = 36,
        emphasis: AnniversaryEmphasis = AnniversaryEmphasis.STANDARD,
    ) = AnniversaryEntryDto(
        kind = kind,
        memberId = "m-1",
        memberDisplayName = "Fixture Mitglied",
        memberStatus = memberStatus,
        occursOn = occursOn,
        originalDate = originalDate,
        shiftedFromLeapDay = shiftedFromLeapDay,
        years = years,
        emphasis = emphasis,
    )

    @Test
    fun anniversaryRowLabel_birthday_saysWirdN() {
        val label = anniversaryRowLabel(entry(kind = AnniversaryEntryKind.BIRTHDAY, years = 40))
        assertEquals(true, label.contains("40"))
        assertEquals(true, label.contains("wird"))
    }

    /** The Tesler-Fund regression test: a DONOR anniversary must NEVER say "Mitgliedschaft". */
    @Test
    fun anniversaryRowLabel_membershipAnniversary_donor_saysFoerderer_neverMitgliedschaft() {
        val label =
            anniversaryRowLabel(
                entry(kind = AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, memberStatus = MemberStatus.DONOR, years = 10),
            )
        assertEquals(true, label.contains("Förderer"))
        assertEquals(false, label.contains("Mitgliedschaft"))
    }

    @Test
    fun anniversaryRowLabel_membershipAnniversary_active_saysMitgliedschaft() {
        val label =
            anniversaryRowLabel(
                entry(kind = AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, memberStatus = MemberStatus.ACTIVE, years = 10),
            )
        assertEquals(true, label.contains("Mitgliedschaft"))
    }

    @Test
    fun anniversaryDateLabel_shiftedFromLeapDay_mentions2902AndShiftedDay() {
        val label = anniversaryDateLabel(entry(shiftedFromLeapDay = true, occursOn = LocalDate(2026, 2, 28)))
        assertEquals(true, label.contains("29.02."))
        assertEquals(true, label.contains("28.02."))
    }

    @Test
    fun anniversaryDateLabel_notShifted_isPlainDayMonth() {
        val label = anniversaryDateLabel(entry(shiftedFromLeapDay = false, occursOn = LocalDate(2026, 3, 1)))
        assertEquals("01.03.", label)
    }

    @Test
    fun anniversaryCoverageText_zeroMissing_isNull() {
        val dto =
            MemberAnniversaryOverviewDto(
                windowDays = 30,
                from = LocalDate(2026, 1, 1),
                through = LocalDate(2026, 1, 31),
                entries = emptyList(),
                eligibleMemberCount = 10,
                membersWithoutDateOfBirth = 0,
            )
        assertNull(anniversaryCoverageText(dto))
    }

    @Test
    fun anniversaryCoverageText_someMissing_mentionsBothCounts() {
        val dto =
            MemberAnniversaryOverviewDto(
                windowDays = 30,
                from = LocalDate(2026, 1, 1),
                through = LocalDate(2026, 1, 31),
                entries = emptyList(),
                eligibleMemberCount = 285,
                membersWithoutDateOfBirth = 36,
            )
        val text = anniversaryCoverageText(dto)
        assertEquals(true, text != null && text.contains("36"))
        assertEquals(true, text != null && text.contains("285"))
    }

    @Test
    fun anniversaryEmphasisCssClass_isSetAndDistinctForNonStandardLiterals() {
        val nonStandard = AnniversaryEmphasis.entries.filter { it != AnniversaryEmphasis.STANDARD }
        val classes = nonStandard.map { anniversaryEmphasisCssClass(it) }
        assertEquals(nonStandard.size, classes.toSet().size)
        classes.forEach { assertNotEquals("", it) }
    }

    @Test
    fun formatDayMonth_zeroPadsSingleDigitDayAndMonth() {
        assertEquals("05.03.", formatDayMonth(LocalDate(2026, 3, 5)))
    }
}
