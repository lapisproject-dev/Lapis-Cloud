package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private typealias NudgeRoomListener = (dynamic, dynamic, dynamic, dynamic) -> Unit

private fun nudgeResolvedUnit(): Promise<Unit> = Promise { resolve, _ -> resolve(Unit) }

/**
 * V1.9.24 -- the `lapis-vote-nudge` transport: a payload is NEVER read (malicious bytes only ever reach
 * [LiveKitRoomSession]'s `onVoteNudge`), other topics never trigger it, nothing fires after
 * [LiveKitRoomSession.disconnect], and [LiveKitRoomSession.sendVoteNudge] publishes one reliable byte.
 */
class LiveKitRoomSessionVoteNudgeTest {
    private class PublishedData(
        val length: Int,
        val topic: String?,
        val reliable: Boolean?,
    )

    private class FakeRoom {
        val listeners = mutableMapOf<String, NudgeRoomListener>()
        val published = mutableListOf<PublishedData>()

        fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = js("([])")
            val local: dynamic = js("({})")
            local.identity = "local-participant"
            local.name = "Local"
            local.publishData = { data: Uint8Array, options: dynamic ->
                published += PublishedData(length = data.length, topic = options.topic as String?, reliable = options.reliable as Boolean?)
                nudgeResolvedUnit()
            }
            fake.localParticipant = local
            fake.connect = { _: String, _: String -> nudgeResolvedUnit() }
            fake.disconnect = { _: dynamic -> nudgeResolvedUnit() }
            fake.on = { event: String, l: NudgeRoomListener ->
                listeners[event] = l
                fake
            }
            fake.off = { _: String, _: NudgeRoomListener -> fake }
            return fake.unsafeCast<Room>()
        }

        fun emitData(
            payload: Uint8Array,
            topic: String,
        ) {
            val participant: dynamic = js("({})")
            participant.identity = "remote-1"
            participant.name = "Remote"
            listeners.getValue(RoomEvent.DataReceived)(payload, participant, null, topic)
        }
    }

    private class Harness {
        val fake = FakeRoom()
        var nudges = 0
        var otherCallbacks = 0
        var clock = 0.0
        val pendingBlocks = mutableListOf<() -> Unit>()
        var cancelled = 0

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
                onVoteNudge = { nudges++ },
                voteNudgeThrottleFactory = { onRefresh ->
                    VoteNudgeThrottle(
                        now = { clock },
                        schedule = { _, block ->
                            pendingBlocks += block
                            pendingBlocks.size
                        },
                        cancelScheduled = { cancelled++ },
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
    fun maliciousPayloadsOnNudgeTopic_onlyTriggerOnVoteNudge() =
        GlobalScope.promise {
            val h = Harness()
            h.session.connect("wss://example.invalid", "token")
            val json = """{"electionId":"x","ownEligible":true,"title":"<img src=x onerror=alert(1)>"}"""
            val jsonBytes = bytes(*json.encodeToByteArray().map { it.toInt() and 0xff }.toIntArray())
            val garbage = Uint8Array(64 * 1024)
            val invalidUtf8 = bytes(0xff, 0xfe, 0xc0, 0x80)
            val empty = Uint8Array(0)
            listOf(jsonBytes, garbage, invalidUtf8, empty).forEachIndexed { i, payload ->
                h.clock = (i + 1) * 3_000.0
                h.fake.emitData(payload, LiveKitRoomSession.VOTE_NUDGE_TOPIC)
            }
            assertEquals(4, h.nudges)
            assertEquals(0, h.otherCallbacks)
        }

    @Test
    fun nudgePayloadOnOtherTopic_doesNotTriggerOnVoteNudge() =
        GlobalScope.promise {
            val h = Harness()
            h.session.connect("wss://example.invalid", "token")
            h.fake.emitData(bytes(0), LiveKitRoomSession.CHAT_TOPIC)
            h.fake.emitData(bytes(0), "lapis-something-else")
            assertEquals(0, h.nudges)
        }

    @Test
    fun afterDisconnect_noCallback_and_pendingTrailingNudgeIsCancelled() =
        GlobalScope.promise {
            val h = Harness()
            h.session.connect("wss://example.invalid", "token")
            h.fake.emitData(bytes(0), LiveKitRoomSession.VOTE_NUDGE_TOPIC) // fires immediately
            h.fake.emitData(bytes(0), LiveKitRoomSession.VOTE_NUDGE_TOPIC) // coalesced -> trailing pending
            assertEquals(1, h.nudges)
            assertEquals(1, h.pendingBlocks.size)

            h.session.disconnect()
            assertEquals(1, h.cancelled)

            h.clock = 10_000.0
            h.fake.emitData(bytes(0), LiveKitRoomSession.VOTE_NUDGE_TOPIC) // old room's listener: onOwned guard
            assertEquals(1, h.nudges)
        }

    @Test
    fun sendVoteNudge_publishesOneReliableByte_andIsNoOpBeforeConnect() =
        GlobalScope.promise {
            val h = Harness()
            h.session.sendVoteNudge()
            assertEquals(0, h.fake.published.size)

            h.session.connect("wss://example.invalid", "token")
            h.session.sendVoteNudge()
            val sent = h.fake.published.single()
            assertEquals(LiveKitRoomSession.VOTE_NUDGE_TOPIC, sent.topic)
            assertEquals(true, sent.reliable)
            assertEquals(1, sent.length)
            assertTrue(LiveKitRoomSession.VOTE_NUDGE_TOPIC == "lapis-vote-nudge")
        }
}
