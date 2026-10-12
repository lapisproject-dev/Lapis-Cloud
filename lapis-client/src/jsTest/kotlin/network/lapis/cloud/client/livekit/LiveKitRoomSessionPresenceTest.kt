package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import network.lapis.cloud.client.PresenceEvent
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.120 -- the presence callbacks of [LiveKitRoomSession] against a duck-typed fake `Room` that keeps its listeners: the first roster and a
 * resync fire NOTHING, a real join/leave carries the counts (local included, recorders excluded), the session is not armed before the first
 * roster, silent between Reconnecting and Reconnected plus the grace window, and disarmed again by a disconnect.
 */
class LiveKitRoomSessionPresenceTest {
    private class Fake(
        val remote: dynamic,
    ) {
        val listeners = mutableMapOf<String, (dynamic, dynamic, dynamic, dynamic) -> Unit>()

        fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = remote
            val local: dynamic = js("({})")
            local.identity = "local"
            fake.localParticipant = local
            fake.connect = { _: String, _: String -> Promise.resolve(Unit) }
            fake.disconnect = { _: dynamic -> Promise.resolve(Unit) }
            fake.on = { event: String, l: (dynamic, dynamic, dynamic, dynamic) -> Unit ->
                listeners[event] = l
                fake
            }
            fake.off = { _: String, _: dynamic -> fake }
            return fake.unsafeCast<Room>()
        }

