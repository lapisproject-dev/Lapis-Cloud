package network.lapis.cloud.client

import io.kvision.panel.Root
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.browser.window
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
 * V1.9.108 -- in full screen the call's heading row paints nothing (the person is looking at faces), but the heading stays in the
 * accessibility tree and "Bearbeiten" leaves the tab order. Built in the REAL structure like [ConferenceFullWidthDomTest] (landmark
 * `lapis-content`, the real route outlet with `conferenceScreenRoot`, the real dock host bound with `ConferenceDock.bindHost`, the real
 * stylesheet): `fullscreen` sits on the call panel, which is the dock host's child -- not on the page root (V1.9.104).
 */
class ConferenceFullscreenTitleDomTest {
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

    private class FakeSession : DockableSession {
        override val kind = DockSessionKind.CONFERENCE

        override fun attach() = Unit

        override fun detach() = Unit

        override fun leave() = Unit

        override suspend fun terminate(reason: DockTerminateReason) = Unit

        override fun toggleMic() = Unit

        override fun toggleCamera() = Unit

        override fun stopScreenShare() = Unit
    }

    private val snap =
        DockSnapshot(
            micOn = true,
            cameraOn = true,
            screenSharing = false,
            recording = false,
            streaming = false,
            streamPaused = false,
            voteOpen = false,
            transitioning = null,
        )

    private fun build(root: Root): VPanel {
        val content = root.vPanel(className = "lapis-content") { width = 1400.px }
        content.vPanel(className = "lapis-route-outlet").conferenceScreenRoot {}
        val host = content.conferenceDockHost()
        ConferenceDock.bindHost(host)
        // the call panel of the real dock, with the heading row built exactly as `enterCall` builds it
        val callPanel = ConferenceDock.callPanel
        val titleRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap $CONFERENCE_TITLE_ROW_CLASS") }
        titleRow.renderConferenceTitleView(roomTitle = "Vorstandssitzung", canModerate = true, onEdit = {})
        return callPanel
    }

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun fullscreen(
        callPanel: VPanel,
        on: Boolean,
    ) {
        val element = assertNotNull(callPanel.getElement() as? HTMLElement)
        if (on) element.classList.add("lapis-conference-fullscreen") else element.classList.remove("lapis-conference-fullscreen")
    }

    @BeforeTest
    fun setUp() {
        assertTrue(stylesLoaded)
        ConferenceDock.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        ConferenceDock.resetForTest()
        document.documentElement!!.classList.remove(CONFERENCE_CALL_VISIBLE_CLASS)
    }

    private suspend fun inCall(
        id: String,
        block: suspend (VPanel, HTMLElement) -> Unit,
    ) = withMountedRoot(id) { root, _ ->
        val callPanel = build(root)
        assertTrue(ConferenceDock.beginJoin("room-1"))
        ConferenceDock.register(FakeSession())
        ConferenceDock.dispatch(DockEvent.Connected(snap))
        delay(50)
        block(callPanel, assertNotNull(callPanel.getElement() as? HTMLElement))
    }

    @Test
    fun outsideFullscreen_theHeadingAndTheEditButtonAreShown(): Promise<Unit> =
        test {
            inCall("conference-title-normal") { _, panel ->
                val title = panel.q(".lapis-conference-title")
                val edit = panel.q(".lapis-conference-title-edit")
                assertEquals("H2", title.tagName)
                assertEquals("Vorstandssitzung", title.textContent.orEmpty().trim())
                assertTrue(title.getBoundingClientRect().width > 50.0 && title.getBoundingClientRect().height > 10.0, "painted")
                assertTrue(edit.getBoundingClientRect().width > 0.0, "the edit button is shown")
                assertEquals("Bearbeiten", edit.textContent.orEmpty().trim())
            }
        }

    @Test
    fun inFullscreen_theHeadingIsOnePixelAndClipped_butStillAHeadingWithItsText_andEditIsGone(): Promise<Unit> =
        test {
            inCall("conference-title-fullscreen") { callPanel, panel ->
                val title = panel.q(".lapis-conference-title")
                val edit = panel.q(".lapis-conference-title-edit")
                val row = panel.q(".lapis-conference-title-row")
                fullscreen(callPanel, true)

                val box = title.getBoundingClientRect()
                assertEquals(1.0, box.width, "1 px wide")
                assertEquals(1.0, box.height, "1 px high")
                assertEquals("rect(0px, 0px, 0px, 0px)", window.getComputedStyle(title).getPropertyValue("clip").replace(", ", ", "))
                // still in the accessibility tree: an h2 in the DOM with its text, not display:none
                assertEquals("H2", title.tagName)
                assertEquals("Vorstandssitzung", title.textContent.orEmpty().trim())
                assertTrue(window.getComputedStyle(title).display != "none")
                // the edit button is display:none -- also out of the tab order
                assertEquals("none", window.getComputedStyle(edit).display)
                edit.focus()
                assertTrue(document.activeElement != edit, "a display:none button cannot take the focus")
                // the row itself takes no room
                val rowStyle = window.getComputedStyle(row)
                assertEquals("0px", rowStyle.marginTop)
                assertEquals("0px", rowStyle.paddingTop)
                assertEquals("0px", rowStyle.getPropertyValue("column-gap").ifEmpty { "0px" }.let { if (it == "normal") "0px" else it })
            }
        }

    @Test
    fun leavingFullscreen_restoresTheNormalHeading(): Promise<Unit> =
        test {
            inCall("conference-title-restore") { callPanel, panel ->
                val title = panel.q(".lapis-conference-title")
                val before = title.getBoundingClientRect()
                fullscreen(callPanel, true)
                fullscreen(callPanel, false)
                val after = title.getBoundingClientRect()
                assertEquals(before.width, after.width, "same width as before")
                assertEquals(before.height, after.height, "same height as before")
                assertTrue(panel.q(".lapis-conference-title-edit").getBoundingClientRect().width > 0.0)
            }
        }

    @Test
    fun theHeadingStaysTheOnlyThingTheLandmarkNeeds_theRegionKeepsItsOwnName(): Promise<Unit> =
        test {
            inCall("conference-title-landmark") { _, panel ->
                // V1.9.104/108: the region is named by its aria-label ("Besprechung"), it is NOT switched to aria-labelledby
                assertEquals("Besprechung", panel.getAttribute("aria-label"))
                assertEquals(null, panel.getAttribute("aria-labelledby"))
            }
        }
}
