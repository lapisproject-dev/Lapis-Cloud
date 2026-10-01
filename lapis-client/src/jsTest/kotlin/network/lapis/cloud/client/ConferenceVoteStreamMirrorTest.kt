package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.26 -- the pure stream mirror: lock levels, the reducer, the banner and dialog texts, the poll rule, and the clock on a fake scheduler. */
internal class OperatorFakeScheduler : ConferenceVoteScheduler {
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

class ConferenceVoteStreamMirrorTest {
    private val s = ConferenceStreamStatus.entries

    private fun stream(
        status: ConferenceStreamStatus,
        reason: ConferenceStreamPauseReason? = null,
        targetStatus: ConferenceStreamTargetStatus = ConferenceStreamTargetStatus.ACTIVE,
    ) = ConferenceStreamDto(
        id = "s1",
        roomId = "r1",
        roomTitle = "Raum",
        status = status,
        layout = ConferenceStreamLayout.GRID,
        latencyMode = ConferenceStreamLatencyMode.STANDARD,
        startedByMemberId = "m1",
        startedByDisplayName = "A",
        startedAt = LocalDateTime(2026, 8, 9, 14, 5),
        pausedAt = null,
        endedAt = null,
        restartCount = 0,
        targets =
            listOf(
                ConferenceStreamTargetStatusDto(
                    destinationId = "d1",
                    label = "L",
                    platform = ConferenceStreamPlatform.YOUTUBE,
                    status = targetStatus,
                    retries = 0,
                    failureReason = null,
                ),
            ),
        failureReason = null,
        pauseReason = reason,
    )

    // ── the lock level ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun quiescedForSecretBallot_isTheServersTable_forEverySevenStatusesAndNull() {
        val free = setOf(ConferenceStreamStatus.PAUSED, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED)
        s.forEach { status -> assertEquals(status in free, status.quiescedForSecretBallot(), "$status") }
        assertTrue((null as ConferenceStreamStatus?).quiescedForSecretBallot(), "no stream known: free")
    }

    @Test
    fun ballotStreamLock_isFreeForAnOpenElection_whatEverTheStreamDoes() {
        s.forEach { status -> assertEquals(BallotStreamLock.FREE, ballotStreamLock(false, status, 0, 999_999), "$status") }
    }

    @Test
    fun ballotStreamLock_secret_followsTheTable_andTheTwoTimeStagesOfPausing() {
        val now = 100_000L
        listOf(ConferenceStreamStatus.PAUSED, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED).forEach {
            assertEquals(BallotStreamLock.FREE, ballotStreamLock(true, it, null, now), "$it")
        }
        assertEquals(BallotStreamLock.FREE, ballotStreamLock(true, null, null, now))
        listOf(ConferenceStreamStatus.STARTING, ConferenceStreamStatus.LIVE, ConferenceStreamStatus.STOPPING).forEach {
            assertEquals(BallotStreamLock.LOCKED, ballotStreamLock(true, it, null, now), "$it")
        }
        val pausing = ConferenceStreamStatus.PAUSING
        assertEquals(BallotStreamLock.LOCKED, ballotStreamLock(true, pausing, now, now), "just started")
        assertEquals(BallotStreamLock.LOCKED, ballotStreamLock(true, pausing, now - 19_999, now))
        assertEquals(BallotStreamLock.LOCKED_SLOW, ballotStreamLock(true, pausing, now - 20_000, now))
        assertEquals(BallotStreamLock.LOCKED_SLOW, ballotStreamLock(true, pausing, now - 59_999, now))
        assertEquals(BallotStreamLock.HUNG, ballotStreamLock(true, pausing, now - 60_000, now))
        assertEquals(BallotStreamLock.LOCKED, ballotStreamLock(true, pausing, null, now), "an unobserved start is not slow")
    }

    @Test
    fun forMember_readsHungAsSlow_andTouchesNothingElse() {
        assertEquals(BallotStreamLock.LOCKED_SLOW, BallotStreamLock.HUNG.forMember())
        assertEquals(BallotStreamLock.FREE, BallotStreamLock.FREE.forMember())
        assertEquals(BallotStreamLock.LOCKED, BallotStreamLock.LOCKED.forMember())
        assertEquals(BallotStreamLock.LOCKED_SLOW, BallotStreamLock.LOCKED_SLOW.forMember())
    }

