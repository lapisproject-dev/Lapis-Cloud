package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.66: the pure core of the conference control bar (overflow order, toggle state model, "Mehr" naming). */
class ConferenceControlBarTest {
    private val gap = 6.0
    private val divider = CONFERENCE_DIVIDER_FOOTPRINT_PX
    private val control = 46.0

    private fun measures(
        moderator: Boolean = true,
        screen: Boolean = true,
        vote: Boolean = true,
        back: Boolean = false,
    ): List<ConferenceControlMeasure> =
        buildList {
            add(ConferenceControlSlot.MIC)
            add(ConferenceControlSlot.CAMERA)
            if (screen) add(ConferenceControlSlot.SCREEN)
            if (moderator) {
                add(ConferenceControlSlot.RECORD)
                add(ConferenceControlSlot.STREAM)
            }
            add(ConferenceControlSlot.ROSTER)
            add(ConferenceControlSlot.CHAT)
            if (vote) add(ConferenceControlSlot.VOTE)
            add(ConferenceControlSlot.MORE)
            if (back) add(ConferenceControlSlot.BACK)
            // V1.9.72: "Für alle beenden" belongs to the exit group, directly before "Verlassen" (main room only).
            if (moderator && !back) add(ConferenceControlSlot.END_FOR_ALL)
            add(ConferenceControlSlot.LEAVE)
        }.map { ConferenceControlMeasure(it, control, conferenceControlGroup(it)) }

    private val never =
        listOf(
            ConferenceControlSlot.MIC,
            ConferenceControlSlot.CAMERA,
            ConferenceControlSlot.CHAT,
            ConferenceControlSlot.MORE,
            ConferenceControlSlot.BACK,
            ConferenceControlSlot.LEAVE,
        )

    @Test
    fun anEmptyButRenderedGroupWrapper_stillCostsAGap() {
        // Without the wrapper the total is smaller, so a width that fits without it must overflow with it.
        val base = measures(moderator = false)
        val total = base.sumOf { it.width } + (base.map { it.group }.distinct().size - 1) * divider + gap * (base.size + 2 - 1)
        // moderation group wrapper (group 1) rendered but empty: one more flex item, one more gap.
        val moved = conferenceControlsOverflow(total, base, gap, divider, containerGroups = setOf(1))
        assertTrue(moved.isNotEmpty(), "the empty wrapper's gap must be counted")
        assertEquals(emptySet(), conferenceControlsOverflow(total, base, gap, divider))
    }

    @Test
    fun wideScreen_movesNothing() {
        assertEquals(emptySet(), conferenceControlsOverflow(1200.0, measures(), gap, divider))
    }

    @Test
    fun overflow_movesStrictlyInThePriorityOrder_asAPrefix() {
        // Walk the width down: whatever moves is always a PREFIX of the order (vote, screen, roster, stream, record, end).
        // End for everyone moves LAST (V1.9.72): it is the control next to "Verlassen".
        var width = 1200.0
        while (width >= 200.0) {
            val moved = conferenceControlsOverflow(width, measures(), gap, divider)
            assertEquals(CONFERENCE_OVERFLOW_ORDER.take(moved.size).toSet(), moved, "width $width")
            width -= 10.0
        }
    }

    @Test
    fun overflow_movesMoreWhenNarrower_andNeverTheFixedControls() {
        var previous = 0
        var width = 1200.0
        while (width >= 200.0) {
            val moved = conferenceControlsOverflow(width, measures(back = true), gap, divider)
            assertTrue(moved.size >= previous, "narrower never moves less (width $width)")
            previous = moved.size
            never.forEach { assertFalse(it in moved, "$it must never move (width $width)") }
            width -= 10.0
        }
    }

