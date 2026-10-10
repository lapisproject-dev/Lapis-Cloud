package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.92 -- the best-fit tile grid in a real DOM with the real stylesheet. A real LiveKit call cannot run in Karma, so `applyTileGrid` of the call
 * view (a local function of `enterCall`) is mirrored here by [apply]: the same measurements go into [computeConferenceTileGrid] and the same
 * variables are written. What is proven here is the CSS side (flex row, centred last row, column cap, small tiles, padding, wide root, "Mehr"-sheet)
 * and that a recomputation moves no node and loses neither focus nor the speaking mark. The wiring of the real function is guarded as text
 * (`ClientConferenceDockTripwireTest`, `ClientUiGuidelineTripwireTest`).
 */
class ConferenceTileGridDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private lateinit var host: HTMLElement

    @BeforeTest
    fun setUp() {
        assertTrue(stylesLoaded)
        host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
    }

    @AfterTest
    fun tearDown() {
        host.remove()
    }

    private class Area(
        val area: HTMLElement,
        val priority: HTMLElement,
        val tiles: List<HTMLElement>,
    )

    private fun buildArea(
        count: Int,
        widthPx: Int,
    ): Area {
        val area = document.createElement("div") as HTMLElement
        area.className = "border rounded lapis-conference-grid"
        area.style.width = "${widthPx}px"
        val priority = document.createElement("div") as HTMLElement
        priority.className = "lapis-conference-tiles"
        priority.style.cssText = "display:flex;flex-wrap:wrap;justify-content:center;align-content:center;gap:8px;"
        priority.style.setProperty("--lapis-tile-w", "320px")
        priority.style.setProperty("--lapis-tile-h", "180px")
        area.appendChild(priority)
        val tiles =
            (1..count).map { index ->
                val tile = document.createElement("div") as HTMLElement
                tile.setAttribute("data-identity", "member-$index")
                tile.style.cssText =
                    "position:relative;overflow:hidden;flex:0 0 auto;width:var(--lapis-tile-w,100%);height:var(--lapis-tile-h,auto);"
                tile.appendChild(createSpeakingBadge())
                val name = document.createElement("div") as HTMLElement
                name.style.cssText = "position:absolute;left:6px;bottom:6px;max-width:var(--lapis-name-max,85%);overflow:hidden;"
                name.textContent = "Teilnehmer $index"
                tile.appendChild(name)
                priority.appendChild(tile)
                tile
            }
        host.appendChild(area)
        return Area(area, priority, tiles)
    }

    /** The write set of `applyTileGrid`: variables on the zone, the small class per tile; nothing else. */
    private fun apply(
        a: Area,
        height: Double,
        previousColumns: Int? = null,
    ): ConferenceTileGrid {
        val style = window.getComputedStyle(a.area)
        val width = a.area.clientWidth - conferenceCssPx(style.paddingLeft) - conferenceCssPx(style.paddingRight)
        val grid = computeConferenceTileGrid(a.tiles.size, width, height, previousColumns = previousColumns)
        a.priority.style.setProperty("--lapis-tile-w", "${grid.tileWidth}px")
        a.priority.style.setProperty("--lapis-tile-h", "${grid.tileHeight}px")
        a.priority.style.setProperty(
            "--lapis-tiles-max-w",
            "${grid.columns * grid.tileWidth + CONFERENCE_TILE_GAP_PX * (grid.columns - 1)}px",
        )
        val small = grid.tileWidth < CONFERENCE_TILE_SMALL_WIDTH_PX
        a.tiles.forEach { it.classList.toggle(CONFERENCE_TILE_SMALL_CLASS, small) }
        return grid
    }

    private fun rowsOf(a: Area): List<List<HTMLElement>> =
        a.tiles
            .groupBy { it.getBoundingClientRect().top.roundToInt() }
            .toList()
            .sortedBy { it.first }
            .map { (_, row) -> row.sortedBy { it.getBoundingClientRect().left } }

    @Test
    fun tilesTakeTheComputedSize_andFormTheComputedRows() {
        for (count in listOf(1, 4, 7, 12)) {
            val a = buildArea(count, 1200)
            val grid = apply(a, height = 500.0)
            val rows = rowsOf(a)
            assertEquals(grid.rows, rows.size, "count $count: rows ${rows.map { it.size }} for $grid")
            assertTrue(rows.all { it.size <= grid.columns }, "count $count: no row wider than the columns of $grid")
            a.tiles.forEach { tile ->
                val rect = tile.getBoundingClientRect()
                assertTrue(abs(rect.width - grid.tileWidth) < 1.0, "count $count: width ${rect.width} vs ${grid.tileWidth}")
                assertTrue(abs(rect.height - grid.tileHeight) < 1.0, "count $count: height ${rect.height} vs ${grid.tileHeight}")
            }
            a.area.remove()
        }
    }

    @Test
    fun theColumnCapHoldsWhenMoreTilesWouldFitInARow() {
        // a very low area: the tile is bound by the height, many more would fit in one row than the computed columns
        val a = buildArea(4, 1200)
        val grid = apply(a, height = 200.0)
        val rows = rowsOf(a)
        assertEquals(grid.rows, rows.size, "$grid vs ${rows.map { it.size }}")
        assertTrue(rows.all { it.size <= grid.columns })
    }

    @Test
    fun theLastRowIsCentred() {
        val a = buildArea(7, 1200)
        val grid = apply(a, height = 520.0)
        val rows = rowsOf(a)
        if (grid.columns * grid.rows == 7) return // a full last row has nothing to centre
        val zone = a.priority.getBoundingClientRect()
        val last = rows.last()
        val left = last.first().getBoundingClientRect().left - zone.left
        val right = zone.right - last.last().getBoundingClientRect().right
        assertTrue(abs(left - right) <= 1.5, "last row left $left right $right")
    }

    @Test
    fun aResize_movesNoNode_losesNoFocus_andKeepsTheSpeakingMark() {
        val a = buildArea(5, 1200)
        val button = document.createElement("button") as HTMLElement
        a.tiles[2].appendChild(button)
        button.focus()
        val speaker = a.tiles[1]
        val badge = speaker.querySelector(".lapis-conference-speaking-badge") as HTMLElement
        toggleSpeakingMark(speaker, badge, true)
        apply(a, height = 520.0)

        val nodesBefore = a.tiles.toList()
        val parentsBefore = a.tiles.map { it.parentNode }
        var childListMutations = 0
        val observer =
            MutationObserver { records, _ ->
                childListMutations += records.count { it.type == "childList" }
            }
        observer.observe(a.area, MutationObserverInit(childList = true, subtree = true))

        a.area.style.width = "420px"
        apply(a, height = 300.0)
        a.area.style.width = "1500px"
        apply(a, height = 700.0)
        val pending = observer.takeRecords().count { it.type == "childList" }
        observer.disconnect()

        assertEquals(0, childListMutations + pending, "a recomputation re-parents or replaces no node")
        a.tiles.forEachIndexed { index, tile ->
            assertSame(nodesBefore[index], tile)
            assertSame(parentsBefore[index], tile.parentNode)
            assertSame(a.priority, tile.parentNode)
        }
        assertSame(button, document.activeElement, "focus survives")
        assertTrue(speaker.classList.contains(SPEAKING_TILE_CLASS), "the speaking mark survives")
        assertEquals("inline-flex", badge.style.display)
    }

    @Test
    fun aSmallTile_hidesTheSpeakingWord_andLeavesRoomForTheSymbol() {
        val a = buildArea(6, 360)
        val grid = apply(a, height = 400.0)
        assertTrue(grid.tileWidth < CONFERENCE_TILE_SMALL_WIDTH_PX, "$grid")
        val tile = a.tiles[0]
        assertTrue(tile.classList.contains(CONFERENCE_TILE_SMALL_CLASS))
        val badge = tile.querySelector(".lapis-conference-speaking-badge") as HTMLElement
        toggleSpeakingMark(tile, badge, true)
        val word = tile.querySelector(".lapis-conference-speaking-text") as HTMLElement
        assertEquals("none", window.getComputedStyle(word).display)
        assertTrue(badge.offsetWidth > 0, "the symbol stays")
        val name = tile.children.item(1) as HTMLElement
        assertTrue(name.getBoundingClientRect().width <= tile.getBoundingClientRect().width - 40.0 + 0.5)

        val wide = buildArea(1, 1200)
        apply(wide, height = 600.0)
        assertTrue(!wide.tiles[0].classList.contains(CONFERENCE_TILE_SMALL_CLASS))
    }

    @Test
    fun aScrollingGrid_keepsTheFloor() {
        val a = buildArea(25, 800)
        val grid = apply(a, height = 300.0)
        assertTrue(grid.scrolls)
        a.tiles.forEach { assertTrue(abs(it.getBoundingClientRect().width - 160.0) < 1.0) }
    }

    @Test
    fun noTransitionOrAnimation_onAreaZoneOrTile() {
        val a = buildArea(2, 800)
        for (element in listOf(a.area, a.priority, a.tiles[0])) {
            val style = window.getComputedStyle(element)
            assertTrue(
                style.transitionDuration.split(",").all { it.trim() == "0s" },
                "transition on ${element.className}: ${style.transitionDuration}",
            )
            assertTrue(style.animationName == "none", "animation on ${element.className}: ${style.animationName}")
        }
    }

    @Test
    fun theAreaPadding_isEightPixelsBelow768_andSixteenAbove() {
        val a = buildArea(1, 800)
        val padding = window.getComputedStyle(a.area).paddingLeft
        val expected = if (window.innerWidth >= 768) "16px" else "8px"
        assertEquals(expected, padding)
    }

    @Test
    fun fullscreenRail_isPaddingOfTheArea() {
        if (window.innerWidth < 768) return
        val outer = document.createElement("div") as HTMLElement
        outer.className = "lapis-conference-fullscreen"
        host.appendChild(outer)
        val a = buildArea(1, 1000)
        outer.appendChild(a.area)
        a.area.style.setProperty("--lapis-rail-inset", "300px")
        assertEquals("316px", window.getComputedStyle(a.area).paddingRight)
        a.area.style.setProperty("--lapis-rail-inset", "0px")
        assertEquals("16px", window.getComputedStyle(a.area).paddingRight)
    }

    @Test
    fun theRootColumn_isNarrowInTheLobby_andFullWidthInACall() {
        val root = document.createElement("div") as HTMLElement
        root.className = "lapis-conference-root"
        host.appendChild(root)
        assertEquals("960px", window.getComputedStyle(root).maxWidth)
        val callPanel = document.createElement("div") as HTMLElement
        callPanel.className = "lapis-conference-call-panel"
        root.appendChild(callPanel)
        assertEquals("960px", window.getComputedStyle(root).maxWidth, "a call panel without the marker is still the lobby")
        callPanel.classList.add(CONFERENCE_CALL_ACTIVE_CLASS)
        assertEquals("none", window.getComputedStyle(root).maxWidth)
        assertEquals("0px", window.getComputedStyle(root).marginTop)
        callPanel.classList.remove(CONFERENCE_CALL_ACTIVE_CLASS)
        assertEquals("960px", window.getComputedStyle(root).maxWidth)
    }

    @Test
    fun theMoreSheet_staysInsideTheViewport_inTheWideRoot() {
        val root = document.createElement("div") as HTMLElement
        root.className = "lapis-conference-root"
        val callPanel = document.createElement("div") as HTMLElement
        callPanel.className = "lapis-conference-call-panel $CONFERENCE_CALL_ACTIVE_CLASS"
        val sheet = document.createElement("div") as HTMLElement
        sheet.className = "lapis-conference-more-sheet"
        sheet.textContent = "Mehr"
        callPanel.appendChild(sheet)
        root.appendChild(callPanel)
        host.appendChild(root)
        val rect = sheet.getBoundingClientRect()
        assertTrue(rect.left >= -0.5, "left ${rect.left}")
        assertTrue(rect.right <= window.innerWidth + 0.5, "right ${rect.right} vs ${window.innerWidth}")
        assertNotEquals("0px", window.getComputedStyle(sheet).width)
    }
}
