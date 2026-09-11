package network.lapis.cloud.client.livekit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Matches [Room.on]/[Room.off]'s own listener shape -- named here purely so lambda literals
 * assigned to a `dynamic`'s `.on`/`.off` property below don't need to spell out a bare function
 * type (`(dynamic, dynamic, dynamic, dynamic) -> Unit`) directly in a lambda parameter position,
 * which reads ambiguously next to that lambda's own trailing `->`. */
private typealias RoomListener = (dynamic, dynamic, dynamic, dynamic) -> Unit

private fun resolvedUnit(): Promise<Unit> = Promise { resolve, _ -> resolve(Unit) }

/**
 * Audit finding "Testabdeckung" (relay-fallback wave, 2026-09-10/11) -- [ConferenceConnectFallbackTest]
 * only ever covered the two pure helpers [conferenceShouldRetryOverRelay]/`conferenceConnectErrorMessage`;
 * the actual retry ORCHESTRATION in [LiveKitRoomSession.connect]/[LiveKitRoomSession.attemptConnect] --
 * the success/failure mapping, the [ConnectAttemptResult] sentinel fix, the "the relay attempt's own
 * result must be re-classified, not blanket-mapped to RELAY_EXHAUSTED" fix, the `onRelayFallback`
 * exactly-once contract, and the `disconnect()`-during-retry race fix -- had zero automated coverage.
 * [roomFactory] (the constructor's new test seam, see its own KDoc) is what makes this file possible:
 * every test below injects a [FakeRoom]-shaped duck-typed object instead of a real `livekit-client`
 * `Room`, so this stays a DOM-free/WebRTC-free `jsTest`, same posture as every other test in this
 * package.
 */
class LiveKitRoomSessionConnectRetryTest {
    // ── Fake Room plumbing ───────────────────────────────────────────────────────────────────

