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
 * [connected_disconnectedSignal_reachesResolving] and [reconnecting_disconnectedSignal_reachesResolving]
 * below -- both the clean case and the mid-reconnect case. Wave 4's own two tests here were named
 * `..._reachesEnded` and asserted `Ended` directly; Wave 6 "Breakout-Räume" retargets
 * `DisconnectedSignal` from `Connected`/`Reconnecting` to the new [ConferenceConnectionState.Resolving]
 * state instead (see that state's own KDoc) -- renamed and re-asserted below, this is an intentional
 * behavior change, not a regression. [Ended] is still always eventually reachable, now via the new
 * [ConferenceConnectionEvent.ResolvedAsEnded] event -- see the new "Resolving" section below.
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
    fun connected_disconnectedSignal_reachesResolving() {
        // Security-relevant, Wave 6 "Breakout-Räume": the clean "something disconnected me while I
        // was fully connected" case -- there must be no reachable state where the UI still shows
        // "connected" afterward. CHANGED from Wave 4's own direct -> Ended assertion: the raw
        // RoomEvent.Disconnected is ambiguous now (kick/room-end vs. a breakout assignment/recall),
        // so it lands in Resolving first, never silently in "still connected".
        val result = conferenceConnectionReduce(ConferenceConnectionState.Connected, ConferenceConnectionEvent.DisconnectedSignal)
        assertSame(ConferenceConnectionState.Resolving, result)
    }

    @Test
    fun connected_userLeft_stillMovesToEnded_bypassesResolving() {
        // Wave 6 regression guard: a LOCAL "Verlassen"/"Für alle beenden"/"Zurück zum Hauptraum"
        // click is a deliberate, client-initiated transition -- it must keep going straight to Ended,
        // never through the new Resolving state (that state exists only for the AMBIGUOUS raw
        // RoomEvent.Disconnected signal, see that state's own KDoc).
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
    fun reconnecting_disconnectedSignal_reachesResolving() {
        // Security-relevant, Wave 6: the mid-reconnect case -- a disconnect that arrives WHILE the
        // client was actively retrying its connection must still be resolved, not silently get lost
        // because the state machine was in a transitional state at the time. CHANGED from Wave 4's
        // own direct -> Ended assertion, same reasoning as connected_disconnectedSignal_reachesResolving.
        val result = conferenceConnectionReduce(ConferenceConnectionState.Reconnecting, ConferenceConnectionEvent.DisconnectedSignal)
        assertSame(ConferenceConnectionState.Resolving, result)
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
    // Resolving -- V1.0 Videokonferenzen, Wave 6 "Breakout-Räume"
    // ---------------------------------------------------------------------------------------

    @Test
    fun resolving_resolvedAsEnded_movesToEnded() {
        // The ONE way out of Resolving that this reducer itself knows about -- see
        // ConferenceConnectionState.Resolving KDoc. The OTHER way out ("hand off to a brand-new
        // enterCall for the resolved breakout/main-room destination") happens entirely outside this
        // pure reducer, in `onDisconnected`'s own async resolution -- not testable at this level.
        val result = conferenceConnectionReduce(ConferenceConnectionState.Resolving, ConferenceConnectionEvent.ResolvedAsEnded)
        assertSame(ConferenceConnectionState.Ended, result)
    }

    @Test
    fun resolving_anyOtherEvent_isIgnored_stateUnchanged() {
        // A stray/late signal (e.g. a delayed ReconnectingSignal from the OLD LiveKit Room object,
        // arriving after this screen already asked the server what the disconnect meant) must not
        // do anything surprising -- same "unlisted pairs are ignored" discipline every other state
        // in this reducer already follows.
        val otherEvents =
            listOf(
                ConferenceConnectionEvent.ConnectRequested,
                ConferenceConnectionEvent.ConnectSucceeded,
                ConferenceConnectionEvent.ConnectFailed("late failure"),
                ConferenceConnectionEvent.ReconnectingSignal,
                ConferenceConnectionEvent.ReconnectedSignal,
                ConferenceConnectionEvent.DisconnectedSignal,
                ConferenceConnectionEvent.UserLeft,
            )
        otherEvents.forEach { event ->
            val result = conferenceConnectionReduce(ConferenceConnectionState.Resolving, event)
            assertSame(ConferenceConnectionState.Resolving, result)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ended -- terminal, idempotent, never throws
    // ---------------------------------------------------------------------------------------

    @Test
    fun ended_anyEvent_staysEnded_neverThrows() {
        // Proves a duplicate LATE DisconnectedSignal (or any other event, including the new Wave 6
        // ResolvedAsEnded) arriving after the user already clicked "Verlassen" cannot do anything
        // surprising -- terminal and idempotent.
        val allEvents =
            listOf(
                ConferenceConnectionEvent.ConnectRequested,
                ConferenceConnectionEvent.ConnectSucceeded,
                ConferenceConnectionEvent.ConnectFailed("late failure"),
                ConferenceConnectionEvent.ReconnectingSignal,
                ConferenceConnectionEvent.ReconnectedSignal,
                ConferenceConnectionEvent.DisconnectedSignal,
                ConferenceConnectionEvent.UserLeft,
                ConferenceConnectionEvent.ResolvedAsEnded,
            )
        allEvents.forEach { event ->
            val result = conferenceConnectionReduce(ConferenceConnectionState.Ended, event)
            assertSame(ConferenceConnectionState.Ended, result)
        }
    }

    // ---------------------------------------------------------------------------------------
    // isLive() -- V1.0 Videokonferenzen, Wave 6 -- the required, minimal-diff fix for
    // pollInFlightRecordingStatus/pollInFlightStreamStatus/sweepGridReflow's own loop guards, which
    // pre-Wave-6 read `connectionState !is Ended` -- see that extension function's own KDoc.
    // ---------------------------------------------------------------------------------------

    @Test
    fun isLive_trueOnlyForConnectedAndReconnecting() {
        val liveStates = setOf(ConferenceConnectionState.Connected, ConferenceConnectionState.Reconnecting)
        val allStates =
            listOf(
                ConferenceConnectionState.Disconnected,
                ConferenceConnectionState.Connecting,
                ConferenceConnectionState.Connected,
                ConferenceConnectionState.Reconnecting,
                ConferenceConnectionState.Failed("reason"),
                ConferenceConnectionState.Resolving,
                ConferenceConnectionState.Ended,
            )
        allStates.forEach { state ->
            assertEquals(state in liveStates, state.isLive(), "isLive() mismatch for $state")
        }
    }
}
