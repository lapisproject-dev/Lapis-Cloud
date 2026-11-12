package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "board-position-treasurer" wave: pins the placement of [CommitteeRole.TREASURER] (Finanzvorstand) against silent drift. */
class CommitteeRoleTreasurerTest {
    /** What a client or peer built before the wave knows: the enum WITHOUT TREASURER. */
    @Serializable
    private enum class OldCommitteeRole {
        CHAIR,
        DEPUTY_CHAIR,
        SECRETARY,
        MEMBER,
        ASSESSOR,
        GENERAL_SECRETARY,
        PRESS_SPOKESPERSON,
        MANAGING_DIRECTOR,
    }

    @Serializable
    private data class OldWire(
        val role: OldCommitteeRole,
    )

    @Test
    fun ranksAreUniqueAndTreasurerSitsBetweenManagingDirectorAndSecretary() {
        val ranks = CommitteeRole.entries.map { it.rank }
        assertEquals(ranks.size, ranks.toSet().size)
        assertEquals(
            listOf(
                CommitteeRole.CHAIR,
                CommitteeRole.DEPUTY_CHAIR,
                CommitteeRole.GENERAL_SECRETARY,
                CommitteeRole.MANAGING_DIRECTOR,
                CommitteeRole.TREASURER,
                CommitteeRole.SECRETARY,
                CommitteeRole.PRESS_SPOKESPERSON,
                CommitteeRole.ASSESSOR,
                CommitteeRole.MEMBER,
            ),
            CommitteeRole.entries.sortedBy { it.rank },
        )
    }

    @Test
    fun treasurerIsSingleHolderButNeitherLeadershipNorRecording() {
        assertTrue(CommitteeRole.TREASURER in SINGLE_HOLDER_COMMITTEE_ROLES)
        assertFalse(CommitteeRole.TREASURER in COMMITTEE_LEADERSHIP_ROLES)
        assertFalse(CommitteeRole.TREASURER in COMMITTEE_RECORDING_ROLES)
        assertFalse(CommitteeRole.MEMBER in SINGLE_HOLDER_COMMITTEE_ROLES)
        assertFalse(CommitteeRole.ASSESSOR in SINGLE_HOLDER_COMMITTEE_ROLES)
    }

    @Test
    fun invalidRoleStringsAreRejected() {
        listOf("TREASURER_X", "treasurer", "").forEach { bad ->
            assertFailsWith<IllegalArgumentException> { CommitteeRole.valueOf(bad) }
        }
    }

    @Test
    fun anOlderDecoderFailsOnTheNewValueWithASerializationException() {
        val json = Json.encodeToString(CommitteeRole.serializer(), CommitteeRole.TREASURER)
        assertEquals("\"TREASURER\"", json)
        assertFailsWith<SerializationException> { Json.decodeFromString<OldWire>("{\"role\":\"TREASURER\"}") }
    }
}
