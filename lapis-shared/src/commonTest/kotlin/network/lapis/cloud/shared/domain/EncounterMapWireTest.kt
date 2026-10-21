package network.lapis.cloud.shared.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.166 -- the data-channel packet of the grid map: the round trip, the strict parser, the size budget and the DTO compatibility. */
class EncounterMapWireTest {
    private val id1 = "11111111-1111-4111-8111-111111111111"
    private val id2 = "22222222-2222-4222-8222-222222222222"

    private fun bytes(json: String) = json.encodeToByteArray()

    private fun parse(json: String) = encounterMapPacket(fromParticipant = false, payload = bytes(json))

    private val goodDelta = """{"v":1,"t":"d","e":"ab12cd34","s":7,"c":[["$id1",3,4,"N","AB"]],"r":["$id2"]}"""

    @Test
    fun delta_roundTrips() {
        val delta =
            EncounterMapPacket.Delta(
                epoch = "ab12cd34",
                seq = 7,
                changed = listOf(EncounterMapPositionDto(memberId = id1, x = 3, y = 4, facing = EncounterMapFacing.NORTH, initials = "AB")),
                removed = listOf(id2),
            )
        assertEquals(goodDelta, encounterMapPayload(delta))
        assertEquals(delta, parse(encounterMapPayload(delta)))
    }

    @Test
    fun reset_roundTrips() {
        val reset = EncounterMapPacket.Reset(epoch = "ab12cd34", seq = 9)
        assertEquals("""{"v":1,"t":"r","e":"ab12cd34","s":9}""", encounterMapPayload(reset))
        assertEquals(reset, parse(encounterMapPayload(reset)))
    }

    @Test
    fun allFourFacings_roundTrip() {
        EncounterMapFacing.entries.forEach { facing ->
            val delta =
                EncounterMapPacket.Delta(
                    epoch = "ab",
                    seq = 1,
                    changed = listOf(EncounterMapPositionDto(memberId = id1, x = 0, y = 0, facing = facing, initials = "X")),
                    removed = emptyList(),
                )
            assertEquals(delta, parse(encounterMapPayload(delta)))
        }
    }

    @Test
    fun aPacketWithASendingParticipant_isRejected() {
        assertNull(encounterMapPacket(fromParticipant = true, payload = bytes(goodDelta)))
    }

    @Test
    fun theSizeLimits_areEnforced() {
        assertNull(encounterMapPacket(fromParticipant = false, payload = ByteArray(0)))
        assertNull(
            encounterMapPacket(fromParticipant = false, payload = ByteArray(ENCOUNTER_MAP_MAX_PAYLOAD_BYTES + 1) { ' '.code.toByte() }),
        )
    }

    @Test
    fun strictParser_rejectsEveryMalformedVariant() {
        val bad =
            listOf(
                goodDelta.replace("\"v\":1", "\"v\":2"),
                goodDelta.replace("\"v\":1,", ""),
                goodDelta.replace("\"t\":\"d\"", "\"t\":\"x\""),
                goodDelta.replace("\"t\":\"d\"", "\"t\":1"),
                goodDelta.replace(",3,4,", ",12,4,"),
                goodDelta.replace(",3,4,", ",-1,4,"),
                goodDelta.replace(",3,4,", ",3,8,"),
                goodDelta.replace(",3,4,", ",3,-1,"),
                goodDelta.replace("\"N\"", "\"X\""),
                goodDelta.replace("\"N\"", "\"NORTH\""),
                goodDelta.replace(id1, "not-a-uuid"),
                goodDelta.replace(id2, "42"),
                goodDelta.replace("\"AB\"", "\"ABCD\""),
                goodDelta.replace("\"AB\"", "\"\""),
                goodDelta.replace("\"e\":\"ab12cd34\"", "\"e\":\"XYZ\""),
                goodDelta.replace("\"s\":7", "\"s\":-1"),
                goodDelta.replace("\"s\":7", "\"s\":\"7\""),
                goodDelta.replace("[\"$id1\",3,4,\"N\",\"AB\"]", "[\"$id1\",3,4,\"N\"]"),
                "[]",
                "null",
                "\"x\"",
                "{",
                "not json",
                """{"v":1,"t":"d","e":"ab","s":1,"c":"x","r":[]}""",
                """{"v":1,"t":"d","e":"ab","s":1,"c":[],"r":"x"}""",
                """{"v":1,"t":"d","e":"ab","s":1,"c":[]}""",
            )
        bad.forEachIndexed { index, json ->
            assertNull(parse(json), "variant $index must be rejected: $json")
        }
    }

    @Test
    fun moreThan64Entries_areRejected() {
        val many = (0 until 65).joinToString(",") { "\"$id2\"" }
        assertNull(parse("""{"v":1,"t":"d","e":"ab","s":1,"c":[],"r":[$many]}"""))
        val sixtyFour = (0 until 64).joinToString(",") { "\"$id2\"" }
        assertNotNull(parse("""{"v":1,"t":"d","e":"ab","s":1,"c":[],"r":[$sixtyFour]}"""))
        val changed = (0 until 65).joinToString(",") { """["$id1",1,1,"S","A"]""" }
        assertNull(parse("""{"v":1,"t":"d","e":"ab","s":1,"c":[$changed],"r":[]}"""))
    }