    /** A [Room]-shaped duck-typed JS object -- `Room` is an `external class`, so [LiveKitRoomSession]'s
     * calls against it (`.connect(...)`, `.disconnect(...)`, `.on(...)`, `.localParticipant`,
     * `.remoteParticipants`, `.isRecording`) compile to plain JS property/method access regardless of
     * the object's real runtime type, exactly like every other `dynamic`/`unsafeCast` boundary this
     * codebase already crosses (see `LiveKitJs.kt` file KDoc). [disconnectCount] lets a test assert a
     * fake room was (or was not) torn down without needing to intercept the call another way. */
    private class FakeRoom(
        private val onConnect: () -> Promise<Unit>,
        private val onDisconnect: () -> Promise<Unit> = { resolvedUnit() },
    ) {
        var disconnectCount = 0
            private set

        fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = js("([])") // empty JS array -- seedRoster's forEach is a no-op
            val localParticipant: dynamic = js("({})")
            localParticipant.identity = "local-participant"
            localParticipant.name = "Local"
            fake.localParticipant = localParticipant
            fake.connect = { _: String, _: String -> onConnect() }
            fake.disconnect = { _: dynamic ->
                disconnectCount++
                onDisconnect()
            }
            fake.on = { _: String, _: RoomListener -> fake }
            fake.off = { _: String, _: RoomListener -> fake }
            return fake.unsafeCast<Room>()
        }
    }

    /** Builds a rejected `room.connect(...)` `Promise`, carrying [reason] as `ConnectionError.reason`
     * exactly like a real `livekit-client` rejection would -- see [connectionErrorReasonOf]. A `null`
     * [reason] simulates a rejection whose `.reason` is unreadable (an `UnsupportedServer`/
     * `NegotiationError`/raw `TypeError` -- see [ConnectAttemptResult] KDoc). */
    private fun rejectedConnect(reason: Int?): Promise<Unit> =
        Promise { _, reject ->
            val error = Exception("fake connect failure")
            if (reason != null) {
                error.asDynamic().reason = reason
            }
            reject(error)
        }

    private class SessionHarness {
        var relayFallbackCount = 0
            private set
        var disconnectedCount = 0
            private set
        val joined = mutableListOf<String>()

        fun build(roomFactory: (RoomOptions) -> Room): LiveKitRoomSession =
            LiveKitRoomSession(
                onRemoteTrack = { _, _, _, _ -> },
                onRemoteTrackGone = { _, _, _ -> },
                onParticipantJoined = { identity, _ -> joined += identity },
                onParticipantLeft = { _ -> },
                onLocalVideoTrack = { _ -> },
                onLocalTrackMuteChanged = { _, _ -> },
                onRecordingStatusChanged = { _ -> },
                onActiveSpeakersChanged = { _ -> },
                onActiveDeviceChanged = { _, _ -> },
                onMediaDevicesChanged = {},
                onMediaDevicesError = { _, _ -> },
                onChat = { _ -> },
                onWhiteboardPreview = { _, _, _ -> },
                onWhiteboardCommit = { _, _, _ -> },
                onNotesCommit = { _, _, _ -> },
                onReconnecting = {},
                onReconnected = {},
                onDisconnected = { disconnectedCount++ },
                onRelayFallback = { relayFallbackCount++ },
                roomFactory = roomFactory,
            )
    }

    // ── Happy path -- no retry needed ────────────────────────────────────────────────────────

    @Test
    fun connect_firstAttemptSucceeds_returnsNull_noRelayFallback() =
        GlobalScope.promise {
            var factoryCallCount = 0
            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    FakeRoom(onConnect = { resolvedUnit() }).asRoom()
                }

            val result = session.connect("wss://example.invalid", "token")

            assertNull(result)
            assertEquals(1, factoryCallCount)
            assertEquals(0, harness.relayFallbackCount)
        }

    // ── Retry path -- first attempt fails with a retryable reason ───────────────────────────

    @Test
    fun connect_firstAttemptRetryable_relaySucceeds_returnsNull_relayFallbackFiresOnce() =
        GlobalScope.promise {
            var factoryCallCount = 0
            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    if (factoryCallCount == 1) {
                        // ConnectionErrorReason.InternalError -- the ICE-never-established case.
                        FakeRoom(onConnect = { rejectedConnect(reason = 2) }).asRoom()
                    } else {
                        FakeRoom(onConnect = { resolvedUnit() }).asRoom()
                    }
                }

            val result = session.connect("wss://example.invalid", "token")

            assertNull(result)
            assertEquals(2, factoryCallCount)
            assertEquals(1, harness.relayFallbackCount)
        }

    @Test
    fun connect_firstAttemptUnreadableReason_isRetried_relaySucceeds() =
        GlobalScope.promise {
            // Regression guard for the exact "Sentinel-Kollision" finding: a Failed(null) attempt (an
            // unreadable rejection) must still reach the retry branch, not be misread as Success.
            var factoryCallCount = 0
            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    if (factoryCallCount == 1) {
                        FakeRoom(onConnect = { rejectedConnect(reason = null) }).asRoom()
                    } else {
                        FakeRoom(onConnect = { resolvedUnit() }).asRoom()
                    }
                }

            val result = session.connect("wss://example.invalid", "token")

            assertNull(result)
            assertEquals(2, factoryCallCount)
            assertEquals(1, harness.relayFallbackCount)
        }

    @Test
    fun connect_firstAttemptRetryable_relayAlsoFailsUnreadable_returnsRelayExhausted() =
        GlobalScope.promise {
            val harness = SessionHarness()
            var factoryCallCount = 0
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    FakeRoom(onConnect = { rejectedConnect(reason = if (factoryCallCount == 1) 2 else null) }).asRoom()
                }

            val result = session.connect("wss://example.invalid", "token")

            assertEquals(ConferenceConnectFailure.RELAY_EXHAUSTED, result)
            assertEquals(1, harness.relayFallbackCount)
        }

    @Test
    fun connect_firstAttemptNotRetryable_returnsOther_noRetryAttempted() =
        GlobalScope.promise {
            var factoryCallCount = 0
            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    // ConnectionErrorReason.NotAllowed -- a bad/expired token, relay cannot help.
                    FakeRoom(onConnect = { rejectedConnect(reason = 0) }).asRoom()
                }

            val result = session.connect("wss://example.invalid", "token")

            assertEquals(ConferenceConnectFailure.OTHER, result)
            assertEquals(1, factoryCallCount)
            assertEquals(0, harness.relayFallbackCount)
        }

    // ── MAJOR finding regression guard: relay-attempt result must be re-classified ──────────

    @Test
    fun connect_relayAttemptFailsWithCancelled_returnsOther_notRelayExhausted() =
        GlobalScope.promise {
            // Audit finding: leaveButton is not gated on connection state, so a participant can hit
            // "Verlassen" while the up-to-15s relay retry is still in flight; Room.disconnect()
            // rejects the in-flight connect() with ConnectionError.reason = 3 (Cancelled). That must
            // surface as the ordinary OTHER outcome, never the alarming RELAY_EXHAUSTED toast.
            var factoryCallCount = 0
            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    if (factoryCallCount == 1) {
                        FakeRoom(onConnect = { rejectedConnect(reason = 2) }).asRoom()
                    } else {
                        FakeRoom(onConnect = { rejectedConnect(reason = 3) }).asRoom()
                    }
                }

            val result = session.connect("wss://example.invalid", "token")

            assertEquals(ConferenceConnectFailure.OTHER, result)
        }

    @Test
    fun connect_relayAttemptFailsWithLeaveRequest_returnsOther_notRelayExhausted() =
        GlobalScope.promise {
            var factoryCallCount = 0
            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    if (factoryCallCount == 1) {
                        FakeRoom(onConnect = { rejectedConnect(reason = 2) }).asRoom()
                    } else {
                        FakeRoom(onConnect = { rejectedConnect(reason = 4) }).asRoom()
                    }
                }

            val result = session.connect("wss://example.invalid", "token")

            assertEquals(ConferenceConnectFailure.OTHER, result)
        }

    // ── MINOR finding regression guard: a post-connect seeding failure must not be
    // ── misclassified as a connect() failure and trigger a relay retry ──────────────────────

    @Test
    fun connect_seedingThrowsAfterSuccessfulConnect_stillReturnsNull_noRelayFallback() =
        GlobalScope.promise {
            // Audit finding: `onRecordingStatusChanged`/`seedRoster` used to run INSIDE the same
            // try/catch that classifies `room.connect(...)`'s own rejection -- a throw from either
            // one (here: the `onRecordingStatusChanged` consumer callback) was indistinguishable from
            // a genuine connect failure, `connectionErrorReasonOf` cannot read a `.reason` off it
            // (always `null`), and `conferenceShouldRetryOverRelay(null)` is `true` by design -- so an
            // ALREADY-CONNECTED room was torn down and a full forced-relay retry launched for a call
            // that never actually failed to connect. This must no longer happen: the connection is
            // real, so `connect()` must return `null` (success), the room must be left connected (no
            // `disconnectCount`), and no relay fallback must fire.
            var factoryCallCount = 0
            var fakeRoom: FakeRoom? = null
            val harness =
                object {
                    var relayFallbackCount = 0
                }
            val session =
                LiveKitRoomSession(
                    onRemoteTrack = { _, _, _, _ -> },
                    onRemoteTrackGone = { _, _, _ -> },
                    onParticipantJoined = { _, _ -> },
                    onParticipantLeft = { _ -> },
                    onLocalVideoTrack = { _ -> },
                    onLocalTrackMuteChanged = { _, _ -> },
                    onRecordingStatusChanged = { throw IllegalStateException("boom -- consumer callback throws") },
                    onActiveSpeakersChanged = { _ -> },
                    onActiveDeviceChanged = { _, _ -> },
                    onMediaDevicesChanged = {},
                    onMediaDevicesError = { _, _ -> },
                    onChat = { _ -> },
                    onWhiteboardPreview = { _, _, _ -> },
                    onWhiteboardCommit = { _, _, _ -> },
                    onNotesCommit = { _, _, _ -> },
                    onReconnecting = {},
                    onReconnected = {},
                    onDisconnected = {},
                    onRelayFallback = { harness.relayFallbackCount++ },
                    roomFactory = { _ ->
                        factoryCallCount++
                        val room = FakeRoom(onConnect = { resolvedUnit() })
                        fakeRoom = room
                        room.asRoom()
                    },
                )

            val result = session.connect("wss://example.invalid", "token")

            assertNull(result)
            assertEquals(1, factoryCallCount)
            assertEquals(0, harness.relayFallbackCount)
            assertEquals(0, fakeRoom?.disconnectCount ?: -1)
        }

    // ── Orphan guard: a late event from the abandoned first Room must be ignored ────────────

    @Test
    fun connect_lateParticipantJoinedFromAbandonedFirstRoom_isIgnored() =
        GlobalScope.promise {
            var factoryCallCount = 0
            var firstRoomJoinedListener: RoomListener? = null
            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    if (factoryCallCount == 1) {
                        val fake: dynamic = js("({})")
                        fake.isRecording = false
                        fake.remoteParticipants = js("([])")
                        val localParticipant: dynamic = js("({})")
                        localParticipant.identity = "local-participant"
                        fake.localParticipant = localParticipant
                        fake.connect = { _: String, _: String -> rejectedConnect(reason = 2) }
                        fake.disconnect = { _: dynamic -> resolvedUnit() }
                        fake.on = { event: String, listener: RoomListener ->
                            if (event == RoomEvent.ParticipantConnected) firstRoomJoinedListener = listener
                            fake
                        }
                        fake.off = { _: String, _: RoomListener -> fake }
                        fake.unsafeCast<Room>()
                    } else {
                        FakeRoom(onConnect = { resolvedUnit() }).asRoom()
                    }
                }

            val result = session.connect("wss://example.invalid", "token")
            assertNull(result)

            // Simulate the abandoned first Room's underlying network stack finally delivering an
            // event after the retry has already taken over -- see connect KDoc "Orphaned first Room".
            val participant: dynamic = js("({})")
            participant.identity = "late-ghost-participant"
            firstRoomJoinedListener?.invoke(participant, undefined, undefined, undefined)

            assertFalse(harness.joined.contains("late-ghost-participant"))
        }

    // ── MINOR finding regression guard: disconnect() during the relay-retry teardown window ─

    @Test
    fun disconnect_duringRelayRetryTeardownWindow_tearsDownRelayRoomAfterwards_notSilentNoOp() =
        GlobalScope.promise {
            val firstDisconnectStarted = CompletableDeferred<Unit>()
            val firstDisconnectGate = CompletableDeferred<Unit>()
            var factoryCallCount = 0
            var secondFakeRoom: FakeRoom? = null

            val harness = SessionHarness()
            val session =
                harness.build { _ ->
                    factoryCallCount++
                    if (factoryCallCount == 1) {
                        FakeRoom(
                            onConnect = { rejectedConnect(reason = 2) },
                            onDisconnect = {
                                firstDisconnectStarted.complete(Unit)
                                Promise { resolve, _ ->
                                    GlobalScope.launch {
                                        firstDisconnectGate.await()
                                        resolve(Unit)
                                    }
                                }
                            },
                        ).asRoom()
                    } else {
                        val room = FakeRoom(onConnect = { resolvedUnit() })
                        secondFakeRoom = room // side channel the test reads below
                        room.asRoom()
                    }
                }

            val connectDeferred = GlobalScope.async { session.connect("wss://example.invalid", "token") }

            // Wait until connect() is inside the "room == null" teardown window (see connectInFlight
            // KDoc) before calling disconnect() -- this is exactly the window the race-condition
            // finding describes.
            firstDisconnectStarted.await()
            session.disconnect() // must NOT silently do nothing
            firstDisconnectGate.complete(Unit) // let the teardown (and then the relay retry) proceed

            val result = connectDeferred.await()
            val secondRoomDisconnectCount = secondFakeRoom?.disconnectCount ?: -1

            assertEquals(ConferenceConnectFailure.OTHER, result)
            assertTrue(
                secondRoomDisconnectCount >= 1,
                "the Room established by the relay retry must be torn down because disconnect() " +
                    "was requested while connect() was still in flight -- was $secondRoomDisconnectCount",
            )
        }
}
