package network.lapis.cloud.client.encounter

import dev.kilua.rpc.RpcSerialization
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ── V1.9.79: the `seat` field of EncounterPresentDto, last and with a default -- compatible both ways ──────────

    private val presentBase = """"memberId":"m1","displayName":"Anna","role":"CONGREGATION","isGuest":false"""

    /** What an old, cached tab knows of a present person (no `seat`). */
    @Serializable
    private class OldPresent(
        val memberId: String,
        val displayName: String,
        val role: EncounterPresenceRole,
        val isGuest: Boolean,
    )

    @Test
    fun aPersonWithoutSeat_decodesToNull_aPersonWithSeat_toTheSeat() {
        val old = RpcSerialization.getJson().decodeFromString(EncounterPresentDto.serializer(), "{$presentBase}")
        assertNull(old.seat)
        val new = RpcSerialization.getJson().decodeFromString(EncounterPresentDto.serializer(), "{$presentBase,\"seat\":17}")
        assertEquals(17, new.seat)
    }

    @Test
    fun anOldClient_decodesTheNewJson_theUnknownSeatKeyIsIgnored() {
        val decoded = RpcSerialization.getJson().decodeFromString(OldPresent.serializer(), "{$presentBase,\"seat\":17}")
        assertEquals("Anna", decoded.displayName)
    }

    @Test
    fun theServerWritesNoSeatKeyForAnUnseatedPerson_andTheSeatForASeatedOne() {
        val json = RpcSerialization.getJson()
        val unseated =
            json.encodeToString(
                EncounterPresentDto.serializer(),
                EncounterPresentDto("m", "A", EncounterPresenceRole.CONGREGATION, false),
            )
        assertFalse(unseated.contains("seat"), unseated)
        val seated =
            json.encodeToString(
                EncounterPresentDto.serializer(),
                EncounterPresentDto("m", "A", EncounterPresenceRole.CONGREGATION, false, seat = 3),
            )
        assertTrue(seated.contains("\"seat\":3"), seated)
    }

    // ── V1.9.80: tables ───────────────────────────────────────────────────

    @Test
    fun aRoomWithoutTables_hasTablesOff_withTheDefaults() {
        val dto = RpcSerialization.getJson().decodeFromString(EncounterSpaceDto.serializer(), "{$base}")
        assertEquals(
            network.lapis.cloud.shared.domain
                .EncounterTablesConfig(enabled = false, count = 4, seats = 6),
            dto.tables,
        )
    }

    @Test
    fun aRoomWithTables_isDecoded() {
        val dto =
            RpcSerialization.getJson().decodeFromString(
                EncounterSpaceDto.serializer(),
                "{$base,\"profile\":\"ASSEMBLY\",\"tables\":{\"enabled\":true,\"count\":5,\"seats\":3}}",
            )
        assertEquals(
            network.lapis.cloud.shared.domain
                .EncounterTablesConfig(enabled = true, count = 5, seats = 3),
            dto.tables,
        )
    }

    @Test
    fun anOldAdminInput_withoutTables_decodesToNullMeaningUnchanged() {
        val input = RpcSerialization.getJson().decodeFromString(EncounterSpaceInput.serializer(), "{\"title\":\"x\"}")
        assertNull(input.tables)
    }

    @Test
    fun aPersonWithoutATable_isInThePlenary_andOneWithATableIsDecoded() {
        val plain =
            RpcSerialization.getJson().decodeFromString(
                EncounterPresentDto.serializer(),
                "{\"memberId\":\"m\",\"displayName\":\"N\",\"role\":\"CONGREGATION\",\"isGuest\":false}",
            )
        assertNull(plain.table)
        assertNull(plain.tableSeat)
        val seated =
            RpcSerialization.getJson().decodeFromString(
                EncounterPresentDto.serializer(),
                "{\"memberId\":\"m\",\"displayName\":\"N\",\"role\":\"CONGREGATION\",\"isGuest\":false,\"table\":2,\"tableSeat\":1}",
            )
        assertEquals(2, seated.table)
        assertEquals(1, seated.tableSeat)
    }

    @Test
    fun theTokenAnswer_withoutAToken_meansNoTable() {
        val none =
            RpcSerialization.getJson().decodeFromString(
                network.lapis.cloud.shared.domain.EncounterTableTokenAnswer
                    .serializer(),
                "{}",
            )
        assertNull(none.token)
    }
}
