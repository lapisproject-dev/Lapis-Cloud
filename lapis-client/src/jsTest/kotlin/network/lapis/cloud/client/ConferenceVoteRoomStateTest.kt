package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotOptionDto
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.RoomVotingStateDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
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
    options: List<RoomBallotOptionDto> = emptyList(),
    winnerOptionId: String? = null,
    motionId: String = "m-$id",
    consensusPhase: SystemicConsensusStatus? = null,
) = RoomBallotDto(
    kind = kind,
    id = id,
    motionId = motionId,
    motionTitle = motionTitle,
    title = title,
    status = status,
    secret = secret,
    ownEligible = eligible,
    ownHasVoted = voted,
    options = options,
    winnerOptionId = winnerOptionId,
    consensusPhase = consensusPhase,
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
        assertTrue(update.newlyOpenedBallots.isEmpty(), "a member who joins a running vote gets the badge, not a pop-up")
        assertEquals(setOf("e1"), update.state.seenOpenBallotIds)
        assertEquals(true, update.state.bound)
        assertTrue(update.listChanged)
    }

    @Test
    fun aLaterAnswer_reportsTheElectionsThatOpenedSince() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot("e1"))).state
        val second = voteRoomReduce(first, roomState(roomBallot("e1"), roomBallot("e2")))
        assertEquals(listOf("e2"), second.newlyOpenedBallots.map { it.id })
        val third = voteRoomReduce(second.state, roomState(roomBallot("e1"), roomBallot("e2")))
        assertTrue(third.newlyOpenedBallots.isEmpty(), "each election is reported once")
    }

    @Test
    fun aConsensusBallot_neverTriggersTheOpeningSignal_butAnOpenMeritVoteDoes_onceAndNeverOnTheFirstAnswer() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState()).state
        val consensusOnly = voteRoomReduce(first, roomState(roomBallot("c1", kind = RoomBallotKind.CONSENSUS)))
        assertTrue(consensusOnly.newlyOpenedBallots.isEmpty(), "a consensus ballot is reserved and never announced")
        val withVote =
            voteRoomReduce(
                consensusOnly.state,
                roomState(roomBallot("c1", kind = RoomBallotKind.CONSENSUS), roomBallot("v1", kind = RoomBallotKind.VOTE)),
            )
        assertEquals(listOf("v1"), withVote.newlyOpenedBallots.map { it.id })
        val again = voteRoomReduce(withVote.state, roomState(roomBallot("v1", kind = RoomBallotKind.VOTE)))
        assertTrue(again.newlyOpenedBallots.isEmpty(), "each vote is reported once")
        val joiner = voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot("v1", kind = RoomBallotKind.VOTE)))
        assertTrue(joiner.newlyOpenedBallots.isEmpty(), "a member who joins a running vote gets the badge, not a pop-up")
        assertEquals(setOf("v1"), joiner.state.seenOpenBallotIds)
    }

    @Test
    fun theBadge_countsOpenElectionsAndVotes_theMemberMayStillActIn() {
        val state =
            voteRoomReduce(
                ConferenceVoteRoomState(),
                roomState(
                    roomBallot("e1"),
                    roomBallot("v1", kind = RoomBallotKind.VOTE, secret = false),
                    roomBallot("v2", kind = RoomBallotKind.VOTE, secret = false, voted = true),
                    roomBallot("v3", kind = RoomBallotKind.VOTE, secret = false, eligible = false),
                    roomBallot("v4", RoomBallotStatus.DECIDED, kind = RoomBallotKind.VOTE, secret = false),
                    roomBallot("c1", kind = RoomBallotKind.CONSENSUS),
                ),
            ).state
        assertEquals(2, state.badgeCount(), "the open election and the open vote that the member has not bid on yet")
    }

    @Test
    fun anElectionThatAppearsAlreadyClosed_isNotNewlyOpened() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState()).state
        val next = voteRoomReduce(first, roomState(roomBallot("e1", RoomBallotStatus.CLOSED_AWAITING_TALLY)))
        assertTrue(next.newlyOpenedBallots.isEmpty())
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
    fun badgeCount_countsOnlyOpenElectionsTheMemberMayStillVoteIn_aReservedConsensusNeverCounts() {
        val state =
            voteRoomReduce(
                ConferenceVoteRoomState(),
                roomState(
                    roomBallot("a"),
                    roomBallot("b", voted = true),
                    roomBallot("c", eligible = false),
                    roomBallot("d", RoomBallotStatus.CLOSED_AWAITING_TALLY),
                    roomBallot("e", kind = RoomBallotKind.CONSENSUS),
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

    // ── V1.9.32: the consensus in the room ───────────────────────────────────────────────────────────────────

    private fun consensusBallot(
        phase: SystemicConsensusStatus,
        id: String = "k1",
        secret: Boolean = true,
        eligible: Boolean = true,
        voted: Boolean = false,
    ) = roomBallot(
        id = id,
        status =
            when (phase) {
                SystemicConsensusStatus.COLLECTION, SystemicConsensusStatus.RATING -> RoomBallotStatus.OPEN
                SystemicConsensusStatus.CLOSED -> RoomBallotStatus.CLOSED_AWAITING_TALLY
                else -> RoomBallotStatus.DECIDED
            },
        kind = RoomBallotKind.CONSENSUS,
        eligible = eligible,
        voted = voted,
        secret = secret,
        consensusPhase = phase,
    )

    @Test
    fun memberActionable_isTrueForAConsensusOnlyInRating_andForOpenElectionsAndVotesAsBefore() {
        assertTrue(consensusBallot(SystemicConsensusStatus.RATING).memberActionable())
        assertFalse(consensusBallot(SystemicConsensusStatus.COLLECTION).memberActionable(), "nothing to rate while options are collected")
        assertFalse(consensusBallot(SystemicConsensusStatus.CLOSED).memberActionable())
        assertFalse(consensusBallot(SystemicConsensusStatus.EVALUATED).memberActionable())
        assertFalse(consensusBallot(SystemicConsensusStatus.RATING, voted = true).memberActionable())
        assertFalse(consensusBallot(SystemicConsensusStatus.RATING, eligible = false).memberActionable())
        assertTrue(roomBallot("e1").memberActionable())
        assertTrue(roomBallot("v1", kind = RoomBallotKind.VOTE, secret = false).memberActionable())
        assertFalse(roomBallot("e2", voted = true).memberActionable())
    }

    @Test
    fun isSecretBallotRunning_isFalseForAnAnonymousConsensusInCollection_butTrueInRating_andForASecretElection() {
        assertFalse(
            consensusBallot(SystemicConsensusStatus.COLLECTION).isSecretBallotRunning(),
            "the server pauses only once the options are frozen",
        )
        assertTrue(consensusBallot(SystemicConsensusStatus.RATING).isSecretBallotRunning())
        assertFalse(
            consensusBallot(SystemicConsensusStatus.RATING, secret = false).isSecretBallotRunning(),
            "an open consensus never locks the stream",
        )
        assertFalse(consensusBallot(SystemicConsensusStatus.CLOSED).isSecretBallotRunning())
        assertTrue(roomBallot("e1").isSecretBallotRunning())
        assertFalse(roomBallot("e2", secret = false).isSecretBallotRunning())
        assertFalse(roomBallot("e3", RoomBallotStatus.CLOSED_AWAITING_TALLY).isSecretBallotRunning())
    }

    @Test
    fun aConsensus_isAnnouncedOnlyWhenItEntersRating_notInCollection_andAReRatingIsAnnouncedAgain() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState()).state
        val collecting = voteRoomReduce(first, roomState(consensusBallot(SystemicConsensusStatus.COLLECTION)))
        assertTrue(collecting.newlyOpenedBallots.isEmpty(), "COLLECTION is open for the room but not announced")
        val rating = voteRoomReduce(collecting.state, roomState(consensusBallot(SystemicConsensusStatus.RATING)))
        assertEquals(listOf("k1"), rating.newlyOpenedBallots.map { it.id })
        val again = voteRoomReduce(rating.state, roomState(consensusBallot(SystemicConsensusStatus.RATING)))
        assertTrue(again.newlyOpenedBallots.isEmpty(), "reported once")
        val closed = voteRoomReduce(again.state, roomState(consensusBallot(SystemicConsensusStatus.CLOSED)))
        assertTrue(closed.newlyOpenedBallots.isEmpty())
        val evaluated = voteRoomReduce(closed.state, roomState(consensusBallot(SystemicConsensusStatus.EVALUATED)))
        assertTrue(evaluated.newlyOpenedBallots.isEmpty())
        val reRating = voteRoomReduce(evaluated.state, roomState(consensusBallot(SystemicConsensusStatus.RATING)))
        assertEquals(1, reRating.newlyOpenedBallots.size, "a rating round after a result opens the panel again")
        assertEquals("k1@2", reRating.newlyOpenedBallots.single().id, "the id handed to the screen's once-per-id memory is a new one")
        assertEquals(
            "k1",
            reRating.state.ballots
                .single()
                .id,
            "the list itself keeps the real id",
        )
    }

    @Test
    fun aConsensus_theMemberCannotActOn_isNeverAnnounced_andAJoinerGetsNoPopUp() {
        val first = voteRoomReduce(ConferenceVoteRoomState(), roomState()).state
        val notEligible = voteRoomReduce(first, roomState(consensusBallot(SystemicConsensusStatus.RATING, eligible = false)))
        assertTrue(notEligible.newlyOpenedBallots.isEmpty())
        val joiner = voteRoomReduce(ConferenceVoteRoomState(), roomState(consensusBallot(SystemicConsensusStatus.RATING)))
        assertTrue(joiner.newlyOpenedBallots.isEmpty())
        assertEquals(1, joiner.state.badgeCount(), "the joiner sees the badge")
    }

    @Test
    fun theBadge_countsAConsensusOnlyInRatingThatTheMemberHasNotRatedYet() {
        fun count(vararg b: RoomBallotDto) = voteRoomReduce(ConferenceVoteRoomState(), roomState(*b)).state.badgeCount()
        assertEquals(1, count(consensusBallot(SystemicConsensusStatus.RATING)))
        assertEquals(0, count(consensusBallot(SystemicConsensusStatus.COLLECTION)))
        assertEquals(0, count(consensusBallot(SystemicConsensusStatus.RATING, voted = true)))
        assertEquals(0, count(consensusBallot(SystemicConsensusStatus.RATING, eligible = false)))
        assertEquals(2, count(consensusBallot(SystemicConsensusStatus.RATING), roomBallot("e1")))
    }

    @Test
    fun openSeenKey_ofAConsensusIsPerRating_ofOtherKindsTheId() {
        assertEquals("k1#rating", consensusBallot(SystemicConsensusStatus.RATING).openSeenKey())
        assertEquals("e1", roomBallot("e1").openSeenKey())
        assertEquals("v1", roomBallot("v1", kind = RoomBallotKind.VOTE).openSeenKey())
    }

    @Test
    fun consensusPhaseLabel_namesEveryPhase_andTheDetailHrefOnlyForIdsOfOurShape() {
        assertEquals("Optionen werden gesammelt", consensusPhaseLabel(SystemicConsensusStatus.COLLECTION))
        assertEquals("Bewertung läuft", consensusPhaseLabel(SystemicConsensusStatus.RATING))
        assertEquals("Bewertung geschlossen", consensusPhaseLabel(SystemicConsensusStatus.CLOSED))
        assertEquals("Ausgewertet", consensusPhaseLabel(SystemicConsensusStatus.EVALUATED))
        assertEquals(
            "#/consensus/123e4567-e89b-12d3-a456-426614174000",
            conferenceConsensusDetailHref("123e4567-e89b-12d3-a456-426614174000"),
        )
        assertNull(conferenceConsensusDetailHref("../../evil"))
        assertNull(conferenceConsensusDetailHref("k1"))
    }

    @Test
    fun theCompactBooth_fitsFrom290px_andNotBelow() {
        assertFalse(consensusFitsPanel(289))
        assertTrue(consensusFitsPanel(290))
        assertTrue(consensusFitsPanel(1200))
    }
}
