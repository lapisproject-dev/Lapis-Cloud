package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberHonorCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- covers the pure, DOM-independent
 * helper functions local to `MemberHonorsScreen.kt`, same scope posture as
 * [MemberFinancialHistoryScreenTest] (no DOM/render harness exists in this module).
 */
class MemberHonorsScreenTest {
    @Test
    fun memberHonorsRoute_withoutId_isTheBareRoute() {
        assertEquals(Routes.MEMBER_HONORS, memberHonorsRoute(null))
    }

    @Test
    fun memberHonorsRoute_withId_appendsMemberQueryParam() {
        assertEquals("${Routes.MEMBER_HONORS}?member=abc-123", memberHonorsRoute("abc-123"))
    }

    @Test
    fun memberHonorCategoryLabel_isDistinctForAllFourLiterals() {
        val labels = MemberHonorCategory.entries.map { memberHonorCategoryLabel(it) }
        assertEquals(MemberHonorCategory.entries.size, labels.toSet().size)
    }

    @Test
    fun memberHonorCategoryIcon_isSetForAllFourLiterals() {
        MemberHonorCategory.entries.forEach { category ->
            val icon = memberHonorCategoryIcon(category)
            assertNotEquals("", icon)
        }
    }

    @Test
    fun memberHonorsHeading_withoutName_isTheGenericTitle() {
        assertEquals(true, memberHonorsHeading(null).isNotBlank())
    }

    @Test
    fun memberHonorsHeading_withName_mentionsTheName() {
        val heading = memberHonorsHeading("Erika Musterfrau")
        assertEquals(true, heading.contains("Erika Musterfrau"))
    }

    @Test
    fun memberHonorsEmptyStateText_withoutName_isGeneric() {
        assertEquals(true, memberHonorsEmptyStateText(null).isNotBlank())
    }

    @Test
    fun memberHonorsEmptyStateText_withName_mentionsTheName() {
        val text = memberHonorsEmptyStateText("Erika Musterfrau")
        assertEquals(true, text.contains("Erika Musterfrau"))
    }
}
