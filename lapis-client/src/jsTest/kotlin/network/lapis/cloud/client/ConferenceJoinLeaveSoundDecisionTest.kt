package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.120 -- the rules of the join/leave tones, with an injected clock only (no `Date`, no `performance.now()`, no time zone): one case per
 * condition of [shouldPlayPresenceSound], the memory of [PresenceSoundGate] and the window of [PresenceTracker].
 */
class ConferenceJoinLeaveSoundDecisionTest {
    private fun decide(
        kind: PresenceSoundKind = PresenceSoundKind.JOIN,
        identity: String = "other",
        local: String = "me",
        before: Int = 3,
        after: Int = 4,
        armed: Boolean = true,
        reconnecting: Boolean = false,
        personal: Boolean = true,
        room: Boolean = true,
        now: Double = 100_000.0,
        lastIdentity: Double? = null,
        lastAny: Double? = null,
    ) = shouldPlayPresenceSound(kind, identity, local, before, after, armed, reconnecting, personal, room, now, lastIdentity, lastAny)

    @Test
    fun theOrdinaryCase_sounds_forJoinAndLeave() {
        assertTrue(decide())
        assertTrue(decide(kind = PresenceSoundKind.LEAVE, before = 4, after = 3))
    }

    @Test
    fun theSizeLimit_isSymmetric_atTenAndEleven() {
        assertTrue(decide(before = 9, after = 10), "a join that makes ten still sounds")
        assertFalse(decide(before = 10, after = 11), "a join into a room of ten does not (eleven afterwards)")
        assertTrue(decide(kind = PresenceSoundKind.LEAVE, before = 10, after = 9), "a leave out of ten still sounds")
        assertFalse(decide(kind = PresenceSoundKind.LEAVE, before = 11, after = 10), "a leave out of eleven does not (eleven before)")
        assertFalse(decide(before = 40, after = 41))
    }

    @Test
    fun theLocalPerson_neverSounds() {
        assertFalse(decide(identity = "me"))
    }

    @Test
    fun aSessionThatIsNotArmed_isSilent() {
        assertFalse(decide(armed = false))
    }

    @Test
    fun whileReconnectingLocally_isSilent() {
        assertFalse(decide(reconnecting = true))
    }

    @Test
    fun thePersonalSwitchOff_isSilent() {
        assertFalse(decide(personal = false))
    }

    @Test
    fun theRoomOptionOff_isSilent() {
        assertFalse(decide(room = false))
    }

    @Test
    fun theDedupOfAnIdentity_endsAtFiveSeconds() {
        assertFalse(decide(now = 104_999.0, lastIdentity = 100_000.0))
        assertTrue(decide(now = 105_000.0, lastIdentity = 100_000.0))
    }

    @Test
    fun theMinimumGapBetweenAnyTwoTones_endsAtOneSecond() {
        assertFalse(decide(now = 100_999.0, lastAny = 100_000.0))
        assertTrue(decide(now = 101_000.0, lastAny = 100_000.0))
    }

    // -- the recorder filter ----------------------------------------------------------------------------------------------------------

    @Test
    fun aRecorder_isNotCountable_byKindOrByIdentity() {
        assertFalse(isPresenceCountable(kind = "2", identity = "x"))
        assertFalse(isPresenceCountable(kind = "EGRESS", identity = "x"))
        assertFalse(isPresenceCountable(kind = "egress", identity = "x"))
        assertFalse(isPresenceCountable(kind = null, identity = "EG_abc"))
        assertTrue(isPresenceCountable(kind = null, identity = "member-1"))
        assertTrue(isPresenceCountable(kind = "0", identity = "member-1"))
        assertTrue(isPresenceCountable(kind = "standard", identity = "member-1"))
    }

    // -- the gate -----------------------------------------------------------------------------------------------------------------------

    private class Clock(
        var now: Double = 1_000.0,
    )