        fun emit(
            event: String,
            participant: dynamic = null,
        ) = listeners.getValue(event)(participant, null, null, null)
    }

    private fun person(
        identity: String,
        kind: dynamic = undefined,
    ): dynamic {
        val p: dynamic = js("({})")
        p.identity = identity
        p.name = identity
        p.kind = kind
        p.trackPublications = js("new Map()")
        return p
    }

    private fun roster(vararg people: dynamic): dynamic {
        val map: dynamic = js("new Map()")
        people.forEach { map.set(it.identity, it) }
        return map
    }

    private class Harness(
        val fake: Fake,
        val now: () -> Double,
    ) {
        val joins = mutableListOf<PresenceEvent>()
        val leaves = mutableListOf<PresenceEvent>()
        val seeded = mutableListOf<String>()
        val session =
            LiveKitRoomSession(
                onRemoteTrack = { _, _, _, _ -> },
                onRemoteTrackGone = { _, _, _ -> },
                onParticipantJoined = { identity, _ -> seeded += identity },
                onParticipantLeft = { },
                onLocalVideoTrack = { },
                onLocalTrackMuteChanged = { _, _ -> },
                onRecordingStatusChanged = { },
                onActiveSpeakersChanged = { },
                onActiveDeviceChanged = { _, _ -> },
                onMediaDevicesChanged = { },
                onMediaDevicesError = { _, _ -> },
                onChat = { },
                onWhiteboardPreview = { _, _, _ -> },
                onWhiteboardCommit = { _, _, _ -> },
                onNotesCommit = { _, _, _ -> },
                onReconnecting = { },
                onReconnected = { },
                onDisconnected = { _ -> },
                roomFactory = { fake.asRoom() },
                onPresenceJoin = { joins += it },
                onPresenceLeave = { leaves += it },
                presenceClock = now,
            )
    }

    private var clock = 50_000.0

    private fun harness(vararg initial: dynamic) = Harness(Fake(roster(*initial)), now = { clock })

    @Test
    fun theFirstRoster_andAResync_fireNoPresenceEvent() =
        GlobalScope.promise {
            val h = harness(person("a"), person("b"))
            assertNull(h.session.connect("wss://example.invalid", "token"))
            assertEquals(listOf("a", "b"), h.seeded, "the roster itself is still announced to the tiles")
            h.session.resyncRoster()
            assertTrue(h.joins.isEmpty())
            assertTrue(h.leaves.isEmpty())
        }

    @Test
    fun aRealJoin_carriesTheCountsIncludingTheLocalOne_andIsArmed() =
        GlobalScope.promise {
            val h = harness(person("a"), person("b"))
            h.session.connect("wss://example.invalid", "token")
            val c = person("c")
            h.fake.remote.set("c", c)
            h.fake.emit(RoomEvent.ParticipantConnected, c)
            val event = h.joins.single()
            assertEquals("c", event.identity)
            assertEquals(3, event.countBefore, "a, b and me")
            assertEquals(4, event.countAfter)
            assertTrue(event.armed)
            assertFalse(event.reconnectingLocally)
        }

    @Test
    fun aRealLeave_carriesTheCounts_whetherOrNotTheSdkAlreadyDroppedTheParticipant() =
        GlobalScope.promise {
            val a = person("a")
            val h = harness(a, person("b"))
            h.session.connect("wss://example.invalid", "token")
            h.fake.emit(RoomEvent.ParticipantDisconnected, a) // still in the map
            h.fake.remote.delete("a")
            h.fake.emit(RoomEvent.ParticipantDisconnected, a) // already gone
            assertEquals(2, h.leaves.size)
            h.leaves.forEach {
                assertEquals(3, it.countBefore)
                assertEquals(2, it.countAfter)
            }
        }

    @Test
    fun aRecorder_isNeitherReportedNorCounted() =
        GlobalScope.promise {
            val h = harness(person("a"), person("EG_xyz"), person("rec", kind = 2))
            h.session.connect("wss://example.invalid", "token")
            val recorder = person("EG_second")
            h.fake.remote.set("EG_second", recorder)
            h.fake.emit(RoomEvent.ParticipantConnected, recorder)
            assertTrue(h.joins.isEmpty(), "a recorder joining makes no sound")
            val b = person("b")
            h.fake.remote.set("b", b)
            h.fake.emit(RoomEvent.ParticipantConnected, b)
            val event = h.joins.single()
            assertEquals(2, event.countBefore, "only a and me")
            assertEquals(3, event.countAfter)
        }

    @Test
    fun afterADisconnect_theSessionIsNotArmed() =
        GlobalScope.promise {
            val h = harness(person("a"))
            h.session.connect("wss://example.invalid", "token")
            h.fake.emit(RoomEvent.Disconnected)
            val b = person("b")
            h.fake.remote.set("b", b)
            h.fake.emit(RoomEvent.ParticipantConnected, b)
            assertFalse(h.joins.single().armed, "after a disconnect nothing may sound")
        }

    @Test
    fun betweenReconnectingAndReconnected_plusTheGraceWindow_theEventsAreMarkedAsLocalReconnect() =
        GlobalScope.promise {
            val h = harness(person("a"))
            h.session.connect("wss://example.invalid", "token")
            h.fake.emit(RoomEvent.Reconnecting)
            val b = person("b")
            h.fake.remote.set("b", b)
            h.fake.emit(RoomEvent.ParticipantConnected, b)
            assertTrue(h.joins.last().reconnectingLocally)
            h.fake.emit(RoomEvent.Reconnected)
            h.fake.emit(RoomEvent.ParticipantConnected, b)
            assertTrue(h.joins.last().reconnectingLocally, "the SDK may replay events right after Reconnected")
            clock += 2_001.0
            h.fake.emit(RoomEvent.ParticipantConnected, b)
            assertFalse(h.joins.last().reconnectingLocally)
        }

    @Test
    fun aThrowingConsumer_neverReachesTheSdk() =
        GlobalScope.promise {
            val fake = Fake(roster(person("a")))
            var calls = 0
            val s =
                LiveKitRoomSession(
                    onRemoteTrack = { _, _, _, _ -> },
                    onRemoteTrackGone = { _, _, _ -> },
                    onParticipantJoined = { _, _ -> },
                    onParticipantLeft = { },
                    onLocalVideoTrack = { },
                    onLocalTrackMuteChanged = { _, _ -> },
                    onRecordingStatusChanged = { },
                    onActiveSpeakersChanged = { },
                    onActiveDeviceChanged = { _, _ -> },
                    onMediaDevicesChanged = { },
                    onMediaDevicesError = { _, _ -> },
                    onChat = { },
                    onWhiteboardPreview = { _, _, _ -> },
                    onWhiteboardCommit = { _, _, _ -> },
                    onNotesCommit = { _, _, _ -> },
                    onReconnecting = { },
                    onReconnected = { },
                    onDisconnected = { _ -> },
                    roomFactory = { fake.asRoom() },
                    onPresenceJoin = {
                        calls++
                        error("consumer bug")
                    },
                )
            s.connect("wss://example.invalid", "token")
            val b = person("b")
            fake.remote.set("b", b)
            fake.emit(RoomEvent.ParticipantConnected, b)
            assertEquals(1, calls)
        }
}
