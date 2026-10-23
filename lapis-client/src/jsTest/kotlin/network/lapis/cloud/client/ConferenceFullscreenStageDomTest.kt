package network.lapis.cloud.client

import io.kvision.panel.Root
import io.kvision.panel.VPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.117 -- in full screen the picture gets the whole height: the 68 px reserve for the control bar is gone, the two detail lines
 * are visually hidden (still in the accessibility tree), the red status bar is compact and the grid has no frame. Real stylesheet and
 * the real dock call panel; the panel's children are built in the shape `enterCall` gives them.
 */
class ConferenceFullscreenStageDomTest {
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

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun HTMLElement.add(
        classes: String,
        text: String = "",
    ): HTMLElement {
        val element = document.createElement("div") as HTMLElement
        element.className = classes
        element.textContent = text
        appendChild(element)
        return element
    }

    @BeforeTest
    fun setUp() {
        assertTrue(stylesLoaded)
        ConferenceDock.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        ConferenceDock.resetForTest()
    }

    private suspend fun withPanel(
        id: String,
        width: Int,
        height: Int,
        block: (HTMLElement) -> Unit,
    ) = withMountedRoot(id) { root: Root, _ ->
        val content: VPanel = root.vPanel(className = "lapis-content") { this.width = width.px }
        val callPanel = ConferenceDock.callPanel
        content.add(callPanel)
        delay(30)
        val panel = assertNotNull(callPanel.getElement() as? HTMLElement)
        panel.style.height = "${height}px"
        panel.style.width = "${width}px"
        panel.add("lapis-conference-status-badges", "Aufzeichnung läuft")
        panel.add("text-muted small lapis-conference-detail-line", "Aufzeichnung gestartet")
        val videoArea = panel.add("lapis-conference-video-area")
        videoArea.add("border rounded lapis-conference-grid")
        block(panel)
    }

    @Test
    fun outsideFullscreen_thePanelReservesNothingForTheBar_itIsStickyNow(): Promise<Unit> =
        test {
            withPanel("conference-stage-normal", 1600, 800) { panel ->
                // V1.9.184: the bar is a sticky box at the end of the panel, no longer a fixed one that needed a 68 px reserve
                assertEquals("0px", window().getComputedStyle(panel).paddingBottom)
            }
        }

    @Test
    fun inFullscreen_noReserveBelow_andTheGridReachesTheBottomEdge(): Promise<Unit> =
        test {
            withPanel("conference-stage-full", 1600, 800) { panel ->
                panel.style.height = "800px"
                panel.classList.add("lapis-conference-fullscreen")
                assertEquals("0px", window().getComputedStyle(panel).paddingBottom)
                val grid = panel.q(".lapis-conference-grid")
                val panelBottom = panel.getBoundingClientRect().bottom
                assertTrue(panelBottom - grid.getBoundingClientRect().bottom <= 2.0, "grid reaches the bottom edge")
                val style = window().getComputedStyle(grid)
                assertEquals("0px", style.borderTopWidth)
                assertEquals("0px", style.paddingTop)
                assertEquals("0px", style.paddingBottom)
            }
        }

    @Test
    fun inFullscreen_theDetailLineIsOnePixel_butKeepsItsText(): Promise<Unit> =
        test {
            withPanel("conference-stage-detail", 1600, 800) { panel ->
                panel.classList.add("lapis-conference-fullscreen")
                val line = panel.q(".lapis-conference-detail-line")
                val box = line.getBoundingClientRect()
                assertTrue(box.width <= 1.0 && box.height <= 1.0, "clipped to one pixel")
                assertTrue(window().getComputedStyle(line).display != "none")
                assertEquals("Aufzeichnung gestartet", line.textContent.orEmpty().trim())
            }
        }

    @Test
    fun inFullscreen_theStatusBarStaysVisibleAndCompact(): Promise<Unit> =
        test {
            withPanel("conference-stage-status", 1600, 800) { panel ->
                panel.classList.add("lapis-conference-fullscreen")
                val bar = panel.q(".lapis-conference-status-badges")
                val height = bar.getBoundingClientRect().height
                assertTrue(height in 20.0..40.0, "compact bar, was $height")
            }
        }

    @Test
    fun inFullscreen_noHorizontalScroll_onPhoneSizes(): Promise<Unit> =
        test {
            for ((w, h) in listOf(360 to 640, 640 to 360)) {
                withPanel("conference-stage-phone-$w", w, h) { panel ->
                    panel.classList.add("lapis-conference-fullscreen")
                    assertTrue(panel.scrollWidth <= panel.clientWidth, "no horizontal scroll at ${w}x$h")
                    val grid = panel.q(".lapis-conference-grid")
                    assertTrue(grid.getBoundingClientRect().bottom <= panel.getBoundingClientRect().bottom + 2.0)
                }
            }
        }

    private fun window() = kotlinx.browser.window
}
