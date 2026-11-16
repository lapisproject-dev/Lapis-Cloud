package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollOptionDto
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Room polls, wave B -- the DOM-free rules of the viewer page. */
class RoomPollViewerModelTest {
    private fun poll(status: RoomPollStatus) =
        RoomPollDto(
            id = "p1",
            question = "Q?!",
            options = listOf(RoomPollOptionDto("o1", 0, "Ja"), RoomPollOptionDto("o2", 1, "Nein")),
            countingMode = RoomPollCountingMode.BOTH,
            status = status,
        )

    @Test
    fun phases() {
        assertEquals(RoomPollViewerPhase.LOADING, roomPollViewerPhase(null))
        assertEquals(RoomPollViewerPhase.NO_POLL, roomPollViewerPhase(RoomPollStateDto(roomId = "r", version = "v")))
        assertEquals(
            RoomPollViewerPhase.OPEN,
            roomPollViewerPhase(RoomPollStateDto(roomId = "r", poll = poll(RoomPollStatus.OPEN), canAnswer = true, version = "v")),
        )
        assertEquals(
            RoomPollViewerPhase.ANSWERED,
            roomPollViewerPhase(RoomPollStateDto(roomId = "r", poll = poll(RoomPollStatus.OPEN), voted = true, version = "v")),
        )
        assertEquals(
            RoomPollViewerPhase.CLOSED,
            roomPollViewerPhase(RoomPollStateDto(roomId = "r", poll = poll(RoomPollStatus.CLOSED), version = "v")),
        )
    }

    @Test
    fun visibleDelayIs5To6Seconds_withTheJitterBounds() {
        assertEquals(5_000, roomPollViewerNextDelayMs(hidden = false, failures = 0, ratePaused = false, random = { 0.0 }))
        assertEquals(5_999, roomPollViewerNextDelayMs(hidden = false, failures = 0, ratePaused = false, random = { 0.9999 }))
        // a random() of exactly 1.0 must not leave the window either
        assertEquals(5_999, roomPollViewerNextDelayMs(hidden = false, failures = 0, ratePaused = false, random = { 1.0 }))
        listOf(0.0, 0.13, 0.5, 0.77, 0.999).forEach {
            val delay = roomPollViewerNextDelayMs(hidden = false, failures = 0, ratePaused = false, random = { it })
            assertTrue(delay in RoomPollRules.VIEWER_VERSION_POLL_MS until RoomPollRules.VIEWER_VERSION_POLL_MS + 1_000)
        }
    }

    @Test
    fun hiddenIs30Seconds_backoffFollowsTheTable_andTheRatePauseIs60Seconds() {
        assertEquals(30_000, roomPollViewerNextDelayMs(hidden = true, failures = 0, ratePaused = false, random = { 0.5 }))
        assertEquals(
            listOf(5_000, 10_000, 20_000, 40_000, 60_000, 60_000, 60_000),
            (1..7).map { roomPollViewerNextDelayMs(hidden = false, failures = it, ratePaused = false, random = { 0.0 }) },
        )
        assertEquals(60_000, roomPollViewerNextDelayMs(hidden = false, failures = 0, ratePaused = true, random = { 0.0 }))
        // a hidden tab never polls faster than 30 s, also during a short backoff
        assertEquals(30_000, roomPollViewerNextDelayMs(hidden = true, failures = 1, ratePaused = false, random = { 0.0 }))
    }

    @Test
    fun announcements_neverOnTheFirstLoad_neverOnAnUnchangedPhase() {
        assertNull(roomPollViewerAnnouncement(null, RoomPollViewerPhase.OPEN, firstLoad = true))
        assertNull(roomPollViewerAnnouncement(RoomPollViewerPhase.LOADING, RoomPollViewerPhase.OPEN, firstLoad = true))
        assertNull(roomPollViewerAnnouncement(RoomPollViewerPhase.OPEN, RoomPollViewerPhase.OPEN, firstLoad = false))
        assertEquals(
            "Eine Umfrage hat begonnen.",
            roomPollViewerAnnouncement(RoomPollViewerPhase.NO_POLL, RoomPollViewerPhase.OPEN, firstLoad = false),
        )
        assertEquals(
            "Die Umfrage ist beendet. Das Ergebnis wird angezeigt.",
            roomPollViewerAnnouncement(RoomPollViewerPhase.ANSWERED, RoomPollViewerPhase.CLOSED, firstLoad = false),
        )
        assertEquals(
            "Die Umfrage ist beendet. Das Ergebnis wird angezeigt.",
            roomPollViewerAnnouncement(RoomPollViewerPhase.OPEN, RoomPollViewerPhase.CLOSED, firstLoad = false),
        )
        assertNull(roomPollViewerAnnouncement(RoomPollViewerPhase.OPEN, RoomPollViewerPhase.NO_POLL, firstLoad = false))
    }

    @Test
    fun roomIdsAreStrictLowerCaseUuids() {
        assertTrue(isRoomPollRoomId("0b9a3c1e-4d2f-4a6b-9c8d-1e2f3a4b5c6d"))
        assertFalse(isRoomPollRoomId(null))
        assertFalse(isRoomPollRoomId(""))
        assertFalse(isRoomPollRoomId("not-an-id"))
        assertFalse(isRoomPollRoomId("0B9A3C1E-4D2F-4A6B-9C8D-1E2F3A4B5C6D"))
        assertFalse(isRoomPollRoomId("0b9a3c1e-4d2f-4a6b-9c8d-1e2f3a4b5c6d/../x"))
        assertFalse(isRoomPollRoomId(" 0b9a3c1e-4d2f-4a6b-9c8d-1e2f3a4b5c6d"))
    }
}
