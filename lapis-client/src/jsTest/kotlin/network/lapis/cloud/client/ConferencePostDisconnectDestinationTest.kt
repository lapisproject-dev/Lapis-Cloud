package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 6 "Breakout-Räume" -- unit coverage for
 * [resolvePostDisconnectDestinationOf], the pure branch logic behind `ConferenceScreen.kt`'s
 * `onDisconnected` resolution (see that function's own KDoc for the full four-cause disambiguation:
 * kicked, meeting ended, assigned to a breakout room, recalled from one). Same DOM-free,
 * RPC-mocking-free unit-test posture as [ConferenceConnectionStateTest]/[ConferenceGridLayoutTest] --
 * the actual RPC calls live in [resolvePostDisconnectDestination] itself, out of scope here.
 */
class ConferencePostDisconnectDestinationTest {
    @Test
    fun parentRoomNull_resolvesToEnded() {
        // getRoom itself failed (network error, or the room id no longer resolves).
        val result = resolvePostDisconnectDestinationOf(parentRoom = null, assignment = null)
        assertIs<PostDisconnectDestination.Ended>(result)
    }

    @Test
    fun parentRoomInactive_resolvesToEnded_regardlessOfAssignment() {
        val inactiveRoom = testRoom(active = false)
        val resultWithoutAssignment = resolvePostDisconnectDestinationOf(inactiveRoom, assignment = null)
        assertIs<PostDisconnectDestination.Ended>(resultWithoutAssignment)

        // Even a (stale/dangling) open assignment must not override an inactive parent room -- the
        // meeting being over always wins.
        val resultWithAssignment = resolvePostDisconnectDestinationOf(inactiveRoom, assignment = testAssignment())
        assertIs<PostDisconnectDestination.Ended>(resultWithAssignment)
    }

    @Test
    fun parentRoomActive_assignmentPresent_resolvesToBreakout_carryingBothValuesThrough() {
        val activeRoom = testRoom(active = true)
        val assignment = testAssignment(breakoutRoomId = "breakout-1", breakoutRoomLabel = "Breakout-Raum 1")
        val result = resolvePostDisconnectDestinationOf(activeRoom, assignment)
        val breakout = assertIs<PostDisconnectDestination.Breakout>(result)
        assertEquals(assignment, breakout.assignment)
        assertEquals(activeRoom, breakout.parentRoom)
    }

    @Test
    fun parentRoomActive_noAssignment_resolvesToMain() {
        val activeRoom = testRoom(active = true)
        val result = resolvePostDisconnectDestinationOf(activeRoom, assignment = null)
        val main = assertIs<PostDisconnectDestination.Main>(result)
        assertEquals(activeRoom, main.parentRoom)
    }

    private fun testRoom(active: Boolean): ConferenceRoomDto =
        ConferenceRoomDto(
            id = "room-1",
            title = "Testsitzung",
            description = "",
            livekitRoomName = "lc-test-room-1",
            createdByMemberId = "member-1",
            createdByDisplayName = "Moderator Muster",
            createdAt = LocalDateTime(2026, 8, 11, 10, 0),
            endedAt = if (active) null else LocalDateTime(2026, 8, 11, 10, 30),
            active = active,
            maxParticipants = 25,
            liveParticipantCount = 3,
            myRole = ConferenceRole.PARTICIPANT,
            allowFederationGuests = false,
        )

    private fun testAssignment(
        breakoutRoomId: String = "breakout-1",
        breakoutRoomLabel: String = "Breakout-Raum 1",
    ): ConferenceBreakoutAssignmentDto =
        ConferenceBreakoutAssignmentDto(
            breakoutRoomId = breakoutRoomId,
            breakoutRoomLabel = breakoutRoomLabel,
            assignedAt = LocalDateTime(2026, 8, 11, 10, 5),
        )
}
