package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import network.lapis.cloud.client.encounter.EncounterReactionWire
import network.lapis.cloud.shared.domain.ENCOUNTER_REACTION_TOPIC
import network.lapis.cloud.shared.domain.ENCOUNTER_SEAT_NUDGE_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ENCOUNTER_SEAT_NUDGE_TOPIC
import network.lapis.cloud.shared.domain.EncounterReaction
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private typealias EncounterRoomListener = (dynamic, dynamic, dynamic, dynamic) -> Unit

private fun encounterResolvedUnit(): Promise<Unit> = Promise { resolve, _ -> resolve(Unit) }

/**
 * V1.9.62 -- the encounter additions of [LiveKitRoomSession]: the runtime lock `publishEnabled = false` (second layer of the
 * listen-only rule: no SDK call, no device enumeration), the reaction topic whose sender is ALWAYS the SDK identity, the audio-autoplay
 * relay and `startAudio`.
 */
class LiveKitRoomSessionEncounterTest {
    private class FakeRoom {
        val listeners = mutableMapOf<String, EncounterRoomListener>()
        val published = mutableListOf<Pair<String?, Int>>()
        var cameraCalls = 0
        var microphoneCalls = 0
        var screenCalls = 0
        var deviceListCalls = 0
        var switchCalls = 0
        var startAudioCalls = 0
        var canPlay = false

        fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = js("([])")
            fake.canPlaybackAudio = canPlay
            val local: dynamic = js("({})")
            local.identity = "local"
            local.name = "Local"
            local.publishData = { data: Uint8Array, options: dynamic ->
                published += (options.topic as String?) to data.length
                encounterResolvedUnit()
            }
            local.setCameraEnabled = { _: Boolean ->
                cameraCalls++
                Promise.resolve(null)
            }
            local.setMicrophoneEnabled = { _: Boolean ->
                microphoneCalls++
                Promise.resolve(null)
            }
            local.setScreenShareEnabled = { _: Boolean ->
                screenCalls++
                Promise.resolve(null)
            }
            fake.localParticipant = local
            fake.connect = { _: String, _: String -> encounterResolvedUnit() }
            fake.disconnect = { _: dynamic -> encounterResolvedUnit() }
            fake.getActiveDevice = { _: String -> "some-device" }
            fake.switchActiveDevice = { _: String, _: String, _: Boolean ->
                switchCalls++
                Promise.resolve(true)
            }
            fake.startAudio = {
                startAudioCalls++
                encounterResolvedUnit()
            }
            fake.on = { event: String, l: EncounterRoomListener ->
                listeners[event] = l
                fake
            }
            fake.off = { _: String, _: EncounterRoomListener -> fake }
            return fake.unsafeCast<Room>()
        }

