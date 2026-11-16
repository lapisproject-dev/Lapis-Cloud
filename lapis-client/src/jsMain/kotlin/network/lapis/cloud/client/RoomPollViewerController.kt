package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollVersionDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceRoomPollService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/**
 * The three calls of the viewer page behind one seam. NOTHING else of the server is reachable from this page (no LiveKit, no room join, no
 * conference service): the tripwire `RoomPollViewerNoLiveKitTest` pins that this class has exactly these three entries.
 */
internal class RoomPollViewerRpc(
    val version: suspend (roomId: String, knownVersion: String?) -> RoomPollVersionDto = { room, known ->
        rpcService<IConferenceRoomPollService>().getRoomPollVersion(room, known)
    },
    val state: suspend (roomId: String) -> RoomPollStateDto = { rpcService<IConferenceRoomPollService>().getRoomPollState(it) },
    val answer: suspend (roomId: String, pollId: String, optionId: String) -> RoomPollStateDto = { room, poll, option ->
        rpcService<IConferenceRoomPollService>().answerRoomPoll(room, poll, option)
    },
)

/** Three conflicts in a row without one success end the page as unavailable (a disabled feature and a rate limit look alike on the wire). */
internal const val ROOM_POLL_VIEWER_MAX_CONFLICTS = 3

/**
 * The polling rhythm of the viewer page. Discipline taken from [ConferenceRoomPollController]: at most ONE request in flight, whatever arrives
 * meanwhile becomes one follow-up, a generation counter drops anything that returns after [stop].
 *
 *  - Visible: one cheap version call every 5.0 to 6.0 s ([roomPollViewerNextDelayMs]); NEVER two version calls closer than 5 s, whatever the
 *    trigger (timer, tab visible, back online, "Erneut prüfen").
 *  - Hidden tab: 30 s; visible again: one call at once (still subject to the 5 s gap). Offline: every timer is cancelled; online: one call.
 *  - A network error backs off 5/10/20/40/60 s and resets on the first success.
 *  - The full state is read only on the first load, when the version changed, and after the person's own answer ([adopt]).
 *  - A refusal ([ForbiddenException], [NotFoundException], [BadRequestException]) is permanent: [onRefused] once, no more timers. Only
 *    [recheck] (once per 10 s) asks again. [UnauthenticatedException] stops the same way with `unauthenticated = true`.
 *  - A [ConflictException] on a read pauses 60 s (the client cannot tell a rate limit from "disabled"); the third one in a row refuses.
 */
