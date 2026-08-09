package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 4 "Politur", D10 -- exhaustive per-state transition
 * coverage for [conferenceConnectionReduce], the pure reducer behind `ConferenceScreen.kt`'s
 * `enterCall#transition` (the client-side named connection-state machine that replaces the Wave 1-3
 * ad-hoc `leftCall` boolean entirely). Same DOM-free unit-test posture as [ConferenceScreenTest]/
 * [ConferenceGridLayoutTest] -- `enterCall#renderConnectionState`'s own DOM-facing rendering is out
 * of scope here.
 *
 * Security-relevant scenarios called out explicitly by this wave's own task framing (kicked/forcibly-
 * terminated session must never leave the UI showing "connected"): see
 * [connected_disconnectedSignal_reachesEnded] and [reconnecting_disconnectedSignal_reachesEnded]
 * below -- both the clean case and the mid-reconnect case.
 */
class ConferenceConnectionStateTest {
    // ---------------------------------------------------------------------------------------
    // Disconnected -- initial state
    // ---------------------------------------------------------------------------------------

    @Test
    fun disconnected_connectRequested_movesToConnecting() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Disconnected, ConferenceConnectionEvent.ConnectRequested)
        assertSame(ConferenceConnectionState.Connecting, result)
    }

    @Test
    fun disconnected_reconnectingSignal_isIgnored_stateUnchanged() {
        // Documents that illegal pairs are silently ignored, never thrown -- see
        // conferenceConnectionReduce's own KDoc.
        val result = conferenceConnectionReduce(ConferenceConnectionState.Disconnected, ConferenceConnectionEvent.ReconnectingSignal)
        assertSame(ConferenceConnectionState.Disconnected, result)
    }

    @Test
    fun disconnected_disconnectedSignal_isIgnored_stateUnchanged() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Disconnected, ConferenceConnectionEvent.DisconnectedSignal)
        assertSame(ConferenceConnectionState.Disconnected, result)
    }

    // ---------------------------------------------------------------------------------------
    // Connecting
    // ---------------------------------------------------------------------------------------

    @Test
    fun connecting_connectSucceeded_movesToConnected() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connecting, ConferenceConnectionEvent.ConnectSucceeded)
        assertSame(ConferenceConnectionState.Connected, result)
    }

    @Test
    fun connecting_connectFailed_movesToFailedWithReason() {
        val result =
            conferenceConnectionReduce(ConferenceConnectionState.Connecting, ConferenceConnectionEvent.ConnectFailed("connect failed"))
        assertEquals(ConferenceConnectionState.Failed("connect failed"), result)
    }

    @Test
    fun connecting_userLeft_movesToEnded() {
        // A cancel-mid-connect path (e.g. the user closes the tab while the join is still in flight).
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connecting, ConferenceConnectionEvent.UserLeft)
        assertSame(ConferenceConnectionState.Ended, result)
    }

    @Test
    fun connecting_reconnectingSignal_isIgnored_stateUnchanged() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connecting, ConferenceConnectionEvent.ReconnectingSignal)
        assertSame(ConferenceConnectionState.Connecting, result)
    }

    // ---------------------------------------------------------------------------------------
    // Connected
    // ---------------------------------------------------------------------------------------

    @Test
    fun connected_reconnectingSignal_movesToReconnecting() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connected, ConferenceConnectionEvent.ReconnectingSignal)
        assertSame(ConferenceConnectionState.Reconnecting, result)
    }

    @Test
    fun connected_disconnectedSignal_reachesEnded() {
        // Security-relevant: the clean "server closed the room while I was fully connected" case --
        // there must be no reachable state where the UI still shows "connected" afterward.
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connected, ConferenceConnectionEvent.DisconnectedSignal)
        assertSame(ConferenceConnectionState.Ended, result)
    }

    @Test
    fun connected_userLeft_movesToEnded() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connected, ConferenceConnectionEvent.UserLeft)
        assertSame(ConferenceConnectionState.Ended, result)
    }

    @Test
    fun connected_connectRequested_isIgnored_stateUnchanged() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connected, ConferenceConnectionEvent.ConnectRequested)
        assertSame(ConferenceConnectionState.Connected, result)
    }

    // ---------------------------------------------------------------------------------------
    // Reconnecting
    // ---------------------------------------------------------------------------------------

    @Test
    fun reconnecting_reconnectedSignal_movesBackToConnected() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Reconnecting, ConferenceConnectionEvent.ReconnectedSignal)
        assertSame(ConferenceConnectionState.Connected, result)
    }

    @Test
    fun reconnecting_disconnectedSignal_reachesEnded() {
        // Security-relevant: the mid-reconnect case -- a kick/room-end that arrives WHILE the client
        // was actively retrying its connection must still reach Ended, not silently get lost because
        // the state machine was in a transitional state at the time.
        val result = conferenceConnectionReduce(ConferenceConnectionState.Reconnecting, ConferenceConnectionEvent.DisconnectedSignal)
        assertSame(ConferenceConnectionState.Ended, result)
    }

    @Test
    fun reconnecting_userLeft_movesToEnded() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Reconnecting, ConferenceConnectionEvent.UserLeft)
        assertSame(ConferenceConnectionState.Ended, result)
    }

    @Test
    fun reconnecting_connectSucceeded_isIgnored_stateUnchanged() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Reconnecting, ConferenceConnectionEvent.ConnectSucceeded)
        assertSame(ConferenceConnectionState.Reconnecting, result)
    }

    // ---------------------------------------------------------------------------------------
    // Failed -- no retry transition (matches the pre-existing boolean-based code, not a regression)
    // ---------------------------------------------------------------------------------------

    @Test
    fun failed_userLeft_movesToEnded() {
        val result = conferenceConnectionReduce(ConferenceConnectionState.Failed("connect failed"), ConferenceConnectionEvent.UserLeft)
        assertSame(ConferenceConnectionState.Ended, result)
    }

    @Test
    fun failed_connectRequested_isIgnored_stateUnchanged() {
        // No automatic retry -- matches pre-existing boolean-based behaviour, not a regression.
        val failed = ConferenceConnectionState.Failed("connect failed")
        val result = conferenceConnectionReduce(failed, ConferenceConnectionEvent.ConnectRequested)
        assertEquals(failed, result)
    }

    @Test
    fun failed_disconnectedSignal_isIgnored_stateUnchanged() {
        val failed = ConferenceConnectionState.Failed("connect failed")
        val result = conferenceConnectionReduce(failed, ConferenceConnectionEvent.DisconnectedSignal)
        assertEquals(failed, result)
    }

    // ---------------------------------------------------------------------------------------
    // Ended -- terminal, idempotent, never throws
    // ---------------------------------------------------------------------------------------

    @Test
    fun ended_anyEvent_staysEnded_neverThrows() {
        // Proves a duplicate LATE DisconnectedSignal (or any other event) arriving after the user
        // already clicked "Verlassen" cannot do anything surprising -- terminal and idempotent.
        val allEvents =
            listOf(
                ConferenceConnectionEvent.ConnectRequested,
                ConferenceConnectionEvent.ConnectSucceeded,
                ConferenceConnectionEvent.ConnectFailed("late failure"),
                ConferenceConnectionEvent.ReconnectingSignal,
                ConferenceConnectionEvent.ReconnectedSignal,
                ConferenceConnectionEvent.DisconnectedSignal,
                ConferenceConnectionEvent.UserLeft,
            )
        allEvents.forEach { event ->
            val result = conferenceConnectionReduce(ConferenceConnectionState.Ended, event)
            assertSame(ConferenceConnectionState.Ended, result)
        }
    }
}
