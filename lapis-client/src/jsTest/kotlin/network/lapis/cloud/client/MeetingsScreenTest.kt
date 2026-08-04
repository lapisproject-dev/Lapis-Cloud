package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Governance UI wave -- covers only the pure, DOM-independent label/color functions factored out
 * of `MeetingsScreen.kt` ([meetingStatusLabel]/[meetingStatusColor]/[meetingFormatLabel]/
 * [attendanceStatusLabel]/[attendanceStatusColor]/[resolutionStatusLabel]/
 * [resolutionStatusColor]/[resolutionModeLabel]/[resolutionModeColor]), same scope posture as
 * [CommitteesScreenTest]/[ValidationTest] (no DOM/rendering test harness exists in this module).
 * Two things asserted per solid-badge enum: every value has a non-blank German label
 * (completeness), and every color returned is one of Bootstrap 5.3.8's real semantic hues (matches
 * `StatusBadge.kt`'s design contract). [resolutionModeColor] is asserted against the same hue set
 * since [network.lapis.cloud.client.typeBadge] reuses Bootstrap's semantic classes too, just
 * unfilled -- see the approved design decisions' exact status/type-badge tables.
 */
class MeetingsScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun meetingStatusLabel_isNonBlankForEveryValue() {
        MeetingStatus.entries.forEach { status ->
            assertTrue(meetingStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun meetingStatusColor_isARealBootstrapHueForEveryValue() {
        MeetingStatus.entries.forEach { status ->
            val color = meetingStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun meetingFormatLabel_isNonBlankForEveryValue() {
        MeetingFormat.entries.forEach { format ->
            assertTrue(meetingFormatLabel(format).isNotBlank(), "expected a non-blank label for $format")
        }
    }

    @Test
    fun attendanceStatusLabel_isNonBlankForEveryValue() {
        AttendanceStatus.entries.forEach { status ->
            assertTrue(attendanceStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun attendanceStatusColor_isARealBootstrapHueForEveryValue() {
        AttendanceStatus.entries.forEach { status ->
            val color = attendanceStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun resolutionStatusLabel_isNonBlankForEveryValue() {
        ResolutionStatus.entries.forEach { status ->
            assertTrue(resolutionStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun resolutionStatusColor_isARealBootstrapHueForEveryValue() {
        ResolutionStatus.entries.forEach { status ->
            val color = resolutionStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun resolutionModeLabel_isNonBlankForEveryValue() {
        ResolutionMode.entries.forEach { mode ->
            assertTrue(resolutionModeLabel(mode).isNotBlank(), "expected a non-blank label for $mode")
        }
    }

    @Test
    fun resolutionModeColor_isARealBootstrapHueForEveryValue() {
        ResolutionMode.entries.forEach { mode ->
            val color = resolutionModeColor(mode)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $mode, got \"$color\"")
        }
    }

    // Design decision D2: `warning` is reserved for the Motions screen's amendment-ordering
    // alert and POSTPONED-shaped statuses -- both POSTPONED-flavoured enum members below are
    // exactly that reservation exercised, not a violation of it.
    @Test
    fun attendanceStatusColor_excusedIsWarning() {
        assertEquals("warning", attendanceStatusColor(AttendanceStatus.EXCUSED))
    }

    @Test
    fun resolutionStatusColor_postponedIsWarning() {
        assertEquals("warning", resolutionStatusColor(ResolutionStatus.POSTPONED))
    }

    @Test
    fun meetingStatusLabel_heldIsDurchgefuehrt() {
        assertEquals("Durchgeführt", meetingStatusLabel(MeetingStatus.HELD))
    }

    @Test
    fun resolutionModeLabel_committeeQuorumIsGremienbeschluss() {
        assertEquals("Gremienbeschluss", resolutionModeLabel(ResolutionMode.COMMITTEE_QUORUM))
    }
}
