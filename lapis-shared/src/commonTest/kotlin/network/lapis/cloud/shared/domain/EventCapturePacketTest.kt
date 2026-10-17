package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.156 -- the capture packet parser of an event room: strict, never throws, never reads an identity, rejects every forgery. A guest without an account may
 * publish data, so a forged "nothing is recorded" must never be accepted: only a packet WITHOUT a sending participant (the server's own) counts.
 */
class EventCapturePacketTest {
    private fun parse(
        text: String,
        fromParticipant: Boolean = false,
    ) = eventCapturePacket(fromParticipant = fromParticipant, payload = text.encodeToByteArray())

    @Test
    fun roundTripOfEveryState() {
        listOf(false, true).forEach { recording ->
            listOf(false, true).forEach { streaming ->
                val live = EventCaptureLiveDto(recording = recording, streaming = streaming)
                assertEquals(live, parse(text = eventCapturePayload(live)))
            }
        }
    }

    @Test
    fun theWireShapeIsFixedAndFitsTheLimit() {
        assertEquals("""{"v":1,"r":1,"s":0}""", eventCapturePayload(EventCaptureLiveDto(recording = true, streaming = false)))
        assertEquals("""{"v":1,"r":0,"s":1}""", eventCapturePayload(EventCaptureLiveDto(recording = false, streaming = true)))
        assertTrue(eventCapturePayload(EventCaptureLiveDto(recording = true, streaming = true)).length <= EVENT_CAPTURE_MAX_PAYLOAD_BYTES)
    }

    @Test
    fun aPacketFromAParticipantIsAForgery() {
        assertNull(parse(text = """{"v":1,"r":0,"s":0}""", fromParticipant = true))
        assertNull(parse(text = """{"v":1,"r":1,"s":1}""", fromParticipant = true))
    }

    @Test
    fun wrongLengthIsDropped() {
        assertNull(eventCapturePacket(fromParticipant = false, payload = ByteArray(0)))
        assertNull(parse(text = """{"v":1,"r":1,"s":0}""" + " ".repeat(EVENT_CAPTURE_MAX_PAYLOAD_BYTES)))
    }

    @Test
    fun anythingThatIsNotTheFixedShapeIsDropped() {
        listOf(
            "not json",
            "[]",
            """{"v":2,"r":1,"s":1}""",
            """{"r":1,"s":1}""",
            """{"v":1,"r":1}""",
            """{"v":1,"s":1}""",
            """{"v":1,"r":2,"s":0}""",
            """{"v":1,"r":"1","s":0}""",
            """{"v":1,"r":1,"s":1,"x":1}""",
            """{"v":1,"r":-1,"s":0}""",
            """{"v":1,"r":true,"s":false}""",
        ).forEach { assertNull(parse(text = it), it) }
    }

    @Test
    fun thePacketCarriesNoIdentity() {
        val text = eventCapturePayload(EventCaptureLiveDto(recording = true, streaming = true))
        assertFalse(text.contains("evg-"))
        assertFalse(text.contains("name"))
    }

    @Test
    fun theEventRecKindIsTheEventCaptureKindAndNoOther() {
        assertTrue(AgreementTextKind.EVENT_REC.isEventCapture())
        AgreementTextKind.entries.filter { it != AgreementTextKind.EVENT_REC }.forEach { assertFalse(it.isEventCapture(), it.name) }
        // its layout is the one of the encounter notices (headline + three key points) -- without the label reserve of "+rec"
        assertEquals(null, EncounterConsentLayout.violation(label = "", body = "a\n\nb\n\nc\n\nd"))
    }
}
