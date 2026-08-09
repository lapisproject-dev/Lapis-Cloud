package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 4 "Politur", D3 -- covers [conferenceGridLayout], the
 * pure partition behind `ConferenceScreen.kt`'s `enterCall#applyConferenceGridReflow` (speaking-
 * priority grid reflow above [CONFERENCE_GRID_REFLOW_THRESHOLD] participants). Same DOM-free unit-
 * test posture as [ConferenceScreenTest]/[ConferenceStreamingUiTest] -- no rendering harness exists
 * in this module, so the raw-DOM re-parenting/restyling side of `applyConferenceGridReflow` is out
 * of scope here, covered only structurally by this wave's own live-browser verification.
 */
class ConferenceGridLayoutTest {
    private fun identities(count: Int): List<String> = (1..count).map { "member-$it" }

    // ---------------------------------------------------------------------------------------
    // <= threshold: unreflowed, byte-for-byte Wave 1-3 behaviour (design review "Required change 2")
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceGridLayout_atOrBelowThreshold_isUnreflowed_everyoneInPriorityInJoinOrder() {
        val ordered = identities(CONFERENCE_GRID_REFLOW_THRESHOLD)
        val layout = conferenceGridLayout(ordered, priorityIdentities = emptySet())
        assertEquals(false, layout.reflowed)
        assertEquals(ordered, layout.priorityIdentities)
        assertTrue(layout.compactIdentities.isEmpty())
    }

    @Test
    fun conferenceGridLayout_wellBelowThreshold_isUnreflowed() {
        val ordered = identities(3)
        val layout = conferenceGridLayout(ordered, priorityIdentities = setOf("member-1"))
        assertEquals(false, layout.reflowed)
        assertEquals(ordered, layout.priorityIdentities)
        assertTrue(layout.compactIdentities.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // > threshold: reflowed
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceGridLayout_oneAboveThreshold_isReflowed() {
        val ordered = identities(CONFERENCE_GRID_REFLOW_THRESHOLD + 1)
        val layout = conferenceGridLayout(ordered, priorityIdentities = emptySet())
        assertEquals(true, layout.reflowed)
    }

    @Test
    fun conferenceGridLayout_moreThanMaxPriorityTiles_prioritized_onlyFirstNInJoinOrderMakeThePriorityZone() {
        // 20 participants, 8 of them currently prioritized (more than CONFERENCE_PRIORITY_ZONE_MAX,
        // i.e. 6) -- documents the deliberate cap trade-off: only the first 6 IN JOIN ORDER end up in
        // priorityIdentities, the rest (even though "prioritized") land in compactIdentities.
        val ordered = identities(20)
        val prioritized = setOf("member-3", "member-5", "member-7", "member-9", "member-11", "member-13", "member-15", "member-17")
        val layout = conferenceGridLayout(ordered, priorityIdentities = prioritized)
        assertEquals(true, layout.reflowed)
        assertEquals(CONFERENCE_PRIORITY_ZONE_MAX, layout.priorityIdentities.size)
        assertEquals(
            listOf("member-3", "member-5", "member-7", "member-9", "member-11", "member-13"),
            layout.priorityIdentities,
        )
        // The two overflow-prioritized identities (member-15, member-17) land in the compact strip
        // too, alongside everyone never prioritized at all.
        assertTrue("member-15" in layout.compactIdentities)
        assertTrue("member-17" in layout.compactIdentities)
        assertEquals(20 - CONFERENCE_PRIORITY_ZONE_MAX, layout.compactIdentities.size)
    }

    @Test
    fun conferenceGridLayout_emptyPriorityIdentities_priorityZoneStillFillsViaFallback_neverEmpty() {
        // Nobody is currently speaking -- the priority zone must still fill with the first
        // CONFERENCE_PRIORITY_ZONE_MAX join-order identities, never render empty.
        val ordered = identities(20)
        val layout = conferenceGridLayout(ordered, priorityIdentities = emptySet())
        assertEquals(true, layout.reflowed)
        assertEquals(identities(CONFERENCE_PRIORITY_ZONE_MAX), layout.priorityIdentities)
        assertEquals(20 - CONFERENCE_PRIORITY_ZONE_MAX, layout.compactIdentities.size)
    }

    @Test
    fun conferenceGridLayout_localIdentityAlwaysPresentInPriorityIdentities_wasIncludedByCaller() {
        // This function's own contract: if the caller includes the local identity in
        // priorityIdentities (as enterCall's currentPriorityIdentities always does, "never demote
        // yourself out of view"), and capacity allows, it appears in the result's priorityIdentities.
        // Structural test of the caller's contract, not a special case inside the pure function
        // itself (which stays a plain set-membership partition, per its own KDoc).
        val ordered = identities(15)
        val localIdentity = "member-1"
        val layout = conferenceGridLayout(ordered, priorityIdentities = setOf(localIdentity))
        assertTrue(localIdentity in layout.priorityIdentities)
    }

    @Test
    fun conferenceGridLayout_priorityAndCompactPartitionCoversEveryIdentityExactlyOnce() {
        val ordered = identities(25)
        val prioritized = setOf("member-2", "member-4")
        val layout = conferenceGridLayout(ordered, priorityIdentities = prioritized)
        val combined = (layout.priorityIdentities + layout.compactIdentities).toSet()
        assertEquals(ordered.toSet(), combined)
        assertEquals(ordered.size, layout.priorityIdentities.size + layout.compactIdentities.size)
    }

    @Test
    fun conferenceGridLayout_customThresholdAndCap_areRespected() {
        val ordered = identities(5)
        val layout = conferenceGridLayout(ordered, priorityIdentities = emptySet(), threshold = 3, maxPriorityTiles = 2)
        assertEquals(true, layout.reflowed)
        assertEquals(listOf("member-1", "member-2"), layout.priorityIdentities)
        assertEquals(listOf("member-3", "member-4", "member-5"), layout.compactIdentities)
    }
}
