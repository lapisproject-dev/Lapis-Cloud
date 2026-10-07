package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * V1.9.69 -- the pure rules behind "a second sign-in with the same account must not loop": [decideAfterDisconnect] and
 * [AutoRejoinGuard] (clock injected, no real time). The wiring in `ConferenceScreen.enterCall` is covered only by the source scan
 * `ClientConferenceRejoinTripwireTest` -- the whole page cannot be mounted in Karma, and a real eviction needs a LiveKit server.
 */
class ConferenceRejoinPolicyTest {
    private val room =
        ConferenceRoomDto(
            id = "room-1",
            title = "Sitzung",
            description = "",
            livekitRoomName = "lc-room-1",
            createdByMemberId = "member-1",
            createdByDisplayName = "Muster",
            createdAt = LocalDateTime(2026, 10, 7, 10, 0),
            endedAt = null,
            active = true,
            maxParticipants = 25,
            liveParticipantCount = 2,
            myRole = ConferenceRole.PARTICIPANT,
            allowFederationGuests = false,
        )
    private val breakout =
        PostDisconnectDestination.Breakout(
            ConferenceBreakoutAssignmentDto("b1", "Raum 1", LocalDateTime(2026, 10, 7, 10, 5)),
            room,
        )
    private val main = PostDisconnectDestination.Main(room)

    private class Counter {
        var calls = 0
        var allow = true

        fun consume(): Boolean {
            calls++
            return allow
        }
    }

    @Test
    fun duplicateIdentity_isDisplaced_forEveryDestination_andNeverTouchesTheGuard() {
        listOf(null, PostDisconnectDestination.Ended, breakout, main).forEach { destination ->
            val counter = Counter()
            assertEquals(
                PostDisconnectAction.Displaced,
                decideAfterDisconnect(DisconnectCause.DuplicateIdentity, destination, counter::consume),
            )
            assertEquals(0, counter.calls)
        }
    }

    @Test
    fun otherCauses_behaveAsBefore_andOnlyMainConsumesTheGuard() {
        val counter = Counter()
        assertEquals(PostDisconnectAction.Ended, decideAfterDisconnect(DisconnectCause.Other, null, counter::consume))
        assertEquals(
            PostDisconnectAction.Ended,
            decideAfterDisconnect(DisconnectCause.Other, PostDisconnectDestination.Ended, counter::consume),
        )
        assertEquals(
            breakout,
            assertIs<PostDisconnectAction.Breakout>(decideAfterDisconnect(DisconnectCause.Other, breakout, counter::consume)).destination,
        )
        assertEquals(0, counter.calls)
        assertEquals(
            main,
            assertIs<PostDisconnectAction.RejoinMain>(decideAfterDisconnect(DisconnectCause.Other, main, counter::consume)).destination,
        )
        assertEquals(1, counter.calls)
    }

    @Test
    fun main_withAnExhaustedGuard_isLoopStopped() {
        val counter = Counter().apply { allow = false }
        assertEquals(PostDisconnectAction.LoopStopped, decideAfterDisconnect(DisconnectCause.Other, main, counter::consume))
        assertEquals(1, counter.calls)
    }

    @Test
    fun guard_allowsThreeThenStops() {
        var t = 0.0
        val guard = AutoRejoinGuard(now = { t })
        assertTrue(guard.tryConsume())
        assertTrue(guard.tryConsume())
        assertTrue(guard.tryConsume())
        assertFalse(guard.tryConsume(), "the fourth within the window")
    }

    @Test
    fun guard_aSingleRejoinAfterAShortNetworkDrop_isAllowed() {
        val guard = AutoRejoinGuard(now = { 1_000.0 })
        assertTrue(guard.tryConsume())
    }

    @Test
    fun guard_allowsAgainWhenTheWindowHasPassed_boundaryIsInclusive() {
        var t = 0.0
        val guard = AutoRejoinGuard(now = { t })
        repeat(3) { assertTrue(guard.tryConsume()) }
        t = 59_999.0
        assertFalse(guard.tryConsume())
        t = 60_000.0 // exactly one window after the first: that stamp no longer counts
        assertTrue(guard.tryConsume())
    }

    @Test
    fun guard_slidesWithTheWindow() {
        var t = 0.0
        val guard = AutoRejoinGuard(now = { t })
        assertTrue(guard.tryConsume()) // 0
        t = 30_000.0
        assertTrue(guard.tryConsume())
        t = 50_000.0
        assertTrue(guard.tryConsume())
        t = 55_000.0
        assertFalse(guard.tryConsume())
        t = 60_000.0 // the stamp at 0 expired
        assertTrue(guard.tryConsume())
        assertFalse(guard.tryConsume())
    }

    @Test
    fun guard_resetStartsAFreshWindow() {
        val guard = AutoRejoinGuard(now = { 0.0 })
        repeat(3) { assertTrue(guard.tryConsume()) }
        assertFalse(guard.tryConsume())
        guard.reset() // a deliberate click
        repeat(3) { assertTrue(guard.tryConsume()) }
        assertFalse(guard.tryConsume())
    }
}
