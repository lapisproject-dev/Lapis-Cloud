package network.lapis.cloud.client

import io.kvision.panel.Root
import io.kvision.panel.VPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import network.lapis.cloud.client.maplibre.ResizeObserver
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.104 -- the call page is really as wide as the content area, in the REAL structure of the app: the landmark (`lapis-content`)
 * holds the route outlet with the real `conferenceScreenRoot` and, next to it, the real permanent dock host (`conferenceDockHost`), bound
 * with `ConferenceDock.bindHost`. V1.9.92 never worked in the app because the dock host carried an inline 960 px cap and the `:has`
 * rule sat on the page root, which does not contain the call panel. The stage is a fixed 1400 px wide, so the test proves something even
 * if the Karma browser is narrower than 960 px.
 */
class ConferenceFullWidthDomTest {
    private companion object {
        const val STAGE_PX = 1400.0
        const val COLUMN_PX = 960.0
        const val TOLERANCE_PX = 1.0

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
        var terminations = 0

        override fun attach() = Unit

        override fun detach() = Unit

        override fun leave() = Unit

        override suspend fun terminate(reason: DockTerminateReason) {
            terminations++
        }

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

    private class Page(
        val content: VPanel,
        val screenRoot: VPanel,
        val host: VPanel,
    )

    private fun build(root: Root): Page {
        val content = root.vPanel(className = "lapis-content") { width = STAGE_PX.toInt().px }
        val outlet = content.vPanel(className = "lapis-route-outlet")
        val screenRoot = outlet.conferenceScreenRoot {}
        val host = content.conferenceDockHost()
        ConferenceDock.bindHost(host)
        return Page(content, screenRoot, host)
    }

    private fun el(widget: VPanel): HTMLElement = widget.getElement() as HTMLElement

    private fun htmlHasSwitch(): Boolean = document.documentElement!!.classList.contains(CONFERENCE_CALL_VISIBLE_CLASS)

    private fun widthOf(element: HTMLElement): Double = element.getBoundingClientRect().width

    private fun assertNear(
        expected: Double,
        actual: Double,
        message: String,
    ) = assertTrue(abs(expected - actual) <= TOLERANCE_PX, "$message: expected $expected, was $actual")

    private suspend fun inPage(
        id: String,
        block: suspend (Page) -> Unit,
    ) = withMountedRoot(id) { root, _ ->
        val page = build(root)
        delay(50)
        block(page)
    }

    @BeforeTest
    fun setUp() {
        assertTrue(stylesLoaded)
        ConferenceDock.resetForTest()
        document.documentElement!!.classList.remove(CONFERENCE_CALL_VISIBLE_CLASS)
    }

    @AfterTest
    fun tearDown() {
        ConferenceDock.resetForTest()
        document.documentElement!!.classList.remove(CONFERENCE_CALL_VISIBLE_CLASS)
    }

    @Test
    fun idle_theColumnIsNarrow_theHostIsHidden_andTheSwitchIsOff(): Promise<Unit> =
        test {
            inPage("conference-fullwidth-idle") { page ->
                assertFalse(htmlHasSwitch())
                assertNear(COLUMN_PX, widthOf(el(page.screenRoot)), "the lobby column")
                assertEquals("none", window.getComputedStyle(el(page.host)).display, "no call: the host is hidden")
                // the host shares the column with the page root (a hidden element has no box, so the rule is read from a probe)
                assertEquals("960px", window.getComputedStyle(el(page.host)).maxWidth)
            }
        }

    @Test
    fun aVisibleCall_liftsTheCapOfRootAndHost_inTheRealStructure_withoutMovingTheHeading(): Promise<Unit> =
        test {
            inPage("conference-fullwidth-call") { page ->
                val topBefore = el(page.screenRoot).getBoundingClientRect().top
                assertTrue(ConferenceDock.beginJoin("room-1"))
                ConferenceDock.register(FakeSession())
                awaitUntil("the call is on screen: the switch is set") { htmlHasSwitch() }
                assertFalse(el(page.host).classList.contains(DOCK_HIDDEN_CLASS), "the host is shown while a call is attached")
                assertEquals("none", window.getComputedStyle(el(page.host)).maxWidth)
                assertEquals("none", window.getComputedStyle(el(page.screenRoot)).maxWidth)
                assertNear(STAGE_PX, widthOf(el(page.host)), "the call host")
                assertNear(STAGE_PX, widthOf(el(page.screenRoot)), "the page root")
                assertNear(
                    el(page.screenRoot).getBoundingClientRect().left,
                    el(page.host).getBoundingClientRect().left,
                    "root and host start at the same left edge",
                )
                assertNear(topBefore, el(page.screenRoot).getBoundingClientRect().top, "the heading does not jump vertically")
                assertEquals("24px", window.getComputedStyle(el(page.host)).marginTop, "same top margin as the lobby column")

                ConferenceDock.dispatch(DockEvent.Connected(snap))
                assertTrue(htmlHasSwitch(), "still set once the call is live")
            }
        }

    @Test
    fun anUndockedCall_hasNoSwitch_andANewViewBringsItBack(): Promise<Unit> =
        test {
            inPage("conference-fullwidth-undock") { page ->
                assertTrue(ConferenceDock.beginJoin("room-1"))
                ConferenceDock.register(FakeSession())
                ConferenceDock.dispatch(DockEvent.Connected(snap))
                assertTrue(htmlHasSwitch())
                ConferenceDock.dispatch(DockEvent.ViewDetached)
                assertFalse(htmlHasSwitch(), "the mini bar shows the call: the page is narrow again")
                assertTrue(el(page.host).classList.contains(DOCK_HIDDEN_CLASS))
                assertNear(COLUMN_PX, widthOf(el(page.screenRoot)), "the column after undocking")
                ConferenceDock.dispatch(DockEvent.ViewAttached)
                assertTrue(htmlHasSwitch())
            }
        }

    @Test
    fun theEndCard_isFullWidthWhileAttached(): Promise<Unit> =
        test {
            inPage("conference-fullwidth-stopped") { page ->
                assertTrue(ConferenceDock.beginJoin("room-1"))
                ConferenceDock.register(FakeSession())
                ConferenceDock.dispatch(DockEvent.Connected(snap))
                ConferenceDock.dispatch(DockEvent.Stopped(DockStopReason.ENDED))
                assertTrue(ConferenceDock.state is DockState.Stopped)
                assertTrue(htmlHasSwitch(), "an attached end card keeps the one condition")
                assertNear(STAGE_PX, widthOf(el(page.host)), "the end card")
            }
        }

    @Test
    fun backToIdle_removesTheSwitch_andTheColumnIsNarrowAgain(): Promise<Unit> =
        test {
            inPage("conference-fullwidth-idle-again") { page ->
                val fake = FakeSession()
                assertTrue(ConferenceDock.beginJoin("room-1"))
                ConferenceDock.register(fake)
                ConferenceDock.dispatch(DockEvent.Connected(snap))
                assertTrue(htmlHasSwitch())
                ConferenceDock.terminate(DockTerminateReason.LEAVE)
                assertEquals(DockState.Idle, ConferenceDock.state)
                assertFalse(htmlHasSwitch())
                assertNear(COLUMN_PX, widthOf(el(page.screenRoot)), "the lobby column after the call")
            }
        }

    @Test
    fun aRootRebuildDuringACall_neverLeavesTheSwitchBehind(): Promise<Unit> =
        test {
            inPage("conference-fullwidth-rebind") { page ->
                val fake = FakeSession()
                assertTrue(ConferenceDock.beginJoin("room-1"))
                ConferenceDock.register(fake)
                ConferenceDock.dispatch(DockEvent.Connected(snap))
                assertTrue(htmlHasSwitch())
                // `App.start()` rebuilds the root: the call cannot survive it and is terminated asynchronously
                ConferenceDock.bindHost(page.content.conferenceDockHost())
                awaitUntil("the rebuild ended the call") { ConferenceDock.state is DockState.Idle }
                assertFalse(htmlHasSwitch(), "no call, no switch")
                assertEquals(1, fake.terminations)
            }
        }

    @Test
    fun theTileArea_followsTheColumn_soTheResizeObserverOfTheGridRunsAgain(): Promise<Unit> =
        test {
            inPage("conference-fullwidth-grid") { page ->
                val area = document.createElement("div") as HTMLElement
                area.className = "border rounded lapis-conference-grid"
                el(page.host).appendChild(area)
                // the real call view watches its tile area with exactly this kind of observer (`tileGridObserver`)
                val widths = mutableListOf<Double>()
                val watching = ResizeObserver { entries, _ -> widths += (entries[0].contentRect.width as Number).toDouble() }
                watching.observe(area)
                try {
                    assertTrue(ConferenceDock.beginJoin("room-1"))
                    ConferenceDock.register(FakeSession())
                    awaitUntil("the grid area was measured at full width") { widths.any { it > COLUMN_PX } }
                    assertTrue(area.clientWidth > COLUMN_PX, "the area is wider than the old 960 px column: ${area.clientWidth}")
                } finally {
                    watching.disconnect()
                }
            }
        }

    @Test
    fun theMoreSheet_isAnchoredOverTheMiddleOfTheWindow_whileACallIsVisible(): Promise<Unit> =
        test {
            if (window.innerWidth < 768) return@test
            inPage("conference-fullwidth-more") { page ->
                val sheet = document.createElement("div") as HTMLElement
                sheet.className = "lapis-conference-more-sheet"
                sheet.textContent = "Mehr"
                el(page.host).appendChild(sheet)
                val narrowRight = window.getComputedStyle(sheet).right
                assertTrue(ConferenceDock.beginJoin("room-1"))
                ConferenceDock.register(FakeSession())
                awaitUntil("the switch is set") { htmlHasSwitch() }
                val expectedLeft = maxOf(8.0, window.innerWidth / 2.0 - 210.0)
                assertNear(expectedLeft, sheet.getBoundingClientRect().left, "left edge of the sheet (before: right=$narrowRight)")
                val rect = sheet.getBoundingClientRect()
                assertTrue(rect.left >= -0.5 && rect.right <= window.innerWidth + 0.5, "inside the viewport: ${rect.left}..${rect.right}")
            }
        }
}
