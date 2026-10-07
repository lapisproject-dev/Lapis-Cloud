package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.70 -- the mini bar in a REAL mounted root with the real stylesheets, driven through the dock like the app does. */
class ConferenceDockBarDomTest {
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

    private class FakeSession : DockableSession {
        override val kind = DockSessionKind.CONFERENCE
        var mic = 0
        var camera = 0
        var stopShare = 0
        var leaves = 0

        override fun attach() = Unit

        override fun detach() = Unit

        override fun leave() {
            leaves++
        }

        override suspend fun terminate(reason: DockTerminateReason) = Unit

        override fun toggleMic() {
            mic++
        }

        override fun toggleCamera() {
            camera++
        }

        override fun stopScreenShare() {
            stopShare++
        }
    }

    @BeforeTest
    fun reset() {
        ConferenceDock.resetForTest()
        ConferenceReceiptGate.visible = false
        ConferenceReceiptGate.casting = false
    }

    @AfterTest
    fun cleanup() {
        ConferenceDock.resetForTest()
        ConferenceReceiptGate.visible = false
        ConferenceReceiptGate.casting = false
    }

    private fun HTMLElement.bar() = assertNotNull(querySelector(".lapis-conference-dock-bar") as? HTMLElement, "no bar")

    private fun HTMLElement.button(label: String): HTMLElement =
        assertNotNull(
            (0 until querySelectorAll("button").length)
                .map { querySelectorAll("button").item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("aria-label") == label || it.textContent?.trim() == label },
            "no button '$label'",
        )

    private fun HTMLElement.visibleButtons(): List<String> =
        (0 until querySelectorAll("button").length)
            .map { querySelectorAll("button").item(it) as HTMLElement }
            .filter { it.offsetWidth > 0 }
            .map { it.getAttribute("aria-label") ?: it.textContent.orEmpty().trim() }

    private fun startUndockedCall(fake: FakeSession = FakeSession()): FakeSession {
        assertTrue(ConferenceDock.beginJoin("room-1"))
        ConferenceDock.register(fake)
        ConferenceDock.dispatch(DockEvent.Connected(snap))
        ConferenceDock.dispatch(DockEvent.ViewDetached)
        return fake
    }

    @Test
    fun hiddenWhileIdleOrAttached_visibleOnceUndocked_inTheRightDomPosition(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withMountedRoot("dock-bar-visibility") { root, element ->
                val content = root.div(className = "lapis-content")
                content.setAttribute("role", "main")
                root.conferenceDockBar()
                awaitUntil("the bar is mounted", detail = { element().outerHTML.take(600) }) {
                    element().querySelector(".lapis-conference-dock-bar") != null
                }
                assertEquals(0, element().bar().offsetWidth, "idle: not shown")
                assertEquals("region", element().bar().getAttribute("role"))
                assertTrue(
                    element()
                        .bar()
                        .getAttribute("aria-label")
                        .orEmpty()
                        .isNotBlank(),
                )

                startUndockedCall()
                awaitUntil("the bar shows") { element().bar().offsetWidth > 0 }
                val children = element().children
                val barIndex = (0 until children.length).first { (children.item(it) as HTMLElement).matches(".lapis-conference-dock-bar") }
                val mainIndex = (0 until children.length).first { (children.item(it) as HTMLElement).matches(".lapis-content") }
                assertTrue(barIndex > mainIndex, "the bar follows the main landmark in the DOM")

                ConferenceDock.dispatch(DockEvent.ViewAttached)
                awaitUntil("attached: the bar is gone again") { element().bar().offsetWidth == 0 }
            }
        }

