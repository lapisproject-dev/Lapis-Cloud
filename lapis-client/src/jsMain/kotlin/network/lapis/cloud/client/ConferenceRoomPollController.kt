package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceRoomPollService
import org.w3c.dom.events.Event

/** Two nudges closer together than this are one request (the server allows 40 reads a minute per account). */
internal const val ROOM_POLL_NUDGE_MIN_GAP_MS = 1_000

/** The one read of the room's poll: the controller and the panel's own refresh share it. */
internal suspend fun fetchRoomPollState(roomId: String): RoomPollStateDto =
    rpcService<IConferenceRoomPollService>().getRoomPollState(roomId)

/**
 * Asks the server for the poll of the room: every 5 s (4 s for the leadership while a poll runs), 15 s in a hidden tab, at once when the tab
 * becomes visible and when the server's nudge arrives. At most ONE request is in flight; whatever arrives meanwhile becomes exactly one
 * follow-up. A refusal ([ForbiddenException]: an event room, a person without access) is permanent: the periodic poll stops at once (a refused
 * call still costs the server a member lookup and a query); only a nudge, [refreshNow] or the tab becoming visible ask again. A failed poll is counted and never shown as an error; [stop] ends everything and a result that arrives afterwards is ignored.
 * Shape and discipline are those of [ConferenceVotePollController].
 */
internal class ConferenceRoomPollController(
    private val roomId: String,
    private val fetch: suspend (String) -> RoomPollStateDto = ::fetchRoomPollState,
    private val isHidden: () -> Boolean = { document.asDynamic().visibilityState == "hidden" },
    private val scheduler: ConferenceVoteScheduler = BrowserVoteScheduler,
    private val scope: CoroutineScope = AppScope,
    private val onUpdate: (previous: RoomPollStateDto?, next: RoomPollStateDto) -> Unit,
    private val onFailure: (failures: Int) -> Unit = {},
) {
    var state: RoomPollStateDto? = null
        private set
    var failures: Int = 0
        private set

    private var started = false
    private var stopped = false
    private var inFlight = false
    private var followUp = false
    private var refused = false
    private var generation = 0
    private var timer: Int? = null
    private var nudgeTimer: Int? = null
    private var lastRequestAt = Double.NEGATIVE_INFINITY
    private val visibilityListener: (Event) -> Unit = { onVisibilityChanged() }

    fun start() {
        if (started || stopped) return
        started = true
        document.addEventListener("visibilitychange", visibilityListener)
        requestNow()
    }

    /** The server's nudge: ask now, but never more often than [ROOM_POLL_NUDGE_MIN_GAP_MS]; the rest waits for one later request. */
    fun nudge() {
        if (!started || stopped) return
        val gap = scheduler.now() - lastRequestAt
        if (gap >= ROOM_POLL_NUDGE_MIN_GAP_MS) {
            requestNow()
        } else if (nudgeTimer == null) {
            nudgeTimer =
                scheduler.schedule((ROOM_POLL_NUDGE_MIN_GAP_MS - gap).toInt().coerceAtLeast(0)) {
                    nudgeTimer = null
                    requestNow()
                }
        }
    }

    /** After a change the person caused (started, ended, answered). */
    fun refreshNow() {
        if (!started || stopped) return
        requestNow()
    }

    /** The state a write already answered with: shown at once, without waiting for the next poll. */
    fun adopt(fresh: RoomPollStateDto) {
        if (stopped) return
        val before = state
        state = fresh
        onUpdate(before, fresh)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        generation++
        cancelTimers()
        if (started) document.removeEventListener("visibilitychange", visibilityListener)
    }

    internal fun onVisibilityChanged() {
        if (!started || stopped) return
        if (isHidden()) {
            if (!inFlight && !refused) scheduleNext()
        } else {
            requestNow()
        }
    }

    private fun cancelTimers() {
        timer?.let { scheduler.cancel(it) }
        timer = null
        nudgeTimer?.let { scheduler.cancel(it) }
        nudgeTimer = null
    }

    private fun scheduleNext() {
        timer?.let { scheduler.cancel(it) }
        timer =
            scheduler.schedule(roomPollPollDelayMs(state, isHidden())) {
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
        lastRequestAt = scheduler.now()
        val mine = generation
        scope.launch {
            var answer: RoomPollStateDto? = null
            var refusal = false
            try {
                answer = fetch(roomId)
            } catch (e: CancellationException) {
                inFlight = false
                throw e
            } catch (e: ForbiddenException) {
                refusal = true
            } catch (ignored: Throwable) {
                // Never shown: `message` is never read, the quiet hint after a few misses is the whole reaction.
            }
            inFlight = false
            if (stopped || mine != generation) return@launch
            refused = refusal
            if (refusal) {
                // not a miss and not shown: no periodic polling any more, nothing to count
                followUp = false
                return@launch
            }
            if (answer == null) {
                failures++
                onFailure(failures)
            } else {
                failures = 0
                val before = state
                state = answer
                onUpdate(before, answer)
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