    @Test
    fun at344px_theBarFitsAfterMoving_andTheOrderIsTheSpecifiedOne() {
        val moved = conferenceControlsOverflow(344.0, measures(), gap, divider)
        val order = CONFERENCE_OVERFLOW_ORDER
        assertEquals(order.take(moved.size).toSet(), moved)
        assertTrue(ConferenceControlSlot.VOTE in moved && ConferenceControlSlot.SCREEN in moved && ConferenceControlSlot.ROSTER in moved)
        assertTrue(ConferenceControlSlot.STREAM in moved, "the stream control moves before the recording control")
        // What stays fits: sum of widths + gaps + dividers <= available.
        val shown = measures().filter { it.slot !in moved }
        val groups = shown.map { it.group }.distinct().size
        val exitExtra = (CONFERENCE_EXIT_GAP_PX - gap) * maxOf(0, shown.count { it.group == CONFERENCE_EXIT_GROUP } - 1)
        val total = shown.sumOf { it.width } + (groups - 1) * divider + gap * (shown.size + groups - 2) + exitExtra
        assertTrue(total <= 344.0, "total $total")
    }

    @Test
    fun aPlainParticipant_hasNothingModerationToMove() {
        val moved = conferenceControlsOverflow(250.0, measures(moderator = false), gap, divider)
        assertFalse(
            moved.any { it in listOf(ConferenceControlSlot.RECORD, ConferenceControlSlot.STREAM, ConferenceControlSlot.END_FOR_ALL) },
        )
    }

    @Test
    fun dividers_countOnlyBetweenNonEmptyGroups() {
        // Two controls in two groups: ONE divider is counted (2*46 + 1*9 + gap*(3-1)).
        val two =
            listOf(
                ConferenceControlMeasure(ConferenceControlSlot.MIC, control, 0),
                ConferenceControlMeasure(ConferenceControlSlot.LEAVE, control, 3),
            )
        val exact = 2 * control + divider + gap * 2
        assertEquals(emptySet(), conferenceControlsOverflow(exact, two, gap, divider))
        // The moderation group is empty (a participant): 3 groups, 2 dividers, not 3.
        val participant = measures(moderator = false, screen = false, vote = false)
        val groups = participant.map { it.group }.distinct().size
        assertEquals(3, groups)
    }

    @Test
    fun endForAll_isImmediatelyBeforeLeave_forEveryWidthAndRole() {
        val domOrder = ConferenceControlSlot.entries
        var checked = 0
        for (moderator in listOf(true, false)) {
            for (screen in listOf(true, false)) {
                for (vote in listOf(true, false)) {
                    for (back in listOf(true, false)) {
                        var width = 200
                        while (width <= 1200) {
                            val all = measures(moderator, screen, vote, back)
                            val moved = conferenceControlsOverflow(width.toDouble(), all, gap, divider)
                            val shown = domOrder.filter { slot -> all.any { it.slot == slot } && slot !in moved }
                            val context = "width $width moderator $moderator screen $screen vote $vote back $back: $shown"
                            assertTrue(endForAllImmediatelyBeforeLeave(shown), context)
                            assertTrue(leaveIsLast(shown), context)
                            checked++
                            width += 10
                        }
                    }
                }
            }
        }
        assertTrue(checked > 500, "the sweep really ran: $checked")
    }

    @Test
    fun endForAllImmediatelyBeforeLeave_detectsAWrongOrder() {
        val end = ConferenceControlSlot.END_FOR_ALL
        val leave = ConferenceControlSlot.LEAVE
        assertTrue(endForAllImmediatelyBeforeLeave(listOf(ConferenceControlSlot.CHAT, end, leave)))
        assertTrue(endForAllImmediatelyBeforeLeave(listOf(ConferenceControlSlot.BACK, end, leave)))
        assertTrue(endForAllImmediatelyBeforeLeave(listOf(ConferenceControlSlot.CHAT, leave)), "no end control: only LEAVE-last counts")
        // Beenden -> Chat -> Verlassen
        assertFalse(endForAllImmediatelyBeforeLeave(listOf(end, ConferenceControlSlot.CHAT, leave)))
        // Verlassen vor Beenden
        assertFalse(endForAllImmediatelyBeforeLeave(listOf(leave, end)))
        assertFalse(endForAllImmediatelyBeforeLeave(listOf(ConferenceControlSlot.CHAT, end)), "no LEAVE at all")
        assertFalse(leaveIsLast(listOf(leave, ConferenceControlSlot.CHAT)))
        assertTrue(leaveIsLast(listOf(ConferenceControlSlot.CHAT, leave)))
    }

