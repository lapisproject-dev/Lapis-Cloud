package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V1.9.92 -- [conferenceTileAreaHeight] and [conferenceRailInset]: pure arithmetic of the tile area.
 * V1.9.109: the best-fit grid itself (`computeConferenceTileGrid`) and its tests moved to `lapis-shared` (`TileGridTest`).
 * The DOM side (variables, node identity) is covered by `ConferenceTileGridDomTest`.
 */
class ConferenceTileGridTest {
    @Test
    fun areaHeight_usesWindowValues() {
        assertEquals(500.0, conferenceTileAreaHeight(viewportHeight = 800.0, areaTop = 200.0, barHeight = 56.0, bottomPad = 44.0))
        // below the minimum the minimum applies
        assertEquals(240.0, conferenceTileAreaHeight(viewportHeight = 500.0, areaTop = 400.0, barHeight = 56.0, bottomPad = 12.0))
        assertEquals(300.0, conferenceTileAreaHeight(100.0, 400.0, 56.0, 12.0, minHeight = 300.0))
        assertEquals(240.0, conferenceTileAreaHeight(Double.NaN, 0.0, 0.0, 0.0))
        assertEquals(240.0, conferenceTileAreaHeight(800.0, Double.POSITIVE_INFINITY, 0.0, 0.0))
    }

    @Test
    fun railInset_onlyInFullscreenWithRail_atLeast768() {
        assertEquals(0.0, conferenceRailInset(railVisible = false, fullscreen = true, viewportWidth = 1280.0))
        assertEquals(0.0, conferenceRailInset(railVisible = true, fullscreen = false, viewportWidth = 1280.0))
        assertEquals(0.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 600.0))
        assertEquals(0.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = Double.NaN))
        // 28vw clamped to 280..380
        assertEquals(280.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 800.0))
        assertEquals(358.4, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 1280.0), 0.001)
        assertEquals(380.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 2560.0))
    }
}
