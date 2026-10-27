package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.226 -- the DOM-free decisions of the LupaBB sync page. */
class LupaSyncLogicTest {
    private fun summary(
        newCount: Int = 0,
        changedCount: Int = 0,
        statusChanges: Int = 0,
        counts: Map<LupaBbCategory, Int> = emptyMap(),
    ) = LupaBbPreviewSummaryDto(
        token = "t",
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        counts = counts,
        newCount = newCount,
        changedCount = changedCount,
        statusChangeCount = statusChanges,
        severeStatusChangeCount = 0,
    )

    @Test
    fun thePreview_canBeLoadedOnlyWithCredentials() {
        assertFalse(LupaSyncLogic.canLoadPreview(null))
        assertFalse(LupaSyncLogic.canLoadPreview(LupaBbSyncStatusDto(credentialsSet = false)))
        assertTrue(LupaSyncLogic.canLoadPreview(LupaBbSyncStatusDto(credentialsSet = true)))
    }

    @Test
    fun theConfirmationDialog_appearsOnlyWhenAStatusChanges() {
        assertFalse(LupaSyncLogic.needsConfirmation(summary(newCount = 5, changedCount = 7)))
        assertTrue(LupaSyncLogic.needsConfirmation(summary(changedCount = 1, statusChanges = 1)))
    }

    @Test
    fun applyingIsPossibleOnlyWhenSomethingWouldBeWritten() {
        assertFalse(LupaSyncLogic.canApply(summary()))
        assertTrue(LupaSyncLogic.canApply(summary(newCount = 1)))
        assertTrue(LupaSyncLogic.canApply(summary(counts = mapOf(LupaBbCategory.MISSING to 2))))
    }

    @Test
    fun theFirstFilter_isWhatNeedsAction_elseWhatWillChange_elseEverything() {
        assertEquals(
            LupaBbPreviewFilter.ACTION_NEEDED,
            LupaSyncLogic.defaultFilter(mapOf(LupaBbCategory.AMBIGUOUS to 1, LupaBbCategory.NEW to 9)),
        )
        assertEquals(LupaBbPreviewFilter.ACTION_NEEDED, LupaSyncLogic.defaultFilter(mapOf(LupaBbCategory.ERROR to 1)))
        assertEquals(LupaBbPreviewFilter.CHANGED, LupaSyncLogic.defaultFilter(mapOf(LupaBbCategory.NEW to 2)))
        assertEquals(LupaBbPreviewFilter.ALL, LupaSyncLogic.defaultFilter(mapOf(LupaBbCategory.UNCHANGED to 4)))
    }

    @Test
    fun actionNeeded_countsAmbiguousAndErrors() {
        assertEquals(
            5,
            LupaSyncLogic.actionNeeded(mapOf(LupaBbCategory.AMBIGUOUS to 2, LupaBbCategory.ERROR to 3, LupaBbCategory.NEW to 9)),
        )
    }

    @Test
    fun theFilterChips_carryWordAndNumber_inAFixedOrder() {
        val counts = mapOf(LupaBbCategory.NEW to 3, LupaBbCategory.CHANGED to 1, LupaBbCategory.AMBIGUOUS to 2, LupaBbCategory.ERROR to 1)
        val options = LupaSyncLogic.filterOptions(counts)
        assertEquals(LupaBbPreviewFilter.entries, options.map { it.first })
        assertEquals("Neu (3)", options.first { it.first == LupaBbPreviewFilter.NEW }.second)
        assertEquals("Handlungsbedarf (3)", options.first { it.first == LupaBbPreviewFilter.ACTION_NEEDED }.second)
        assertEquals("Alle (7)", options.last().second)
    }

    @Test
    fun theApplyLabel_namesTheNumbersItActsOn() {
        assertEquals("3 neue und 4 geänderte Datensätze übernehmen", LupaSyncLogic.applyLabel(summary(newCount = 3, changedCount = 4)))
    }
}
