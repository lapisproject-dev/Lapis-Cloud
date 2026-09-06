package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind
import network.lapis.cloud.shared.domain.FinancialHistoryYearDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- covers the pure, DOM-independent helper functions local to
 * `MemberFinancialHistoryScreen.kt`, same scope posture as [CostCentersScreenTest] (no DOM/render
 * harness exists in this module).
 */
class MemberFinancialHistoryScreenTest {
    @Test
    fun financialHistoryKindLabel_isDistinctForAllFourLiterals() {
        val labels = FinancialHistoryEntryKind.entries.map { financialHistoryKindLabel(it) }
        assertEquals(FinancialHistoryEntryKind.entries.size, labels.toSet().size)
    }

    @Test
    fun financialHistoryKindColor_isSetForAllFourLiterals() {
        FinancialHistoryEntryKind.entries.forEach { kind ->
            val color = financialHistoryKindColor(kind)
            assertNotEquals("", color)
        }
    }

    @Test
    fun memberFinancesRoute_withoutId_isTheBareRoute() {
        assertEquals(Routes.MEMBER_FINANCES, memberFinancesRoute(null))
    }

    @Test
    fun memberFinancesRoute_withId_appendsMemberQueryParam() {
        assertEquals("${Routes.MEMBER_FINANCES}?member=abc-123", memberFinancesRoute("abc-123"))
    }

    @Test
    fun financialHistoryYearHeading_includesYearAndBothAmounts() {
        val year =
            FinancialHistoryYearDto(
                year = 2025,
                contributionsPaid = 120.0.toDecimal(),
                donationsTotal = 50.0.toDecimal(),
                entries = emptyList(),
            )
        val heading = financialHistoryYearHeading(year)
        assertEquals(true, heading.contains("2025"))
        assertEquals(true, heading.contains("120"))
        assertEquals(true, heading.contains("50"))
    }

    @Test
    fun financialHistoryYearHeading_withZeroSums_stillRenders() {
        val year =
            FinancialHistoryYearDto(
                year = 2024,
                contributionsPaid = 0.0.toDecimal(),
                donationsTotal = 0.0.toDecimal(),
                entries = emptyList(),
            )
        val heading = financialHistoryYearHeading(year)
        assertEquals(true, heading.contains("2024"))
    }

    @Test
    fun financialHistoryEmptyStateText_mentionsJoinedAt() {
        val text = financialHistoryEmptyStateText(LocalDate(2022, 5, 1))
        assertEquals(true, text.contains("2022-05-01"))
    }
}