    private fun PresenceSoundGate.play(
        identity: String,
        kind: PresenceSoundKind = PresenceSoundKind.JOIN,
        before: Int = 2,
        after: Int = 3,
        armed: Boolean = true,
        personal: Boolean = true,
    ) = tryPlay(kind, identity, "me", before, after, armed, reconnectingLocally = false, personalOn = personal, roomOn = true)

    @Test
    fun theGate_remembersOnlyATonethatSounded() {
        val clock = Clock()
        val gate = PresenceSoundGate { clock.now }
        assertFalse(gate.play("a", armed = false))
        assertFalse(gate.play("a", personal = false))
        assertEquals(0, gate.rememberedIdentities, "a discarded event leaves no trace")
        assertTrue(gate.play("a"))
        assertEquals(1, gate.rememberedIdentities)
    }

    @Test
    fun theGate_discardsInsteadOfQueueing_andAnAcceptedToneStartsTheWindows() {
        val clock = Clock()
        val gate = PresenceSoundGate { clock.now }
        assertTrue(gate.play("a"))
        clock.now += 500.0
        assertFalse(gate.play("b"), "inside the minimum gap: dropped")
        clock.now += 500.0
        assertTrue(gate.play("b"), "the dropped one was not queued, a new event sounds once the gap is over")
        assertEquals(2, gate.rememberedIdentities)
    }

    @Test
    fun theGate_sharesTheDedupWindowBetweenJoinAndLeave_soAReconnectSoundsOnce() {
        val clock = Clock()
        val gate = PresenceSoundGate { clock.now }
        assertTrue(gate.play("a", PresenceSoundKind.LEAVE, before = 3, after = 2))
        clock.now += 1_500.0
        assertFalse(gate.play("a", PresenceSoundKind.JOIN), "the join of the same person right after the leave is silent")
        clock.now += 3_500.0
        assertTrue(gate.play("a", PresenceSoundKind.JOIN), "five seconds after the leave it is a new event")
    }

    @Test
    fun theGate_forgetsExpiredIdentities_andNeverGrowsPastItsCap() {
        val clock = Clock()
        val gate = PresenceSoundGate { clock.now }
        repeat(200) { index ->
            clock.now += JOIN_LEAVE_SOUND_MIN_GAP_MS
            gate.play("spam-$index")
            assertTrue(gate.rememberedIdentities <= 6, "only the identities of the last five seconds are kept")
        }
        clock.now += 10_000.0
        gate.play("late")
        assertEquals(1, gate.rememberedIdentities)
    }

    // -- the window of the own session ------------------------------------------------------------------------------------------------

    @Test
    fun theTracker_isArmedOnlyAfterTheFirstRoster_andSilentWhileReconnecting_plusAGraceWindow() {
        val clock = Clock(now = 10_000.0)
        val tracker = PresenceTracker { clock.now }
        assertFalse(tracker.armed)
        tracker.arm()
        assertTrue(tracker.armed)
        assertFalse(tracker.reconnectingLocally())
        tracker.onReconnecting()
        assertTrue(tracker.reconnectingLocally())
        clock.now += 30_000.0
        assertTrue(tracker.reconnectingLocally(), "as long as the reconnect lasts")
        tracker.onReconnected()
        assertTrue(tracker.reconnectingLocally(), "replayed events right after Reconnected are still ignored")
        clock.now += JOIN_LEAVE_SOUND_RECONNECT_GRACE_MS - 1
        assertTrue(tracker.reconnectingLocally())
        clock.now += 1.0
        assertFalse(tracker.reconnectingLocally())
        tracker.disarm()
        assertFalse(tracker.armed)
    }

    @Test
    fun theConstants_matchTheSpecification() {
        assertEquals(10, JOIN_LEAVE_SOUND_MAX_PARTICIPANTS)
        assertEquals(5_000.0, JOIN_LEAVE_SOUND_DEDUP_MS)
        assertEquals(1_000.0, JOIN_LEAVE_SOUND_MIN_GAP_MS)
        assertEquals(0.10, JOIN_SOUND_PEAK_GAIN)
        assertEquals(0.05, LEAVE_SOUND_PEAK_GAIN)
    }
}
