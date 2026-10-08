package network.lapis.cloud.client.encounter

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.livekit.Room
import network.lapis.cloud.client.livekit.RoomEvent
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias TableRoomListener = (dynamic, dynamic, dynamic, dynamic) -> Unit

private fun resolvedUnit(): Promise<Unit> = Promise { resolve, _ -> resolve(Unit) }

/**
 * V1.9.80 -- the audio session of a table, against a fake LiveKit room: it publishes nothing on connect, only a token that may publish
 * can switch the microphone (the runtime flag refuses the rest before the SDK is touched), a video track is dropped unread and the
 * camera / screen are not even part of the type.
 */
class EncounterTableSessionTest {
    private class FakeRoom {
        val listeners = mutableMapOf<String, TableRoomListener>()
        var cameraCalls = 0
        var microphoneCalls = 0
        var screenCalls = 0
        var disconnects = 0

        fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = js("([])")
            fake.canPlaybackAudio = true
            val local: dynamic = js("({})")
            local.identity = "local"
            local.name = "Local"
            local.publishData = { _: dynamic, _: dynamic -> resolvedUnit() }
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
            fake.connect = { _: String, _: String -> resolvedUnit() }
            fake.disconnect = { _: dynamic ->
                disconnects++
                resolvedUnit()
            }
            fake.on = { event: String, l: TableRoomListener ->
                listeners[event] = l
                fake
            }
            fake.off = { _: String, _: TableRoomListener -> fake }
            return fake.unsafeCast<Room>()
        }

        fun emitTrack(
            kind: String,
            identity: String = "remote-1",
        ) {
            val track: dynamic = js("({})")
            track.kind = kind
            val publication: dynamic = js("({})")
            publication.source = if (kind == "video") "camera" else "microphone"
            val participant: dynamic = js("({})")
            participant.identity = identity
            participant.name = "Remote"
            listeners.getValue(RoomEvent.TrackSubscribed)(track, publication, participant, null)
        }
    }

    private class Heard {
        val audio = mutableListOf<String>()
        val gone = mutableListOf<String>()
        val speakers = mutableListOf<List<String>>()
        val disconnects = mutableListOf<DisconnectCause>()

        val callbacks =
            EncounterTableCallbacks(
                onAudioTrack = { identity, _ -> audio += identity },
                onAudioTrackGone = { identity, _ -> gone += identity },
                onActiveSpeakers = { speakers += it },
                onDisconnected = { disconnects += it },
            )
    }

    private fun open(
        fake: FakeRoom,
        heard: Heard,
        canPublish: Boolean,
    ): EncounterTableSession =
        openEncounterTableSession(token = testTableToken(canPublish = canPublish), callbacks = heard.callbacks, roomFactory = { _ ->
            fake.asRoom()
        })

    @Test
    fun connecting_publishesNothing_theMicrophoneStartsOff(): Promise<Unit> =
        GlobalScope.promise {
            val fake = FakeRoom()
            val session = open(fake = fake, heard = Heard(), canPublish = true)
            assertNull(session.connect())
            assertEquals(0, fake.microphoneCalls, "no microphone call on connect: the room starts muted")
            assertEquals(0, fake.cameraCalls)
            assertEquals(0, fake.screenCalls)
        }

    @Test
    fun aTokenThatMayPublish_switchesTheMicrophoneThroughTheSdk(): Promise<Unit> =
        GlobalScope.promise {
            val fake = FakeRoom()
            val session = open(fake = fake, heard = Heard(), canPublish = true)
            session.connect()
            assertNull(session.microphone(true))
            assertEquals(1, fake.microphoneCalls)
            assertEquals(0, fake.cameraCalls, "the table session has no camera at all")
            assertEquals(0, fake.screenCalls)
        }

    @Test
    fun aQuietedTablesToken_cannotSwitchTheMicrophone_theSdkIsNeverTouched(): Promise<Unit> =
        GlobalScope.promise {
            val fake = FakeRoom()
            val session = open(fake = fake, heard = Heard(), canPublish = false)
            session.connect()
            assertEquals(ConferenceDeviceFailure.OTHER, session.microphone(true))
            assertEquals(0, fake.microphoneCalls)
        }

    @Test
    fun aVideoTrack_isDroppedUnread_anAudioTrackIsHandedOn(): Promise<Unit> =
        GlobalScope.promise {
            val fake = FakeRoom()
            val heard = Heard()
            val session = open(fake = fake, heard = heard, canPublish = true)
            session.connect()
            fake.emitTrack(kind = "video", identity = "camera-person")
            fake.emitTrack(kind = "audio", identity = "voice-person")
            assertEquals(listOf("voice-person"), heard.audio)
        }

    @Test
    fun disconnecting_leavesTheRoom_andTheEndOfARoomIsReported(): Promise<Unit> =
        GlobalScope.promise {
            val fake = FakeRoom()
            val heard = Heard()
            val session = open(fake = fake, heard = heard, canPublish = true)
            session.connect()
            fake.listeners.getValue(RoomEvent.Disconnected)(null, null, null, null)
            assertEquals(listOf(DisconnectCause.Other), heard.disconnects, "a deleted room is reported (the controller reconnects)")
            session.disconnect()
            assertTrue(fake.disconnects >= 1)
        }
}
