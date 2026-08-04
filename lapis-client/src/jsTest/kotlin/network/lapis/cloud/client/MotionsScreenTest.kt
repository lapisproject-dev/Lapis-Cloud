package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.VoteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Governance UI wave -- covers the pure, DOM-independent label/color functions factored out of
 * `MotionsScreen.kt` ([motionStatusLabel]/[motionStatusColor]/[voteStatusLabel]/[voteStatusColor]),
 * same scope posture as [MeetingsScreenTest]/[CommitteesScreenTest]/[ValidationTest] (no DOM/
 * rendering test harness exists in this module). Same two-assertions-per-solid-badge-enum shape as
 * [MeetingsScreenTest]: every value has a non-blank German label, and every color is one of
 * Bootstrap 5.3.8's real semantic hues.
 */
class MotionsScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun motionStatusLabel_isNonBlankForEveryValue() {
        MotionStatus.entries.forEach { status ->
            assertTrue(motionStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun motionStatusColor_isARealBootstrapHueForEveryValue() {
        MotionStatus.entries.forEach { status ->
            val color = motionStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun voteStatusLabel_isNonBlankForEveryValue() {
        VoteStatus.entries.forEach { status ->
            assertTrue(voteStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun voteStatusColor_isARealBootstrapHueForEveryValue() {
        VoteStatus.entries.forEach { status ->
            val color = voteStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    // Design decision D2: `warning` is reserved for the amendment-ordering alert and
    // POSTPONED-shaped statuses -- this is exactly that reservation exercised.
    @Test
    fun motionStatusColor_postponedIsWarning() {
        assertEquals("warning", motionStatusColor(MotionStatus.POSTPONED))
    }

    @Test
    fun motionStatusLabel_withdrawnIsZurueckgezogen() {
        assertEquals("Zurückgezogen", motionStatusLabel(MotionStatus.WITHDRAWN))
    }

    // Design decision D3: a sealed-bid Vote reads as "läuft"/primary while OPEN, matching
    // MeetingStatus/MotionStatus's own convention of using `primary` for "the thing in progress
    // right now that this screen exists to manage" (MotionStatus.SCHEDULED uses the same hue).
    @Test
    fun voteStatusColor_openIsPrimary() {
        assertEquals("primary", voteStatusColor(VoteStatus.OPEN))
    }

    @Test
    fun motionStatusLabel_resolvedMatchesResolutionStatusAdoptedWording() {
        // MotionDto KDoc: MotionStatus.RESOLVED maps 1:1 from ResolutionStatus.ADOPTED -- the
        // design review requires identical wording for the same underlying fact (Norman: the UI
        // must not lie about the system's own model).
        assertEquals(resolutionStatusLabel(ResolutionStatus.ADOPTED), motionStatusLabel(MotionStatus.RESOLVED))
    }
}
