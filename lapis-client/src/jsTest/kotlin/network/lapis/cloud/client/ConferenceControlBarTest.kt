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
                add(ConferenceControlSlot.END_FOR_ALL)
            }
            add(ConferenceControlSlot.ROSTER)
            add(ConferenceControlSlot.CHAT)
            if (vote) add(ConferenceControlSlot.VOTE)
            add(ConferenceControlSlot.MORE)
            if (back) add(ConferenceControlSlot.BACK)
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
        val total = shown.sumOf { it.width } + (groups - 1) * divider + gap * (shown.size + groups - 2)
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
    fun endForAll_isNeverAdjacentToLeave_forEveryWidthAndRole() {
        val domOrder = ConferenceControlSlot.entries
        for (moderator in listOf(true, false)) {
            for (screen in listOf(true, false)) {
                for (vote in listOf(true, false)) {
                    for (back in listOf(true, false)) {
                        var width = 300
                        while (width <= 1200) {
                            val all = measures(moderator, screen, vote, back)
                            val moved = conferenceControlsOverflow(width.toDouble(), all, gap, divider)
                            val shown = domOrder.filter { slot -> all.any { it.slot == slot } && slot !in moved }
                            assertFalse(endForAllAdjacentToLeave(shown), "width $width moderator $moderator screen $screen vote $vote")
                            width += 10
                        }
                    }
                }
            }
        }
    }

    @Test
    fun endForAllAdjacentToLeave_detectsAdjacency() {
        assertTrue(endForAllAdjacentToLeave(listOf(ConferenceControlSlot.END_FOR_ALL, ConferenceControlSlot.LEAVE)))
        assertTrue(endForAllAdjacentToLeave(listOf(ConferenceControlSlot.LEAVE, ConferenceControlSlot.END_FOR_ALL)))
        assertFalse(
            endForAllAdjacentToLeave(
                listOf(ConferenceControlSlot.END_FOR_ALL, ConferenceControlSlot.CHAT, ConferenceControlSlot.LEAVE),
            ),
        )
        assertFalse(endForAllAdjacentToLeave(listOf(ConferenceControlSlot.LEAVE)))
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
