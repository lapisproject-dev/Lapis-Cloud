package network.lapis.cloud.shared.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * V1.9.304 -- wire compatibility of the room-poll DTOs: a field that is missing decodes to its default (an older client / server), and an unknown
 * enum literal fails (the same pinned behaviour as every enum on this wire).
 */
class RoomPollsWireTest {
    private val json = Json

    @Test
    fun missingOptionalFieldsDecodeToTheirDefaults() {
        val state = json.decodeFromString(RoomPollStateDto.serializer(), """{"roomId":"r","version":"none"}""")
        assertNull(state.poll)
        assertEquals(false, state.voted)
        assertEquals(false, state.canAnswer)
        assertEquals(false, state.isLeadership)
        assertNull(state.answerCount)
        assertNull(state.interim)
        assertNull(state.result)
        val poll =
            json.decodeFromString(
                RoomPollDto.serializer(),
                """{"id":"p","question":"q","options":[],"countingMode":"BOTH","status":"OPEN"}""",
            )
        assertEquals(false, poll.binding)
        assertNull(poll.closeReason)
        val input = json.decodeFromString(RoomPollStartInput.serializer(), """{"roomId":"r","question":"q","options":["a","b"]}""")
        assertEquals(RoomPollCountingMode.BOTH, input.countingMode)
    }

    @Test
    fun anUnknownEnumLiteralFails() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(
                RoomPollDto.serializer(),
                """{"id":"p","question":"q","options":[],"countingMode":"NEW","status":"OPEN"}""",
            )
        }
    }
}
