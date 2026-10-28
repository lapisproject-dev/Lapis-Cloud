package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AttendanceRuleKind
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingSessionRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun meetingState(
    phase: MeetingSessionPhase = MeetingSessionPhase.OPEN,
    role: MeetingSessionRole = MeetingSessionRole.LEAD,
) = ConferenceMeetingStateDto(
    meetingId = "m1",
    meetingTitle = "Sitzung",
    committeeId = "c1",
    phase = phase,
    openedAt = if (phase == MeetingSessionPhase.NOT_OPENED) null else LocalDateTime(2030, 5, 4, 19, 5),
    callerRole = role,
    ruleKind = AttendanceRuleKind.NAME_LIST,
)

private class MeetingFakeScheduler : ConferenceVoteScheduler {
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

    fun advance(ms: Int) {
        val target = nowMs + ms
        while (true) {
            val due = tasks.toList().filter { (_, task) -> task.first <= target }.minByOrNull { (_, task) -> task.first } ?: break
            nowMs = maxOf(nowMs, due.second.first)
            tasks.remove(due.first)
            due.second.second()
        }
        nowMs = target
    }
}

private class MeetingHarness(
    var hidden: Boolean = false,
    var open: Boolean = false,
    var answer: () -> ConferenceMeetingStateDto? = { meetingState() },
) {
    val scheduler = MeetingFakeScheduler()
    val updates = mutableListOf<ConferenceMeetingStateDto?>()
    val failures = mutableListOf<Int>()
    var requests = 0
    var gate: CompletableDeferred<Unit>? = null
    var failing = false

    val controller =
        ConferenceMeetingPollController(
            roomId = "r1",
            fetch = {
                requests++
                gate?.await()
                if (failing) error("down")
                answer()
            },
            isPanelOpen = { open },
            isHidden = { hidden },
            scheduler = scheduler,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onUpdate = { updates += it },
            onFailure = { failures += it },
        )
}

/** V1.9.236 -- the poll controller of the Sitzung panel: intervals 5 / 15 / 30 s, no polling for a closed panel, one request in flight. */
class ConferenceMeetingPollControllerTest {
    @Test
    fun delays_are5SecondsForAnOpenSitzung_15Otherwise_30InAHiddenTab() {
        assertEquals(5_000, conferenceMeetingPollDelayMs(meetingState(MeetingSessionPhase.OPEN), hidden = false))
        assertEquals(15_000, conferenceMeetingPollDelayMs(meetingState(MeetingSessionPhase.NOT_OPENED), hidden = false))
        assertEquals(15_000, conferenceMeetingPollDelayMs(meetingState(MeetingSessionPhase.CLOSED), hidden = false))
        assertEquals(15_000, conferenceMeetingPollDelayMs(null, hidden = false))
        assertEquals(30_000, conferenceMeetingPollDelayMs(meetingState(MeetingSessionPhase.OPEN), hidden = true))
    }

    @Test
    fun start_readsOnce_andANullAnswerIsReported_butAClosedPanelIsNotPolled() {
        val h = MeetingHarness(answer = { null })
        h.controller.start()
        assertEquals(1, h.requests)
        assertEquals(listOf<ConferenceMeetingStateDto?>(null), h.updates)
        assertEquals(0, h.scheduler.pendingTimers, "nothing is scheduled while the panel is closed")
        h.scheduler.advance(120_000)
        assertEquals(1, h.requests)
    }

    @Test
    fun anOpenPanel_pollsEvery5Seconds_whileTheSitzungIsOpen_andEvery15WhileItIsNot() {
        val h = MeetingHarness(open = true)
        h.controller.start()
        assertEquals(listOf(5_000), h.scheduler.scheduledDelays)
        h.scheduler.advance(5_000)
        assertEquals(2, h.requests)
        h.answer = { meetingState(MeetingSessionPhase.NOT_OPENED) }
        h.scheduler.advance(5_000)
        assertEquals(3, h.requests)
        assertEquals(15_000, h.scheduler.scheduledDelays.last())
    }

    @Test
    fun aHiddenTab_pollsEvery30Seconds() {
        val h = MeetingHarness(open = true, hidden = true)
        h.controller.start()
        assertEquals(30_000, h.scheduler.scheduledDelays.last())
    }

    @Test
    fun openingThePanel_readsAtOnce_andClosingItStopsTheTimer() {
        val h = MeetingHarness()
        h.controller.start()
        assertEquals(1, h.requests)
        h.open = true
        h.controller.panelOpenChanged()
        assertEquals(2, h.requests)
        assertEquals(1, h.scheduler.pendingTimers)
        h.open = false
        h.controller.panelOpenChanged()
        assertEquals(0, h.scheduler.pendingTimers)
        h.scheduler.advance(120_000)
        assertEquals(2, h.requests, "no timer is left after the panel closed")
    }

    @Test
    fun atMostOneRequestIsInFlight_whatArrivesMeanwhileBecomesOneFollowUp() {
        val h = MeetingHarness(open = true)
        h.gate = CompletableDeferred()
        h.controller.start()
        h.controller.refreshNow()
        h.controller.refreshNow()
        assertEquals(1, h.requests)
        val release = h.gate!!
        h.gate = null
        release.complete(Unit)
        assertEquals(2, h.requests, "exactly one follow-up")
    }

    @Test
    fun aFailedPoll_isCounted_notShown_andResetByTheNextGoodAnswer() {
        val h = MeetingHarness(open = true)
        h.failing = true
        h.controller.start()
        assertEquals(listOf(1), h.failures)
        assertTrue(h.updates.isEmpty())
        h.scheduler.advance(15_000)
        h.scheduler.advance(15_000)
        assertEquals(3, h.failures.last())
        assertTrue(h.failures.last() >= MEETING_QUIET_HINT_AFTER_FAILURES)
        h.failing = false
        h.scheduler.advance(15_000)
        assertEquals(0, h.controller.consecutiveFailures)
    }

    @Test
    fun stop_endsEverything_andALateAnswerIsIgnored() {
        val h = MeetingHarness(open = true)
        h.gate = CompletableDeferred()
        h.controller.start()
        h.controller.stop()
        h.gate!!.complete(Unit)
        assertTrue(h.updates.isEmpty())
        assertEquals(0, h.scheduler.pendingTimers)
        h.controller.refreshNow()
        assertEquals(1, h.requests)
    }

    @Test
    fun adopt_takesAFreshStateWithoutARequest() {
        val h = MeetingHarness(open = true)
        h.controller.start()
        h.controller.adopt(meetingState(MeetingSessionPhase.CLOSED))
        assertEquals(MeetingSessionPhase.CLOSED, h.controller.state?.phase)
        assertEquals(1, h.requests)
    }

    @Test
    fun theLeadsPanelOpensItselfOncePerRoom() {
        ConferenceMeetingAutoOpen.resetForTest()
        assertTrue(ConferenceMeetingAutoOpen.claim("r1"))
        assertFalse(ConferenceMeetingAutoOpen.claim("r1"))
        assertTrue(ConferenceMeetingAutoOpen.claim("r2"))
        ConferenceMeetingAutoOpen.resetForTest()
        assertNull(null)
    }
}
