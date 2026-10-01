package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.RoomVotingStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.25 -- the pure room state ([voteRoomReduce]) and the polling controller on a fake clock. No DOM: the controller runs on
 * `Dispatchers.Unconfined` and a manual scheduler, so every request and every timer is observable and deterministic.
 */

internal fun roomBallot(
    id: String,
    status: RoomBallotStatus = RoomBallotStatus.OPEN,
    kind: RoomBallotKind = RoomBallotKind.ELECTION,
    eligible: Boolean = true,
    voted: Boolean = false,
    secret: Boolean = true,
    title: String = "Vorstandswahl",
    motionTitle: String = "Antrag zur Vorstandswahl",
) = RoomBallotDto(
    kind = kind,
    id = id,
    motionId = "m-$id",
    motionTitle = motionTitle,
    title = title,
    status = status,
    secret = secret,
    ownEligible = eligible,
    ownHasVoted = voted,
)

internal fun roomState(
    vararg ballots: RoomBallotDto,
    bound: Boolean = true,
    truncated: Boolean = false,
) = RoomVotingStateDto(roomId = "r1", bound = bound, ballots = ballots.toList(), truncated = truncated)

private class FakeVoteScheduler : ConferenceVoteScheduler {
    var nowMs = 1_000_000.0
    private var nextHandle = 1
    private val tasks = mutableMapOf<Int, Pair<Double, () -> Unit>>()
    val scheduledDelays = mutableListOf<Int>()

    val pendingTimers: Int get() = tasks.size

    override fun now(): Double = nowMs

    override fun schedule(
        delayMs: Int,
        block: () -> Unit,
    ): Int {
        val handle = nextHandle++
        tasks[handle] = (nowMs + delayMs) to block
        scheduledDelays += delayMs
        return handle
    }

    override fun cancel(handle: Int) {
        tasks.remove(handle)
    }

    /** Moves the clock forward, running every timer that falls due on the way, in order. */
    fun advance(ms: Int) {
        val target = nowMs + ms
        while (true) {
            val due = tasks.toList().filter { (_, task) -> task.first <= target }.minByOrNull { (_, task) -> task.first } ?: break
            val (handle, task) = due
            nowMs = maxOf(nowMs, task.first)
            tasks.remove(handle)
            task.second()
        }
        nowMs = target
    }
}

private class Harness(
    var hidden: Boolean = false,
    var answer: () -> RoomVotingStateDto = { roomState() },
) {
    val scheduler = FakeVoteScheduler()
    val updates = mutableListOf<ConferenceVoteRoomUpdate>()
    val failures = mutableListOf<ConferenceVoteRoomState>()
    var requests = 0
    var gate: CompletableDeferred<Unit>? = null

    val controller =
        ConferenceVotePollController(
            roomId = "r1",
            fetch = {
                requests++
                gate?.await()
                answer()
            },
            isHidden = { hidden },
            scheduler = scheduler,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onUpdate = { updates += it },
            onFailure = { failures += it },
        )
}

