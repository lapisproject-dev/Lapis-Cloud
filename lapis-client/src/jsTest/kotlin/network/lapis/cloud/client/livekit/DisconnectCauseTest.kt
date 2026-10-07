package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V1.9.69 -- [disconnectCauseOf] against the REAL `livekit-client` module (Karma loads it), and the wiring in
 * [LiveKitRoomSession]: the first argument of `RoomEvent.Disconnected` must reach `onDisconnected` as a [DisconnectCause]
 * (it used to be dropped, which is why a second sign-in with the same account looped).
 */
class DisconnectCauseTest {
    @Test
    fun theLibraryConstant_isTwo() {
        // The value this client acts on is the one `@livekit/protocol` pins; a library bump that changes it must fail here.
        assertEquals(2, DisconnectReason.DUPLICATE_IDENTITY)
    }

    @Test
    fun duplicateIdentity_isRecognised() {
        assertEquals(DisconnectCause.DuplicateIdentity, disconnectCauseOf(2))
    }

    @Test
    fun everythingElse_isOther() {
        assertEquals(DisconnectCause.Other, disconnectCauseOf(null))
        assertEquals(DisconnectCause.Other, disconnectCauseOf(undefined))
        listOf(0, 1, 3, 4, 10).forEach { assertEquals(DisconnectCause.Other, disconnectCauseOf(it), "reason $it") }
        assertEquals(DisconnectCause.Other, disconnectCauseOf("2"), "a string is never a reason code")
    }

    private class FakeRoom {
        val listeners = mutableMapOf<String, MutableList<(dynamic, dynamic, dynamic, dynamic) -> Unit>>()

        fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = js("([])")
            val local: dynamic = js("({})")
            local.identity = "local"
            fake.localParticipant = local
            fake.connect = { _: String, _: String -> Promise.resolve(Unit) }
            fake.disconnect = { _: dynamic -> Promise.resolve(Unit) }
            fake.on = { event: String, handler: (dynamic, dynamic, dynamic, dynamic) -> Unit ->
                listeners.getOrPut(event) { mutableListOf() }.add(handler)
                fake
            }
            fake.off = { _: String, _: dynamic -> fake }
            return fake.unsafeCast<Room>()
        }
    }

    private fun sessionWith(
        fake: FakeRoom,
        causes: MutableList<DisconnectCause>,
    ) = LiveKitRoomSession(
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
        onChat = { _ -> },
        onWhiteboardPreview = { _, _, _ -> },
        onWhiteboardCommit = { _, _, _ -> },
        onNotesCommit = { _, _, _ -> },
        onReconnecting = {},
        onReconnected = {},
        onDisconnected = { cause -> causes += cause },
        roomFactory = { fake.asRoom() },
    )

    @Test
    fun theDisconnectedEvent_carriesItsReasonToTheCallback() =
        GlobalScope.promise {
            val fake = FakeRoom()
            val causes = mutableListOf<DisconnectCause>()
            val session = sessionWith(fake, causes)
            assertEquals(null, session.connect("wss://example.invalid", "token"))
            val handlers = fake.listeners.getValue(RoomEvent.Disconnected)
            handlers.forEach { it(DisconnectReason.DUPLICATE_IDENTITY, undefined, undefined, undefined) }
            handlers.forEach { it(undefined, undefined, undefined, undefined) }
            handlers.forEach { it(1, undefined, undefined, undefined) }
            assertEquals(listOf(DisconnectCause.DuplicateIdentity, DisconnectCause.Other, DisconnectCause.Other), causes)
        }
}
