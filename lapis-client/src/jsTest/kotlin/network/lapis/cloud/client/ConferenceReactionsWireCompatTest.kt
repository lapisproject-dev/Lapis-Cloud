package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.127 -- wire compatibility of the room's `reactionsEnabled` field and of the hand queue DTO, with the JSON configuration Kilua RPC
 * really uses: a room from an older server (no field) has the reactions ON, a new server's value round-trips.
 */
class ConferenceReactionsWireCompatTest {
    private val base =
        """"id":"r","title":"T","description":"","livekitRoomName":"lc-1","createdByMemberId":"m","createdByDisplayName":"M",""" +
            """"createdAt":"2026-10-13T10:00:00","endedAt":null,"active":true,"maxParticipants":25,"liveParticipantCount":0,"myRole":"MODERATOR""""

    @Test
    fun aRoomFromAnOlderServer_hasTheReactionsOn() {
        val dto = RpcSerialization.getJson().decodeFromString(ConferenceRoomDto.serializer(), "{$base}")
        assertTrue(dto.reactionsEnabled)
    }

    @Test
    fun aRoomFromANewServer_carriesTheField_andItRoundTrips() {
        val json = RpcSerialization.getJson()
        val off = json.decodeFromString(ConferenceRoomDto.serializer(), "{$base,\"reactionsEnabled\":false}")
        assertFalse(off.reactionsEnabled)
        val again = json.decodeFromString(ConferenceRoomDto.serializer(), json.encodeToString(ConferenceRoomDto.serializer(), off))
        assertEquals(off, again)
    }

    @Test
    fun theHandQueueDtoRoundTrips() {
        val json = RpcSerialization.getJson()
        val dto = ConferenceHandQueueDto(epoch = "0123456789abcdef", seq = 7, identities = listOf("11111111-1111-1111-1111-111111111111"))
        assertEquals(
            dto,
            json.decodeFromString(ConferenceHandQueueDto.serializer(), json.encodeToString(ConferenceHandQueueDto.serializer(), dto)),
        )
    }
}
