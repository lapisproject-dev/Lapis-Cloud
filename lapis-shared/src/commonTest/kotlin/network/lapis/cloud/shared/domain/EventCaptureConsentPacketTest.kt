package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.280 -- the two packets of the in-room consent (the ask and the count): strict parsers that never throw and never read an identity. A guest without an
 * account may publish data, so only a packet WITHOUT a sending participant (the server's own) counts, with exactly the fixed keys and in-range numbers.
 */
class EventCaptureConsentPacketTest {
    private fun ask(
        text: String,
        fromParticipant: Boolean = false,
    ) = eventCaptureAskPacket(fromParticipant = fromParticipant, payload = text.encodeToByteArray())

    private fun count(
        text: String,
        fromParticipant: Boolean = false,
    ) = eventCaptureCountPacket(fromParticipant = fromParticipant, payload = text.encodeToByteArray())

    @Test
    fun theAskHasOneFixedShapeAndFitsTheLimit() {
        assertEquals("""{"v":1}""", eventCaptureAskPayload())
        assertTrue(eventCaptureAskPayload().length <= EVENT_CAPTURE_ASK_MAX_PAYLOAD_BYTES)
        assertTrue(ask(text = eventCaptureAskPayload()))
    }

    @Test
    fun anAskFromAParticipantOrOfAnyOtherShapeIsDropped() {
        assertFalse(ask(text = """{"v":1}""", fromParticipant = true))
        assertFalse(eventCaptureAskPacket(fromParticipant = false, payload = ByteArray(0)))
        assertFalse(ask(text = """{"v":1}""" + " ".repeat(EVENT_CAPTURE_ASK_MAX_PAYLOAD_BYTES)))
        listOf("not json", "[]", """{"v":2}""", """{"v":"1"}""", """{}""", """{"v":1,"x":1}""", """{"x":1}""", "null", "1").forEach {
            assertFalse(ask(text = it), it)
        }
    }

    @Test
    fun theCountRoundTripsAndFitsTheLimit() {
        listOf(0 to 0, 0 to 1, 3 to 7, 999 to 999).forEach { (n, m) ->
            val dto = EventCaptureConsentCountDto(acknowledged = n, total = m)
            val payload = eventCaptureCountPayload(dto)
            assertTrue(payload.length <= EVENT_CAPTURE_COUNT_MAX_PAYLOAD_BYTES, payload)
            assertEquals(dto, count(text = payload))
        }
        assertEquals("""{"v":1,"n":2,"m":5}""", eventCaptureCountPayload(EventCaptureConsentCountDto(acknowledged = 2, total = 5)))
    }

    @Test
    fun theSenderClampsTheNumbers() {
        assertEquals(
            EventCaptureConsentCountDto(acknowledged = 999, total = 999),
            count(text = eventCaptureCountPayload(EventCaptureConsentCountDto(acknowledged = 5000, total = 5000))),
        )
        assertEquals(
            EventCaptureConsentCountDto(acknowledged = 3, total = 3),
            count(text = eventCaptureCountPayload(EventCaptureConsentCountDto(acknowledged = 9, total = 3))),
        )
        assertEquals(
            EventCaptureConsentCountDto(acknowledged = 0, total = 0),
            count(text = eventCaptureCountPayload(EventCaptureConsentCountDto(acknowledged = -4, total = -2))),
        )
    }

    @Test
    fun aCountFromAParticipantIsAForgery() {
        assertNull(count(text = """{"v":1,"n":1,"m":1}""", fromParticipant = true))
    }

    @Test
    fun wrongLengthIsDropped() {
        assertNull(eventCaptureCountPacket(fromParticipant = false, payload = ByteArray(0)))
        assertNull(count(text = """{"v":1,"n":1,"m":1}""" + " ".repeat(EVENT_CAPTURE_COUNT_MAX_PAYLOAD_BYTES)))
    }

    @Test
    fun overlongOutOfRangeOrForeignKeysAreDropped() {
        listOf(
            "not json",
            "[]",
            """{"v":2,"n":1,"m":1}""",
            """{"n":1,"m":1}""",
            """{"v":1,"n":1}""",
            """{"v":1,"m":1}""",
            """{"v":1,"n":2,"m":1}""",
            """{"v":1,"n":-1,"m":1}""",
            """{"v":1,"n":0,"m":1000}""",
            """{"v":1,"n":1000,"m":1000}""",
            """{"v":1,"n":"1","m":1}""",
            """{"v":1,"n":1.5,"m":2}""",
            """{"v":1,"n":1,"m":1,"x":1}""",
            """{"v":1,"n":1,"x":1}""",
            """{"v":1,"n":null,"m":1}""",
        ).forEach { assertNull(count(text = it), it) }
    }

    @Test
    fun theTopicsAreDistinctAndDifferFromTheBanner() {
        val topics = setOf(EVENT_CAPTURE_TOPIC, EVENT_CAPTURE_ASK_TOPIC, EVENT_CAPTURE_COUNT_TOPIC)
        assertEquals(3, topics.size)
    }
}
