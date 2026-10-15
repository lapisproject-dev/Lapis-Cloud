package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.138 -- the floor packet parser: strict, never throws, never reads an identity, rejects every forgery. */
class EncounterFloorPacketTest {
    private fun parse(
        text: String,
        fromParticipant: Boolean = false,
    ) = encounterFloorPacket(fromParticipant = fromParticipant, payload = text.encodeToByteArray())

    @Test
    fun roundTripOfEverySignal() {
        listOf(
            EncounterFloorSignal.On(118),
            EncounterFloorSignal.On(0),
            EncounterFloorSignal.You(600),
            EncounterFloorSignal.Off,
        ).forEach { signal ->
            assertEquals(signal, parse(text = encounterFloorPayload(signal)))
        }
    }

    @Test
    fun theWireShapeIsFixed() {
        assertEquals("""{"s":"ON","t":118}""", encounterFloorPayload(EncounterFloorSignal.On(118)))
        assertEquals("""{"s":"YOU","t":118}""", encounterFloorPayload(EncounterFloorSignal.You(118)))
        assertEquals("""{"s":"OFF"}""", encounterFloorPayload(EncounterFloorSignal.Off))
    }

    @Test
    fun theLargestPacketFitsTheLimit() {
        assertTrue(encounterFloorPayload(EncounterFloorSignal.You(600)).length <= ENCOUNTER_FLOOR_MAX_PAYLOAD_BYTES)
    }

    @Test
    fun aPacketFromAParticipantIsAForgery() {
        assertNull(parse(text = """{"s":"YOU","t":118}""", fromParticipant = true))
        assertNull(parse(text = """{"s":"OFF"}""", fromParticipant = true))
    }

    @Test
    fun wrongLengthIsDropped() {
        assertNull(encounterFloorPacket(fromParticipant = false, payload = ByteArray(0)))
        val tooLong = """{"s":"ON","t":1}""" + " ".repeat(ENCOUNTER_FLOOR_MAX_PAYLOAD_BYTES)
        assertNull(parse(text = tooLong))
    }

    @Test
    fun unknownKeysAreDropped() {
        assertNull(parse(text = """{"s":"ON","t":1,"who":"x"}"""))
        assertNull(parse(text = """{"s":"OFF","id":"x"}"""))
        assertNull(parse(text = """{"x":1}"""))
    }

    @Test
    fun unknownOrWronglyTypedStateIsDropped() {
        assertNull(parse(text = """{"s":"on","t":1}"""))
        assertNull(parse(text = """{"s":"GIVE","t":1}"""))
        assertNull(parse(text = """{"s":1,"t":1}"""))
        assertNull(parse(text = """{"s":"ON","t":"5"}"""))
        assertNull(parse(text = """{"s":"ON","t":1.5}"""))
        assertNull(parse(text = """{"t":5}"""))
    }

    @Test
    fun theTimeMustBeInRange() {
        assertNull(parse(text = """{"s":"ON","t":-1}"""))
        assertNull(parse(text = """{"s":"ON","t":601}"""))
        assertNull(parse(text = """{"s":"YOU","t":99999}"""))
        assertNull(parse(text = """{"s":"ON"}"""))
        assertNull(parse(text = """{"s":"YOU"}"""))
        assertNull(parse(text = """{"s":"OFF","t":5}"""))
        assertEquals(EncounterFloorSignal.On(600), parse(text = """{"s":"ON","t":600}"""))
    }

    @Test
    fun garbageNeverThrows() {
        listOf("", "{", "[]", "null", "42", "\"s\"", "{\"s\":", "\u0000\u0001", "{\"s\":\"ON\",\"t\":1}}").forEach {
            if (it.isNotEmpty()) assertNull(parse(text = it), it)
        }
        assertNull(encounterFloorPacket(fromParticipant = false, payload = byteArrayOf(-1, -2, -3)))
    }
}
