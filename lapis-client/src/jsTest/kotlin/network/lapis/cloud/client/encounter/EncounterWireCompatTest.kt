package network.lapis.cloud.client.encounter

import dev.kilua.rpc.RpcSerialization
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * V1.9.67 -- C1: wire compatibility of the new `profile`/`reactions` fields, with the JSON configuration Kilua RPC really uses (not a
 * plain `Json`). The server writes with `encodeDefaults = false`, so a church room carries neither key; a client without the fields
 * (a cached old tab) must not break on an assembly room's extra keys. The second test is the guard for that assumption: if Kilua's
 * `ignoreUnknownKeys` is ever `false`, it fails and the plan "extra keys are harmless for old tabs" must be revisited.
 */
class EncounterWireCompatTest {
    private val base =
        """"id":"s","title":"T","description":"","theme":"CHURCH","mode":"SERVICE","guestPolicy":"MEMBERS_ONLY","closedNotice":null,""" +
            """"open":false,"openedAt":null,"presentCount":0,"maxParticipants":10,"pulpitDisplayNames":[],"myRole":null,""" +
            """"canModerate":false,"archived":false"""

    @Test
    fun aRoomWithoutProfileAndReactions_isAChurchServiceWithHandAndAmen() {
        val dto = RpcSerialization.getJson().decodeFromString(EncounterSpaceDto.serializer(), "{$base}")
        assertEquals(EncounterProfile.CHURCH_SERVICE, dto.profile)
        assertEquals(listOf(EncounterReactionOption.HAND, EncounterReactionOption.AMEN), dto.reactions)
    }

    @Test
    fun anAssemblyRoom_isDecodedWithItsProfileAndReactions() {
        val dto =
            RpcSerialization.getJson().decodeFromString(
                EncounterSpaceDto.serializer(),
                "{$base,\"profile\":\"ASSEMBLY\",\"reactions\":[\"HAND\",\"HEART\"]}",
            )
        assertEquals(EncounterProfile.ASSEMBLY, dto.profile)
        assertEquals(listOf(EncounterReactionOption.HAND, EncounterReactionOption.HEART), dto.reactions)
    }

    @Test
    fun theRpcJson_ignoresUnknownKeys_soAnOldClientSurvivesNewFields() {
        // the key `futureField` stands for any field a newer server adds
        val dto = RpcSerialization.getJson().decodeFromString(EncounterSpaceDto.serializer(), "{$base,\"futureField\":{\"x\":1}}")
        assertEquals("T", dto.title)
    }

    @Test
    fun anOldAdminInput_withoutProfileAndReactions_decodesToNullMeaningUnchanged() {
        val input = RpcSerialization.getJson().decodeFromString(EncounterSpaceInput.serializer(), "{\"title\":\"x\"}")
        assertNull(input.profile)
        assertNull(input.reactions)
    }

    @Test
    fun aRoomWithoutNotifyMode_isNone() {
        val dto = RpcSerialization.getJson().decodeFromString(EncounterSpaceDto.serializer(), "{$base}")
        assertEquals(EncounterNotifyMode.NONE, dto.notifyMode)
    }

    @Test
    fun aRoomWithNotifyMode_isDecoded() {
        val dto =
            RpcSerialization.getJson().decodeFromString(
                EncounterSpaceDto.serializer(),
                "{$base,\"notifyMode\":\"EVERY_GUEST\"}",
            )
        assertEquals(EncounterNotifyMode.EVERY_GUEST, dto.notifyMode)
    }

    @Test
    fun anOldAdminInput_withoutNotifyMode_decodesToNullMeaningUnchanged() {
        val input = RpcSerialization.getJson().decodeFromString(EncounterSpaceInput.serializer(), "{\"title\":\"x\"}")
        assertNull(input.notifyMode)
    }

    @Test
    fun anAdminInputWithNotifyMode_roundTrips() {
        val json = RpcSerialization.getJson()
        val text =
            json.encodeToString(
                EncounterSpaceInput.serializer(),
                EncounterSpaceInput(title = "x", notifyMode = EncounterNotifyMode.FIRST_GUEST),
            )
        assertEquals(EncounterNotifyMode.FIRST_GUEST, json.decodeFromString(EncounterSpaceInput.serializer(), text).notifyMode)
    }
}