internal class RoomPollViewerController(
    private val roomId: String,
    private val rpc: RoomPollViewerRpc = RoomPollViewerRpc(),
    private val isHidden: () -> Boolean = { document.asDynamic().visibilityState == "hidden" },
    private val scheduler: ConferenceVoteScheduler = BrowserVoteScheduler,
    private val scope: CoroutineScope = AppScope,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
    private val onState: (RoomPollStateDto) -> Unit,
    private val onRefused: (unauthenticated: Boolean) -> Unit,
) {
    var failures: Int = 0
        private set

    private var started = false
    private var inFlight = false
    private var followUp = false
    private var refused = false
    private var offline = false
    private var ratePaused = false
    private var conflicts = 0
    private var forceState = false
    private var knownVersion: String? = null
    private var loaded = false
    private var generation = 0
    private var timer: Int? = null
    private var lastCallAt = Double.NEGATIVE_INFINITY
    private var recheckLockedUntil = Double.NEGATIVE_INFINITY

    fun start() {
        if (started) return
        started = true
        generation++
        inFlight = false
        followUp = false
        refused = false
        ratePaused = false
        conflicts = 0
        failures = 0
        loaded = false
        knownVersion = null
        offline = false
        requestNow()
    }

    /** Ends everything; a result that arrives afterwards is ignored. The controller can be started again (a language restart rebuilds the DOM). */
    fun stop() {
        if (!started) return
        started = false
        generation++
        cancelTimer()
    }

    /** "Erneut prüfen": exactly one new version call, then locked for 10 s. A refusal that was permanent is lifted for that call. */
    fun recheck() {
        if (!started) return
        val now = scheduler.now()
        if (now < recheckLockedUntil) return
        recheckLockedUntil = now + RoomPollRules.VIEWER_RECHECK_LOCK_MS
        refused = false
        ratePaused = false
        conflicts = 0
        failures = 0
        forceState = true
        requestNow()
    }

    /** The state the person's own answer returned (or a state read right after a conflict): shown at once, the version is adopted. */
    fun adopt(fresh: RoomPollStateDto) {
        if (!started) return
        knownVersion = fresh.version
        loaded = true
        onState(fresh)
    }

    fun onVisibilityChanged() {
        if (!started || refused || offline) return
        if (isHidden()) {
            if (!inFlight) scheduleNext()
        } else {
            requestNow()
        }
    }

    fun onOnline() {
        if (!started) return
        offline = false
        if (!refused) requestNow()
    }

    fun onOffline() {
        if (!started) return
        offline = true
        cancelTimer()
    }

    private fun cancelTimer() {
        timer?.let { scheduler.cancel(it) }
        timer = null
    }

    private fun scheduleNext() {
        if (!started || refused || offline) return
        cancelTimer()
        timer =
            scheduler.schedule(
                roomPollViewerNextDelayMs(hidden = isHidden(), failures = failures, ratePaused = ratePaused, random = random),
            ) {
                timer = null
                requestNow()
            }
    }

    /** Asks now, or as soon as the 5 s gap since the last version call allows. */
    private fun requestNow() {
        if (!started || refused || offline) return
        if (inFlight) {
            followUp = true
            return
        }
        val gap = scheduler.now() - lastCallAt
        if (gap < RoomPollRules.VIEWER_VERSION_POLL_MS) {
            cancelTimer()
            timer =
                scheduler.schedule((RoomPollRules.VIEWER_VERSION_POLL_MS - gap).toInt().coerceAtLeast(0) + 1) {
                    timer = null
                    requestNow()
                }
            return
        }
        cancelTimer()
        inFlight = true
        lastCallAt = scheduler.now()
        val mine = generation
        scope.launch {
            var fresh: RoomPollStateDto? = null
            var versionToken: String? = knownVersion
            var failure: Throwable? = null
            try {
                val version = rpc.version(roomId, if (forceState) null else knownVersion)
                if (version.changed || !loaded || forceState) {
                    fresh = rpc.state(roomId)
                    versionToken = version.version ?: fresh.version
                }
            } catch (e: CancellationException) {
                if (mine == generation) inFlight = false
                throw e
            } catch (e: Throwable) {
                failure = e
            }
            if (mine != generation) return@launch
            inFlight = false
            if (failure != null) {
                handleFailure(failure)
            } else {
                failures = 0
                conflicts = 0
                ratePaused = false
                forceState = false
                knownVersion = versionToken
                if (fresh != null) {
                    loaded = true
                    onState(fresh)
                }
                if (generation != mine || !started) return@launch
            }
            if (refused || !started) return@launch
            if (followUp) {
                followUp = false
                requestNow()
            } else {
                scheduleNext()
            }
        }
    }

    private fun handleFailure(failure: Throwable) {
        when (failure) {
            is UnauthenticatedException -> refuse(unauthenticated = true)
            is ForbiddenException, is NotFoundException, is BadRequestException -> refuse(unauthenticated = false)
            is ConflictException -> {
                conflicts++
                ratePaused = true
                if (conflicts >= ROOM_POLL_VIEWER_MAX_CONFLICTS) refuse(unauthenticated = false)
            }
            else -> failures++
        }
    }

    private fun refuse(unauthenticated: Boolean) {
        refused = true
        followUp = false
        cancelTimer()
        onRefused(unauthenticated)
    }
}