    @Test
    fun endForAll_movesLast_andLeaveAndBackNever() {
        assertEquals(ConferenceControlSlot.END_FOR_ALL, CONFERENCE_OVERFLOW_ORDER.last())
        assertFalse(ConferenceControlSlot.LEAVE in CONFERENCE_OVERFLOW_ORDER)
        assertFalse(ConferenceControlSlot.BACK in CONFERENCE_OVERFLOW_ORDER)
        // The narrowest width still keeps LEAVE; END_FOR_ALL is gone only after everything else that can move has moved.
        val moved = conferenceControlsOverflow(60.0, measures(), gap, divider)
        assertEquals(CONFERENCE_OVERFLOW_ORDER.toSet(), moved)
        assertFalse(ConferenceControlSlot.LEAVE in moved)
    }

    @Test
    fun theExitGroup_isGroupThree_andEndForAllNoLongerSharesTheModerationGroup() {
        assertEquals(3, conferenceControlGroup(ConferenceControlSlot.END_FOR_ALL))
        assertEquals(3, conferenceControlGroup(ConferenceControlSlot.LEAVE))
        assertEquals(3, conferenceControlGroup(ConferenceControlSlot.BACK))
        assertEquals(1, conferenceControlGroup(ConferenceControlSlot.RECORD))
        assertEquals(1, conferenceControlGroup(ConferenceControlSlot.STREAM))
    }

    @Test
    fun exitGap_isCounted_exactFitStays_oneLessMovesEndForAll() {
        // Only the exit group remains besides the fixed controls: shown = MIC, CAMERA, CHAT, MORE (groups 0, 2) + END, LEAVE (group 3).
        val fixed =
            listOf(
                ConferenceControlSlot.MIC,
                ConferenceControlSlot.CAMERA,
                ConferenceControlSlot.CHAT,
                ConferenceControlSlot.MORE,
                ConferenceControlSlot.END_FOR_ALL,
                ConferenceControlSlot.LEAVE,
            ).map { ConferenceControlMeasure(it, control, conferenceControlGroup(it)) }
        // 6 controls, 3 groups -> 2 dividers, 5 + 2 = 7 items... items = shown(6) + (groups-1)(2) = 8, gaps = 7; one exit gap is 12, not 6.
        val exact = 6 * control + 2 * divider + gap * 7 + (CONFERENCE_EXIT_GAP_PX - gap)
        assertEquals(emptySet(), conferenceControlsOverflow(exact, fixed, gap, divider), "an exact fit moves nothing")
        assertEquals(
            setOf(ConferenceControlSlot.END_FOR_ALL),
            conferenceControlsOverflow(exact - 1.0, fixed, gap, divider),
            "1 px less: only END_FOR_ALL moves",
        )
        // Without the 12 px gap the same width would still fit -- the gap is really counted.
        assertEquals(emptySet(), conferenceControlsOverflow(exact - 1.0, fixed, gap, divider, exitGap = gap))
    }

    // ── toggle state model ───────────────────────────────────────────────────────────────────────

    private fun resolved(text: String) = text.removePrefix("###KvI18nS###")

