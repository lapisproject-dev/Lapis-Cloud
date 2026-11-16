package network.lapis.cloud.client

import kotlinx.browser.sessionStorage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ID = "0b9a3c1e-4d2f-4a6b-9c8d-1e2f3a4b5c6d"

/** Room polls, wave B -- the narrow "back to the viewer page after login" memory (it never widens `safeReturnTo`). */
class RoomPollViewerReturnTest {
    @BeforeTest
    fun clean() = sessionStorage.removeItem(ROOM_POLL_RETURN_KEY)

    @AfterTest
    fun cleanAfter() = sessionStorage.removeItem(ROOM_POLL_RETURN_KEY)

    @Test
    fun aValidIdIsRememberedAndConsumedOnce() {
        rememberRoomPollReturn(ID, nowMs = 1_000.0)
        assertEquals(ID, consumeRoomPollReturn(nowMs = 2_000.0))
        assertNull(consumeRoomPollReturn(nowMs = 2_000.0), "consumed")
    }

    @Test
    fun anInvalidIdIsNeverStored() {
        listOf(null, "", "x", ID.uppercase(), "$ID/../admin", "javascript:alert(1)").forEach {
            rememberRoomPollReturn(it, nowMs = 1_000.0)
            assertNull(sessionStorage.getItem(ROOM_POLL_RETURN_KEY), "stored: $it")
        }
    }

    @Test
    fun anEntryOlderThan30MinutesIsIgnored_andRemoved() {
        rememberRoomPollReturn(ID, nowMs = 1_000.0)
        assertNull(consumeRoomPollReturn(nowMs = 1_000.0 + ROOM_POLL_RETURN_TTL_MS + 1))
        assertNull(sessionStorage.getItem(ROOM_POLL_RETURN_KEY))
        rememberRoomPollReturn(ID, nowMs = 1_000.0)
        assertEquals(ID, consumeRoomPollReturn(nowMs = 1_000.0 + ROOM_POLL_RETURN_TTL_MS))
    }

    @Test
    fun tamperedStorageIsIgnored() {
        listOf(
            "javascript:alert(1)|1000",
            "$ID|abc",
            "$ID",
            "$ID|1000|extra",
            "../../admin|1000",
            "|",
        ).forEach {
            sessionStorage.setItem(ROOM_POLL_RETURN_KEY, it)
            assertNull(consumeRoomPollReturn(nowMs = 2_000.0), "accepted: $it")
        }
        // a time stamp in the future is not trusted either
        sessionStorage.setItem(ROOM_POLL_RETURN_KEY, "$ID|9999999")
        assertNull(consumeRoomPollReturn(nowMs = 2_000.0))
    }

    @Test
    fun theTargetIsRebuiltFromTheValidatedId() {
        assertEquals("/room-poll/$ID", roomPollViewerRoute(ID))
    }
}
