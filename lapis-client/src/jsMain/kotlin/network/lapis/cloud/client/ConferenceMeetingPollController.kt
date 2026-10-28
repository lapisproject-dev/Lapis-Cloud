package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import org.w3c.dom.events.Event

private const val MEETING_POLL_ACTIVE_MS = 5_000
private const val MEETING_POLL_IDLE_MS = 15_000
private const val MEETING_POLL_HIDDEN_MS = 30_000

/** Three failed polls in a row: a calm "status is being refreshed" line, never an error text. */
internal const val MEETING_QUIET_HINT_AFTER_FAILURES = 3

/**
 * How long until the next read: 5 s while the panel is open and the Sitzung is OPEN, 15 s while it is open otherwise (not opened yet,
 * closed), 30 s in a hidden tab. A CLOSED panel is not polled at all -- the caller never asks for a delay then (see
 * [ConferenceMeetingPollController.scheduleNext]).
 */
internal fun conferenceMeetingPollDelayMs(
    state: ConferenceMeetingStateDto?,
    hidden: Boolean,
): Int =
    when {
        hidden -> MEETING_POLL_HIDDEN_MS
        state?.phase == MeetingSessionPhase.OPEN -> MEETING_POLL_ACTIVE_MS
        else -> MEETING_POLL_IDLE_MS
    }

/**
 * Reads the Sitzung state of the room. One read at [start] (that decides whether the toggle exists at all: a `null` answer means "no Sitzung
 * for you here"); afterwards it polls ONLY while the panel is open ([isPanelOpen]) and stops completely when it closes. Opening the panel
 * asks again at once ([panelOpenChanged]). At most ONE request is in flight; whatever arrives meanwhile becomes exactly one follow-up. A
 * failed poll is counted and never shown as an error; [stop] ends everything, and a result that arrives afterwards is ignored.
 */
internal class ConferenceMeetingPollController(
    private val roomId: String,
    private val fetch: suspend (String) -> ConferenceMeetingStateDto?,
    private val isPanelOpen: () -> Boolean,
    private val isHidden: () -> Boolean = { document.asDynamic().visibilityState == "hidden" },
    private val scheduler: ConferenceVoteScheduler = BrowserVoteScheduler,
    private val scope: CoroutineScope = AppScope,
    private val onUpdate: (ConferenceMeetingStateDto?) -> Unit,
    private val onFailure: (consecutiveFailures: Int) -> Unit,
) {
    var state: ConferenceMeetingStateDto? = null
        private set
    var consecutiveFailures: Int = 0
        private set

    private var started = false
    private var stopped = false
    private var inFlight = false
    private var followUp = false
    private var generation = 0
    private var timer: Int? = null
    private val visibilityListener: (Event) -> Unit = { onVisibilityChanged() }

    fun start() {
        if (started || stopped) return
        started = true
        document.addEventListener("visibilitychange", visibilityListener)
        requestNow()
    }

    /** After a state change the member caused (Sitzung assigned, panel opened). */
    fun refreshNow() {
        if (!started || stopped) return
        requestNow()
    }

    /** The panel was opened or closed: opening reads at once, closing ends the timer. */
    fun panelOpenChanged() {
        if (!started || stopped) return
        if (isPanelOpen()) {
            requestNow()
        } else {
            timer?.let { scheduler.cancel(it) }
            timer = null
        }
    }

    /** A mutation answered with the fresh state: take it as the latest reading without a second request. */
    fun adopt(fresh: ConferenceMeetingStateDto?) {
        if (stopped) return
        state = fresh
        consecutiveFailures = 0
    }

    fun stop() {
        if (stopped) return
        stopped = true
        generation++
        timer?.let { scheduler.cancel(it) }
        timer = null
        if (started) document.removeEventListener("visibilitychange", visibilityListener)
    }

    internal fun onVisibilityChanged() {
        if (!started || stopped) return
        if (isHidden()) {
            if (!inFlight) scheduleNext()
        } else if (isPanelOpen()) {
            requestNow()
        }
    }

    private fun scheduleNext() {
        timer?.let { scheduler.cancel(it) }
        timer = null
        if (!isPanelOpen()) return
        timer =
            scheduler.schedule(conferenceMeetingPollDelayMs(state, isHidden())) {
                timer = null
                requestNow()
            }
    }

    private fun requestNow() {
        if (stopped) return
        if (inFlight) {
            followUp = true
            return
        }
        timer?.let { scheduler.cancel(it) }
        timer = null
        inFlight = true
        val mine = generation
        scope.launch {
            var answer: ConferenceMeetingStateDto? = null
            var failed = false
            try {
                answer = fetch(roomId)
            } catch (e: CancellationException) {
                inFlight = false
                throw e
            } catch (ignored: Throwable) {
                // Never shown: the quiet hint after three misses is the whole reaction, and `message` is never read.
                failed = true
            }
            inFlight = false
            if (stopped || mine != generation) return@launch
            if (failed) {
                consecutiveFailures++
                onFailure(consecutiveFailures)
            } else {
                consecutiveFailures = 0
                state = answer
                onUpdate(answer)
            }
            if (stopped) return@launch
            if (followUp) {
                followUp = false
                requestNow()
            } else {
                scheduleNext()
            }
        }
    }
}

/** Rooms in which the lead's panel already opened itself once (module-level: the Sitzung session of this page load). */
internal object ConferenceMeetingAutoOpen {
    private val opened = mutableSetOf<String>()

    /** `true` exactly once per room. */
    fun claim(roomId: String): Boolean = opened.add(roomId)

    internal fun resetForTest() = opened.clear()
}