    @Test
    fun live_showsTheStatus_theDeviceButtonsWithAriaPressed_andLeave_allAtLeast44px(): Promise<Unit> =
        formTest {
            withMountedRoot("dock-bar-live") { root, element ->
                root.conferenceDockBar()
                val fake = startUndockedCall()
                awaitUntil("the bar shows") {
                    element().querySelector(".lapis-conference-dock-bar")?.let {
                        (it as HTMLElement).offsetWidth >
                            0
                    } ==
                        true
                }
                val bar = element().bar()
                assertTrue(bar.textContent.orEmpty().contains("Besprechung läuft"))
                assertEquals(
                    listOf("Zur Konferenz: Besprechung läuft", "Mikrofon ausschalten", "Kamera ausschalten", "Verlassen"),
                    bar.visibleButtons(),
                )
                assertEquals("true", bar.button("Mikrofon ausschalten").getAttribute("aria-pressed"))

                bar.button("Mikrofon ausschalten").click()
                bar.button("Kamera ausschalten").click()
                assertEquals(1, fake.mic)
                assertEquals(1, fake.camera)

                ConferenceDock.publish(snap.copy(micOn = false))
                awaitUntil("the mic button reflects the snapshot") {
                    bar.button("Mikrofon einschalten").getAttribute("aria-pressed") == "false"
                }
                (0 until bar.querySelectorAll("button").length)
                    .map { bar.querySelectorAll("button").item(it) as HTMLElement }
                    .filter { it.offsetWidth > 0 }
                    .forEach {
                        assertTrue(
                            it.offsetWidth >= 44 && it.offsetHeight >= 44,
                            "'${it.getAttribute("aria-label")}' is below 44 px",
                        )
                    }
            }
        }

    @Test
    fun consentBadges_andTheStopShareButton_followTheSnapshot_andAreNeverClipped(): Promise<Unit> =
        formTest {
            withMountedRoot("dock-bar-consent") { root, element ->
                root.conferenceDockBar()
                val fake = startUndockedCall()
                ConferenceDock.publish(
                    snap.copy(recording = true, streaming = true, streamPaused = true, screenSharing = true, voteOpen = true),
                )
                awaitUntil("the badges show") {
                    element()
                        .bar()
                        .textContent
                        .orEmpty()
                        .contains("◆ Live-Stream pausiert")
                }
                val bar = element().bar()
                val text = bar.textContent.orEmpty()
                assertTrue(text.contains("● Aufzeichnung"))
                assertTrue(text.contains("Abstimmung läuft"))
                val badges = bar.querySelectorAll(".lapis-dock-badges .badge")
                for (i in 0 until badges.length) {
                    val badge = badges.item(i) as HTMLElement
                    assertTrue(badge.offsetWidth >= badge.scrollWidth, "a badge must never be shortened: '${badge.textContent}'")
                }
                assertTrue(bar.visibleButtons().contains("Bildschirmfreigabe beenden"))
                bar.button("Bildschirmfreigabe beenden").click()
                assertEquals(1, fake.stopShare)

                ConferenceDock.publish(snap)
                awaitUntil("the stop-share button is gone") { !bar.visibleButtons().contains("Bildschirmfreigabe beenden") }
            }
        }

    @Test
    fun leave_callsTheSession_butWithAReceiptOnScreenItTakesThePersonToTheView(): Promise<Unit> =
        formTest {
            withMountedRoot("dock-bar-leave") { root, element ->
                root.conferenceDockBar()
                val fake = startUndockedCall()
                awaitUntil("the bar shows") {
                    element().querySelector(".lapis-conference-dock-bar")?.let {
                        (it as HTMLElement).offsetWidth >
                            0
                    } ==
                        true
                }
                ConferenceReceiptGate.visible = true
                element().bar().button("Verlassen").click()
                assertEquals(0, fake.leaves, "a secret-ballot receipt exists only in the view: leaving from the bar would lose it")
                assertTrue(ConferenceDock.pendingFocusFromBar, "the person is taken to the view, focus follows")
                ConferenceDock.pendingFocusFromBar = false

                ConferenceReceiptGate.visible = false
                element().bar().button("Verlassen").click()
                assertEquals(1, fake.leaves)
            }
        }

