package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.143 -- the recording control of the bar: its own group (V1.9.184: behind the view group, in front of the transmission), kept in the bar until only the chat and the tail are left, never next to a reaction or the exit. */
class EncounterRecordingControlTest {
    private val recording = EncounterControlSlot.Recording
    private val chat = EncounterControlSlot.Chat
    private val more = EncounterControlSlot.More

    @Test
    fun theRecordingControlHasItsOwnGroupBehindTheViewAndInFrontOfTheTransmission() {
        assertEquals(EncounterControlGroup.RECORDING, encounterControlGroup(recording))
        val groups = EncounterControlGroup.entries
        assertTrue(groups.indexOf(EncounterControlGroup.VIEW) < groups.indexOf(EncounterControlGroup.RECORDING))
        assertTrue(groups.indexOf(EncounterControlGroup.RECORDING) < groups.indexOf(EncounterControlGroup.MODERATION))
        assertEquals(EncounterControlGroup.SETTINGS, groups.first())
        assertEquals(EncounterControlGroup.EXIT, groups.last())
    }

    @Test
    fun theOverflowOrderKeepsTheRecordingControlInTheBarUntilOnlyTheChatAndTheTailAreLeft() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries.toSet(), blessing = true, bell = true, recording = true)
        // V1.9.184: the recording stays right in front of the chat; the chat leads the tail (doors, gear, camera, microphone)
        assertEquals(order.indexOf(chat) - 1, order.indexOf(recording))
        assertEquals(
            listOf(
                chat,
                EncounterControlSlot.CloseDoors,
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Camera,
                EncounterControlSlot.Mic,
            ),
            order.takeLast(5),
        )
        assertTrue(order.indexOf(EncounterControlSlot.Blessing) < order.indexOf(recording))
        assertFalse(encounterOverflowOrder(EncounterReactionOption.entries.toSet()).contains(recording), "no control without the option")
    }

    @Test
    fun theInvariantAcceptsTheRealOrdersAndRefusesAControlNextToAReactionOrAnExitControlInTheSameZone() {
        val bar =
            listOf(
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Mic,
                EncounterControlSlot.Hand,
                EncounterControlSlot.Bell,
                chat,
                EncounterControlSlot.Scene,
                recording,
                EncounterControlSlot.Broadcast,
                more,
                EncounterControlSlot.CloseDoors,
                EncounterControlSlot.Leave,
            )
        assertTrue(moderationToolsNeverAdjacentToExitOrReactions(bar))
        // V1.9.184: the recording and the transmission now stand together; the end zone is a zone of its own
        assertTrue(moderationToolsNeverAdjacentToExitOrReactions(listOf(recording, EncounterControlSlot.Broadcast)))
        assertTrue(moderationToolsNeverAdjacentToExitOrReactions(listOf(EncounterControlSlot.Leave, recording)), "different zones")
        // ... but never right behind a reaction
        assertFalse(moderationToolsNeverAdjacentToExitOrReactions(listOf(EncounterControlSlot.Hand, recording)))
        assertFalse(
            moderationToolsNeverAdjacentToExitOrReactions(listOf(recording, EncounterControlSlot.Reaction(EncounterReactionOption.AMEN))),
        )
        assertTrue(moderationToolsNeverAdjacentToExitOrReactions(listOf(recording)))
    }

    @Test
    fun overflowingTheBarNeverPutsTheRecordingControlNextToAReaction() {
        // simulate the overflow: remove the controls in the order the room moves them into the sheet and test every intermediate bar
        val full =
            mutableListOf(
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Mic,
                EncounterControlSlot.Hand,
                EncounterControlSlot.Reaction(EncounterReactionOption.AMEN),
                EncounterControlSlot.Bell,
                EncounterControlSlot.Blessing,
                chat,
                EncounterControlSlot.Scene,
                EncounterControlSlot.Fullscreen,
                recording,
                EncounterControlSlot.Broadcast,
                more,
                EncounterControlSlot.CloseDoors,
                EncounterControlSlot.Leave,
            )
        encounterOverflowOrder(setOf(EncounterReactionOption.AMEN), blessing = true, bell = true, recording = true).forEach { moved ->
            assertTrue(moderationToolsNeverAdjacentToExitOrReactions(full), "before moving $moved: $full")
            assertTrue(moreImmediatelyBeforeEnd(full), "before moving $moved: $full")
            full -= moved
        }
    }
}
