package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.70 -- the pure state machine of the conference dock ([conferenceDockReduce]): the transitions of a call, "attached" as an
 * orthogonal flag, hard termination from every state, and that an unknown (state, event) pair never changes anything.
 */
class ConferenceDockReduceTest {
    private val snap =
        DockSnapshot(
            micOn = true,
            cameraOn = false,
            screenSharing = false,
            recording = false,
            streaming = false,
            streamPaused = false,
            voteOpen = false,
            transitioning = null,
        )

    private fun reduce(
        state: DockState,
        vararg events: DockEvent,
    ): DockState = events.fold(state) { acc, event -> conferenceDockReduce(acc, event) }

    @Test
    fun aJoin_runsIdleJoiningLive_withTheViewAttached() {
        assertEquals(DockState.Joining(attached = true), reduce(DockState.Idle, DockEvent.JoinRequested))
        assertEquals(
            DockState.Live(attached = true, snapshot = snap),
            reduce(DockState.Idle, DockEvent.JoinRequested, DockEvent.Connected(snap)),
        )
    }

    @Test
    fun attachedIsOrthogonal_viewDetachedAndAttached_changeOnlyTheFlag() {
        val live = DockState.Live(attached = true, snapshot = snap)
        assertEquals(DockState.Live(attached = false, snapshot = snap), reduce(live, DockEvent.ViewDetached))
        assertEquals(live, reduce(live, DockEvent.ViewDetached, DockEvent.ViewAttached))
        assertEquals(DockState.Joining(attached = false), reduce(DockState.Joining(attached = true), DockEvent.ViewDetached))
        assertEquals(DockState.Idle, reduce(DockState.Idle, DockEvent.ViewDetached))
        assertEquals(DockState.Idle, reduce(DockState.Idle, DockEvent.ViewAttached))
    }

    @Test
    fun aSecondJoinRequest_isIgnoredWhileAnythingIsThere() {
        for (state in listOf(
            DockState.Joining(true),
            DockState.Live(true, snap),
            DockState.Resolving(true, snap),
            DockState.Stopped(DockStopReason.ENDED, true),
        )) {
            assertSame(state, conferenceDockReduce(state, DockEvent.JoinRequested))
        }
    }

    @Test
    fun aBreakoutHandOver_goesLiveResolvingJoiningLive() {
        val live = DockState.Live(attached = false, snapshot = snap)
        val resolving = reduce(live, DockEvent.DisconnectResolving)
        assertEquals(DockState.Resolving(attached = false, snapshot = snap), resolving)
        val joining = reduce(resolving, DockEvent.ReEntered)
        assertEquals(DockState.Joining(attached = false), joining)
        assertEquals(DockState.Live(attached = false, snapshot = snap), reduce(joining, DockEvent.Connected(snap)))
    }

    @Test
    fun snapshots_updateLiveAndResolving_andNothingElse() {
        val changed = snap.copy(micOn = false, recording = true)
        assertEquals(DockState.Live(true, changed), reduce(DockState.Live(true, snap), DockEvent.SnapshotChanged(changed)))
        assertEquals(DockState.Resolving(true, changed), reduce(DockState.Resolving(true, snap), DockEvent.SnapshotChanged(changed)))
        assertEquals(DockState.Joining(true), reduce(DockState.Joining(true), DockEvent.SnapshotChanged(changed)))
        assertEquals(DockState.Idle, reduce(DockState.Idle, DockEvent.SnapshotChanged(changed)))
    }

    @Test
    fun everyStopReason_endsInStopped_andDismissedLeadsToIdle() {
        for (reason in DockStopReason.entries) {
            val stopped = reduce(DockState.Live(false, snap), DockEvent.Stopped(reason))
            assertEquals(DockState.Stopped(reason, attached = false), stopped)
            assertEquals(DockState.Idle, reduce(stopped, DockEvent.Dismissed))
        }
        assertEquals(DockState.Idle, reduce(DockState.Idle, DockEvent.Stopped(DockStopReason.ENDED)))
        assertEquals(DockState.Joining(true), reduce(DockState.Joining(true), DockEvent.Dismissed))
    }

    @Test
    fun aStoppedState_neverJoinsByItself_onlyAnExplicitReEntryDoes() {
        val stopped = DockState.Stopped(DockStopReason.DUPLICATE_IDENTITY, attached = true)
        assertSame(stopped, conferenceDockReduce(stopped, DockEvent.Connected(snap)))
        assertSame(stopped, conferenceDockReduce(stopped, DockEvent.DisconnectResolving))
        assertEquals(DockState.Joining(true), conferenceDockReduce(stopped, DockEvent.ReEntered))
    }

    @Test
    fun termination_leadsToIdle_fromEveryState_forEveryReason() {
        val states =
            listOf(
                DockState.Idle,
                DockState.Joining(true),
                DockState.Live(false, snap),
                DockState.Resolving(true, snap),
                DockState.Stopped(DockStopReason.REJOIN_EXHAUSTED, false),
            )
        for (state in states) {
            for (reason in DockTerminateReason.entries) {
                assertEquals(DockState.Idle, conferenceDockReduce(state, DockEvent.Terminated(reason)), "$state / $reason")
            }
        }
    }

    @Test
    fun unknownPairs_returnTheOldState() {
        val live = DockState.Live(true, snap)
        assertSame(live, conferenceDockReduce(live, DockEvent.Dismissed))
        assertSame(live, conferenceDockReduce(live, DockEvent.JoinRequested))
        val idle = DockState.Idle
        assertSame(idle, conferenceDockReduce(idle, DockEvent.Connected(snap)))
        assertSame(idle, conferenceDockReduce(idle, DockEvent.DisconnectResolving))
        assertSame(idle, conferenceDockReduce(idle, DockEvent.ReEntered))
    }

    @Test
    fun theDeviceWish_isTheLastOneOrBothOn() {
        assertEquals(ConferenceDeviceIntent(mic = true, camera = true), conferenceInitialDevices(null))
        assertEquals(
            ConferenceDeviceIntent(mic = false, camera = false),
            conferenceInitialDevices(ConferenceDeviceIntent(mic = false, camera = false)),
        )
        assertEquals(
            ConferenceDeviceIntent(mic = true, camera = false),
            conferenceInitialDevices(ConferenceDeviceIntent(mic = true, camera = false)),
        )
    }

    @Test
    fun attachedAndSnapshotAccessors() {
        assertTrue(DockState.Idle.isAttached)
        assertTrue(DockState.Live(true, snap).isAttached)
        assertEquals(false, DockState.Stopped(DockStopReason.ENDED, false).isAttached)
        assertEquals(snap, DockState.Resolving(false, snap).snapshotOrNull)
        assertEquals(null, DockState.Joining(true).snapshotOrNull)
    }
}
