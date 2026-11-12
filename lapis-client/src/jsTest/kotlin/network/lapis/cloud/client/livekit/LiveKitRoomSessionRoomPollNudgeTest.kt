package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.ROOM_POLL_TOPIC
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias PollRoomListener = (dynamic, dynamic, dynamic, dynamic) -> Unit

private fun pollResolvedUnit(): Promise<Unit> = Promise { resolve, _ -> resolve(Unit) }

/**
 * V1.9.304 -- the `lapis-room-poll` packet: accepted ONLY from the server (no sending participant), its payload is never read, a packet that
 * carries a participant is a forgery and dropped, other topics never trigger it, and nothing fires after [LiveKitRoomSession.disconnect].
 */
class LiveKitRoomSessionRoomPollNudgeTest {
    private class FakeRoom {
        val listeners = mutableMapOf<String, PollRoomListener>()

        fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = js("([])")
            val local: dynamic = js("({})")
            local.identity = "local-participant"
            local.name = "Local"
            local.publishData = { _: Uint8Array, _: dynamic -> pollResolvedUnit() }
            fake.localParticipant = local
            fake.connect = { _: String, _: String -> pollResolvedUnit() }
            fake.disconnect = { _: dynamic -> pollResolvedUnit() }
            fake.on = { event: String, l: PollRoomListener ->
                listeners[event] = l
                fake
            }
            fake.off = { _: String, _: PollRoomListener -> fake }
            return fake.unsafeCast<Room>()
        }

        fun emit(
            payload: Uint8Array,
            topic: String,
            fromParticipant: Boolean,
        ) {
            val participant: dynamic = if (fromParticipant) js("({identity: 'remote-1', name: 'Remote'})") else null
            listeners.getValue(RoomEvent.DataReceived)(payload, participant, null, topic)
        }
    }

    private class Harness {
        val fake = FakeRoom()
        var nudges = 0
        var otherCallbacks = 0
        var clock = 0.0
        val pending = mutableListOf<() -> Unit>()

        val session =
            LiveKitRoomSession(
                onRemoteTrack = { _, _, _, _ -> },
                onRemoteTrackGone = { _, _, _ -> },
                onParticipantJoined = { _, _ -> },
                onParticipantLeft = { _ -> },
                onLocalVideoTrack = { _ -> },
                onLocalTrackMuteChanged = { _, _ -> },
                onRecordingStatusChanged = { _ -> },
                onActiveSpeakersChanged = { _ -> },
                onActiveDeviceChanged = { _, _ -> },
                onMediaDevicesChanged = {},
                onMediaDevicesError = { _, _ -> },
                onChat = { _ -> otherCallbacks++ },
                onWhiteboardPreview = { _, _, _ -> otherCallbacks++ },
                onWhiteboardCommit = { _, _, _ -> otherCallbacks++ },
                onNotesCommit = { _, _, _ -> otherCallbacks++ },
                onReconnecting = {},
                onReconnected = {},
                onDisconnected = { _ -> },
                roomFactory = { _ -> fake.asRoom() },
                onRoomPollNudge = { nudges++ },
                roomPollNudgeThrottleFactory = { onRefresh ->
                    VoteNudgeThrottle(
                        now = { clock },
                        schedule = { _, block ->
                            pending += block
                            pending.size
                        },
                        cancelScheduled = { },
                        onRefresh = onRefresh,
                    )
                },
            )
    }

    private fun bytes(vararg values: Int): Uint8Array {
        val arr = Uint8Array(values.size)
        values.forEachIndexed { i, v -> arr.asDynamic()[i] = v }
        return arr
    }

    @Test
    fun aServerPacket_refreshes_whateverItsPayloadSays() =
        GlobalScope.promise {
            val h = Harness()
            h.session.connect("wss://example.invalid", "token")
            val hostile =
                """{"question":"<img src=x onerror=alert(1)>","poll":"x"}"""
                    .encodeToByteArray()
                    .map { it.toInt() and 0xff }
                    .toIntArray()
            listOf(bytes(*hostile), Uint8Array(64 * 1024), bytes(0xff, 0xfe, 0xc0, 0x80), Uint8Array(0)).forEachIndexed { i, payload ->
                h.clock = (i + 1) * 3_000.0
                h.fake.emit(payload, ROOM_POLL_TOPIC, fromParticipant = false)
            }
            assertEquals(4, h.nudges)
            assertEquals(0, h.otherCallbacks)
        }

    @Test
    fun aPacketFromAParticipant_isAForgery_andDropped() =
        GlobalScope.promise {
            val h = Harness()
            h.session.connect("wss://example.invalid", "token")
            h.fake.emit(bytes(1), ROOM_POLL_TOPIC, fromParticipant = true)
            assertEquals(0, h.nudges)
            assertTrue(roomPollNudgeAccepted(fromParticipant = false))
            assertFalse(roomPollNudgeAccepted(fromParticipant = true))
        }

    @Test
    fun otherTopicsNeverTrigger_andNothingFiresAfterDisconnect() =
        GlobalScope.promise {
            val h = Harness()
            h.session.connect("wss://example.invalid", "token")
            h.fake.emit(bytes(0), "lapis-something-else", fromParticipant = false)
            h.fake.emit(bytes(0), LiveKitRoomSession.CHAT_TOPIC, fromParticipant = true)
            assertEquals(0, h.nudges)
            h.fake.emit(bytes(0), ROOM_POLL_TOPIC, fromParticipant = false)
            assertEquals(1, h.nudges)
            h.session.disconnect()
            h.clock = 10_000.0
            h.fake.emit(bytes(0), ROOM_POLL_TOPIC, fromParticipant = false)
            assertEquals(1, h.nudges)
        }
}
