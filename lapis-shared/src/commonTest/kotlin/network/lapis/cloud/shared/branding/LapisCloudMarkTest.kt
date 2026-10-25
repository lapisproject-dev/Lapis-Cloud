package network.lapis.cloud.shared.branding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tripwire against accidental edits of the brand mark geometry. */
class LapisCloudMarkTest {
    @Test
    fun geometry_isIntact() {
        assertEquals("128 193 340 340", LapisCloudMark.VIEW_BOX)
        assertEquals(365, LapisCloudMark.CLOUD_D.length)
        assertTrue(LapisCloudMark.CLOUD_D.startsWith("M180 410 C154.061 403.107"))
        assertTrue(LapisCloudMark.CLOUD_D.endsWith("435 410 Z"))
        val paths =
            listOf(
                LapisCloudMark.CLOUD_D,
                LapisCloudMark.F1_D,
                LapisCloudMark.F2_D,
                LapisCloudMark.F3_D,
                LapisCloudMark.F4_D,
                LapisCloudMark.F5_D,
                LapisCloudMark.F6_D,
                LapisCloudMark.SPARK_D,
            )
        for (d in paths) {
            assertTrue(d.startsWith("M"), d)
            assertTrue(d.endsWith(" Z"), d)
        }
        assertEquals("12", LapisCloudMark.STROKE_CLOUD)
        assertEquals("5", LapisCloudMark.STROKE_EDGE)
    }
}
