package network.lapis.cloud.shared.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.39: `rationale` is additive and defaulted -- JSON from before the field existed must still decode. */
class SystemicConsensusRationaleBackwardCompatTest {
    @Test
    fun optionWithoutRationaleDecodes() {
        val json =
            """{"id":"a","systemicConsensusId":"b","label":"L","position":1,"isStatusQuoOption":false,""" +
                """"createdById":"c","createdByDisplayName":"D"}"""
        val dto = Json.decodeFromString(SystemicConsensusOptionDto.serializer(), json)
        assertNull(dto.rationale)
        assertEquals("L", dto.label)
    }

    @Test
    fun inputWithoutRationaleDecodes() {
        val dto = Json.decodeFromString(SystemicConsensusOptionInput.serializer(), """{"label":"L"}""")
        assertNull(dto.rationale)
    }
}
