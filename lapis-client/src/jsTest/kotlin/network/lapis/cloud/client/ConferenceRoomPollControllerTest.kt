package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollOptionDto
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.304 -- the poll controller on a fake clock: rhythm, nudge throttle, one request in flight, failure counting, stop. */
class ConferenceRoomPollControllerTest {
    private fun open(leadership: Boolean = false) =
        RoomPollStateDto(
            roomId = "r1",
            poll =
                RoomPollDto(
                    id = "p1",
                    question = "Q?!",
                    options = listOf(RoomPollOptionDto("o1", 0, "Ja"), RoomPollOptionDto("o2", 1, "Nein")),
                    countingMode = RoomPollCountingMode.BOTH,
                    status = RoomPollStatus.OPEN,
                ),
            canAnswer = true,
            isLeadership = leadership,
            version = "v",
        )

    private class Rig(
        var answer: () -> RoomPollStateDto? = { null },
    ) {
        val scheduler = OperatorFakeScheduler()
        var fetches = 0
        val updates = mutableListOf<Pair<RoomPollStateDto?, RoomPollStateDto>>()
        var failures = 0
        var hidden = false
        var throwForbidden = false

        val controller =
            ConferenceRoomPollController(
                roomId = "r1",
                fetch = {
                    fetches++
                    if (throwForbidden) throw ForbiddenException()
                    answer() ?: throw IllegalStateException("down")
                },
                isHidden = { hidden },
                scheduler = scheduler,
                scope = CoroutineScope(Dispatchers.Unconfined),
                onUpdate = { before, next -> updates += before to next },
                onFailure = { failures = it },
            )
    }

    @Test
    fun polls_every5s_andEvery4sForTheLeadershipWhileAPollRuns() {
        val rig = Rig(answer = { null })
        rig.answer = { RoomPollStateDto(roomId = "r1", version = "none") }
        rig.controller.start()
        assertEquals(1, rig.fetches)
        rig.scheduler.advance(4_999)
        assertEquals(1, rig.fetches)
        rig.scheduler.advance(1)
        assertEquals(2, rig.fetches)
        // the leadership with a running poll: 4 s
        rig.answer = { open(leadership = true) }
        rig.scheduler.advance(5_000)
        val before = rig.fetches
        rig.scheduler.advance(4_000)
        assertEquals(before + 1, rig.fetches)
    }

    @Test
    fun aRefusalStopsThePeriodicPolling_aNudgeOrRefreshStillAsks_andNothingIsCountedAsAMiss() {
        val rig = Rig(answer = { null })
        rig.throwForbidden = true
        rig.controller.start()
        assertEquals(1, rig.fetches)
        rig.scheduler.advance(120_000)
        assertEquals(1, rig.fetches, "a refused room is not polled again")
        assertEquals(0, rig.failures)
        assertEquals(0, rig.controller.failures)
        rig.controller.refreshNow()
        assertEquals(2, rig.fetches)
        // access arrives later: the poll resumes
        rig.throwForbidden = false
        rig.answer = { open() }
        rig.controller.refreshNow()
        assertEquals(3, rig.fetches)
        rig.scheduler.advance(5_000)
        assertEquals(4, rig.fetches)
    }

    @Test
    fun aHiddenTabWaits15s_andTheNudgeAsksAtOnce_butNotMoreOftenThanEverySecond() {
        val rig = Rig(answer = { open() })
        rig.hidden = true
        rig.controller.start()
        assertEquals(1, rig.fetches)
        rig.scheduler.advance(14_999)
        assertEquals(1, rig.fetches)
        rig.scheduler.advance(1)
        assertEquals(2, rig.fetches)
        rig.scheduler.advance(2_000)
        rig.controller.nudge()
        assertEquals(3, rig.fetches, "a nudge after a quiet time asks at once")
        rig.controller.nudge()
        rig.controller.nudge()
        assertEquals(3, rig.fetches, "within a second: waits")
        rig.scheduler.advance(1_000)
        assertEquals(4, rig.fetches, "exactly one follow-up")
    }

    @Test
    fun aFailure_isCountedAndNeverThrown_andTheNextSuccessResetsIt() {
        val rig = Rig(answer = { null })
        rig.controller.start()
        assertEquals(1, rig.failures)
        rig.scheduler.advance(5_000)
        assertEquals(2, rig.failures)
        rig.answer = { open() }
        rig.scheduler.advance(5_000)
        assertEquals(0, rig.controller.failures)
        assertNotNull(rig.controller.state)
        assertNull(rig.updates.first().first)
    }

    @Test
    fun adoptShowsAWriteAnswerAtOnce_andStopEndsEverything() {
        val rig = Rig(answer = { open() })
        rig.controller.start()
        val updatesBefore = rig.updates.size
        rig.controller.adopt(open(leadership = true))
        assertEquals(updatesBefore + 1, rig.updates.size)
        assertTrue(rig.controller.state!!.isLeadership)
        rig.controller.stop()
        val fetches = rig.fetches
        rig.scheduler.advance(60_000)
        rig.controller.nudge()
        rig.controller.refreshNow()
        assertEquals(fetches, rig.fetches)
        assertEquals(0, rig.scheduler.pendingTimers)
    }
}
