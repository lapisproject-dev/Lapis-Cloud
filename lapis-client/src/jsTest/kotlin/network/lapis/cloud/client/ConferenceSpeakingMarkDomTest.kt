package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.85 -- the active-speaker mark in a real DOM with the real stylesheet: the plaque (symbol AND word, decorative for assistive
 * technology), the early-return toggle, the floating window's cells, node identity across changes and the selection that never reads the mark.
 * A real LiveKit call cannot run in Karma; the wiring of the call view is guarded as text (`ClientConferenceSpeakingMarkTripwireTest`).
 */
class ConferenceSpeakingMarkDomTest {
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
        val badge = createSpeakingBadge()
        tile.appendChild(badge)
        host.appendChild(tile)
        return tile to badge
    }

    @Test
    fun badge_hasSymbolAndWord_isDecorative_andHiddenByDefault() {
        val (_, badge) = tile()
        assertEquals("none", badge.style.display)
        assertEquals("true", badge.getAttribute("aria-hidden"))
        assertNull(badge.getAttribute("role"))
        assertNull(badge.getAttribute("aria-live"))
        assertNull(badge.closest("button"))
        val icon = badge.querySelector("i") as HTMLElement
        assertTrue(icon.className.contains("fa-volume-high"), icon.className)
        assertEquals("true", icon.getAttribute("aria-hidden"))
        assertEquals("spricht", (badge.querySelector(".lapis-conference-speaking-text") as HTMLElement).textContent)
    }

    @Test
    fun toggle_flipsOnlyAClassAndADisplay_nodesAreNeverReplaced() {
        val (tile, badge) = tile()
        val before = (0 until tile.childNodes.length).map { tile.childNodes.item(it) }
        val label = "Tile von Anna"
        tile.setAttribute("aria-label", label)
        toggleSpeakingMark(tile, badge, true)
        assertTrue(tile.classList.contains(SPEAKING_TILE_CLASS))
        assertEquals("inline-flex", badge.style.display)
        assertTrue(badge.offsetWidth > 0, "the plaque is visible")
        val ring = window.getComputedStyle(tile, "::after")
        assertEquals("3px", ring.borderTopWidth, "3 px ring")
        toggleSpeakingMark(tile, badge, false)
        assertFalse(tile.classList.contains(SPEAKING_TILE_CLASS))
        assertEquals("none", badge.style.display)
        val after = (0 until tile.childNodes.length).map { tile.childNodes.item(it) }
        assertEquals(before.size, after.size)
        before.indices.forEach { assertSame(before[it], after[it], "child $it is the same node") }
        assertEquals(label, tile.getAttribute("aria-label"), "the accessible name is untouched")
        assertNull(tile.getAttribute("aria-live"))
        assertNull(tile.getAttribute("role"))
    }

    private fun source(
        key: String,
        marked: Boolean,
        isLocal: Boolean = false,
    ) = FloatMediaSource(
        key = key,
        label = key,
        isLocal = isLocal,
        isScreenShare = false,
        video = null,
        home = host,
        lastSpokeAtMs = 0L,
        setQuality = null,
        speakingMarked = marked,
    )

    private fun cells(stage: FloatStage) = stage.root.querySelectorAll(".lapis-conference-speaking-badge")

    @Test
    fun floatWindow_marksTheCells_andRepaintsMarksWithoutReplacingNodes() {
        val stage = FloatStage()
        host.appendChild(stage.root)
        val ledger = ConferenceVideoLedger()
        val sources = listOf(source("a", marked = true), source("b", marked = false), source("c", marked = true))
        val selection = FloatSelection(mainKey = "a", stripKeys = listOf("b", "c"), insetKey = null, hiddenCount = 0)
        stage.render(selection, sources, ledger, resolvingNow = false)

        val main = stage.root.querySelector(".lapis-float-main") as HTMLElement
        assertTrue(main.classList.contains(SPEAKING_TILE_CLASS))
        val mainBadge = main.querySelector(".lapis-conference-speaking-badge") as HTMLElement
        assertEquals("inline-flex", mainBadge.style.display)
        assertTrue(
            (mainBadge.querySelector(".lapis-conference-speaking-text") as HTMLElement).offsetWidth > 0,
            "the big picture shows the word",
        )

        val strip = stage.root.querySelectorAll(".lapis-float-strip-cell")
        val cellB = strip.item(0) as HTMLElement
        val cellC = strip.item(1) as HTMLElement
        assertFalse(cellB.classList.contains(SPEAKING_TILE_CLASS))
        assertTrue(cellC.classList.contains(SPEAKING_TILE_CLASS))
        val textC = cellC.querySelector(".lapis-conference-speaking-text") as HTMLElement
        assertEquals(0, textC.offsetWidth, "small cells show the symbol only")
        assertEquals("spricht", textC.textContent, "the word is hidden by CSS, not removed")

        val nodesBefore = stage.root.querySelectorAll("*").length
        val firstBadges = (0 until cells(stage).length).map { cells(stage).item(it) }
        // the mark moves from a to b, nothing else changes
        stage.applySpeaking(listOf(source("a", marked = false), source("b", marked = true), source("c", marked = true)))
        assertFalse(main.classList.contains(SPEAKING_TILE_CLASS))
        assertTrue(cellB.classList.contains(SPEAKING_TILE_CLASS))
        assertEquals("none", mainBadge.style.display)
        assertEquals(nodesBefore, stage.root.querySelectorAll("*").length, "no node added or removed")
        firstBadges.indices.forEach { assertSame(firstBadges[it], cells(stage).item(it), "plaque $it is the same node") }

        // a full render with the same sources does not rebuild either
        stage.render(selection, listOf(source("a", marked = false), source("b", marked = true), source("c", marked = false)), ledger, false)
        assertFalse(cellC.classList.contains(SPEAKING_TILE_CLASS))
        assertEquals(nodesBefore, stage.root.querySelectorAll("*").length)
        stage.dispose()
        assertFalse(cellB.classList.contains(SPEAKING_TILE_CLASS), "dispose clears the marks")
    }

    @Test
    fun floatWindow_theOwnPicture_isMarkedByItsIdentityFlag_notByItsLabel() {
        val stage = FloatStage()
        host.appendChild(stage.root)
        val selection = FloatSelection(mainKey = "tile:me", stripKeys = emptyList(), insetKey = null, hiddenCount = 0)
        stage.render(selection, listOf(source("tile:me", marked = true, isLocal = true)), ConferenceVideoLedger(), false)
        val main = stage.root.querySelector(".lapis-float-main") as HTMLElement
        assertEquals("Eigenbild", (main.querySelector(".lapis-float-label") as HTMLElement).textContent)
        assertTrue(main.classList.contains(SPEAKING_TILE_CLASS))
    }

    @Test
    fun selection_isIdentical_whateverTheMarkSays() {
        fun pick(marked: Boolean): Pair<FloatSelection, FloatSpeakerMemo> {
            val sources =
                listOf(
                    source("a", marked = marked).copy(lastSpokeAtMs = 99_800L),
                    source("b", marked = !marked),
                    source("c", marked = marked),
                )
            return floatSelectionOf(
                sources.map { it.info() },
                FloatSize.MEDIUM,
                cameraOn = true,
                nowMs = 100_000L,
                memo = FloatSpeakerMemo(null, 0L),
            )
        }
        assertEquals(pick(true), pick(false))
        assertEquals("a", pick(true).first.mainKey)
    }

    @Test
    fun theMarkSelectors_haveNoAnimationAndNoTransition() {
        val rules = StringBuilder()
        for (i in 0 until document.styleSheets.length) {
            runCatching {
                val list =
                    document.styleSheets
                        .item(i)!!
                        .asDynamic()
                        .cssRules
                for (r in 0 until (list.length as Int)) {
                    val rule = list[r]
                    val selector = rule.selectorText?.toString() ?: continue
                    val body: String = rule.style.cssText.toString()
                    if (selector.contains("speaking")) rules.append("$selector{$body}\n")
                }
            }
        }
        val text = rules.toString()
        assertTrue(text.contains("lapis-conference-tile--speaking"), "the rules are loaded: $text")
        assertFalse(text.contains("animation"), text)
        assertFalse(text.contains("transition"), text)
    }
}
