package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.108 -- wire compatibility of the two new room fields, with the JSON configuration Kilua RPC really uses (not a plain `Json`), the
 * way `EncounterWireCompatTest` pins it: a new client reads an older server's room (no fields -> defaults: unlocked, not muted on join), an
 * old client reads a new server's room (unknown keys are ignored). No version gate is needed.
 */
class ConferenceModerationWireCompatTest {
    private val base =
        """"id":"r","title":"T","description":"","livekitRoomName":"lc-1","createdByMemberId":"m","createdByDisplayName":"M",""" +
            """"createdAt":"2026-10-11T10:00:00","endedAt":null,"active":true,"maxParticipants":25,"liveParticipantCount":0,"myRole":"MODERATOR""""

    @Test
    fun aRoomFromAnOlderServer_isUnlockedAndNotMutedOnJoin() {
        val dto = RpcSerialization.getJson().decodeFromString(ConferenceRoomDto.serializer(), "{$base}")
        assertFalse(dto.muteOnJoin)
        assertTrue(dto.participantsMayUnmute)
    }

    @Test
    fun aRoomFromANewServer_carriesTheTwoFields() {
        val dto =
            RpcSerialization.getJson().decodeFromString(
                ConferenceRoomDto.serializer(),
                "{$base,\"muteOnJoin\":true,\"participantsMayUnmute\":false}",
            )
        assertTrue(dto.muteOnJoin)
        assertFalse(dto.participantsMayUnmute)
    }

    @Serializable
    private data class OldClientRoom(
        val id: String,
        val title: String,
        val active: Boolean,
    )

    @Test
    fun anOldClient_readsANewServersRoom_becauseUnknownKeysAreIgnored() {
        val old =
            RpcSerialization.getJson().decodeFromString(
                OldClientRoom.serializer(),
                "{$base,\"muteOnJoin\":true,\"participantsMayUnmute\":false}",
            )
        assertEquals("T", old.title)
        assertTrue(old.active)
    }

    @Test
    fun anOldInput_withoutTheFields_decodesToTheDefaults() {
        val input = RpcSerialization.getJson().decodeFromString(ConferenceRoomInput.serializer(), "{\"title\":\"x\"}")
        assertFalse(input.muteOnJoin)
        assertTrue(input.participantsMayUnmute)
    }
}
