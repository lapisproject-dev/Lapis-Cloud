package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.StorageSpaceLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.263 -- pure formatting of the storage-space indicator: decimal units, truncated, Long-only percent. */
class StorageSpaceFormatTest {
    @Test
    fun bytesAreFormattedDecimalAndTruncated() {
        assertEquals("0 MB", formatStorageBytes(0L))
        assertEquals("999 MB", formatStorageBytes(999_999_999L))
        assertEquals("1,0 GB", formatStorageBytes(1_000_000_000L))
        assertEquals("18,4 GB", formatStorageBytes(18_499_999_999L))
        assertEquals("999,9 GB", formatStorageBytes(999_999_999_999L))
        assertEquals("1,0 TB", formatStorageBytes(1_000_000_000_000L))
        assertEquals("0 MB", formatStorageBytes(-5L))
    }

    @Test
    fun percentIsFlooredAndNeverRoundedUp() {
        assertEquals("19 %", storagePercentLabel(199L, 1000L))
        assertEquals("20 %", storagePercentLabel(200L, 1000L))
        assertEquals("unter 1 %", storagePercentLabel(5L, 1000L))
        assertEquals("0 %", storagePercentLabel(0L, 1000L))
    }

    @Test
    fun percentDoesNotOverflowForVeryLargeVolumes() {
        val total = Long.MAX_VALUE
        assertEquals(50L, storagePercentFloor(total / 2, total))
        assertEquals(100L, storagePercentFloor(total, total))
        assertEquals(0L, storagePercentFloor(1L, 0L))
    }

    @Test
    fun everyLevelHasItsOwnIcon() {
        val icons = StorageSpaceLevel.entries.map { storageIconClass(it) }
        assertEquals(StorageSpaceLevel.entries.size, icons.toSet().size)
        assertTrue(icons.all { it.startsWith("fas fa-") })
    }
}
