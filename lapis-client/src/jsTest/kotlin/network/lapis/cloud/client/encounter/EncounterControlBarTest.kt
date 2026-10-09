package network.lapis.cloud.client.encounter

import network.lapis.cloud.client.CONTROL_BAR_DIVIDER_FOOTPRINT_PX
import network.lapis.cloud.client.CONTROL_BAR_EXIT_GAP_PX
import network.lapis.cloud.client.CONTROL_BAR_FALLBACK_WIDTH_PX
import network.lapis.cloud.client.ControlMeasure
import network.lapis.cloud.client.controlBarOverflow
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.74: the pure parts of the encounter room's icon bar -- groups, overflow order, invariants and the arithmetic at phone widths. */
class EncounterControlBarTest {
    private val hand = EncounterControlSlot.Hand
    private val leave = EncounterControlSlot.Leave
    private val doors = EncounterControlSlot.CloseDoors
    private val broadcast = EncounterControlSlot.Broadcast

    @Test
    fun everySlot_belongsToOneGroup_andLeaveAndDoorsAreTheExitGroup() {
        assertEquals(EncounterControlGroup.REACTIONS, encounterControlGroup(hand))
        assertEquals(EncounterControlGroup.REACTIONS, encounterControlGroup(EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)))
        assertEquals(EncounterControlGroup.DEVICES, encounterControlGroup(EncounterControlSlot.Mic))
        assertEquals(EncounterControlGroup.DEVICES, encounterControlGroup(EncounterControlSlot.Camera))
        assertEquals(EncounterControlGroup.PANELS, encounterControlGroup(EncounterControlSlot.Chat))
        assertEquals(EncounterControlGroup.PANELS, encounterControlGroup(EncounterControlSlot.More))
        assertEquals(EncounterControlGroup.VIEW, encounterControlGroup(EncounterControlSlot.Scene))
        assertEquals(EncounterControlGroup.VIEW, encounterControlGroup(EncounterControlSlot.Fullscreen))
        assertEquals(EncounterControlGroup.MODERATION, encounterControlGroup(broadcast))
        assertEquals(EncounterControlGroup.EXIT, encounterControlGroup(doors))
        assertEquals(EncounterControlGroup.EXIT, encounterControlGroup(leave))
        assertEquals(EncounterControlGroup.EXIT, EncounterControlGroup.entries.last(), "the exit group is the last group of the DOM")
        assertEquals(EncounterControlGroup.DEVICES, EncounterControlGroup.entries.first(), "the devices are the first group of the DOM")
    }

    @Test
    fun theOverflowOrder_isViewThenTransmissionThenEventsBackwards_thenDoors_andChatLast() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries.toSet())
        val events = EncounterReactionOption.entries.filter { it != EncounterReactionOption.ALWAYS_ON }.reversed()
        assertEquals(
            listOf(EncounterControlSlot.Fullscreen, EncounterControlSlot.Scene, broadcast) +
                events.map { EncounterControlSlot.Reaction(it) } + listOf(doors, EncounterControlSlot.Chat),
            order,
        )
    }

    @Test
    fun theOverflowOrder_followsTheRoomsReactionSet() {
        val order = encounterOverflowOrder(EncounterReactionOption.defaultsFor(EncounterProfile.CHURCH_SERVICE))
        assertEquals(
            listOf(EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)),
            order.filterIsInstance<EncounterControlSlot.Reaction>(),
        )
        assertEquals(EncounterControlSlot.Chat, order.last())
    }

    @Test
    fun neverMoved_handDevicesMoreAndLeave() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries.toSet())
        listOf(hand, EncounterControlSlot.Mic, EncounterControlSlot.Camera, EncounterControlSlot.More, leave).forEach {
            assertFalse(it in order, "$it never moves into the sheet")
        }
    }

    @Test
    fun theInvariants_onTheirShownLists() {
        val reaction = EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)
        val full = listOf(hand, reaction, EncounterControlSlot.Chat, EncounterControlSlot.Scene, broadcast, doors, leave)
        assertTrue(encounterLeaveIsLast(full))
        assertTrue(doorsImmediatelyBeforeLeave(full))
        assertTrue(moderationNeverAdjacentToReactions(full))
        assertFalse(encounterLeaveIsLast(listOf(hand, leave, doors)))
        assertFalse(doorsImmediatelyBeforeLeave(listOf(doors, EncounterControlSlot.Chat, leave)))
        // a transmission that stands next to a reaction is the slip the layout avoids
        assertFalse(moderationNeverAdjacentToReactions(listOf(hand, broadcast, leave)))
        assertFalse(moderationNeverAdjacentToReactions(listOf(broadcast, reaction, leave)))
    }

    @Test
    fun devicesAreFirst_positiveAndNegative() {
        val reaction = EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)
        val mic = EncounterControlSlot.Mic
        val camera = EncounterControlSlot.Camera
        assertTrue(devicesAreFirst(listOf(mic, camera, hand, reaction, EncounterControlSlot.Chat, leave)))
        assertTrue(devicesAreFirst(listOf(hand, EncounterControlSlot.Chat, leave)), "no devices at all is fine")
        assertTrue(devicesAreFirst(listOf(EncounterControlSlot.TableMic, EncounterControlSlot.PulpitLouder, hand, leave)))
        assertFalse(devicesAreFirst(listOf(hand, mic, camera, leave)))
        assertFalse(devicesAreFirst(listOf(mic, hand, camera, leave)))
    }

    @Test
    fun devicesAndLeave_frameTheBar_inAFullPulpitList() {
        val full =
            listOf(
                EncounterControlSlot.Mic,
                EncounterControlSlot.Camera,
                hand,
                EncounterControlSlot.Reaction(EncounterReactionOption.AMEN),
                EncounterControlSlot.Chat,
                EncounterControlSlot.More,
                EncounterControlSlot.Scene,
                broadcast,
                doors,
                leave,
            )
        assertTrue(devicesAreFirst(full) && encounterLeaveIsLast(full) && doorsImmediatelyBeforeLeave(full))
        assertTrue(moderationNeverAdjacentToReactions(full))
    }

    // ── the arithmetic at phone widths: 46 px controls, the bar's gap 4 px below 768 px, 16 px padding ───────────────────────

    private fun overflowAt(
        barWidth: Double,
        shown: List<EncounterControlSlot>,
        order: List<EncounterControlSlot>,
    ): Set<EncounterControlSlot> =
        controlBarOverflow(
            available = barWidth - 16.0,
            visible = shown.map { ControlMeasure(it, CONTROL_BAR_FALLBACK_WIDTH_PX, encounterControlGroup(it).ordinal) },
            order = order,
            gap = 4.0,
            dividerWidth = CONTROL_BAR_DIVIDER_FOOTPRINT_PX,
            exitGroup = EncounterControlGroup.EXIT.ordinal,
            exitGap = CONTROL_BAR_EXIT_GAP_PX,
            moreWidth = CONTROL_BAR_FALLBACK_WIDTH_PX,
            moreGroup = EncounterControlGroup.PANELS.ordinal,
        )

    private val pulpitPerson =
        listOf(
            hand,
            EncounterControlSlot.Mic,
            EncounterControlSlot.Camera,
            EncounterControlSlot.Chat,
            EncounterControlSlot.Scene,
            EncounterControlSlot.Fullscreen,
            leave,
        )

    @Test
    fun at360px_aPulpitPerson_keepsHandDevicesChatAndLeave_andOnlyViewMovesAway() {
        val moved = overflowAt(360.0, pulpitPerson, encounterOverflowOrder(emptySet()))
        assertEquals(setOf<EncounterControlSlot>(EncounterControlSlot.Fullscreen, EncounterControlSlot.Scene), moved)
    }

    @Test
    fun at320px_theChatMovesIntoTheSheet_butLeaveNeverDoes() {
        val moved = overflowAt(320.0, pulpitPerson, encounterOverflowOrder(emptySet()))
        assertTrue(EncounterControlSlot.Chat in moved)
        assertFalse(leave in moved)
        assertFalse(hand in moved)
    }

    @Test
    fun aModerator_losesTheDoorsBeforeTheChat() {
        val shown = pulpitPerson.dropLast(1) + listOf(broadcast, doors, leave)
        val order = encounterOverflowOrder(emptySet())
        var widest = 0.0
        // sweep the widths: whenever the chat has moved, the doors have moved too (and the other way round never forces the chat)
        for (width in 300..900 step 10) {
            val moved = overflowAt(width.toDouble(), shown, order)
            if (EncounterControlSlot.Chat in moved) assertTrue(doors in moved, "width $width: the doors go before the chat")
            if (doors !in moved) widest = width.toDouble()
            assertFalse(leave in moved, "width $width: Verlassen stays")
        }
        assertTrue(widest > 0.0)
    }
}