    @Test
    fun recording_mapsEveryStatus() {
        val idle = recordingToggleView(null, canStart = true)
        assertTrue(idle.visible && !idle.pressed && !idle.disabled && !idle.busy)
        assertEquals("Aufzeichnung starten", resolved(idle.title))
        assertTrue(recordingToggleView(null, canStart = false).disabled, "cannot start: disabled")

        val recording = recordingToggleView(ConferenceRecordingStatus.RECORDING, canStart = false)
        assertTrue(recording.pressed && !recording.disabled && !recording.busy)
        assertEquals("Aufzeichnung beenden", resolved(recording.title))

        val stopping = recordingToggleView(ConferenceRecordingStatus.STOPPING, canStart = false)
        assertTrue(stopping.pressed && stopping.disabled && stopping.busy)
        assertEquals("Aufzeichnung wird beendet …", resolved(stopping.progressText!!))

        val processing = recordingToggleView(ConferenceRecordingStatus.PROCESSING, canStart = false)
        assertTrue(!processing.pressed && processing.disabled && processing.busy, "not recording any more, not clickable")
        assertEquals("Aufzeichnung wird zusammengeführt …", resolved(processing.progressText!!))
        assertNull(idle.progressText)
    }

    @Test
    fun stream_mapsEveryStatus_likeTheOldButtons() {
        for (status in listOf(null, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED)) {
            val v = streamToggleView(status, null, canStart = true)
            assertTrue(v.visible && !v.pressed && !v.disabled && !v.busy, "$status")
            assertEquals("Live-Stream starten …", resolved(v.title))
            assertTrue(streamToggleView(status, null, canStart = false).disabled)
        }
        for (
        status in
        listOf(
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.PAUSED,
        )
        ) {
            val v = streamToggleView(status, null, canStart = false)
            assertTrue(v.visible && v.pressed && !v.disabled && !v.busy, "$status")
            assertEquals("Live-Stream beenden", resolved(v.title))
        }
        val stopping = streamToggleView(ConferenceStreamStatus.STOPPING, null, canStart = false)
        assertTrue(stopping.pressed && stopping.disabled && stopping.busy)
        assertEquals("Stream wird beendet …", resolved(stopping.progressText!!))
    }

    @Test
    fun stream_secretBallot_hidesTheStartControl_butNotAStopControl() {
        assertFalse(streamToggleView(null, ConferenceStreamPauseReason.SECRET_BALLOT, canStart = true).visible)
        assertFalse(streamToggleView(ConferenceStreamStatus.ENDED, ConferenceStreamPauseReason.SECRET_BALLOT, canStart = true).visible)
        // While paused for a secret ballot the stop control stays (the moderator can still end the stream).
        assertTrue(streamToggleView(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT, canStart = false).visible)
    }

    @Test
    fun streamToggleAction_neverStartsWithoutTheDialog_neverStopsWithoutConfirmation() {
        assertEquals(StreamToggleAction.OPEN_START_DIALOG, conferenceStreamToggleAction(null))
        assertEquals(StreamToggleAction.OPEN_START_DIALOG, conferenceStreamToggleAction(ConferenceStreamStatus.ENDED))
        assertEquals(StreamToggleAction.OPEN_START_DIALOG, conferenceStreamToggleAction(ConferenceStreamStatus.FAILED))
        for (
        status in
        listOf(
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.PAUSED,
        )
        ) {
            assertEquals(StreamToggleAction.CONFIRM_STOP, conferenceStreamToggleAction(status), "$status")
        }
        assertEquals(StreamToggleAction.NONE, conferenceStreamToggleAction(ConferenceStreamStatus.STOPPING))
    }

    @Test
    fun moreLabelAndGlyphs_coverTheFourCombinations() {
        assertEquals("Mehr", resolved(conferenceMoreButtonLabel(false, false)))
        assertEquals("Mehr, Aufzeichnung läuft", resolved(conferenceMoreButtonLabel(true, false)))
        assertEquals("Mehr, Live-Stream läuft", resolved(conferenceMoreButtonLabel(false, true)))
        assertEquals("Mehr, Aufzeichnung und Live-Stream laufen", resolved(conferenceMoreButtonLabel(true, true)))
        assertEquals("", conferenceMoreBadgeGlyphs(false, false))
        assertEquals("●", conferenceMoreBadgeGlyphs(true, false))
        assertEquals("◆", conferenceMoreBadgeGlyphs(false, true))
        assertEquals("●◆", conferenceMoreBadgeGlyphs(true, true))
    }
}