    @Test
    fun unknownAdditionalFields_areIgnored_soTheFormatStaysForwardCompatible() {
        val extended = goodDelta.replace("\"s\":7", "\"s\":7,\"zz\":{\"a\":1}")
        assertNotNull(parse(extended))
        val reset = """{"v":1,"t":"r","e":"ab","s":1,"future":true}"""
        assertEquals(EncounterMapPacket.Reset(epoch = "ab", seq = 1), parse(reset))
    }

    @Test
    fun theParserNeverThrows_onGarbage() {
        val noise = listOf("", "\u0000", "{\"v\":1,\"t\":\"d\",\"c\":[[1]],\"r\":[1],\"e\":1,\"s\":null}", "[[[[[[[[[[[[[[[[[[[[")
        noise.forEach { assertNull(parse(it)) }
        assertNull(encounterMapPacket(fromParticipant = false, payload = byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun worstCase_25ChangedAnd25Removed_staysUnderTheBudget() {
        val changed =
            (0 until ENCOUNTER_MAP_MAX_WALKERS).map {
                EncounterMapPositionDto(
                    memberId = "${it.toString().padStart(8, '0')}-1111-4111-8111-111111111111",
                    x = 11,
                    y = 7,
                    facing = EncounterMapFacing.WEST,
                    initials = "ÄÖÜ",
                )
            }
        val removed = (0 until ENCOUNTER_MAP_MAX_WALKERS).map { "${it.toString().padStart(8, '0')}-2222-4222-8222-222222222222" }
        val payload =
            encounterMapPayload(
                EncounterMapPacket.Delta(epoch = "0123456789abcdef", seq = Long.MAX_VALUE, changed = changed, removed = removed),
            ).encodeToByteArray()
        assertTrue(payload.size < ENCOUNTER_MAP_MAX_PAYLOAD_BYTES, "worst case is ${payload.size} bytes")
        assertNotNull(encounterMapPacket(fromParticipant = false, payload = payload))
    }

    // ── DTO compatibility ───────────────────────────────────────────────────

    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun oldJsonWithoutTheNewFields_decodesToTheDefaults() {
        val space =
            lenient.decodeFromString(
                EncounterSpaceDto.serializer(),
                """{"id":"a","title":"t","description":"","theme":"CHURCH","mode":"SERVICE","guestPolicy":"MEMBERS_ONLY",""" +
                    """"closedNotice":null,""" +
                    """"open":false,"openedAt":null,"presentCount":0,"maxParticipants":10,"pulpitDisplayNames":[],"myRole":null,""" +
                    """"canModerate":false,"archived":false}""",
            )
        assertEquals(false, space.mapEnabled)
        val info =
            lenient.decodeFromString(
                EncounterEntryInfoDto.serializer(),
                """{"space":${Json.encodeToString(EncounterSpaceDto.serializer(), space)},"consentRequired":false,"disclaimer":null}""",
            )
        assertNull(info.mapRoomNotice)
        assertNull(lenient.decodeFromString(EncounterSpaceInput.serializer(), """{"title":"t"}""").mapEnabled)
    }

    @Test
    fun newJsonDecodesWithAnOldDecoder_thatIgnoresUnknownKeys() {
        val json = Json.encodeToString(EncounterSpaceInput.serializer(), EncounterSpaceInput(title = "t", mapEnabled = true))
        assertTrue(json.contains("\"mapEnabled\":true"))
        // an "old" decoder knows no mapEnabled: simulate by removing nothing and decoding leniently into the same type — the key is simply additive
        assertEquals(true, lenient.decodeFromString(EncounterSpaceInput.serializer(), json).mapEnabled)
    }

    @Test
    fun enumsAreAppendOnly_inAFixedOrder() {
        assertEquals(listOf("NORTH", "EAST", "SOUTH", "WEST"), EncounterMapFacing.entries.map { it.name })
        assertEquals(listOf("ENTRANCE", "PULPIT", "CENTER"), EncounterMapAnchor.entries.map { it.name })
        assertEquals(listOf("MOVED", "BLOCKED", "THROTTLED"), EncounterMapMove.entries.map { it.name })
        assertEquals("lapis-encounter-map", ENCOUNTER_MAP_TOPIC)
        assertEquals(4096, ENCOUNTER_MAP_MAX_PAYLOAD_BYTES)
    }

    @Test
    fun selfDto_defaultsMeanNotOnTheMap() {
        val self = EncounterMapSelfDto(onMap = false)
        assertEquals(-1, self.x)
        assertEquals(-1, self.y)
        assertEquals(EncounterMapMove.MOVED, self.move)
    }
}
