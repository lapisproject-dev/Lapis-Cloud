package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.143 -- the recording packet parser: strict, never throws, never reads an identity, rejects every forgery. A forged "recording off" would hide a
 * running recording, so the rule "only from the server" matters more here than anywhere.
 */
class EncounterRecordingPacketTest {
    private fun parse(
        text: String,
        fromParticipant: Boolean = false,
    ) = encounterRecordingPacket(fromParticipant = fromParticipant, payload = text.encodeToByteArray())

    @Test
    fun roundTripOfEverySignal() {
        listOf(
            EncounterRecordingSignal(active = true),
            EncounterRecordingSignal(active = false),
            EncounterRecordingSignal(active = true, ask = true),
            EncounterRecordingSignal(active = false, ask = true),
        ).forEach { signal -> assertEquals(signal, parse(text = encounterRecordingPayload(signal))) }
    }

    @Test
    fun theWireShapeIsFixed() {
        assertEquals("""{"v":1,"a":1}""", encounterRecordingPayload(EncounterRecordingSignal(active = true)))
        assertEquals("""{"v":1,"a":0}""", encounterRecordingPayload(EncounterRecordingSignal(active = false)))
        assertEquals("""{"v":1,"a":1,"k":1}""", encounterRecordingPayload(EncounterRecordingSignal(active = true, ask = true)))
    }

    @Test
    fun theLargestPacketFitsTheLimit() {
        assertTrue(
            encounterRecordingPayload(EncounterRecordingSignal(active = true, ask = true)).length <= ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES,
        )
    }

    @Test
    fun aPacketFromAParticipantIsAForgery() {
        assertNull(parse(text = """{"v":1,"a":0}""", fromParticipant = true))
        assertNull(parse(text = """{"v":1,"a":1}""", fromParticipant = true))
    }

    @Test
    fun wrongLengthIsDropped() {
        assertNull(encounterRecordingPacket(fromParticipant = false, payload = ByteArray(0)))
        assertNull(parse(text = """{"v":1,"a":1}""" + " ".repeat(ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES)))
    }

    @Test
    fun anythingThatIsNotTheFixedShapeIsDropped() {
        assertNull(parse(text = "not json"))
        assertNull(parse(text = "[]"))
        assertNull(parse(text = """{"v":2,"a":1}"""))
        assertNull(parse(text = """{"a":1}"""))
        assertNull(parse(text = """{"v":1}"""))
        assertNull(parse(text = """{"v":1,"a":2}"""))
        assertNull(parse(text = """{"v":1,"a":"1"}"""))
        assertNull(parse(text = """{"v":1,"a":1,"k":0}"""))
        assertNull(parse(text = """{"v":1,"a":1,"k":2}"""))
        assertNull(parse(text = """{"v":1,"a":1,"x":1}"""))
        assertNull(parse(text = """{"v":1,"a":1,"identity":"a"}"""))
        assertNull(parse(text = """{"v":"1","a":1}"""))
    }
}