        fun emitData(
            payload: Uint8Array,
            topic: String,
            identity: String = "remote-1",
        ) {
            val participant: dynamic = js("({})")
            participant.identity = identity
            participant.name = "Remote"
            listeners.getValue(RoomEvent.DataReceived)(payload, participant, null, topic)
        }
    }

    private class Harness(
        publishEnabled: Boolean,
        canPlay: Boolean = false,
    ) {
        val fake = FakeRoom().also { it.canPlay = canPlay }
        val reactions = mutableListOf<Pair<String, EncounterReaction>>()
        val playback = mutableListOf<Boolean>()
        val seatNudges = mutableListOf<String>()
        var chats = 0

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
                onChat = { _ -> chats++ },
                onWhiteboardPreview = { _, _, _ -> },
                onWhiteboardCommit = { _, _, _ -> },
                onNotesCommit = { _, _, _ -> },
                onReconnecting = {},
                onReconnected = {},
                onDisconnected = { _ -> },
                roomFactory = { _ -> fake.asRoom() },
                publishEnabled = publishEnabled,
                onEncounterReaction = { sender, reaction -> reactions += sender to reaction },
                onAudioPlaybackChanged = { playback += it },
                onEncounterSeatNudge = { seatNudges += it },
            )
    }

    private fun bytesOf(text: String): Uint8Array {
        val encoded = text.encodeToByteArray()
        val array = Uint8Array(encoded.size)
        encoded.forEachIndexed { index, byte -> array.asDynamic()[index] = byte.toInt() and 0xff }
        return array
    }

    @Test
    fun publishDisabled_neverTouchesTheSdk_forCamera_microphone_screen_devices() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")

            assertEquals(ConferenceDeviceFailure.OTHER, h.session.setCamera(true))
            assertEquals(ConferenceDeviceFailure.OTHER, h.session.setMicrophone(true))
            h.session.setScreenShare(true)
            assertTrue(h.session.listDevices(ConferenceDeviceKind.MICROPHONE).isEmpty())
            assertEquals(null, h.session.activeDeviceId(ConferenceDeviceKind.CAMERA))
            assertEquals(ConferenceDeviceFailure.OTHER, h.session.switchDevice(ConferenceDeviceKind.CAMERA, "some-device"))

            assertEquals(0, h.fake.cameraCalls, "no camera call reached the SDK")
            assertEquals(0, h.fake.microphoneCalls)
            assertEquals(0, h.fake.screenCalls)
            assertEquals(0, h.fake.switchCalls)
        }

    @Test
    fun publishEnabled_isTheDefault_andTheConferenceKeepsWorking() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = true)
            h.session.connect("wss://example.invalid", "token")
            assertEquals(null, h.session.setCamera(true))
            assertEquals(null, h.session.setMicrophone(true))
            assertEquals(1, h.fake.cameraCalls)
            assertEquals(1, h.fake.microphoneCalls)
        }

    @Test
    fun aReaction_isAttributedToTheSdkIdentity_neverToAFieldOfThePayload() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")
            h.fake.emitData(bytesOf("""{"r":"AMEN","s":"forged"}"""), ENCOUNTER_REACTION_TOPIC, identity = "real-identity")
            assertEquals(listOf("real-identity" to EncounterReaction.AMEN), h.reactions)
        }

    @Test
    fun hostileReactionPayloads_neverReachTheCallback_andNeverThrow() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")
            listOf(
                bytesOf("""{"r":"WAVE"}"""),
                bytesOf("not json"),
                bytesOf("""{"r":"AMEN","pad":"${"x".repeat(100)}"}"""),
                Uint8Array(64 * 1024),
                Uint8Array(0),
            ).forEach { h.fake.emitData(it, ENCOUNTER_REACTION_TOPIC) }
            assertTrue(h.reactions.isEmpty())
            assertEquals(0, h.chats)
        }

    @Test
    fun theReactionTopic_doesNotLeakIntoTheChat_andAChatPayloadIsNoReaction() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")
            h.fake.emitData(bytesOf("""{"r":"AMEN"}"""), LiveKitRoomSession.CHAT_TOPIC)
            h.fake.emitData(bytesOf("""{"r":"AMEN"}"""), "lapis-something-else")
            assertTrue(h.reactions.isEmpty())
        }

    @Test
    fun sendEncounterReaction_publishesOneReliableSmallPacketOnTheTopic() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")
            h.session.sendEncounterReaction(EncounterReaction.HAND_LOWERED)
            assertEquals(
                listOf<Pair<String?, Int>>(ENCOUNTER_REACTION_TOPIC to EncounterReactionWire.encode(EncounterReaction.HAND_LOWERED).length),
                h.fake.published,
            )
            assertNotEquals(
                LiveKitRoomSession.CHAT_TOPIC,
                h.fake.published
                    .single()
                    .first,
            )
        }

    @Test
    fun theAudioAutoplayState_isSeededAfterConnect_andRelayedOnChange_andStartAudioIsForwarded() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false, canPlay = false)
            h.session.connect("wss://example.invalid", "token")
            assertEquals(listOf(false), h.playback, "seeded once after connect")
            h.fake.listeners.getValue(RoomEvent.AudioPlaybackStatusChanged)(true, null, null, null)
            assertEquals(listOf(false, true), h.playback)
            h.session.startAudio()
            assertEquals(1, h.fake.startAudioCalls)
        }

    // ── V1.9.79: the seat nudge ──────────────────────────────────────────────────

    @Test
    fun aSeatNudge_reportsOnlyTheSdkIdentity_andNeverReadsThePayload() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")
            h.fake.emitData(bytesOf("""{"s":1}"""), ENCOUNTER_SEAT_NUDGE_TOPIC, identity = "real-identity")
            // a payload that is not even JSON is still a valid nudge: it is never decoded, only measured
            h.fake.emitData(bytesOf("junk"), ENCOUNTER_SEAT_NUDGE_TOPIC, identity = "other")
            assertEquals(listOf("real-identity", "other"), h.seatNudges)
        }

    @Test
    fun anOversizedSeatNudge_isDroppedUnread_andTheOtherTopicsDoNotTriggerIt() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")
            h.fake.emitData(Uint8Array(ENCOUNTER_SEAT_NUDGE_MAX_PAYLOAD_BYTES + 1), ENCOUNTER_SEAT_NUDGE_TOPIC)
            h.fake.emitData(Uint8Array(64 * 1024), ENCOUNTER_SEAT_NUDGE_TOPIC)
            h.fake.emitData(bytesOf("""{"s":1}"""), ENCOUNTER_REACTION_TOPIC)
            h.fake.emitData(bytesOf("""{"s":1}"""), LiveKitRoomSession.CHAT_TOPIC)
            assertTrue(h.seatNudges.isEmpty())
            assertTrue(h.reactions.isEmpty())
        }

    @Test
    fun sendEncounterSeatNudge_publishesOneReliableTinyPacketOnItsOwnTopic() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.session.connect("wss://example.invalid", "token")
            h.session.sendEncounterSeatNudge()
            val (topic, length) = h.fake.published.single()
            assertEquals(ENCOUNTER_SEAT_NUDGE_TOPIC, topic)
            assertTrue(length <= ENCOUNTER_SEAT_NUDGE_MAX_PAYLOAD_BYTES, "the nudge fits the receiver's size limit: $length")
        }
}
