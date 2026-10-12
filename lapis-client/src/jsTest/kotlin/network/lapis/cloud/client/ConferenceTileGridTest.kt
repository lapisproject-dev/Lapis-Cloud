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

    private fun stage(
        panel: Double,
        area: Double,
        panelTop: Double,
        scroll: Double,
    ) = conferenceFullscreenStageHeight(panel, area, panelTop, scroll)

    @Test
    fun fullscreenStageHeight_runsFromAreaTopToPanelBottom() {
        // unscrolled, area 100 px below the panel top: 900 - 100
        assertEquals(800.0, stage(900.0, 100.0, 0.0, 0.0))
        // panel itself offset in the viewport: only the relative offset counts
        assertEquals(800.0, stage(900.0, 130.0, 30.0, 0.0))
        // scrolled by 40: the area's rect moved up by 40, the content offset stays 100
        assertEquals(800.0, stage(900.0, 60.0, 0.0, 40.0))
        // area at the very top gets the full panel height
        assertEquals(900.0, stage(900.0, 0.0, 0.0, 0.0))
        // minimum applies; NaN falls back to it
        assertEquals(240.0, stage(300.0, 200.0, 0.0, 0.0))
        assertEquals(240.0, stage(Double.NaN, 0.0, 0.0, 0.0))
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
