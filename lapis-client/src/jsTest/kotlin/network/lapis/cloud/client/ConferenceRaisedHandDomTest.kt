package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.253 -- the raised hand in a real DOM with the real stylesheet: badge state (gold fill, display, accessible name), the tile ring,
 * the ring in the floating window's cells, and that the speaker mark never changes the order of the tiles. The wiring of the call view is
 * not runnable in Karma (no LiveKit); the CSS rules are additionally guarded as text (`ClientConferenceRaisedHandCssTripwireTest`).
 * `prefers-reduced-motion` cannot be switched from page script in Karma, so the "no motion" side is carried by that text tripwire alone.
 */
class ConferenceRaisedHandDomTest {
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

    private fun tile(): Pair<HTMLElement, HTMLElement> {
        val tile = document.createElement("div") as HTMLElement
        tile.style.cssText = "position:relative;width:320px;height:180px;"
        val badge = createHandBadge()
        tile.appendChild(badge)
        host.appendChild(tile)
        return tile to badge
    }

    @Test
    fun raising_setsBadgeAndTileClass_lowering_removesThem() {
        val (tile, badge) = tile()
        assertEquals("none", badge.style.display)
        assertFalse(badge.classList.contains("is-raised"))
        toggleHandBadge(badge, true)
        tile.classList.toggle(CONFERENCE_TILE_HAND_CLASS, true)
        assertTrue(badge.classList.contains("is-raised"))
        assertEquals("inline-flex", badge.style.display)
        assertTrue(tile.classList.contains("lapis-conference-tile--hand"))
        toggleHandBadge(badge, false)
        tile.classList.toggle(CONFERENCE_TILE_HAND_CLASS, false)
        assertFalse(badge.classList.contains("is-raised"))
        assertEquals("none", badge.style.display)
        assertFalse(tile.classList.contains("lapis-conference-tile--hand"))
    }

    @Test
    fun badge_keepsItsWords_andTheAccessibleNameIsUntouched() {
        val (tile, badge) = tile()
        tile.setAttribute("aria-label", "Kachel von Anna")
        val text = badge.querySelector(".lapis-conference-hand-text") as HTMLElement
        val label = badge.getAttribute("aria-label")
        toggleHandBadge(badge, true)
        assertEquals("Hand gehoben", text.textContent)
        assertEquals(label, badge.getAttribute("aria-label"))
        assertEquals("Kachel von Anna", tile.getAttribute("aria-label"))
    }

    @Test
    fun raisedBadge_isGold_andTheTileHasATwoPixelRing() {
        val (tile, badge) = tile()
        toggleHandBadge(badge, true)
        tile.classList.add(CONFERENCE_TILE_HAND_CLASS)
        val probe = document.createElement("div") as HTMLElement
        probe.style.cssText = "color:var(--lapis-fleck)"
        host.appendChild(probe)
        val gold = window.getComputedStyle(probe).color
        assertEquals(gold, window.getComputedStyle(badge).backgroundColor, "the fill is the gold token")
        val ring = window.getComputedStyle(tile, "::before")
        assertEquals("2px", ring.borderTopWidth)
        assertEquals(gold, ring.borderTopColor)
        assertEquals("4px", ring.top)
    }

    @Test
    fun speakerRing_andHandRing_areOnDifferentPseudoElements() {
        val (tile, badge) = tile()
        toggleHandBadge(badge, true)
        tile.classList.add(CONFERENCE_TILE_HAND_CLASS)
        tile.classList.add(SPEAKING_TILE_CLASS)
        assertEquals("2px", window.getComputedStyle(tile, "::before").borderTopWidth)
        assertEquals("3px", window.getComputedStyle(tile, "::after").borderTopWidth)
    }

    @Test
    fun badge_bobsOnlyWhenMotionIsAllowed() {
        val (_, badge) = tile()
        toggleHandBadge(badge, true)
        val name = window.getComputedStyle(badge).animationName
        if (window.matchMedia("(prefers-reduced-motion: no-preference)").matches) {
            assertEquals("lapis-conference-hand-bob", name)
        } else {
            assertEquals("none", name)
        }
    }

    private fun source(
        key: String,
        hand: Boolean,
    ) = FloatMediaSource(
        key = key,
        label = key,
        isLocal = false,
        isScreenShare = false,
        video = null,
        home = host,
        lastSpokeAtMs = 0L,
        setQuality = null,
        handRaised = hand,
    )

    @Test
    fun floatWindow_ringsTheCellsWithARaisedHand_withoutReplacingNodes() {
        val stage = FloatStage()
        host.appendChild(stage.root)
        val ledger = ConferenceVideoLedger()
        val sources = listOf(source("a", hand = false), source("b", hand = true), source("c", hand = false))
        stage.render(FloatSelection(mainKey = "a", stripKeys = listOf("b", "c"), insetKey = null, hiddenCount = 0), sources, ledger, false)
        val strip = stage.root.querySelectorAll(".lapis-float-strip-cell")
        val cellB = strip.item(0) as HTMLElement
        val cellC = strip.item(1) as HTMLElement
        assertTrue(cellB.classList.contains(CONFERENCE_TILE_HAND_CLASS))
        assertFalse(cellC.classList.contains(CONFERENCE_TILE_HAND_CLASS))
        assertEquals("2px", window.getComputedStyle(cellB, "::before").top)
        val nodes = stage.root.querySelectorAll("*").length
        stage.applySpeaking(listOf(source("a", hand = true), source("b", hand = false), source("c", hand = false)))
        assertEquals(nodes, stage.root.querySelectorAll("*").length)
        assertFalse(cellB.classList.contains(CONFERENCE_TILE_HAND_CLASS))
        assertSame(cellB, stage.root.querySelectorAll(".lapis-float-strip-cell").item(0))
        assertTrue((stage.root.querySelector(".lapis-float-main") as HTMLElement).classList.contains(CONFERENCE_TILE_HAND_CLASS))
    }

    @Test
    fun aSpeakerMark_neverChangesTheOrderOfTheTiles() {
        val tiles = (1..4).map { tile().first }
        val order = { (0 until host.children.length).map { host.children.item(it) } }
        val before = order()
        val badge = createSpeakingBadge()
        tiles[2].appendChild(badge)
        toggleSpeakingMark(tiles[2], badge, true)
        toggleSpeakingMark(tiles[2], badge, false)
        toggleSpeakingMark(tiles[0], badge, true)
        assertEquals(before, order())
    }
}
