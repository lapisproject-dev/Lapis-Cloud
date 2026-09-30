package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** V1.9.17: the pure chunking and counting behind [LoadMode.Refetch]. */
class OpenItemsRefetchTest {
    @Test
    fun planRefetchLimits_aDepthWithinTheCap_isOneCall() {
        assertEquals(listOf(150), planRefetchLimits(150, 200))
        assertEquals(listOf(200), planRefetchLimits(200, 200))
        assertEquals(listOf(1), planRefetchLimits(1, 200))
    }

    @Test
    fun planRefetchLimits_aDeeperList_isChunkedByTheCap() {
        assertEquals(listOf(200, 200, 50), planRefetchLimits(450, 200))
        assertEquals(listOf(200, 200), planRefetchLimits(400, 200))
    }

    @Test
    fun planRefetchLimits_nothingLoadedYet_isOnePageOfTheCurrentPageSize() {
        assertEquals(listOf(50), planRefetchLimits(0, 200))
        assertEquals(listOf(100), planRefetchLimits(0, 200, pageSize = 100))
        assertEquals(listOf(200), planRefetchLimits(-5, 200, pageSize = 500), "a page size above the cap is clamped")
    }

    @Test
    fun planRefetchLimits_isBoundedByTheLoadCap() {
        val limits = planRefetchLimits(1_000_000, 200)
        assertEquals(OPEN_ITEMS_MAX_LOADS, limits.size)
        assertEquals(OPEN_ITEMS_MAX_LOADS * 200, limits.sum())
    }

    @Test
    fun planRefetchLimits_rejectsANonPositiveCap() {
        assertFailsWith<IllegalArgumentException> { planRefetchLimits(10, 0) }
    }

    @Test
    fun loadCountForDepth_roundsUpAndNeverDropsBelowOne() {
        assertEquals(1, loadCountForDepth(0, 50))
        assertEquals(1, loadCountForDepth(50, 50))
        assertEquals(3, loadCountForDepth(150, 50))
        assertEquals(4, loadCountForDepth(151, 50))
    }

    @Test
    fun theClientCapMatchesTheServerCap() {
        assertEquals(200, MAX_OPEN_ITEMS_LIMIT)
    }
}