    @Test
    fun theReturnArea_navigatesOnly_aDoubleClickNeverJoins(): Promise<Unit> =
        formTest {
            withMountedRoot("dock-bar-return") { root, element ->
                root.conferenceDockBar()
                startUndockedCall()
                awaitUntil("the bar shows") {
                    element().querySelector(".lapis-conference-dock-bar")?.let {
                        (it as HTMLElement).offsetWidth >
                            0
                    } ==
                        true
                }
                val generation = ConferenceDock.generation
                val returnButton = element().bar().button("Zur Konferenz: Besprechung läuft")
                returnButton.click()
                returnButton.click()
                assertEquals(generation, ConferenceDock.generation, "no join, no new call")
                assertTrue(ConferenceDock.state is DockState.Live)
            }
        }

    @Test
    fun anEndState_showsItsMessage_inAStatusOrAlertRegion_withoutMovingTheFocus(): Promise<Unit> =
        formTest {
            withMountedRoot("dock-bar-end") { root, element ->
                val focusTarget = document.createElement("button") as HTMLElement
                focusTarget.id = "dock-bar-end-focus"
                document.body!!.appendChild(focusTarget)
                try {
                    root.conferenceDockBar()
                    // V1.9.71: the live regions live in the announcer, a sibling of the bar (the bar can be display:none)
                    root.conferenceDockAnnouncer()
                    startUndockedCall()
                    awaitUntil("the bar shows") {
                        element().querySelector(".lapis-conference-dock-bar")?.let {
                            (it as HTMLElement).offsetWidth >
                                0
                        } ==
                            true
                    }
                    focusTarget.focus()
                    ConferenceDock.dispatch(DockEvent.Stopped(DockStopReason.DUPLICATE_IDENTITY))
                    awaitUntil("the end message shows") {
                        element()
                            .textContent
                            .orEmpty()
                            .contains("Dieses Konto ist auf einem anderen Gerät verbunden.")
                    }
                    assertTrue(document.activeElement === focusTarget, "the bar never takes the focus")
                    val alert = element().querySelector("[role=alert]") as HTMLElement
                    assertTrue(alert.textContent.orEmpty().contains("anderen Gerät"), "an involuntary end is an alert")
                    assertEquals(listOf("Zur Konferenz", "Schließen"), element().bar().visibleButtons())

                    element().bar().button("Schließen").click()
                    assertEquals(DockState.Idle, ConferenceDock.state)
                } finally {
                    focusTarget.remove()
                }
            }
        }

    @Test
    fun escapeDoesNothing_andTheTabOrderIsReturnMicCameraLeave(): Promise<Unit> =
        formTest {
            withMountedRoot("dock-bar-keys") { root, element ->
                root.conferenceDockBar()
                startUndockedCall()
                awaitUntil("the bar shows") {
                    element().querySelector(".lapis-conference-dock-bar")?.let {
                        (it as HTMLElement).offsetWidth >
                            0
                    } ==
                        true
                }
                val bar = element().bar()
                val before = ConferenceDock.state
                bar.dispatchEvent(
                    org.w3c.dom.events
                        .KeyboardEvent(
                            "keydown",
                            org.w3c.dom.events
                                .KeyboardEventInit(key = "Escape", bubbles = true),
                        ),
                )
                assertEquals(before, ConferenceDock.state, "Escape has no effect")
                val order = bar.visibleButtons()
                assertEquals(listOf("Mikrofon ausschalten", "Kamera ausschalten", "Verlassen"), order.drop(1))
                assertTrue(order.first().startsWith("Zur Konferenz"))
            }
        }

    @Test
    fun theBar_hasNoPersonalDataAndNoRoomTitle(): Promise<Unit> =
        formTest {
            withMountedRoot("dock-bar-privacy") { root, element ->
                root.conferenceDockBar()
                ConferenceDock.beginJoin("00000000-aaaa-bbbb-cccc-room-id-1234")
                ConferenceDock.register(FakeSession())
                ConferenceDock.dispatch(DockEvent.Connected(snap))
                ConferenceDock.dispatch(DockEvent.ViewDetached)
                awaitUntil("the bar shows") {
                    element().querySelector(".lapis-conference-dock-bar")?.let {
                        (it as HTMLElement).offsetWidth >
                            0
                    } ==
                        true
                }
                assertFalse(element().bar().innerHTML.contains("room-id-1234"), "the room id never reaches the DOM")
            }
        }
}
