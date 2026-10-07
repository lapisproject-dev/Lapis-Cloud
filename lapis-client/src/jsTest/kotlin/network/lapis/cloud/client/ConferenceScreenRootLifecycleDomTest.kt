package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The teardown hook of the video-conference screen in a REAL, mounted `Root` (the "late hooks" audit).
 *
 * V1.9.70: the screen's destroy hook no longer ends the call -- the call lives in the conference dock and the screen is only one view
 * on it. The hook must (a) not run while the screen is still shown (the original late-hook bug: it ran once, right after the screen
 * came up, because it was registered after the screen root had been rendered), and (b) when the screen really goes away only DETACH
 * the view: no disconnect, call presence stays, nothing is terminated.
 *
 * The RPC behind the screen fails under Karma (no server); with a running call in the dock the screen does not even ask for it.
 */
class ConferenceScreenRootLifecycleDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private class FakeSession : DockableSession {
        override val kind = DockSessionKind.CONFERENCE
        var attaches = 0
        var detaches = 0
        var terminations = 0

        override fun attach() {
            attaches++
        }

        override fun detach() {
            detaches++
        }

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

    @BeforeTest
    fun reset() {
        ConferenceDock.resetForTest()
        ConferenceLobbyPort.resetForTest()
    }

    @AfterTest
    fun cleanup() {
        ConferenceDock.resetForTest()
        ConferenceLobbyPort.resetForTest()
    }

    @Test
    fun screenTeardown_doesNotRunWhileTheScreenIsStillShown_andOnlyDetachesTheViewWhenItGoes(): Promise<Unit> =
        test {
            withMountedRoot("conference-root-lifecycle-test") { root, _ ->
                val fake = FakeSession()
                assertTrue(ConferenceDock.beginJoin("room-1"))
                ConferenceDock.register(fake)
                ConferenceDock.dispatch(DockEvent.Connected(snap))
                renderConferenceScreen(root)
                delay(500) // the screen patched itself (status line hidden, notice) -- this patch used to fire the hook early
                assertTrue(ConferenceCallPresence.live, "the call is running")
                assertEquals(0, fake.detaches, "the teardown hook must not have run: the screen is still mounted")
                assertTrue(ConferenceDock.state.isAttached, "the screen shows the call view")
                assertEquals(1, fake.attaches)

                root.removeAll()
                awaitUntil("the screen's destroy hook ran") { !ConferenceDock.state.isAttached }
                assertEquals(1, fake.detaches, "leaving the screen detaches the view exactly once")
                assertEquals(0, fake.terminations, "...and never ends the call")
                assertTrue(ConferenceCallPresence.live, "the call is still live without its screen")
                assertTrue(ConferenceDock.state is DockState.Live)
                assertFalse(ConferenceDock.state.isAttached)
            }
        }

    @Test
    fun withoutACall_theScreenComesAndGoes_withoutTouchingTheDock(): Promise<Unit> =
        test {
            withMountedRoot("conference-root-lifecycle-idle") { root, _ ->
                renderConferenceScreen(root)
                delay(500)
                root.removeAll()
                delay(200)
                assertEquals(DockState.Idle, ConferenceDock.state)
                assertFalse(ConferenceCallPresence.live)
                assertTrue(ConferenceLobbyPort.current == null, "the screen's lobby port goes with the screen")
            }
        }
}
