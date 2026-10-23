package network.lapis.cloud.client.encounter

import network.lapis.cloud.client.CONTROL_BAR_DIVIDER_FOOTPRINT_PX
import network.lapis.cloud.client.CONTROL_BAR_EXIT_GAP_PX
import network.lapis.cloud.client.CONTROL_BAR_FALLBACK_WIDTH_PX
import network.lapis.cloud.client.ControlMeasure
import network.lapis.cloud.client.RoomBarZone
import network.lapis.cloud.client.RoomControlBar
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
        assertEquals(EncounterControlGroup.SETTINGS, encounterControlGroup(EncounterControlSlot.AudioDevices))
        assertEquals(EncounterControlGroup.LITURGY, encounterControlGroup(EncounterControlSlot.Blessing))
        assertEquals(EncounterControlGroup.PANELS, encounterControlGroup(EncounterControlSlot.Chat))
        assertEquals(EncounterControlGroup.MORE, encounterControlGroup(EncounterControlSlot.More))
        assertEquals(EncounterControlGroup.VIEW, encounterControlGroup(EncounterControlSlot.Scene))
        assertEquals(EncounterControlGroup.VIEW, encounterControlGroup(EncounterControlSlot.Fullscreen))
        assertEquals(EncounterControlGroup.MODERATION, encounterControlGroup(broadcast))
        assertEquals(EncounterControlGroup.EXIT, encounterControlGroup(doors))
        assertEquals(EncounterControlGroup.EXIT, encounterControlGroup(leave))
        assertEquals(EncounterControlGroup.EXIT, EncounterControlGroup.entries.last(), "the exit group is the last group of the DOM")
        assertEquals(
            EncounterControlGroup.SETTINGS,
            EncounterControlGroup.entries.first(),
            "the settings gear is the first group of the DOM",
        )
    }

    @Test
    fun theZones_gearAtTheStart_exitAtTheEnd_everythingElseInTheCentre() {
        assertEquals(RoomBarZone.START, encounterControlZone(EncounterControlSlot.AudioDevices))
        assertEquals(RoomBarZone.END, encounterControlZone(leave))
        assertEquals(RoomBarZone.END, encounterControlZone(doors))
        listOf(
            hand,
            EncounterControlSlot.Mic,
            EncounterControlSlot.Camera,
            EncounterControlSlot.Chat,
            EncounterControlSlot.Scene,
            EncounterControlSlot.Recording,
            broadcast,
            EncounterControlSlot.More,
        ).forEach { assertEquals(RoomBarZone.CENTRE, encounterControlZone(it), "$it") }
        // along the DOM order of the groups the zones never go backwards: START, then CENTRE, then END
        val zones = EncounterControlGroup.entries.map { encounterControlZone(it).ordinal }
        assertEquals(zones.sorted(), zones)
    }

    @Test
    fun theOverflowOrder_isViewThenTransmissionThenEventsBackwards_thenChat_thenDoorsAndTheDevices() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries.toSet())
        val events = EncounterReactionOption.entries.filter { it != EncounterReactionOption.ALWAYS_ON }.reversed()
        assertEquals(
            listOf(EncounterControlSlot.Fullscreen, EncounterControlSlot.Scene, broadcast) +
                events.map { EncounterControlSlot.Reaction(it) } +
                listOf(
                    EncounterControlSlot.Chat,
                    // V1.9.184: the way out and the devices go last: doors, gear, camera, microphone
                    doors,
                    EncounterControlSlot.AudioDevices,
                    EncounterControlSlot.Camera,
                    EncounterControlSlot.Mic,
                ),
            order,
        )
    }

    @Test
    fun withAllNineOptions_thereAreEightReactionSlotsPlusTheHand_andTheyOverflowInto_Mehr() {
        val all = EncounterReactionOption.entries
        val order = encounterOverflowOrder(all)
        assertEquals(8, order.filterIsInstance<EncounterControlSlot.Reaction>().size)
        val shown = listOf(hand) + all.filter { it != EncounterReactionOption.HAND }.map { EncounterControlSlot.Reaction(it) } + leave
        val moved = overflowAt(360.0, shown, order)
        assertTrue(moved.isNotEmpty() && moved.all { it != hand && it != leave })
    }

    @Test
    fun theOverflowOrder_followsTheRoomsReactionSet() {
        val order = encounterOverflowOrder(EncounterReactionOption.defaultsFor(EncounterProfile.CHURCH_SERVICE))
        assertEquals(
            listOf(EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)),
            order.filterIsInstance<EncounterControlSlot.Reaction>(),
        )
        assertEquals(
            listOf(
                EncounterControlSlot.Chat,
                doors,
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Camera,
                EncounterControlSlot.Mic,
            ),
            order.takeLast(5),
        )
    }

    @Test
    fun neverMoved_handMoreAndLeave_theDevicesMoveLastOfAll() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries.toSet())
        listOf(hand, EncounterControlSlot.More, leave).forEach {
            assertFalse(it in order, "$it never moves into the sheet")
        }
        // V1.9.184: the gear, the camera and, very last, the microphone move -- every one of them has a twin in the sheet
        assertEquals(
            listOf(EncounterControlSlot.AudioDevices, EncounterControlSlot.Camera, EncounterControlSlot.Mic),
            order.takeLast(3),
        )
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
    fun settingsIsFirst_positiveAndNegative() {
        val reaction = EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)
        val gear = EncounterControlSlot.AudioDevices
        val mic = EncounterControlSlot.Mic
        val camera = EncounterControlSlot.Camera
        assertTrue(settingsIsFirst(listOf(gear, mic, camera, hand, reaction, EncounterControlSlot.Chat, leave)))
        assertTrue(settingsIsFirst(listOf(hand, EncounterControlSlot.Chat, leave)), "no gear at all (moved into the sheet) is fine")
        assertFalse(settingsIsFirst(listOf(mic, gear, camera, hand, leave)), "the gear never stands behind another control")
        assertFalse(settingsIsFirst(listOf(hand, gear, leave)))
    }

    @Test
    fun moreImmediatelyBeforeEnd_positiveAndNegative() {
        val more = EncounterControlSlot.More
        assertTrue(moreImmediatelyBeforeEnd(listOf(hand, EncounterControlSlot.Chat, broadcast, more, doors, leave)))
        assertTrue(moreImmediatelyBeforeEnd(listOf(hand, more, leave)))
        assertTrue(moreImmediatelyBeforeEnd(listOf(hand, EncounterControlSlot.Chat, leave)), "no 'Mehr' shown: nothing to check")
        assertFalse(moreImmediatelyBeforeEnd(listOf(hand, more, EncounterControlSlot.Chat, leave)))
        assertFalse(moreImmediatelyBeforeEnd(listOf(more, hand, doors, leave)))
    }

    @Test
    fun theRecordingAndTheTransmission_neverStandNextToAReactionOrAnExitControl_inTheirOwnZone() {
        val reaction = EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)
        val recording = EncounterControlSlot.Recording
        val chat = EncounterControlSlot.Chat
        val more = EncounterControlSlot.More
        assertTrue(moderationToolsNeverAdjacentToExitOrReactions(listOf(hand, reaction, chat, recording, broadcast, more, doors, leave)))
        // the end zone is a zone of its own: "transmission, then the doors" is the intended layout while nothing is in the sheet
        assertTrue(moderationToolsNeverAdjacentToExitOrReactions(listOf(hand, chat, broadcast, doors, leave)))
        assertFalse(moderationToolsNeverAdjacentToExitOrReactions(listOf(hand, recording, chat)), "the recording right behind a reaction")
        assertFalse(
            moderationToolsNeverAdjacentToExitOrReactions(listOf(reaction, broadcast, more)),
            "the transmission right behind a reaction",
        )
        assertFalse(moderationToolsNeverAdjacentToExitOrReactions(listOf(chat, broadcast, hand)))
    }

    @Test
    fun theGear_movesIntoTheSheet_afterTheDoors_andBeforeTheCamera() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries.toSet())
        assertTrue(order.indexOf(doors) < order.indexOf(EncounterControlSlot.AudioDevices))
        assertTrue(order.indexOf(EncounterControlSlot.AudioDevices) < order.indexOf(EncounterControlSlot.Camera))
        assertTrue(order.indexOf(EncounterControlSlot.Camera) < order.indexOf(EncounterControlSlot.Mic))
    }

    @Test
    fun aFullPulpitList_isFramedByTheGearAndLeave_andKeepsTheInvariants() {
        val full =
            listOf(
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Mic,
                EncounterControlSlot.Camera,
                hand,
                EncounterControlSlot.Reaction(EncounterReactionOption.AMEN),
                EncounterControlSlot.Chat,
                EncounterControlSlot.Scene,
                EncounterControlSlot.Recording,
                broadcast,
                EncounterControlSlot.More,
                doors,
                leave,
            )
        assertTrue(
            settingsIsFirst(full) && encounterLeaveIsLast(full) && doorsImmediatelyBeforeLeave(full) && moreImmediatelyBeforeEnd(full),
        )
        assertTrue(moderationNeverAdjacentToReactions(full))
        assertTrue(moderationToolsNeverAdjacentToExitOrReactions(full))
    }

    @Test
    fun theRoomBarsZoneSeparators_areReservedInTheWidthModel() {
        // two separators (gear | centre | exit): 16 + 22 px beyond the 21 px per group boundary the model already counts
        assertEquals(38.0, RoomControlBar.ZONE_SEPARATORS_RESERVED_PX)
    }

    // ── the arithmetic at phone widths: 46 px controls, the bar's gap 4 px below 768 px, 16 px padding ───────────────────────

    private fun overflowAt(
        barWidth: Double,
        shown: List<EncounterControlSlot>,
        order: List<EncounterControlSlot>,
    ): Set<EncounterControlSlot> =
        controlBarOverflow(
            available = barWidth - 16.0 - RESERVED,
            visible = shown.map { ControlMeasure(it, CONTROL_BAR_FALLBACK_WIDTH_PX, encounterControlGroup(it).ordinal) },
            order = order,
            gap = 4.0,
            dividerWidth = CONTROL_BAR_DIVIDER_FOOTPRINT_PX,
            exitGroup = EncounterControlGroup.EXIT.ordinal,
            exitGap = CONTROL_BAR_EXIT_GAP_PX,
            moreWidth = CONTROL_BAR_FALLBACK_WIDTH_PX,
            moreGroup = EncounterControlGroup.MORE.ordinal,
        )

    private companion object {
        /** The two zone separators beyond the group dividers the model already counts (`ControlBarOverflow.reservedWidth`). */
        val RESERVED = RoomControlBar.ZONE_SEPARATORS_RESERVED_PX
    }

    private val pulpitPerson =
        listOf(
            EncounterControlSlot.AudioDevices,
            EncounterControlSlot.Mic,
            EncounterControlSlot.Camera,
            hand,
            EncounterControlSlot.Chat,
            EncounterControlSlot.Scene,
            EncounterControlSlot.Fullscreen,
            leave,
        )

    @Test
    fun at360px_aPulpitPerson_keepsHandMicrophoneCameraAndLeave_viewChatAndGearMoveAway() {
        // V1.9.184: the gear and the two zone separators take room; the order is view, chat, gear -- the devices go last
        val moved = overflowAt(360.0, pulpitPerson, encounterOverflowOrder(emptySet()))
        assertEquals(
            setOf(
                EncounterControlSlot.Fullscreen,
                EncounterControlSlot.Scene,
                EncounterControlSlot.Chat,
                EncounterControlSlot.AudioDevices,
            ),
            moved,
        )
        assertFalse(hand in moved || leave in moved || EncounterControlSlot.Mic in moved || EncounterControlSlot.Camera in moved)
    }

    @Test
    fun at320px_theGearAndTheCameraMoveIntoTheSheet_butMicrophoneHandAndLeaveStay() {
        val moved = overflowAt(320.0, pulpitPerson, encounterOverflowOrder(emptySet()))
        assertTrue(EncounterControlSlot.Chat in moved && EncounterControlSlot.AudioDevices in moved && EncounterControlSlot.Camera in moved)
        assertFalse(EncounterControlSlot.Mic in moved, "the microphone is the very last to go")
        assertFalse(leave in moved)
        assertFalse(hand in moved)
    }

    @Test
    fun aModerator_losesTheChatBeforeTheDoors() {
        val shown = pulpitPerson.dropLast(1) + listOf(broadcast, doors, leave)
        val order = encounterOverflowOrder(emptySet())
        var widest = 0.0
        // sweep the widths: whenever the doors have moved, the chat has moved too (V1.9.184: the way out is the last thing to go)
        for (width in 300..900 step 10) {
            val moved = overflowAt(width.toDouble(), shown, order)
            if (doors in moved) assertTrue(EncounterControlSlot.Chat in moved, "width $width: the chat goes before the doors")
            if (doors !in moved) widest = width.toDouble()
            assertFalse(leave in moved, "width $width: Verlassen stays")
        }
        assertTrue(widest > 0.0)
    }

    // ── V1.9.95: the blessing ──────────────────────────────────────────────────────────────────────────────────────────────────

    private val blessing = EncounterControlSlot.Blessing

    @Test
    fun theGroupsStandInOrder_theGearFirst_theRecordingBehindTheView_moreBeforeTheExit() {
        assertEquals(
            listOf(
                EncounterControlGroup.SETTINGS,
                EncounterControlGroup.DEVICES,
                EncounterControlGroup.REACTIONS,
                EncounterControlGroup.LITURGY,
                EncounterControlGroup.PANELS,
                EncounterControlGroup.VIEW,
                EncounterControlGroup.RECORDING,
                EncounterControlGroup.MODERATION,
                EncounterControlGroup.MORE,
                EncounterControlGroup.EXIT,
            ),
            EncounterControlGroup.entries,
        )
    }

    @Test
    fun theOverflowOrder_putsTheBlessingBeforeTheChat_onlyWhenThereIsOne() {
        val withBlessing = encounterOverflowOrder(EncounterReactionOption.entries.toSet(), blessing = true)
        val withoutBlessing = encounterOverflowOrder(EncounterReactionOption.entries.toSet(), blessing = false)
        assertEquals(
            listOf(
                blessing,
                EncounterControlSlot.Chat,
                doors,
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Camera,
                EncounterControlSlot.Mic,
            ),
            withBlessing.takeLast(6),
        )
        assertFalse(blessing in withoutBlessing)
        assertEquals(withoutBlessing, withBlessing.filterNot { it == blessing })
        assertFalse(blessing in encounterOverflowOrder(emptySet()), "the default is no blessing")
    }

    @Test
    fun theMapButton_isInThePanelsGroup_andMovesIntoTheSheetJustBeforeTheChat_onlyForARoomWithAMap() {
        val map = EncounterControlSlot.Map
        assertEquals(EncounterControlGroup.PANELS, encounterControlGroup(map))
        val withMap = encounterOverflowOrder(EncounterReactionOption.entries.toSet(), blessing = true, recording = true, map = true)
        val withoutMap = encounterOverflowOrder(EncounterReactionOption.entries.toSet(), blessing = true, recording = true)
        val tail =
            listOf(
                EncounterControlSlot.Chat,
                doors,
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Camera,
                EncounterControlSlot.Mic,
            )
        assertEquals(listOf(map) + tail, withMap.takeLast(6), "the map goes right before the chat, which leads the tail")
        assertFalse(map in withoutMap, "the default is no map")
        assertEquals(withoutMap, withMap.filterNot { it == map })
        // the map button is not a device: the gear still comes first with it in the list
        assertTrue(
            settingsIsFirst(
                listOf(
                    EncounterControlSlot.AudioDevices,
                    EncounterControlSlot.Mic,
                    EncounterControlSlot.Camera,
                    hand,
                    EncounterControlSlot.Chat,
                    map,
                    EncounterControlSlot.More,
                    leave,
                ),
            ),
        )
    }

    @Test
    fun theBlessingNeverStandsNextToTheModeration_positiveAndNegative() {
        val reaction = EncounterControlSlot.Reaction(EncounterReactionOption.AMEN)
        val chat = EncounterControlSlot.Chat
        assertTrue(blessingNeverAdjacentToModeration(listOf(hand, reaction, blessing, chat, broadcast, doors, leave)))
        assertFalse(blessingNeverAdjacentToModeration(listOf(hand, blessing, broadcast, leave)))
        assertFalse(blessingNeverAdjacentToModeration(listOf(hand, broadcast, blessing, leave)))
    }

    @Test
    fun theOldInvariants_holdOnAPulpitListWithTheBlessing() {
        val full =
            listOf(
                EncounterControlSlot.AudioDevices,
                EncounterControlSlot.Mic,
                EncounterControlSlot.Camera,
                hand,
                EncounterControlSlot.Reaction(EncounterReactionOption.AMEN),
                blessing,
                EncounterControlSlot.Chat,
                EncounterControlSlot.Scene,
                broadcast,
                EncounterControlSlot.More,
                doors,
                leave,
            )
        assertTrue(
            settingsIsFirst(full) && encounterLeaveIsLast(full) && doorsImmediatelyBeforeLeave(full) && moreImmediatelyBeforeEnd(full),
        )
        assertTrue(moderationNeverAdjacentToReactions(full))
        assertTrue(blessingNeverAdjacentToModeration(full))
    }

    @Test
    fun at320px_aPulpitPerson_losesTheBlessingBeforeTheChat_andNeverHandOrLeave() {
        val shown = pulpitPerson.dropLast(1) + listOf(blessing, leave)
        val order = encounterOverflowOrder(emptySet(), blessing = true)
        for (width in 300..900 step 10) {
            val moved = overflowAt(width.toDouble(), shown, order)
            if (EncounterControlSlot.Chat in moved) assertTrue(blessing in moved, "width $width: the blessing goes before the chat")
            assertFalse(leave in moved, "width $width: Verlassen stays")
            assertFalse(hand in moved, "width $width: the hand stays")
        }
    }
}
