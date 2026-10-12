package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.120 -- wire compatibility of the room's `joinLeaveSounds` field, with the JSON configuration Kilua RPC really uses: a room from an
 * older server (no field) keeps the tones on, a new server's value round-trips.
 */
class ConferenceJoinLeaveSoundsWireCompatTest {
    private val base =
        """"id":"r","title":"T","description":"","livekitRoomName":"lc-1","createdByMemberId":"m","createdByDisplayName":"M",""" +
            """"createdAt":"2026-10-12T10:00:00","endedAt":null,"active":true,"maxParticipants":25,"liveParticipantCount":0,"myRole":"MODERATOR""""

    @Test
    fun aRoomFromAnOlderServer_hasTheTonesOn() {
        val dto = RpcSerialization.getJson().decodeFromString(ConferenceRoomDto.serializer(), "{$base}")
        assertTrue(dto.joinLeaveSounds)
    }

    @Test
    fun aRoomFromANewServer_carriesTheField() {
        val off = RpcSerialization.getJson().decodeFromString(ConferenceRoomDto.serializer(), "{$base,\"joinLeaveSounds\":false}")
        assertFalse(off.joinLeaveSounds)
    }

    @Test
    fun theFieldRoundTrips() {
        val json = RpcSerialization.getJson()
        val dto = json.decodeFromString(ConferenceRoomDto.serializer(), "{$base,\"joinLeaveSounds\":false}")
        val again = json.decodeFromString(ConferenceRoomDto.serializer(), json.encodeToString(ConferenceRoomDto.serializer(), dto))
        assertEquals(dto, again)
        assertFalse(again.joinLeaveSounds)
    }
}
