package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollOptionDto
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVersionDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Room polls, wave B -- the viewer page's polling discipline on a fake clock. */
class RoomPollViewerControllerTest {
    private fun openState(version: String = "p1.O") =
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
            version = version,
        )

    private class Rig {
        val scheduler = OperatorFakeScheduler()
        var versionCalls = 0
        var stateCalls = 0
        var token = "none"
        var failWith: (() -> Throwable)? = null
        var stateFailWith: (() -> Throwable)? = null
        var hidden = false
        var randomValue = 0.0
        val states = mutableListOf<RoomPollStateDto>()
        val refusals = mutableListOf<Boolean>()
        val knownSeen = mutableListOf<String?>()
        var stateToServe: RoomPollStateDto = RoomPollStateDto(roomId = "r1", version = "none")

        val controller =
            RoomPollViewerController(
                roomId = "r1",
                rpc =
                    RoomPollViewerRpc(
                        version = { _, known ->
                            versionCalls++
                            knownSeen += known
                            failWith?.let { throw it() }
                            if (known == token) RoomPollVersionDto(changed = false) else RoomPollVersionDto(changed = true, version = token)
                        },
                        state = {
                            stateCalls++
                            stateFailWith?.let { throw it() }
                            stateToServe
                        },
                        answer = { _, _, _ -> stateToServe },
                    ),
                isHidden = { hidden },
                scheduler = scheduler,
                scope = CoroutineScope(Dispatchers.Unconfined),
                random = { randomValue },
                onState = { states += it },
                onRefused = { refusals += it },
            )
    }

    @Test
    fun aVisibleTabMakesAtMost12VersionCallsAMinute_andReadsTheStateOnlyOnAChange() {
        val rig = Rig()
        rig.controller.start()
        assertEquals(1, rig.versionCalls)
        assertEquals(1, rig.stateCalls, "the first load reads the state")
        rig.scheduler.advance(59_999)
        assertTrue(rig.versionCalls <= 12, "calls: ${rig.versionCalls}")
        assertEquals(12, rig.versionCalls)
        assertEquals(1, rig.stateCalls, "unchanged token: no state read")
        // a change reads the state once
        rig.token = "p1.O"
        rig.stateToServe = openState()
        rig.scheduler.advance(5_000)
        assertEquals(2, rig.stateCalls)
        assertEquals("p1.O", rig.states.last().version)
    }

    @Test
    fun theJitterKeepsEveryGapBetween5And6Seconds() {
        val rig = Rig()
        rig.randomValue = 0.999
        rig.controller.start()
        rig.scheduler.advance(5_998)
        assertEquals(1, rig.versionCalls)
        rig.scheduler.advance(1)
        assertEquals(2, rig.versionCalls)
    }

    @Test
    fun aHiddenTabWaits30s_andBecomingVisibleAsksAtOnce_butNeverWithinFiveSeconds() {
        val rig = Rig()
        rig.hidden = true
        rig.controller.start()
        assertEquals(1, rig.versionCalls)
        rig.scheduler.advance(29_999)
        assertEquals(1, rig.versionCalls)
        rig.scheduler.advance(1)
        assertEquals(2, rig.versionCalls)
        rig.scheduler.advance(10_000)
        rig.hidden = false
        rig.controller.onVisibilityChanged()
        assertEquals(3, rig.versionCalls, "visible again: one call at once")
        rig.controller.onVisibilityChanged()
        rig.controller.onVisibilityChanged()
        assertEquals(3, rig.versionCalls, "the 5 s gap holds against repeated triggers")
        rig.scheduler.advance(5_001)
        assertEquals(4, rig.versionCalls)
    }

    @Test
    fun offlineCancelsEveryTimer_onlineAsksOnce() {
        val rig = Rig()
        rig.controller.start()
        rig.controller.onOffline()
        assertEquals(0, rig.scheduler.pendingTimers)
        rig.scheduler.advance(120_000)
        assertEquals(1, rig.versionCalls)
        rig.controller.onOnline()
        assertEquals(2, rig.versionCalls)
        rig.controller.onOnline()
        assertEquals(2, rig.versionCalls, "a second trigger inside five seconds waits")
    }

    @Test
    fun aNetworkErrorBacksOff5_10_20_40_60_andTheFirstSuccessResets() {
        val rig = Rig()
        rig.failWith = { IllegalStateException("down") }
        rig.controller.start()
        assertEquals(1, rig.versionCalls)
        listOf(5_000, 10_000, 20_000, 40_000, 60_000, 60_000).forEachIndexed { index, wait ->
            rig.scheduler.advance(wait - 1)
            assertEquals(index + 1, rig.versionCalls, "before $wait")
            rig.scheduler.advance(1)
            assertEquals(index + 2, rig.versionCalls, "after $wait")
        }
        rig.failWith = null
        rig.scheduler.advance(60_000)
        assertEquals(0, rig.controller.failures)
        val before = rig.versionCalls
        rig.scheduler.advance(5_000)
        assertEquals(before + 1, rig.versionCalls, "the rhythm is back to 5 s")
    }

    @Test
    fun aConflictOnAReadPausesFor60Seconds_andTheThirdInARowRefuses() {
        val rig = Rig()
        rig.failWith = { ConflictException("limit") }
        rig.controller.start()
        assertEquals(1, rig.versionCalls)
        rig.scheduler.advance(59_999)
        assertEquals(1, rig.versionCalls)
        rig.scheduler.advance(1)
        assertEquals(2, rig.versionCalls)
        assertTrue(rig.refusals.isEmpty())
        rig.scheduler.advance(60_000)
        assertEquals(3, rig.versionCalls)
        assertEquals(listOf(false), rig.refusals, "after the third conflict: refused")
        rig.scheduler.advance(600_000)
        assertEquals(3, rig.versionCalls, "and no further timer")
    }

    @Test
    fun aSuccessBetweenConflictsResetsTheStreak() {
        val rig = Rig()
        rig.failWith = { ConflictException("limit") }
        rig.controller.start()
        rig.scheduler.advance(60_000)
        rig.failWith = null
        rig.scheduler.advance(60_000)
        assertTrue(rig.refusals.isEmpty())
        rig.failWith = { ConflictException("limit") }
        rig.scheduler.advance(5_100)
        rig.scheduler.advance(60_000)
        assertTrue(rig.refusals.isEmpty(), "only two in a row")
    }

    @Test
    fun aForbiddenStopsForGood_unauthenticatedToo_andNoTimerRemains() {
        val rig = Rig()
        rig.failWith = { ForbiddenException() }
        rig.controller.start()
        assertEquals(listOf(false), rig.refusals)
        assertEquals(0, rig.scheduler.pendingTimers)
        rig.scheduler.advance(600_000)
        assertEquals(1, rig.versionCalls)

        val anonymous = Rig()
        anonymous.failWith = { UnauthenticatedException() }
        anonymous.controller.start()
        assertEquals(listOf(true), anonymous.refusals)
        assertEquals(0, anonymous.scheduler.pendingTimers)
    }

    @Test
    fun aFailedStateReadAfterAChangedVersionIsRetriedWithTheOldToken() {
        val rig = Rig()
        rig.controller.start()
        rig.token = "p1.O"
        rig.stateToServe = openState()
        rig.stateFailWith = { IllegalStateException("boom") }
        rig.scheduler.advance(5_000)
        assertEquals(1, rig.controller.failures)
        rig.stateFailWith = null
        rig.scheduler.advance(5_000)
        assertEquals("p1.O", rig.states.last().version, "the state arrived with the next cycle")
    }

    @Test
    fun recheckMakesExactlyOneVersionCall_thenLocksFor10Seconds_andLiftsAPermanentRefusalForThatCall() {
        val rig = Rig()
        rig.failWith = { ForbiddenException() }
        rig.controller.start()
        assertEquals(1, rig.versionCalls)
        rig.failWith = null
        rig.scheduler.advance(5_000)
        rig.controller.recheck()
        assertEquals(2, rig.versionCalls)
        assertTrue(rig.states.isNotEmpty(), "a successful recheck brings the state back")
        val after = rig.versionCalls
        rig.controller.recheck()
        rig.controller.recheck()
        assertEquals(after, rig.versionCalls, "locked")
        rig.scheduler.advance(10_000)
        rig.failWith = { ForbiddenException() }
        rig.controller.recheck()
        rig.scheduler.advance(5_100)
        assertEquals(2, rig.refusals.size, "refused again")
        val settled = rig.versionCalls
        rig.scheduler.advance(120_000)
        assertEquals(settled, rig.versionCalls, "and silent again")
    }

    @Test
    fun nothingIsAppliedAfterStop_andTheControllerRestartsCleanly() {
        val rig = Rig()
        rig.controller.start()
        val calls = rig.versionCalls
        rig.controller.stop()
        assertEquals(0, rig.scheduler.pendingTimers)
        rig.scheduler.advance(120_000)
        assertEquals(calls, rig.versionCalls)
        rig.controller.adopt(openState())
        assertEquals(1, rig.states.size, "adopt after stop is ignored")
        rig.controller.start()
        rig.scheduler.advance(5_001)
        assertTrue(rig.versionCalls > calls)
        assertEquals(2, rig.states.size, "the restart reads the state again")
    }

    @Test
    fun adoptShowsTheStateAtOnce_andTakesItsVersion() {
        val rig = Rig()
        rig.controller.start()
        rig.controller.adopt(openState(version = "p1.O"))
        assertEquals("p1.O", rig.states.last().version)
        rig.token = "p1.O"
        rig.scheduler.advance(5_000)
        assertEquals("p1.O", rig.knownSeen.last(), "the next version call carries the adopted token")
    }
}
