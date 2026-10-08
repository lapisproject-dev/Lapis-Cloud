package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V1.9.74: the generic overflow arithmetic ([controlBarOverflow]) that the conference bar and the encounter room's bar share. The
 * conference's own expectations stay in `ConferenceControlBarTest` (unchanged); this file pins what the generalisation added: a slot type
 * of its own and the width of a "Mehr" control that only appears once something has moved.
 */
class ControlBarOverflowTest {
    private val gap = 6.0
    private val divider = CONTROL_BAR_DIVIDER_FOOTPRINT_PX
    private val exitGroup = 1

    /** A, B in group 0; C, D in the exit group 1. All 46 px wide. */
    private fun measures(vararg skip: String): List<ControlMeasure<String>> =
        listOf(
            ControlMeasure("A", 46.0, 0),
            ControlMeasure("B", 46.0, 0),
            ControlMeasure("C", 46.0, 1),
            ControlMeasure("D", 46.0, 1),
        ).filter { it.slot !in skip }

    private fun run(
        available: Double,
        order: List<String>,
        moreWidth: Double = 0.0,
        visible: List<ControlMeasure<String>> = measures(),
    ): Set<String> =
        controlBarOverflow(
            available = available,
            visible = visible,
            order = order,
            gap = gap,
            dividerWidth = divider,
            exitGroup = exitGroup,
            moreWidth = moreWidth,
            moreGroup = 0,
        )

    // 4 * 46 + 1 divider + 4 gaps (5 items - 1... 4 controls + 1 divider = 5 items -> 4 gaps of 6) + exit extra (12 - 6) = 223
    private val everything = 4 * 46.0 + divider + gap * 4 + (CONTROL_BAR_EXIT_GAP_PX - gap)

    @Test
    fun whenEverythingFits_nothingMoves() {
        assertEquals(emptySet(), run(everything, listOf("B", "A")))
    }

    @Test
    fun slotsMoveStrictlyInTheGivenOrder_untilTheRestFits() {
        assertEquals(setOf("B"), run(everything - 1, listOf("B", "A")))
        assertEquals(setOf("A"), run(everything - 1, listOf("A", "B")))
    }

    @Test
    fun aSlotThatIsNotShown_isSkipped_andNeverMoved() {
        assertEquals(setOf("A"), run(everything - 60, listOf("B", "A"), visible = measures("B")))
    }

    @Test
    fun theMoreControl_costsRoomOnceTheFirstSlotHasMoved() {
        // Without a "Mehr" control moving B is enough; with one it takes B's room again, so A has to follow.
        assertEquals(setOf("B"), run(everything - 1, listOf("B", "A"), moreWidth = 0.0))
        assertEquals(setOf("B", "A"), run(everything - 1, listOf("B", "A"), moreWidth = 46.0))
    }

    @Test
    fun theMoreControl_isCountedAsAGroupOfItsOwn_whenItsGroupHasNoShownSlot() {
        // A and B (group 0) moved, "Mehr" (group 0) stands there alone with C and D: it needs its divider and its gap, 171 px in all.
        // Without that group the same row would measure 156 px and fit into 160, so C would (wrongly) stay.
        assertEquals(setOf("B", "A", "C"), run(available = 160.0, order = listOf("B", "A", "C"), moreWidth = 46.0))
        assertEquals(setOf("B", "A"), run(available = 171.0, order = listOf("B", "A", "C"), moreWidth = 46.0))
    }

    @Test
    fun anUnreachableWidth_movesEverythingInTheOrder_andNothingElse() {
        assertEquals(setOf("B", "A"), run(1.0, listOf("B", "A"), moreWidth = 46.0))
    }
}
