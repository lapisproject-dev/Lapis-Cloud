package network.lapis.cloud.shared.domain

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.24 -- the wire contract of the room voting state: the exact field sets (ballot secrecy) and the reserved enum value. */
private fun SerialDescriptor.names(): Set<String> = (0 until elementsCount).map { getElementName(it) }.toSet()

class RoomVotingStateDtoTest {
    @Test
    fun roomBallotDto_hasExactlyTheAllowedFields() {
        val names = RoomBallotDto.serializer().descriptor.names()
        assertEquals(
            setOf(
                "kind",
                "id",
                "motionId",
                "motionTitle",
                "title",
                "status",
                "secret",
                "ownEligible",
                "ownHasVoted",
                // V1.9.27: option labels and the winner of a meritocratic vote -- never an amount
                "options",
                "winnerOptionId",
            ),
            names,
        )
        assertEquals(setOf("id", "label", "position"), RoomBallotOptionDto.serializer().descriptor.names())
    }

    @Test
    fun roomVotingStateDto_hasExactlyTheAllowedFields() {
        val names = RoomVotingStateDto.serializer().descriptor.names()
        assertEquals(setOf("roomId", "bound", "ballots", "truncated"), names)
    }

    @Test
    fun consensusKindIsReserved() {
        assertTrue(RoomBallotKind.CONSENSUS in RoomBallotKind.entries)
        assertEquals(listOf("ELECTION", "VOTE", "CONSENSUS"), RoomBallotKind.entries.map { it.name })
    }
}