class ConferenceVoteRoomStateTest {
    // ── voteRoomReduce ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFirstAnswer_reportsNoNewlyOpenedElection_butRemembersTheOpenOnes() {
        val update = voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot("e1"), roomBallot("e2", RoomBallotStatus.DECIDED)))
        assertTrue(update.newlyOpenedElections.isEmpty(), "a member who joins a running vote gets the badge, not a pop-up")
        assertEquals(setOf("e1"), update.state.seenOpenElectionIds)
        assertEquals(true, update.state.bound)
        assertTrue(update.listChanged)
    }

    @Test
    fun aLaterAnswer_reportsTheElectionsThatOpenedSince() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot("e1"))).state
        val second = voteRoomReduce(first, roomState(roomBallot("e1"), roomBallot("e2")))
        assertEquals(listOf("e2"), second.newlyOpenedElections.map { it.id })
        val third = voteRoomReduce(second.state, roomState(roomBallot("e1"), roomBallot("e2")))
        assertTrue(third.newlyOpenedElections.isEmpty(), "each election is reported once")
    }

    @Test
    fun voteAndConsensusBallots_neverTriggerTheOpeningSignal() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState()).state
        val next =
            voteRoomReduce(
                first,
                roomState(roomBallot("v1", kind = RoomBallotKind.VOTE), roomBallot("c1", kind = RoomBallotKind.CONSENSUS)),
            )
        assertTrue(next.newlyOpenedElections.isEmpty())
    }

    @Test
    fun anElectionThatAppearsAlreadyClosed_isNotNewlyOpened() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState()).state
        val next = voteRoomReduce(first, roomState(roomBallot("e1", RoomBallotStatus.CLOSED_AWAITING_TALLY)))
        assertTrue(next.newlyOpenedElections.isEmpty())
    }

    @Test
    fun listChanged_isFalse_forAnIdenticalAnswer_andTrueForAnyChangeOfTheList() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot("e1"))).state
        assertFalse(voteRoomReduce(first, roomState(roomBallot("e1"))).listChanged)
        assertTrue(voteRoomReduce(first, roomState(roomBallot("e1", voted = true))).listChanged)
        assertTrue(voteRoomReduce(first, roomState(roomBallot("e1"), truncated = true)).listChanged)
        assertTrue(voteRoomReduce(first, roomState(bound = false)).listChanged)
    }

    @Test
    fun badgeCount_countsOnlyOpenElectionsTheMemberMayStillVoteIn() {
        val state =
            voteRoomReduce(
                ConferenceVoteRoomState(),
                roomState(
                    roomBallot("a"),
                    roomBallot("b", voted = true),
                    roomBallot("c", eligible = false),
                    roomBallot("d", RoomBallotStatus.CLOSED_AWAITING_TALLY),
                    roomBallot("e", kind = RoomBallotKind.VOTE),
                    roomBallot("f"),
                ),
            ).state
        assertEquals(2, state.badgeCount())
    }

    @Test
    fun anyActive_isTrueForOpenAndAwaitingTally_notForDecided() {
        fun active(vararg b: RoomBallotDto) = voteRoomReduce(ConferenceVoteRoomState(), roomState(*b)).state.anyActive()
        assertTrue(active(roomBallot("a")))
        assertTrue(active(roomBallot("a", RoomBallotStatus.CLOSED_AWAITING_TALLY)))
        assertFalse(active(roomBallot("a", RoomBallotStatus.DECIDED)))
        assertFalse(active())
    }

    @Test
    fun failures_areCounted_resetByAnAnswer_andTheQuietHintStartsAtThree() {
        var state = voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot("e1"))).state
        repeat(2) { state = voteRoomFailure(state) }
        assertFalse(state.showQuietRefreshHint())
        state = voteRoomFailure(state)
        assertTrue(state.showQuietRefreshHint())
        assertEquals(listOf("e1"), state.ballots.map { it.id }, "a failed poll never changes the list the member sees")
        assertEquals(0, voteRoomReduce(state, roomState(roomBallot("e1"))).state.consecutiveFailures)
    }

    @Test
    fun pollDelay_isFiveSecondsWhileActive_fifteenWhenIdle_thirtyWhenHidden() {
        val active = voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot("e1"))).state
        val idle = voteRoomReduce(ConferenceVoteRoomState(), roomState()).state
        assertEquals(5_000, conferenceVotePollDelayMs(active, hidden = false))
        assertEquals(15_000, conferenceVotePollDelayMs(idle, hidden = false))
        assertEquals(30_000, conferenceVotePollDelayMs(active, hidden = true))
        assertEquals(30_000, conferenceVotePollDelayMs(idle, hidden = true))
    }

    @Test
    fun theElectionLink_existsOnlyForAnIdWithTheShapeOfOurs() {
        assertEquals(
            "#/elections/123e4567-e89b-12d3-a456-426614174000",
            conferenceElectionDetailHref("123e4567-e89b-12d3-a456-426614174000"),
        )
        assertNull(conferenceElectionDetailHref("../../evil"))
        assertNull(conferenceElectionDetailHref("javascript:alert(1)"))
        assertNull(conferenceElectionDetailHref(""))
        assertNull(conferenceElectionDetailHref("123e4567-e89b-12d3-a456-426614174000/../x"))
    }

    // ── the controller ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun start_asksAtOnce_andThenEveryFiveSecondsWhileABallotIsOpen() {
        val h = Harness(answer = { roomState(roomBallot("e1")) })
        h.controller.start()
        assertEquals(1, h.requests)
        assertEquals(1, h.updates.size)
        assertEquals(listOf(5_000), h.scheduler.scheduledDelays)
        h.scheduler.advance(4_999)
        assertEquals(1, h.requests)
        h.scheduler.advance(1)
        assertEquals(2, h.requests)
        h.scheduler.advance(5_000)
        assertEquals(3, h.requests)
        h.controller.stop()
    }

    @Test
    fun withNothingRunning_thePollIsLazy_fifteenSeconds() {
        val h = Harness(answer = { roomState() })
        h.controller.start()
        assertEquals(listOf(15_000), h.scheduler.scheduledDelays)
        h.controller.stop()
    }

    @Test
    fun inAHiddenTab_thePollIsThirtySeconds_andBecomingVisibleAsksAtOnce() {
        val h = Harness(hidden = true, answer = { roomState(roomBallot("e1")) })
        h.controller.start()
        assertEquals(listOf(30_000), h.scheduler.scheduledDelays)
        h.hidden = false
        h.controller.onVisibilityChanged()
        assertEquals(2, h.requests, "visible again: a look right now")
        h.controller.stop()
    }

    @Test
    fun hidingTheTab_replacesTheRunningTimerByTheLongOne() {
        val h = Harness(answer = { roomState(roomBallot("e1")) })
        h.controller.start()
        h.hidden = true
        h.controller.onVisibilityChanged()
        assertEquals(1, h.scheduler.pendingTimers)
        assertEquals(30_000, h.scheduler.scheduledDelays.last())
        h.scheduler.advance(29_999)
        assertEquals(1, h.requests)
        h.scheduler.advance(1)
        assertEquals(2, h.requests)
        h.controller.stop()
    }

    @Test
    fun aRequest_neverRunsWhileAnotherIsInFlight_andWhatArrivesMeanwhileBecomesExactlyOneFollowUp() {
        val h = Harness(answer = { roomState(roomBallot("e1")) })
        val gate = CompletableDeferred<Unit>()
        h.gate = gate
        h.controller.start()
        assertEquals(1, h.requests)
        h.scheduler.advance(5_000)
        h.controller.refreshNow()
        h.controller.refreshNow()
        h.controller.nudge()
        assertEquals(1, h.requests, "still in flight: nothing new is sent")
        h.gate = null
        gate.complete(Unit)
        assertEquals(2, h.requests, "exactly one follow-up for the three requests that arrived meanwhile")
        assertEquals(2, h.updates.size)
        h.controller.stop()
    }

    @Test
    fun twoNudgesWithinASecond_areOneImmediateAndOneDeferredRequest() {
        val h = Harness(answer = { roomState(roomBallot("e1")) })
        h.controller.start()
        h.scheduler.advance(2_000)
        h.controller.nudge()
        assertEquals(2, h.requests, "the first nudge asks at once")
        h.scheduler.advance(300)
        h.controller.nudge()
        h.controller.nudge()
        assertEquals(2, h.requests, "inside the minimum gap nothing is sent yet")
        h.scheduler.advance(700)
        assertEquals(3, h.requests, "the nudges of the window became one request, at the end of the gap")
        h.controller.stop()
    }

    @Test
    fun aNudge_beforeStart_orAfterStop_doesNothing() {
        val h = Harness(answer = { roomState() })
        h.controller.nudge()
        assertEquals(0, h.requests)
        h.controller.start()
        h.controller.stop()
        h.controller.nudge()
        h.controller.refreshNow()
        h.scheduler.advance(60_000)
        assertEquals(1, h.requests)
        assertEquals(0, h.scheduler.pendingTimers)
    }

    @Test
    fun failedPolls_areCounted_neverThrown_andTheHintAppearsAfterThree() {
        val h = Harness(answer = { error("boom") })
        h.controller.start()
        assertEquals(1, h.failures.size)
        assertFalse(h.failures.last().showQuietRefreshHint())
        h.scheduler.advance(15_000)
        h.scheduler.advance(15_000)
        assertEquals(3, h.failures.size)
        assertTrue(h.failures.last().showQuietRefreshHint())
        assertTrue(h.updates.isEmpty())
        // the controller keeps trying, and an answer clears the count
        h.answer = { roomState(roomBallot("e1")) }
        h.scheduler.advance(15_000)
        assertEquals(0, h.controller.state.consecutiveFailures)
        h.controller.stop()
    }

    @Test
    fun stop_endsEverything_andALateResultIsIgnored() {
        val h = Harness(answer = { roomState(roomBallot("e1")) })
        val gate = CompletableDeferred<Unit>()
        h.gate = gate
        h.controller.start()
        h.controller.stop()
        assertEquals(0, h.scheduler.pendingTimers)
        gate.complete(Unit)
        assertTrue(h.updates.isEmpty(), "a result that arrives after stop is dropped")
        assertTrue(h.failures.isEmpty())
        assertEquals(0, h.scheduler.pendingTimers, "and it schedules nothing")
        h.controller.stop() // idempotent
    }

    @Test
    fun theControllerStartsOnlyOnce() {
        val h = Harness(answer = { roomState() })
        h.controller.start()
        h.controller.start()
        assertEquals(1, h.requests)
        h.controller.stop()
    }
}
