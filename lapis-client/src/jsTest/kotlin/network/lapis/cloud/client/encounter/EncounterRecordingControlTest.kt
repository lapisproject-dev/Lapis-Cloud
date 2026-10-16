package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.143 -- the recording control of the bar: its own group before the panels, kept in the bar until only the chat is left, never next to the exit or a moderation control. */
class EncounterRecordingControlTest {
    private val recording = EncounterControlSlot.Recording
    private val chat = EncounterControlSlot.Chat
    private val more = EncounterControlSlot.More

    @Test
    fun theRecordingControlHasItsOwnGroupBetweenTheLiturgyAndThePanels() {
        assertEquals(EncounterControlGroup.RECORDING, encounterControlGroup(recording))
        val groups = EncounterControlGroup.entries
        assertTrue(groups.indexOf(EncounterControlGroup.LITURGY) < groups.indexOf(EncounterControlGroup.RECORDING))
        assertTrue(groups.indexOf(EncounterControlGroup.RECORDING) < groups.indexOf(EncounterControlGroup.PANELS))
        assertEquals(EncounterControlGroup.DEVICES, groups.first())
        assertEquals(EncounterControlGroup.EXIT, groups.last())
    }

    @Test
    fun theOverflowOrderKeepsTheRecordingControlInTheBarUntilOnlyTheChatIsLeft() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries.toSet(), blessing = true, bell = true, recording = true)
        assertEquals(chat, order.last())
        assertEquals(recording, order[order.size - 2])
        assertTrue(order.indexOf(EncounterControlSlot.Blessing) < order.indexOf(recording))
        assertFalse(encounterOverflowOrder(EncounterReactionOption.entries.toSet()).contains(recording), "no control without the option")
    }

    @Test
    fun theInvariantAcceptsTheRealOrdersAndRefusesAControlNextToTheExitOrAModerationControl() {
        val bar =
            listOf(
                EncounterControlSlot.Mic,
                EncounterControlSlot.Hand,
                EncounterControlSlot.Bell,
                recording,
                chat,
                more,
                EncounterControlSlot.Scene,
                EncounterControlSlot.Broadcast,
                EncounterControlSlot.CloseDoors,
                EncounterControlSlot.Leave,
            )
        assertTrue(recordingNeverAdjacentToExitOrModeration(bar))
        // the same bar once the chat and the sheet's neighbours are gone: nothing safe stands next to it
        assertFalse(recordingNeverAdjacentToExitOrModeration(listOf(recording, EncounterControlSlot.Broadcast)))
        assertFalse(recordingNeverAdjacentToExitOrModeration(listOf(EncounterControlSlot.Leave, recording)))
        assertFalse(recordingNeverAdjacentToExitOrModeration(listOf(EncounterControlSlot.CloseDoors, recording)))
        assertTrue(recordingNeverAdjacentToExitOrModeration(listOf(recording)))
    }

    @Test
    fun overflowingTheBarNeverPutsTheRecordingControlNextToADangerousOne() {
        // simulate the overflow: remove the controls in the order the room moves them into the sheet and test every intermediate bar
        val full =
            mutableListOf(
                EncounterControlSlot.Mic,
                EncounterControlSlot.Hand,
                EncounterControlSlot.Reaction(EncounterReactionOption.AMEN),
                EncounterControlSlot.Bell,
                EncounterControlSlot.Blessing,
                recording,
                chat,
                more,
                EncounterControlSlot.Scene,
                EncounterControlSlot.Fullscreen,
                EncounterControlSlot.Broadcast,
                EncounterControlSlot.CloseDoors,
                EncounterControlSlot.Leave,
            )
        encounterOverflowOrder(setOf(EncounterReactionOption.AMEN), blessing = true, bell = true, recording = true).forEach { moved ->
            assertTrue(recordingNeverAdjacentToExitOrModeration(full), "before moving $moved: $full")
            full -= moved
            // "Mehr" stays as soon as something moved; the chat stays last
        }
    }
}