    @Test
    fun ballotLockReason_isNullWhenFree_oneSentenceWhenLocked_andAddsOneAfterTwentySeconds() {
        assertNull(ballotLockReason(BallotStreamLock.FREE))
        val locked = assertNotNull(ballotLockReason(BallotStreamLock.LOCKED))
        val slow = assertNotNull(ballotLockReason(BallotStreamLock.LOCKED_SLOW))
        assertTrue(slow.startsWith(locked), "the slow text keeps the first sentence")
        assertTrue(slow.length > locked.length)
        assertEquals(slow, ballotLockReason(BallotStreamLock.HUNG), "HUNG reads like slow where it is shown at all")
    }

    // ── the reducer ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun streamMirrorReduce_setsTheStartOnTheFirstPausing_keepsItWhilePausing_andClearsItOtherwise() {
        var m = StreamMirrorState()
        m = streamMirrorReduce(m, ConferenceStreamStatus.LIVE, null, 10)
        assertNull(m.pausingSinceMs)
        m = streamMirrorReduce(m, ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT, 20)
        assertEquals(20L, m.pausingSinceMs)
        m = streamMirrorReduce(m, ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT, 35)
        assertEquals(20L, m.pausingSinceMs, "a second PAUSING observation keeps the first time")
        m = streamMirrorReduce(m, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT, 50)
        assertNull(m.pausingSinceMs)
        m = streamMirrorReduce(m, ConferenceStreamStatus.PAUSING, null, 70)
        assertEquals(70L, m.pausingSinceMs, "PAUSING again after PAUSED starts a new stopwatch")
        m = streamMirrorReduce(m, ConferenceStreamStatus.LIVE, null, 80)
        assertNull(m.pausingSinceMs)
        m = streamMirrorReduce(m, ConferenceStreamStatus.PAUSING, null, 90)
        m = streamMirrorReduce(m, null, null, 95)
        assertNull(m.pausingSinceMs)
        assertNull(m.status)
    }

    @Test
    fun lockRecheckDelayMs_pointsAtTheNextTimeStage() {
        assertNull(lockRecheckDelayMs(null, 5))
        assertEquals(20_000, lockRecheckDelayMs(100, 100))
        assertEquals(1, lockRecheckDelayMs(0, 19_999))
        assertEquals(40_000, lockRecheckDelayMs(0, 20_000))
        assertEquals(1, lockRecheckDelayMs(0, 59_999))
        assertNull(lockRecheckDelayMs(0, 60_000))
    }

    // ── banner and dialog ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun secretBallotBannerText_coversStatusTimesSecretOpenTimesRecording() {
        val recordingNote = "Die Aufzeichnung der Sitzung läuft weiter."
        for (secretOpen in listOf(true, false, null)) {
            for (rec in listOf(true, false, null)) {
                for (status in s + listOf(null)) {
                    val state = StreamMirrorState(status, ConferenceStreamPauseReason.SECRET_BALLOT)
                    val text = secretBallotBannerText(secretOpen, state, rec)
                    val pausedForBallot = status == ConferenceStreamStatus.PAUSING || status == ConferenceStreamStatus.PAUSED
                    val expectShown = secretOpen == true || pausedForBallot
                    val label = "open=$secretOpen rec=$rec status=$status"
                    if (!expectShown) {
                        assertNull(text, label)
                    } else {
                        val shown = assertNotNull(text, label)
                        assertTrue(shown.startsWith("Geheime Wahl läuft"), label)
                        assertEquals(rec != false, shown.endsWith(recordingNote), label)
                    }
                }
            }
        }
    }

    @Test
    fun secretBallotBannerText_namesTheStreamState() {
        fun head(status: ConferenceStreamStatus?) = secretBallotBannerText(true, StreamMirrorState(status), false)
        assertEquals("Geheime Wahl läuft: Der Livestream ist angehalten.", head(ConferenceStreamStatus.PAUSED))
        listOf(
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.STOPPING,
        ).forEach { assertEquals("Geheime Wahl läuft: Der Livestream wird angehalten.", head(it), "$it") }
        listOf(ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED, null)
            .forEach { assertEquals("Geheime Wahl läuft.", head(it), "$it") }
    }

    @Test
    fun aGuest_whoHasNoRoomState_seesTheBannerOnlyForAStreamPausedForABallot() {
        val manual = StreamMirrorState(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.MANUAL)
        assertNull(secretBallotBannerText(null, manual, true), "a manual pause is not about a ballot")
        val ballot = StreamMirrorState(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)
        assertNotNull(secretBallotBannerText(null, ballot, true))
    }

    @Test
    fun secretOpenPreflightLines_alwaysEndWithTheRecordingNote_andNameTheStreamState() {
        for (status in s + listOf(null)) {
            val lines = secretOpenPreflightLines(status)
            assertEquals(3, lines.size, "$status")
            assertEquals("Die Aufzeichnung der Sitzung wird nicht angehalten.", lines.last(), "$status")
            assertEquals("Das gilt auch für Live-Streams in anderen Räumen dieser Sitzung.", lines[1])
        }
        assertEquals(
            "In diesem Raum läuft kein Live-Stream.",
            secretOpenPreflightLines(null).first(),
        )
        assertEquals("In diesem Raum läuft kein Live-Stream.", secretOpenPreflightLines(ConferenceStreamStatus.ENDED).first())
        assertEquals("In diesem Raum läuft kein Live-Stream.", secretOpenPreflightLines(ConferenceStreamStatus.FAILED).first())
        assertTrue(secretOpenPreflightLines(ConferenceStreamStatus.LIVE).first().contains("angehalten"))
        assertTrue(secretOpenPreflightLines(ConferenceStreamStatus.PAUSED).first().contains("bleibt es"))
    }

    // ── polling ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theStreamPoll_runsForALiveStreamOnlyWhileASecretBallotIsOpen_andFasterWhileNotQuiesced() {
        val live = stream(ConferenceStreamStatus.LIVE)
        assertFalse(conferenceStreamNeedsPollWithBallot(live, false), "LIVE is pushed, not polled")
        assertTrue(conferenceStreamNeedsPollWithBallot(live, true))
        assertFalse(conferenceStreamNeedsPollWithBallot(null, true))
        assertFalse(conferenceStreamNeedsPollWithBallot(stream(ConferenceStreamStatus.ENDED), true))
        assertTrue(conferenceStreamNeedsPollWithBallot(stream(ConferenceStreamStatus.PAUSING), false), "the old rule still holds")
        assertTrue(
            conferenceStreamNeedsPollWithBallot(stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT), false),
        )
        assertEquals(5_000L, conferenceStreamPollIntervalMs(live, true))
        assertEquals(5_000L, conferenceStreamPollIntervalMs(stream(ConferenceStreamStatus.PAUSING), true))
        assertEquals(15_000L, conferenceStreamPollIntervalMs(live, false))
        assertEquals(
            15_000L,
            conferenceStreamPollIntervalMs(stream(ConferenceStreamStatus.PAUSED), true),
            "quiesced: back to the slow rhythm",
        )
        assertEquals(15_000L, conferenceStreamPollIntervalMs(null, true))
    }

    // ── the clock ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theClock_announcesTheTwoTimeStagesByItself_andStopsWhenDisposed() {
        val scheduler = OperatorFakeScheduler()
        val clock = BallotLockClock(scheduler)
        val levels = mutableListOf<BallotStreamLock>()
        clock.subscribe { levels += clock.lock(true) }
        clock.update(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
        assertEquals(listOf(BallotStreamLock.LOCKED), levels)
        scheduler.advance(19_999)
        assertEquals(1, levels.size)
        scheduler.advance(1)
        assertEquals(BallotStreamLock.LOCKED_SLOW, levels.last())
        scheduler.advance(40_000)
        assertEquals(BallotStreamLock.HUNG, levels.last())
        assertEquals(0, scheduler.pendingTimers, "nothing pending once the last stage is reached")
        clock.update(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)
        assertEquals(BallotStreamLock.FREE, levels.last())
        clock.update(ConferenceStreamStatus.PAUSING, null)
        assertEquals(1, scheduler.pendingTimers)
        clock.dispose()
        assertEquals(0, scheduler.pendingTimers, "dispose cancels the pending stage")
        val count = levels.size
        clock.update(ConferenceStreamStatus.LIVE, null)
        assertEquals(count, levels.size, "a disposed clock hears nothing")
    }

    @Test
    fun theClock_unsubscribeStopsDelivery() {
        val clock = BallotLockClock(OperatorFakeScheduler())
        var heard = 0
        val off = clock.subscribe { heard++ }
        clock.update(ConferenceStreamStatus.LIVE, null)
        off()
        clock.update(ConferenceStreamStatus.PAUSED, null)
        assertEquals(1, heard)
    }
}
