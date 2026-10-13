package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.71 -- how the dock presents a running call: bar, floating window or the full view. */
class ConferenceDockPresentationTest {
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

    private fun states(attached: Boolean): Map<String, DockState> =
        mapOf(
            "joining" to DockState.Joining(attached),
            "live" to DockState.Live(attached, snap),
            "resolving" to DockState.Resolving(attached, snap),
            "stopped" to DockState.Stopped(DockStopReason.ENDED, attached),
        )

    @Test
    fun idle_isNeverFloating() {
        for (mode in FloatMode.entries) {
            for (wide in listOf(true, false)) {
                assertEquals(DockPresentation.BAR, dockPresentationOf(DockState.Idle, mode, wide))
            }
        }
    }

    @Test
    fun anAttachedView_isAlwaysFull() {
        for ((name, state) in states(attached = true)) {
            for (mode in FloatMode.entries) {
                for (wide in listOf(true, false)) {
                    assertEquals(DockPresentation.FULL, dockPresentationOf(state, mode, wide), "$name $mode wide=$wide")
                }
            }
        }
    }

    @Test
    fun liveAndResolving_floatOnlyWithTheWishAndAWideViewport() {
        for (name in listOf("live", "resolving")) {
            val state = states(false).getValue(name)
            assertEquals(DockPresentation.FLOAT, dockPresentationOf(state, FloatMode.FLOAT, wide = true), name)
            assertEquals(DockPresentation.BAR, dockPresentationOf(state, FloatMode.FLOAT, wide = false), "$name narrow")
            assertEquals(DockPresentation.BAR, dockPresentationOf(state, FloatMode.BAR, wide = true), "$name folded")
            assertEquals(DockPresentation.BAR, dockPresentationOf(state, FloatMode.BAR, wide = false), "$name folded narrow")
        }
    }

    @Test
    fun joiningAndStopped_areAlwaysTheBar() {
        for (name in listOf("joining", "stopped")) {
            val state = states(false).getValue(name)
            for (mode in FloatMode.entries) {
                for (wide in listOf(true, false)) {
                    assertEquals(DockPresentation.BAR, dockPresentationOf(state, mode, wide), "$name $mode wide=$wide")
                }
            }
        }
    }

    // -- V1.9.127: the raised hands in the mini bar and the floating window -----------------------------------------------------------------

    @Test
    fun theBarAndTheWindow_sayTheHandsInWords_onlyWhileLive() {
        val withHands = snap.copy(handRaisedSelf = true, raisedHandsCount = 2)
        val live = DockState.Live(attached = false, snapshot = withHands)
        val expected = listOf("Ihre Hand ist gehoben", "Gehobene Hände: 2")
        assertEquals(expected, dockBarViewOf(live, narrow = false).handBadges)
        assertEquals(expected, floatWindowViewOf(live).handBadges)
        // no hands: nothing, and an old snapshot (defaults) is the same
        assertEquals(emptyList(), dockBarViewOf(DockState.Live(false, snap), narrow = false).handBadges)
        assertEquals(emptyList(), floatWindowViewOf(DockState.Live(false, snap)).handBadges)
        // not live (resolving a breakout switch): the hands of the old room are not claimed
        assertEquals(emptyList(), dockBarViewOf(DockState.Resolving(false, withHands), narrow = false).handBadges)
        assertEquals(emptyList(), floatWindowViewOf(DockState.Resolving(false, withHands)).handBadges)
    }

    @Test
    fun aNarrowBar_takesTwoRowsForTheHandBadges() {
        val withHands = snap.copy(handRaisedSelf = true)
        assertEquals(true, dockBarViewOf(DockState.Live(false, withHands), narrow = true).twoRows)
        assertEquals(false, dockBarViewOf(DockState.Live(false, snap), narrow = true).twoRows)
    }
}
